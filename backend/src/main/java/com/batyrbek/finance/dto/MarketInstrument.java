package com.batyrbek.finance.dto;

import java.time.LocalDate;
import java.util.List;

public record MarketInstrument(
        String symbol, String name, String instrumentType, boolean active,
        String exchange, String cik, String source, LocalDate priceDate,
        List<String> availableHistoryRanges, Capabilities capabilities, boolean curated
) {
    public record Capabilities(boolean overview, boolean history, boolean financials, boolean earnings) {}
}
