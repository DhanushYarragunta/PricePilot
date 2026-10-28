package com.pricepilot.scrape;

import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.scrape.extract.PriceExtractor;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.scrape.rules.SelectorSpec;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches one product page and turns it into a price. The centre of the whole project.
 *
 * <p>Every price PricePilot stores comes through here, and every one of them is read off a live
 * product page at the moment it is stored. Nothing is inferred from a search result, a cached
 * aggregator, or a snippet — Tavily is used elsewhere to <em>find</em> which pages exist, and never
 * to say what anything costs. That constraint is why this class is as careful as it is.
 *
 * <p>The pipeline, in order:
 *
 * <ol>
 *   <li><b>Politeness.</b> {@link PolitenessGate} enforces one request per host at a time, a
 *       jittered minimum delay, and a global concurrency cap.
 *   <li><b>Fetch.</b> {@link HttpPageFetcher} for free, {@link UnblockerPageFetcher} when a host has
 *       defeated the free path and a provider is configured.
 *   <li><b>Block detection.</b> Before parsing, because a CAPTCHA page parses perfectly well and
 *       would otherwise be misdiagnosed as a selector problem.
 *   <li><b>Extraction.</b> The strategies named by the host's rule, in the rule's order, each
 *       contributing what it can until the snapshot is complete.
 *   <li><b>Availability.</b> Resolved from the page separately, since the price of an out-of-stock
 *       item is real but not actionable.
 * </ol>
 *
 * <p>Two rules this class holds to without exception. It never throws for an ordinary scraping
 * failure — it returns a {@link ScrapeResult} carrying the diagnosis, so the caller has one code
 * path and cannot forget to record the bad case. And it never lets one retailer's problem become
 * everyone's: a malformed selector, a hostile regex, a 4MB response or an extractor throwing on
 * weird input all end as a single logged, recorded failure for one listing.
 */
@Service
public class ScrapeEngine {

    private static final Logger log = LoggerFactory.getLogger(ScrapeEngine.class);

    /** Phrases are only trusted inside a reasonably small element; see {@link #resolveAvailability}. */
    private static final int MAX_AVAILABILITY_TEXT_LENGTH = 300;

    private final DomainRules rules;
    private final PolitenessGate gate;
    private final HttpPageFetcher directFetcher;
    private final UnblockerPageFetcher unblocker;
    private final UrlCanonicalizer canonicalizer;

    /** Extractors by strategy name, so the rule file's ordering can drive the chain. */
    private final Map<String, PriceExtractor> extractors = new LinkedHashMap<>();

    public ScrapeEngine(DomainRules rules,
                        PolitenessGate gate,
                        HttpPageFetcher directFetcher,
                        UnblockerPageFetcher unblocker,
                        UrlCanonicalizer canonicalizer,
                        List<PriceExtractor> available) {
        this.rules = rules;
        this.gate = gate;
        this.directFetcher = directFetcher;
        this.unblocker = unblocker;
        this.canonicalizer = canonicalizer;
        for (PriceExtractor extractor : available) {
            extractors.put(extractor.name(), extractor);
        }
        log.info("Scrape engine ready with strategies {} and {} host rules; unblocker fallback is {}",
                extractors.keySet(), rules.all().size(),
                unblocker.isAvailable() ? "configured" : "not configured (free direct fetches only)");
    }

    /**
     * Scrapes a URL that has already been canonicalised and stored.
     *
     * @param url  a canonical product URL
     * @param host the host it belongs to, used to pick the rule
     */
    public ScrapeResult scrape(String url, String host) {
        return scrape(url, rules.forHost(host));
    }

    /**
     * Scrapes a raw, user-supplied URL, canonicalising it first. Used by the add-product flow,
     * where the URL has not been through the pipeline yet.
     *
     * @throws UnsupportedUrlException if the URL is not a usable product page. This one <em>does</em>
     *                                 throw, because it is a refusal to be shown to the person who
     *                                 typed it, not a scraping failure to be recorded
     */
    public ScrapeResult scrapeRaw(String rawUrl) {
        UrlCanonicalizer.Canonical canonical = canonicalizer.canonicalize(rawUrl);
        return scrape(canonical.url(), canonical.rule());
    }

    // ------------------------------------------------------------------ main pipeline

