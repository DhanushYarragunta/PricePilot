package com.pricepilot.domain;

/**
 * Paid third-party services whose monthly consumption is metered and capped.
 */
public enum ApiProvider {
    /** Claude, for deal analysis. Metered in tokens and dollars. */
    ANTHROPIC,
    /** OpenAI-compatible gateways, including AgentRouter. Provider rates are configured separately. */
    OPENAI_COMPATIBLE,
    /** Tavily, for cross-retailer discovery. Metered in calls. */
    TAVILY,
    /** Optional scraping/unblocker API, the last resort in the fetch chain. */
    UNBLOCKER,
    /** BrowserAct workflow submissions, including unsuccessful attempts. */
    BROWSERACT
}
