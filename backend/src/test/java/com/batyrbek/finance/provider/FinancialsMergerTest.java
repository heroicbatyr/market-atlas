package com.batyrbek.finance.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.batyrbek.finance.dto.AnnualFinancial;
import com.batyrbek.finance.dto.CompanyFinancials;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialsMergerTest {
    @Test
    void fillsOnlyMissingFactsAndRetainsTheOriginalFilingLink() {
        LocalDate date = LocalDate.parse("2025-12-31");
        Instant fetched = Instant.parse("2026-02-20T20:00:00Z");
        CompanyFinancials sec = new CompanyFinancials("TEST", "USD", List.of(
                new AnnualFinancial(date, "2025", 100.0, 30.0, 20.0, 2.0,
                        null, null, 0.2, "https://www.sec.gov/Archives/edgar/data/1/2/3-index.htm",
                        "10-K", LocalDate.parse("2026-02-20"))), 10.0, null, null,
                "SEC EDGAR", fetched, date, false);
        CompanyFinancials fmp = new CompanyFinancials("TEST", "USD", List.of(
                new AnnualFinancial(date, "2025", 999.0, 999.0, 999.0, 999.0,
                        18.0, 0.1, 0.9)), 999.0, 5.0, 0.5, "FMP", fetched, date, false);

        CompanyFinancials merged = FinancialsMerger.fillMissing(sec, fmp);

        assertThat(merged.annual().getFirst().revenue()).isEqualTo(100.0);
        assertThat(merged.annual().getFirst().freeCashFlow()).isEqualTo(18.0);
        assertThat(merged.annual().getFirst().filingUrl()).isEqualTo(sec.annual().getFirst().filingUrl());
        assertThat(merged.cashAndEquivalents()).isEqualTo(10.0);
        assertThat(merged.totalDebt()).isEqualTo(5.0);
        assertThat(merged.source()).isEqualTo("SEC EDGAR + FMP");
    }
}
