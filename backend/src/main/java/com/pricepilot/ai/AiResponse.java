package com.pricepilot.ai;

import java.math.BigDecimal;

/** A provider-neutral reply. A null cost means the provider's rates are not configured. */
public record AiResponse(
        String text, String model, long inputTokens, long outputTokens,
        long cacheWriteTokens, long cacheReadTokens, BigDecimal costUsd, String stopReason) {

    public long totalInputTokens() { return inputTokens + cacheWriteTokens + cacheReadTokens; }
    public boolean truncated() { return "max_tokens".equals(stopReason) || "length".equals(stopReason); }
    public boolean cacheHit() { return cacheReadTokens > 0; }

    static AiResponse from(ClaudeResponse response) {
        return new AiResponse(response.text(), response.model(), response.inputTokens(), response.outputTokens(),
                response.cacheWriteTokens(), response.cacheReadTokens(), response.costUsd(), response.stopReason());
    }
}
