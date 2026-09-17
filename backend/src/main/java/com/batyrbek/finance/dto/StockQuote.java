package com.batyrbek.finance.dto;

import java.time.Instant;
import java.time.LocalDate;

public record StockQuote(
        Double price, Double change, Double changePercent, Long volume,
        Double peRatio, Double eps, Double week52High, Double week52Low,
        Instant updatedAt, LocalDate priceDate
) {
    public StockQuote(Double price, Double change, Double changePercent, Long volume,
                      Double peRatio, Double eps, Double week52High, Double week52Low,
                      Instant updatedAt) {
        this(price, change, changePercent, volume, peRatio, eps, week52High, week52Low,
                updatedAt, null);
    }
}
