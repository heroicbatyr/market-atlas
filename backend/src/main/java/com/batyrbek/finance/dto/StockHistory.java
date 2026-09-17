package com.batyrbek.finance.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record StockHistory(
        String ticker, String currency, String range, String resolution,
        List<PricePoint> points, Instant updatedAt, LocalDate dataAsOf,
        Instant nextCheckAt, boolean stale
) {
    public StockHistory(String ticker, String currency, String range, String resolution,
                        List<PricePoint> points, Instant updatedAt, boolean stale) {
        this(ticker, currency, range, resolution, points, updatedAt,
                points == null || points.isEmpty() ? null : points.getLast().date(), null, stale);
    }

    public StockHistory asStale() {
        return new StockHistory(ticker, currency, range, resolution, points, updatedAt,
                dataAsOf, nextCheckAt, true);
    }
}
