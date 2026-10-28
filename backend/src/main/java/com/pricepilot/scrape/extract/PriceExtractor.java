package com.pricepilot.scrape.extract;

import com.pricepilot.scrape.SnapshotBuilder;
import com.pricepilot.scrape.rules.HostRule;
import org.jsoup.nodes.Document;

/**
 * One way of getting a price out of a page.
 *
 * <p>The chain is ordered by the host's {@code strategies} list in {@code domain-rules.yml}, which
 * means the order can be changed per retailer without touching Java. Structured data comes first
 * for most hosts and CSS comes first for Amazon, and both of those are configuration decisions
 * rather than code.
 *
 * <p>Implementations must never throw. A strategy that fails is a strategy that contributed
 * nothing, not a failed scrape — the next one in the chain still deserves its turn. The engine
 * enforces this by catching anything that escapes, but implementations should handle their own
 * malformed input, because a swallowed exception with no context is hard to debug at 3am.
 */
public interface PriceExtractor {

    /**
     * Must match the strategy key used in {@code domain-rules.yml}: one of {@code json-ld},
     * {@code microdata}, {@code meta}, {@code embedded-json}, {@code css}. This same string is
     * stored on every {@code scrape_attempt} row, so it is also what the ops page groups by.
     */
    String name();

    /**
     * Adds whatever this strategy can find to {@code out}. Fields already present must not be
     * overwritten — {@link SnapshotBuilder} enforces first-wins, so simply offering everything
     * found is correct.
     *
     * @param doc  the parsed document, with its base URI set so relative image URLs resolve
     * @param html the raw response body, for strategies that need to look outside the DOM
     */
    void contribute(Document doc, String html, HostRule rule, SnapshotBuilder out);
}
