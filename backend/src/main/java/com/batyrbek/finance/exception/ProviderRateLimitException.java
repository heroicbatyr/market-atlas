package com.batyrbek.finance.exception;

import java.time.Instant;

public class ProviderRateLimitException extends StockProviderException {
    private final Instant retryAfter;

    public ProviderRateLimitException() {
        this(Instant.now().plusSeconds(60));
    }

    public ProviderRateLimitException(Instant retryAfter) {
        super("The market-data provider is temporarily rate limited. Please try again later.");
        this.retryAfter = retryAfter;
    }

    public Instant retryAfter() { return retryAfter; }
}
