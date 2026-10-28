package com.pricepilot.ai;

import com.pricepilot.domain.DealVerdict;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the two halves of the Claude request: a fixed rubric and a per-product fact sheet.
 *
 * <p>The split is deliberate and load-bearing. The rubric is byte-identical on every call, which is
 * what makes it cacheable — it is sent as the cached system block, so after the first call of a
 * session the bulk of the input is billed at a tenth of the normal rate. The fact sheet changes
 * every time and goes in the user turn, where caching would never hit anyway.
 *
 * <p>Two things the prompt does that are worth pointing out, because they are where a price adviser
 * usually goes wrong:
 *
 * <ul>
 *   <li><b>It forbids outside knowledge.</b> The model is told, in as many words, that it may only
 *       reason from the supplied figures. A model asked about a phone will otherwise happily recall
 *       a launch price from its training data and compare against it, which produces confident
 *       advice built on a number that was never observed and may be two years stale.
 *   <li><b>It treats MRP as a claim.</b> In the Indian market a struck-through "₹1,29,900" next to a
 *       "42% off" badge is frequently a number the product has never actually sold at. The rubric
 *       asks for that to be called out against the observed price history, which is the one piece of
 *       evidence a shopper does not have and this system does.
 * </ul>
 *
 * <p>The reply is constrained to JSON by prefilling the assistant turn with an opening brace rather
 * than by asking politely, so there is no markdown fence or preamble to strip.
 */
public final class DealPrompt {

    private DealPrompt() {
    }

    /** Prefilled as the first characters of the reply, forcing JSON from the first token. */
    public static final String JSON_PREFILL = "{";

    /**
     * The cached half. Keep this stable: editing it invalidates the prompt cache, so the next call
     * pays a cache write instead of a cache read. That is a few hundredths of a cent, not a crisis,
     * but it is the reason this is a constant and not assembled per call.
     */
    public static final String SYSTEM_PROMPT = """
            You are the pricing analyst inside PricePilot, a price-tracking service for Indian \
            online shoppers. You are given the full observed price history of one product, collected \
            by scraping real retailer product pages, and you decide whether buying it right now is a \
            good idea.

            ## Absolute constraints

            1. Reason ONLY from the figures in the fact sheet you are given. You have no other \
            knowledge of this product's pricing. Do not recall launch prices, typical street prices, \
            competitor prices, or upcoming sales from memory, and do not reason about them. If a \
            figure you want is not in the fact sheet, you do not have it.
            2. Never invent a number. Every figure in your reasoning must appear in the fact sheet or \
            be a direct comparison between figures that do.
            3. "MRP" is the retailer's own claimed list price. It is a marketing figure, not an \
            observation. Judge discounts against the observed price history, never against MRP alone.
            4. If the observed history is too thin to support a judgement, say so with the \
            INSUFFICIENT_DATA verdict rather than guessing. A hedged verdict on no evidence is worse \
            than admitting there is no evidence.
            5. All prices are in Indian rupees unless the fact sheet says otherwise.

            ## Verdicts

            Choose exactly one:

            - STRONG_BUY — at or very near the lowest price ever observed, and meaningfully below the \
            recent average. The kind of price that does not come round often.
            - GOOD_VALUE — clearly below the recent average and in the lower part of the observed \
            range, without being the outright floor.
            - FAIR — around the typical observed price. Nothing wrong with buying, nothing gained by \
            rushing.
            - WAIT — in the upper part of the observed range, or recently risen, with evidence it has \
            been and plausibly will again be cheaper. Use this when the history shows the price moves.
            - OVERPRICED — at or near the highest price observed, or well above every other retailer \
            tracked.
            - INSUFFICIENT_DATA — fewer than a handful of observations, or no price history to compare \
            against. Do not dress up a guess as one of the other verdicts.

            ## Fake discount detection

            Set fake_discount to true when the advertised discount off MRP is not supported by the \
            observed history. The clearest signal: the "discounted" price is at or above the average \
            of what the product has actually been selling for, while the badge claims a large saving. \
            A genuine discount shows up as a price below the observed norm. If there is no MRP, or no \
            history to compare against, set fake_discount to false — it is a claim about evidence, \
            and absence of evidence is not evidence of a fake.

            ## Confidence

            A number from 0.0 to 1.0 reflecting how much the evidence supports your verdict. Drive it \
            from the evidence, not from how strong the verdict sounds:

            - 0.85-1.0: many observations over a long span, a clear picture
            - 0.6-0.85: enough history to be reasonably sure
            - 0.3-0.6: thin history, or conflicting signals between retailers
            - below 0.3: barely any evidence; pair this with INSUFFICIENT_DATA

            ## Output format

            Reply with a single JSON object and nothing else. No markdown fence, no commentary \
            before or after.

            {
              "verdict": "one of the six verdicts above",
              "confidence": 0.0 to 1.0,
              "headline": "one sentence, under 120 characters, written to be read on a card in a \
            dashboard. Lead with the decision. No markdown.",
              "reasoning": "2-4 sentences citing the specific figures that drove the verdict. Write \
            to a shopper, not an analyst: plain language, concrete numbers, no jargon, no bullet \
            points, no markdown.",
              "fake_discount": true or false,
              "suggested_price": a number, or null
            }

            suggested_price is the price at which you would call this a clear buy, expressed as a \
            plain number with no currency symbol or separators. Base it on the observed history — the \
            all-time low is usually the right anchor. Use null when there is not enough history to \
            name a figure.

            Write the headline and reasoning as if the reader is about to spend their own money on \
            this. Be direct, be specific, and do not hedge every sentence.""";

