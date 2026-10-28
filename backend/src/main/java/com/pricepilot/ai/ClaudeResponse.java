package com.pricepilot.ai;

import java.math.BigDecimal;

/**
 * One reply from the Claude Messages API, with the accounting attached.
 *
 * @param text              the assistant's text content, concatenated across content blocks
 * @param model             the model that actually served the request, as reported by the API
 *                          rather than as requested — they can differ, and the cost follows the
 *                          one that served it
 * @param inputTokens       uncached input tokens
 * @param outputTokens      generated tokens
 * @param cacheWriteTokens  input tokens written into the prompt cache on this call
 * @param cacheReadTokens   input tokens served from the prompt cache, billed at a tenth of the
 *                          normal rate. A non-zero value here is the proof that caching is working
 * @param costUsd           computed from the four token counts by {@link ClaudePricing}
 * @param stopReason        why generation stopped. {@code max_tokens} means the JSON was cut off
 *                          mid-object, which is worth distinguishing from a malformed reply
 */
public record ClaudeResponse(
        String text,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheWriteTokens,
        long cacheReadTokens,
        BigDecimal costUsd,
        String stopReason) {

    public long totalInputTokens() {
        return inputTokens + cacheWriteTokens + cacheReadTokens;
    }

    /** True when the reply was cut short, so the caller should not expect parseable JSON. */
    public boolean truncated() {
        return "max_tokens".equals(stopReason);
    }

    public boolean cacheHit() {
        return cacheReadTokens > 0;
    }
}
