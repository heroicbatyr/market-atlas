package com.batyrbek.finance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.batyrbek.finance.cache.PersistentCacheStore;
import com.batyrbek.finance.dto.CompanyEarnings;
import com.batyrbek.finance.dto.QuarterlyEarnings;
import com.batyrbek.finance.provider.sec.SecFinancialsProvider;
import com.batyrbek.finance.validation.TickerNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EarningsFilingRefreshTest {
    @Test
    void aNewTenQInvalidatesTheDailyEarningsCache() {
        SecFinancialsProvider sec = mock(SecFinancialsProvider.class);
        CompanyEarnings old = earnings("Q1", "0000123456-26-000001");
        CompanyEarnings current = earnings("Q2", "0000123456-26-000002");
        when(sec.fetchQuarterlyEarnings("TEST")).thenReturn(old, current);
        when(sec.hasNewQuarterlyFiling("TEST", old)).thenReturn(true);
        EarningsService service = new EarningsService(sec, new TickerNormalizer(), mock(PersistentCacheStore.class));

        assertThat(service.getEarnings("TEST").reported().getFirst().fiscalPeriod()).isEqualTo("Q1");
        assertThat(service.getEarnings("TEST").reported().getFirst().fiscalPeriod()).isEqualTo("Q2");
        verify(sec, times(2)).fetchQuarterlyEarnings("TEST");
    }

    private CompanyEarnings earnings(String quarter, String accession) {
        LocalDate period = quarter.equals("Q1") ? LocalDate.parse("2026-03-31") : LocalDate.parse("2026-06-30");
        return new CompanyEarnings("TEST", "USD", List.of(new QuarterlyEarnings(period, quarter,
                1.0, 100.0, 20.0, "https://www.sec.gov/Archives/edgar/data/123456/"
                + accession.replace("-", "") + "/" + accession + "-index.htm", period.plusDays(35))),
                null, "SEC EDGAR", Instant.now(), false);
    }
}