    public ScrapeResult scrape(String url, HostRule rule) {
        String host = rule.host();

        // A host in cool-off gets no direct request at all. But cool-off is about our IP address,
        // and the unblocker's request leaves from somewhere else entirely, so the fallback is still
        // allowed — this is in fact exactly the situation it was bought for.
        if (gate.isCoolingOff(host)) {
            if (unblocker.isAvailable()) {
                log.debug("{} is cooling off; going straight to the unblocker", host);
                return viaUnblocker(url, rule, "direct fetches are paused: " + host
                        + " is in cool-off after blocking us");
            }
            return ScrapeResult.aborted(ScrapeOutcome.BLOCKED, host, url, null, 0,
                    host + " recently blocked us and is in cool-off. No request was sent. "
                    + "Configure SCRAPER_PROVIDER to fetch hosts like this through a residential "
                    + "proxy, or wait for the cool-off to expire.");
        }

        if (rule.unblockerFirst() && unblocker.isAvailable()) {
            ScrapeResult paid = viaUnblocker(url, rule, null);
            if (paid.isSuccess()) {
                return paid;
            }
            log.info("Unblocker-first fetch of {} returned {}; falling back to a direct attempt",
                    url, paid.outcome());
        }

        ScrapeResult direct;
        try {
            direct = gate.execute(host, rule.minDelayMs(), () -> fetchAndExtract(url, rule));
        } catch (ScrapeException e) {
            direct = ScrapeResult.aborted(e.outcome(), host, url, e.httpStatus(), 0, e.getMessage());
        }

        if (direct.isSuccess() || !shouldEscalate(direct)) {
            return direct;
        }

        if (!unblocker.isAvailable()) {
            return direct;
        }

        log.info("Direct fetch of {} returned {} — escalating to the unblocker", url, direct.outcome());
        ScrapeResult paid = viaUnblocker(url, rule, direct.message());
        // If the paid attempt also failed, report the free attempt's diagnosis. It is the more
        // informative of the two: "Amazon served a CAPTCHA" is more actionable than "the provider
        // gave up too".
        return paid.isSuccess() ? paid : direct;
    }

    /**
     * Whether a failed direct fetch is worth paying to retry.
     *
     * <p>Only blocks escalate. {@code NO_PRICE} deliberately does not, even though a stealth block
     * can look like one, because the far more common cause is a stale selector — and spending a
     * paid credit on every listing affected by a rules bug would empty a month's budget in an hour
     * while fixing nothing. The free attempt's {@code NO_PRICE} shows up on the ops page by host and
     * strategy, which points at the actual fix.
     */
    private static boolean shouldEscalate(ScrapeResult direct) {
        return direct.outcome() == ScrapeOutcome.BLOCKED;
    }

    private ScrapeResult viaUnblocker(String url, HostRule rule, String priorReason) {
        try {
            Optional<FetchedPage> page = unblocker.fetch(url, rule);
            if (page.isEmpty()) {
                return ScrapeResult.aborted(ScrapeOutcome.BLOCKED, rule.host(), url, null, 0,
                        (priorReason == null ? "" : priorReason + " — ")
                        + "unblocker unavailable (not configured, or monthly budget used up)");
            }
            return extractFrom(page.get(), rule);
        } catch (ScrapeException e) {
            return ScrapeResult.aborted(e.outcome(), rule.host(), url, e.httpStatus(), 0,
                    "unblocker: " + e.getMessage());
        }
    }

    /** The free path: one politeness-gated fetch, then the shared extraction half. */
    private ScrapeResult fetchAndExtract(String url, HostRule rule) {
        FetchedPage page = directFetcher.fetch(url, rule);
        return extractFrom(page, rule);
    }

