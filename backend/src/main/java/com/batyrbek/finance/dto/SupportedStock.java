package com.batyrbek.finance.dto;

public record SupportedStock(
        String symbol, String name, String sector, String category, boolean popular, FmpCapabilities fmp
) {
    public record FmpCapabilities(boolean overview, boolean history, boolean financials) {}
}
