package com.batyrbek.finance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.batyrbek.finance.cache.PersistentCacheStore;
import com.batyrbek.finance.cache.VercelSnapshotMirror;
import com.batyrbek.finance.dto.AnnualFinancial;
import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.provider.ProviderRouter;
import com.batyrbek.finance.provider.StockDataProvider;
import com.batyrbek.finance.validation.TickerNormalizer;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnualFilingRefreshTest {
    @Test
    void aNewSecFilingInvalidatesTheSevenDayFinancialCache() {
        ProviderRouter router = mock(ProviderRouter.class);
        PersistentCacheStore disk = mock(PersistentCacheStore.class);
        CompanyFinancials old = financials("2025", "0000123456-26-000001");
        CompanyFinancials fresh = financials("2026", "0000123456-27-000002");
        when(router.fetchFinancials("TEST")).thenReturn(old, fresh);
        when(router.hasNewAnnualFiling("TEST", old)).thenReturn(true);
        StockService service = new StockService(router, new TickerNormalizer(),
                Caffeine.newBuilder().build(), Caffeine.newBuilder().build(),
                Caffeine.newBuilder().build(), Caffeine.newBuilder().build(),
                Caffeine.newBuilder().build(), Caffeine.newBuilder().build(),
                Caffeine.newBuilder().build(), Caffeine.newBuilder().build(),
                disk, mock(VercelSnapshotMirror.class), new UsMarketCalendar());

        assertThat(service.getFinancials("TEST").annual().getLast().fiscalYear()).isEqualTo("2025");
        assertThat(service.getFinancials("TEST").annual().getLast().fiscalYear()).isEqualTo("2026");
        verify(router, times(2)).fetchFinancials("TEST");
    }

    private static CompanyFinancials financials(String year, String accession) {
        LocalDate date = LocalDate.parse(year + "-12-31");
        return new CompanyFinancials("TEST", "USD", List.of(new AnnualFinancial(date, year,
                100.0, 30.0, 20.0, 2.0, 18.0, null, 0.2,
                "https://www.sec.gov/Archives/edgar/data/123456/" + accession.replace("-", "")
                        + "/" + accession + "-index.htm", "10-K", date.plusDays(50))),
                10.0, 5.0, 0.5, "SEC EDGAR", Instant.now(), date, false);
    }
}