    /**
     * The half of the pipeline that is identical whether the bytes were free or paid for. Keeping it
     * shared is what makes the unblocker a fetch source rather than a second code path — there is
     * exactly one place where HTML becomes a price.
     */
    private ScrapeResult extractFrom(FetchedPage page, HostRule rule) {
        String host = rule.host();

        if (BlockDetector.isGone(page.status())) {
            return ScrapeResult.failure(ScrapeOutcome.HTTP_ERROR, host, page,
                    "HTTP " + page.status() + " — the retailer says this product page no longer "
                    + "exists. The listing will be retired.");
        }

        ScrapeOutcome statusOutcome = BlockDetector.classifyStatus(page.status());
        if (statusOutcome == ScrapeOutcome.BLOCKED) {
            if (page.source() == FetchedPage.FetchSource.DIRECT) {
                gate.recordBlocked(host);
            }
            return ScrapeResult.failure(ScrapeOutcome.BLOCKED, host, page,
                    "HTTP " + page.status() + " — refused by " + host + "'s anti-bot layer");
        }
        if (statusOutcome == ScrapeOutcome.HTTP_ERROR) {
            return ScrapeResult.failure(ScrapeOutcome.HTTP_ERROR, host, page,
                    "HTTP " + page.status() + " from " + host);
        }

        Document doc;
        try {
            // The base URI is the final URL, not the requested one, so that relative image paths
            // resolve correctly even when the retailer redirected us somewhere else.
            doc = Jsoup.parse(page.html(), page.finalUrl());
        } catch (RuntimeException e) {
            return ScrapeResult.failure(ScrapeOutcome.ERROR, host, page,
                    "Could not parse the response as HTML: " + e.getMessage());
        }

        String blockReason = BlockDetector.blockReason(page, doc, rule);
        if (blockReason != null) {
            if (page.source() == FetchedPage.FetchSource.DIRECT) {
                gate.recordBlocked(host);
            }
            return ScrapeResult.failure(ScrapeOutcome.BLOCKED, host, page,
                    "HTTP 200 but it was not a product page: " + blockReason);
        }

        if (page.redirected()) {
            log.debug("{} redirected to {}", page.requestedUrl(), page.finalUrl());
        }

        SnapshotBuilder builder = new SnapshotBuilder(rule.currency());
        StringBuilder tried = new StringBuilder();

        for (String strategyName : rule.strategies()) {
            PriceExtractor extractor = extractors.get(strategyName);
            if (extractor == null) {
                // DomainRules validates strategy names at load time, so this means a new strategy
                // was added to the rules file without a matching bean.
                log.warn("No extractor registered for strategy '{}' (host {})", strategyName, host);
                continue;
            }
            if (!tried.isEmpty()) {
                tried.append(", ");
            }
            tried.append(strategyName);
            try {
                extractor.contribute(doc, page.html(), rule, builder);
            } catch (RuntimeException e) {
                // One strategy failing is not a failed scrape. Log it with the host and strategy so
                // it is findable, and let the rest of the chain run.
                log.warn("Strategy {} threw on {}: {}: {}", strategyName, host,
                        e.getClass().getSimpleName(), e.getMessage());
            }
            if (builder.isComplete()) {
                break;
            }
        }

        if (!builder.hasPrice()) {
            return ScrapeResult.noPrice(host, page,
                    "Fetched " + page.bytes() + " bytes successfully but found no price. Tried: "
                    + tried + ". The selectors for " + host + " are probably stale — check "
                    + "domain-rules.yml against the page source.");
        }

        resolveAvailability(doc, rule, builder);

        if (page.source() == FetchedPage.FetchSource.DIRECT) {
            gate.recordSuccess(host);
        }

        ProductSnapshot snapshot = builder.build();
        log.debug("{} -> {} {} via {} ({}ms, {} bytes)", host, snapshot.currency(),
                snapshot.price(), snapshot.strategy(), page.latencyMs(), page.bytes());
        return ScrapeResult.success(snapshot, host, page);
    }

    // ------------------------------------------------------------------ availability

    /**
     * Works out whether the item can actually be bought.
     *
     * <p>Worth getting right, because a price with no stock behind it is the most annoying kind of
     * wrong answer: the alert fires, the user clicks through, and the button says "Notify me". The
     * structured-data strategies already set this when the page declares it, and
     * {@link SnapshotBuilder} keeps the first answer, so everything here is a fallback for pages
     * that declare nothing.
     *
     * <p>Evidence is ranked. An explicit out-of-stock element from the rule beats everything. A
     * present add-to-cart button is strong positive evidence. A text phrase is the weakest, because
     * "Out of stock" appears in recommendation tiles for other products — which is why the phrase
     * search is confined to small elements and skipped entirely once better evidence exists.
     */
    private void resolveAvailability(Document doc, HostRule rule, SnapshotBuilder builder) {
        for (SelectorSpec spec : rule.outOfStockSelectors()) {
            if (matchesVisible(doc, spec)) {
                builder.offerInStock(false);
                return;
            }
        }
        for (SelectorSpec spec : rule.availabilitySelectors()) {
            if (matchesVisible(doc, spec)) {
                builder.offerInStock(true);
                return;
            }
        }

        List<String> phrases = rule.outOfStockText();
        if (phrases.isEmpty()) {
            return;
        }
        for (Element el : doc.select("div, span, p, button, strong, h1, h2, h3, td, li")) {
            String text = el.ownText().trim();
            if (text.isEmpty() || text.length() > MAX_AVAILABILITY_TEXT_LENGTH) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String phrase : phrases) {
                if (lower.contains(phrase)) {
                    builder.offerInStock(false);
                    return;
                }
            }
        }
    }

    /**
     * True when a selector matches something that is actually on the page. The emptiness check
     * matters: storefronts ship both the in-stock and out-of-stock containers in the markup and
     * reveal one of them, so a bare "does this element exist" test reports everything as out of
     * stock.
     */
    private static boolean matchesVisible(Document doc, SelectorSpec spec) {
        try {
            for (Element el : doc.select(spec.cssSelector())) {
                if (spec.usesAttribute()) {
                    if (!el.attr(spec.attribute()).isBlank()) {
                        return true;
                    }
                } else if (!el.text().isBlank()) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Invalid availability selector '{}': {}", spec.cssSelector(), e.getMessage());
        }
        return false;
    }
}
