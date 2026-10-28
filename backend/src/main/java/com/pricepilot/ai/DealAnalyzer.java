package com.pricepilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.DealAnalysis;
import com.pricepilot.domain.DealVerdict;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.domain.Product;
import com.pricepilot.repo.DealAnalysisRepository;
import com.pricepilot.repo.PricePointRepository;
import com.pricepilot.repo.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides when a product is worth asking Claude about, asks, and stores the answer.
 *
 * <p>The judgement this class exists to make is <em>when not to ask</em>. Running an analysis on
 * every scrape would be both expensive and pointless: a price that has not moved does not have a new
 * verdict, and the monthly budget would be gone in days with nothing gained. So an analysis is
 * triggered only when the evidence has actually changed — the price moved by more than
 * {@code ai.reanalysis-price-delta} and the last verdict is older than
 * {@code ai.reanalysis-min-age-hours} — or when a user explicitly asks for a fresh opinion.
 *
 * <p>Nothing here is allowed to break price tracking. Claude being unconfigured, out of budget,
 * unreachable, rate-limited or simply wrong about JSON all end the same way: no new verdict row, a
 * log line, and a product that still has its full price history, charts and alerts.
 * {@link #analyseIfDue} therefore returns an empty Optional rather than throwing. The one exception
 * is {@link #analyseNow}, which a person triggered from the UI and who deserves to be told why it
 * did not work.
 *
 * <p>No database transaction is held open across the API call. A Claude request can take the better
 * part of a minute on a retry, and on a single small box with a handful of pool connections, holding
 * one for that long is how a slow dependency turns into a dead website. Evidence is read first, the
 * call happens with no connection checked out, and the result is written in one short save.
 */
@Service
public class DealAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DealAnalyzer.class);

    /** Window for the rolling average shown to the model. */
    private static final Duration AVERAGE_WINDOW = Duration.ofDays(30);

    private final ClaudeClient claude;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final ProductRepository products;
    private final PricePointRepository pricePoints;
    private final DealAnalysisRepository analyses;

    public DealAnalyzer(ClaudeClient claude,
                        ObjectMapper mapper,
                        AppProperties props,
                        ProductRepository products,
                        PricePointRepository pricePoints,
                        DealAnalysisRepository analyses) {
        this.claude = claude;
        this.mapper = mapper;
        this.props = props;
        this.products = products;
        this.pricePoints = pricePoints;
        this.analyses = analyses;
    }

    public boolean isEnabled() {
        return claude.isEnabled();
    }

    public Optional<DealAnalysis> latest(Long productId) {
        return analyses.findFirstByProductIdOrderByCreatedAtDesc(productId);
    }

    /**
     * Analyses the product if the evidence has moved enough to justify the spend.
     *
     * <p>Called by the scheduler after a successful scrape. Returns empty when the analysis was not
     * due, was not possible, or failed — all three are normal and none is worth an alarm.
     */
    public Optional<DealAnalysis> analyseIfDue(Long productId) {
        if (!claude.isEnabled()) {
            return Optional.empty();
        }
        if (!claude.hasBudget()) {
            log.debug("Skipping analysis of product {}: monthly Claude budget is used up", productId);
            return Optional.empty();
        }

        DealContext context;
        try {
            context = buildContext(productId);
        } catch (RuntimeException e) {
            log.warn("Could not assemble analysis context for product {}: {}", productId, e.toString());
            return Optional.empty();
        }
        if (context == null) {
            return Optional.empty();
        }

        DealAnalysis previous = latest(productId).orElse(null);
        if (!isDue(context, previous)) {
            return Optional.empty();
        }

        try {
            return Optional.of(runAnalysis(context, previous));
        } catch (AiException e) {
            // The price history is intact and the dashboard still works; the card just keeps the
            // previous verdict, or shows none. Logged at info for an expected condition like a spent
            // budget, warn for anything that suggests a real problem.
            if (e.isRetryable()) {
                log.warn("Analysis of product {} failed and may work later: {}", productId, e.getMessage());
            } else {
                log.info("Analysis of product {} was not possible: {}", productId, e.getMessage());
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            log.error("Unexpected failure analysing product {}", productId, e);
            return Optional.empty();
        }
    }

    /**
     * Forces a fresh analysis, ignoring the due check. Backs the "analyse again" button.
     *
     * @throws AiException when the analysis could not be produced, with a message written to be
     *                     shown to the person who clicked
     */
    public DealAnalysis analyseNow(Long productId) {
        DealContext context = buildContext(productId);
        if (context == null) {
            throw new AiException("There is no price for this product yet, so there is nothing to "
                                  + "analyse. Try again once a check has succeeded.", false, null);
        }
        return runAnalysis(context, latest(productId).orElse(null));
    }

    // ------------------------------------------------------------------ the decision to spend

    /**
     * Whether a fresh verdict is worth paying for.
     *
     * <p>Three gates, in increasing cost of being wrong. No previous verdict means always analyse —
     * a product with no verdict looks broken on the dashboard. A recent verdict is left alone
     * regardless of price movement, which puts a hard floor on how often one product can cost money.
     * And past that, the price must have moved by more than the configured fraction, because a
     * verdict is a judgement about a price and an unchanged price has the same judgement.
     */
    private boolean isDue(DealContext context, DealAnalysis previous) {
        if (previous == null) {
            return true;
        }

        Instant cutoff = Instant.now().minus(Duration.ofHours(props.getAi().getReanalysisMinAgeHours()));
        if (previous.getCreatedAt() != null && previous.getCreatedAt().isAfter(cutoff)) {
            return false;
        }

        BigDecimal before = previous.getAnalysedPrice();
        BigDecimal now = context.currentPrice();
        if (before == null || before.compareTo(BigDecimal.ZERO) <= 0 || now == null) {
            // No comparable baseline. Analysing is the safe choice: the alternative is a product
            // whose verdict can never refresh.
            return true;
        }

        BigDecimal delta = now.subtract(before).abs()
                .divide(before, 6, RoundingMode.HALF_UP);
        BigDecimal threshold = BigDecimal.valueOf(props.getAi().getReanalysisPriceDelta());
        boolean moved = delta.compareTo(threshold) >= 0;
        if (!moved) {
            log.debug("Product {} moved {}%, below the {}% re-analysis threshold; keeping the "
                      + "existing {} verdict",
                    context.productId(),
                    delta.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                    threshold.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                    previous.getVerdict());
        }
        return moved;
    }

    // ------------------------------------------------------------------ evidence

    /**
     * Gathers everything known about a product's pricing.
     *
     * <p>Returns null when there is no observation to reason about, which happens for a product
     * added seconds ago or one whose every retailer is currently blocking us. That is a legitimate
     * state, not an error — hence null-and-skip rather than an exception.
     */
    public DealContext buildContext(Long productId) {
        Product product = products.findById(productId).orElse(null);
        if (product == null) {
            log.debug("Product {} no longer exists; nothing to analyse", productId);
            return null;
        }

        List<PricePoint> latest = pricePoints.latestObservations(productId);
        if (latest.isEmpty()) {
            return null;
        }

        Instant now = Instant.now();
        List<DealContext.RetailerPrice> offers = new ArrayList<>(latest.size());
        for (PricePoint point : latest) {
            offers.add(new DealContext.RetailerPrice(
                    point.getListing().getRetailer(),
                    point.getPrice(),
                    point.isInStock(),
                    point.getCapturedAt(),
                    Duration.between(point.getCapturedAt(), now).toMinutes()));
        }

        // The headline price is the cheapest one that can actually be bought. Falling back to the
        // cheapest overall when nothing is in stock keeps the card populated — the out-of-stock flag
        // travels with the offer, so the model is not misled about whether it is purchasable.
        PricePoint best = latest.stream()
                .filter(PricePoint::isInStock)
                .findFirst()
                .orElse(latest.get(0));

        long observations = pricePoints.countForProduct(productId);
        long spanDays = pricePoints.firstObservedAt(productId)
                .map(first -> Duration.between(first, now).toDays())
                .orElse(0L);

        return new DealContext(
                productId,
                product.getTitle(),
                product.getBrand(),
                product.getCategory(),
                product.getCurrency(),
                best.getPrice(),
                best.getListing().getRetailer(),
                best.getListPrice(),
                pricePoints.allTimeLow(productId).orElse(null),
                pricePoints.allTimeHigh(productId).orElse(null),
                roundMoney(averageInWindow(productId, now.minus(AVERAGE_WINDOW))),
                observations,
                spanDays,
                List.copyOf(offers));
    }

    // ------------------------------------------------------------------ the call

    private DealAnalysis runAnalysis(DealContext context, DealAnalysis previous) {
        String userMessage = DealPrompt.userMessage(context);
        log.info("Analysing {} [{}]", context.title(), DealPrompt.summarise(context));

        ClaudeResponse response = claude.complete(
                DealPrompt.SYSTEM_PROMPT, userMessage, DealPrompt.JSON_PREFILL);

        if (response.truncated()) {
            throw new AiException("Claude's reply was cut off at the " + props.getAi().getMaxTokens()
                                  + "-token limit, so the JSON is incomplete. Raise ai.max-tokens.",
                    false, null);
        }

        DealVerdictJson parsed = parse(response.text());
        DealVerdict verdict = parsed.resolvedVerdict();

        // Loaded rather than referenced by proxy. getReferenceById outside a transaction hands back
        // a proxy bound to an already-closed session, which then blows up when the save runs in a
        // different one. A detached, fully-loaded entity is safe here: there is no cascade, so
        // Hibernate only reads its identifier to write the foreign key.
        Product product = products.findById(context.productId())
                .orElseThrow(() -> new AiException(
                        "Product " + context.productId() + " was deleted while it was being analysed",
                        false, null));

        DealAnalysis analysis = new DealAnalysis();
        analysis.setProduct(product);
        analysis.setVerdict(verdict);
        analysis.setConfidence(parsed.resolvedConfidence());
        analysis.setHeadline(parsed.resolvedHeadline(verdict));
        analysis.setReasoning(parsed.resolvedReasoning());
        analysis.setFakeDiscount(parsed.resolvedFakeDiscount());
        analysis.setSuggestedPrice(sanitiseSuggestedPrice(parsed, context));
        analysis.setAnalysedPrice(context.currentPrice());
        analysis.setModel(response.model());
        analysis.setInputTokens(toInt(response.totalInputTokens()));
        analysis.setOutputTokens(toInt(response.outputTokens()));
        analysis.setCostUsd(response.costUsd());

        DealAnalysis saved = analyses.save(analysis);

        log.info("Product {} -> {} ({} confidence){} | {} | ${} {}",
                context.productId(), verdict, saved.getConfidence(),
                previous == null ? "" : " was " + previous.getVerdict(),
                saved.getHeadline(), saved.getCostUsd().toPlainString(),
                response.cacheHit() ? "(prompt cache hit)" : "");
        return saved;
    }

    /**
     * Parses the reply, salvaging the object if the model wrapped it in anything.
     *
     * <p>The assistant prefill makes a bare JSON object overwhelmingly likely, so the salvage path
     * should never run. It is here because the failure it prevents is a wasted paid call: the money
     * is already spent by the time parsing happens, and throwing away a perfectly good verdict over
     * a trailing sentence would be the most annoying possible way to lose it.
     */
    private DealVerdictJson parse(String text) {
        try {
            return mapper.readValue(text, DealVerdictJson.class);
        } catch (Exception first) {
            String salvaged = outermostObject(text);
            if (salvaged != null) {
                try {
                    DealVerdictJson result = mapper.readValue(salvaged, DealVerdictJson.class);
                    log.warn("Claude's reply needed salvaging before it parsed. Raw reply: {}",
                            abbreviate(text));
                    return result;
                } catch (Exception ignored) {
                    // Fall through to the failure below, which carries the more useful first cause.
                }
            }
            throw new AiException("Claude's reply was not usable JSON: " + first.getMessage()
                                  + ". Raw reply: " + abbreviate(text), true, null, first);
        }
    }

    private static String outermostObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /**
     * Drops a suggested price that is above the current one.
     *
     * <p>"Wait until it costs more" is not advice anyone can act on, and showing it would undermine
     * every other number on the card. Equal to the current price is kept — for a product sitting at
     * its all-time low, "this is the price to buy at" is exactly right.
     */
    private static BigDecimal sanitiseSuggestedPrice(DealVerdictJson parsed, DealContext context) {
        BigDecimal suggested = parsed.resolvedSuggestedPrice();
        if (suggested == null || context.currentPrice() == null) {
            return suggested;
        }
        if (suggested.compareTo(context.currentPrice()) > 0) {
            log.debug("Dropping suggested price {} for product {}: it is above the current price {}",
                    suggested, context.productId(), context.currentPrice());
            return null;
        }
        return suggested;
    }

    private static BigDecimal roundMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The windowed average price, converted from the {@code Double} that {@code AVG} returns.
     *
     * <p>{@code BigDecimal.valueOf(double)} rather than {@code new BigDecimal(double)}: the
     * constructor keeps the full binary expansion, so an average of 1999.9 would arrive as
     * 1999.90000000000009094947017729282379150390625 and go into the prompt that way.
     */
    private BigDecimal averageInWindow(Long productId, java.time.Instant since) {
        Double average = pricePoints.averageSince(productId, since).orElse(null);
        return average == null ? null : BigDecimal.valueOf(average);
    }

    /** Token counts are stored as int; clamped rather than overflowed into a negative. */
    private static Integer toInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private static String abbreviate(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= 400 ? flat : flat.substring(0, 400) + "...";
    }
}