    /**
     * Renders one product's facts as the user turn.
     *
     * <p>Plain labelled lines rather than JSON. It reads unambiguously, costs fewer tokens than the
     * equivalent JSON, and — the part that matters — stays legible in the logs, so when a verdict is
     * questioned the exact input can be read back without decoding anything.
     */
    public static String userMessage(DealContext ctx) {
        StringBuilder sb = new StringBuilder(1024);

        sb.append("Product: ").append(ctx.title()).append('\n');
        if (notBlank(ctx.brand())) {
            sb.append("Brand: ").append(ctx.brand()).append('\n');
        }
        if (notBlank(ctx.category())) {
            sb.append("Category: ").append(ctx.category()).append('\n');
        }
        sb.append("Currency: ").append(ctx.currency()).append('\n');

        sb.append("\n## Right now\n");
        sb.append("Best current price: ").append(money(ctx.currentPrice()))
                .append(" at ").append(ctx.currentRetailer()).append('\n');

        if (ctx.mrp() != null) {
            sb.append("Retailer's claimed MRP: ").append(money(ctx.mrp()));
            ctx.claimedDiscountPercent().ifPresent(pct ->
                    sb.append(" (advertised as ").append(plain(pct)).append("% off)"));
            sb.append('\n');
        } else {
            sb.append("Retailer's claimed MRP: not published on the page\n");
        }

        sb.append("\n## Observed price history\n");
        sb.append("Observations recorded: ").append(ctx.observations());
        if (ctx.historySpanDays() > 0) {
            sb.append(" over ").append(ctx.historySpanDays()).append(" days");
        }
        sb.append('\n');

        if (ctx.hasHistory()) {
            sb.append("Lowest ever observed: ").append(money(ctx.allTimeLow())).append('\n');
            sb.append("Highest ever observed: ").append(money(ctx.allTimeHigh())).append('\n');
            if (ctx.average30d() != null) {
                sb.append("Average over the last 30 days: ").append(money(ctx.average30d()));
                ctx.belowAveragePercent().ifPresent(pct -> {
                    if (pct.signum() >= 0) {
                        sb.append(" (current price is ").append(plain(pct)).append("% below it)");
                    } else {
                        sb.append(" (current price is ").append(plain(pct.negate()))
                                .append("% above it)");
                    }
                });
                sb.append('\n');
            }
            if (ctx.atAllTimeLow()) {
                sb.append("The current price matches the lowest price ever observed.\n");
            } else {
                ctx.premiumOverLowPercent().ifPresent(pct ->
                        sb.append("Current price is ").append(plain(pct))
                                .append("% above the lowest ever observed.\n"));
            }
            ctx.positionInRangePercent().ifPresent(pct ->
                    sb.append("Position in the observed range: ").append(plain(pct))
                            .append("% of the way from the lowest to the highest.\n"));
        } else {
            sb.append("Not enough history yet to establish a range. Tracking started recently.\n");
        }

        sb.append("\n## Retailers tracked for this product\n");
        List<DealContext.RetailerPrice> offers = ctx.retailers();
        if (offers.isEmpty()) {
            sb.append("None with a recorded price.\n");
        } else {
            for (DealContext.RetailerPrice offer : offers) {
                sb.append("- ").append(offer.retailer()).append(": ").append(money(offer.price()));
                sb.append(offer.inStock() ? " (in stock" : " (OUT OF STOCK");
                sb.append(", checked ").append(describeAge(offer.ageMinutes())).append(")\n");
            }
            ctx.retailerSpreadPercent().ifPresent(pct ->
                    sb.append("Spread between the cheapest and dearest in-stock retailer: ")
                            .append(plain(pct)).append("%\n"));
        }

        sb.append("\nGive your verdict as the single JSON object described in your instructions.");
        return sb.toString();
    }

    /**
     * A one-line summary of the input, for the application log.
     *
     * <p>Logged alongside every verdict so that a surprising answer can be traced to the facts it was
     * based on without re-running anything.
     */
    public static String summarise(DealContext ctx) {
        return "product=" + ctx.productId()
               + " price=" + money(ctx.currentPrice())
               + " low=" + (ctx.allTimeLow() == null ? "-" : money(ctx.allTimeLow()))
               + " high=" + (ctx.allTimeHigh() == null ? "-" : money(ctx.allTimeHigh()))
               + " obs=" + ctx.observations()
               + " retailers=" + ctx.retailers().size();
    }

    /** The verdict names the model is allowed to return, used in validation error messages. */
    public static List<String> allowedVerdicts() {
        return Arrays.stream(DealVerdict.values()).map(Enum::name).toList();
    }

    // ------------------------------------------------------------------ formatting

    private static String money(BigDecimal value) {
        return value == null ? "unknown" : plain(value);
    }

    /**
     * A bare number: no currency symbol and no grouping separators.
     *
     * <p>Separators are left out on purpose. The Indian grouping style — "1,29,900" — tokenises
     * badly, and the model does not need separators to compare two figures. {@code toPlainString}
     * also rules out scientific notation, which {@code stripTrailingZeros} can otherwise produce for
     * round numbers.
     */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String describeAge(long minutes) {
        if (minutes < 2) {
            return "just now";
        }
        if (minutes < 90) {
            return minutes + " minutes ago";
        }
        long hours = minutes / 60;
        if (hours < 36) {
            return hours + " hours ago";
        }
        return (hours / 24) + " days ago";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
