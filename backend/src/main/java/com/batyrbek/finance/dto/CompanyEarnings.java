package com.batyrbek.finance.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CompanyEarnings(
        String symbol, String currency, List<QuarterlyEarnings> reported,
        LocalDate expectedDate, String source, Instant fetchedAt, boolean stale
) {
    public CompanyEarnings asStale() {
        return new CompanyEarnings(symbol, currency, reported, expectedDate, source, fetchedAt, true);
    }
}
