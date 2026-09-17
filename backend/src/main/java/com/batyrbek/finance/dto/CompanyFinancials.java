package com.batyrbek.finance.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CompanyFinancials(
        String symbol, String currency, List<AnnualFinancial> annual,
        Double cashAndEquivalents, Double totalDebt, Double debtToEquity,
        String source, Instant fetchedAt, LocalDate dataAsOf, boolean stale
) {
    public CompanyFinancials asStale() {
        return new CompanyFinancials(symbol, currency, annual, cashAndEquivalents, totalDebt,
                debtToEquity, source, fetchedAt, dataAsOf, true);
    }
}
