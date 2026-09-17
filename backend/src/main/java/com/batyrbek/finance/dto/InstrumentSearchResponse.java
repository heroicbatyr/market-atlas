package com.batyrbek.finance.dto;

import java.util.List;

public record InstrumentSearchResponse(String resolution, List<MarketInstrument> results) {
    public static InstrumentSearchResponse from(List<MarketInstrument> results) {
        return new InstrumentSearchResponse(results.isEmpty() ? "not_found" : "resolved", results);
    }
}
