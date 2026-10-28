package com.pricepilot.discovery;

/**
 * A candidate product page found by search, before anything is known about its price.
 *
 * <p>The name is precise on purpose. This is a <em>URL worth fetching</em>, nothing more. Tavily
 * returns a title and a content snippet alongside each result, and those snippets frequently contain
 * a price — which is exactly why this type does not have a price field. A snippet price is whatever
 * the search index last saw, with no way to tell whether that was ten minutes or ten weeks ago, and
 * storing one would silently poison the price history that the entire product is built on.
 *
 * <p>So a discovered listing is a lead. It becomes a tracked listing only after the scraping engine
 * fetches the page for real and reads a price off it. If the fetch fails, the lead is dropped and no
 * row is written.
 *
 * @param url       canonicalised product URL, ready to hand to the scraping engine
 * @param host      the host it belongs to, already confirmed as one with extraction rules
 * @param retailer  display name for the retailer
 * @param urlHash   canonical hash, used to spot a URL already being tracked before fetching it
 * @param title     the title search reported. Used only to score relevance, never stored as the
 *                  product title — that comes from the page itself
 * @param relevance the search engine's own score, 0 to 1, used for ordering candidates
 */
public record DiscoveredListing(
        String url,
        String host,
        String retailer,
        String urlHash,
        String title,
        double relevance) {
}
