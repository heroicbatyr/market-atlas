package com.batyrbek.finance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.batyrbek.finance.cache.PersistentCacheStore;
import com.batyrbek.finance.cache.VercelSnapshotMirror;
import com.batyrbek.finance.dto.AnnualFinancial;
import com.batyrbek.finance.dto.CompanyFundamentals;
import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.dto.PricePoint;
import com.batyrbek.finance.dto.StockHistory;
import com.batyrbek.finance.dto.StockOverview;
import com.batyrbek.finance.exception.ProviderRateLimitException;
import com.batyrbek.finance.dto.StockQuote;
import com.batyrbek.finance.exception.StockProviderException;
import com.batyrbek.finance.provider.ProviderRouter;
import com.batyrbek.finance.provider.StockDataProvider;
import com.batyrbek.finance.validation.TickerNormalizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockServiceTest {
    private final StockDataProvider provider = mock(StockDataProvider.class);
    private final Cache<String, StockQuote> quotes = Caffeine.newBuilder().build();
    private final Cache<String, StockQuote> staleQuotes = Caffeine.newBuilder().build();
    private final Cache<String, CompanyFundamentals> fundamentals = Caffeine.newBuilder().build();
    private final Cache<String, CompanyFundamentals> staleFundamentals = Caffeine.newBuilder().build();
    private final Cache<String, CompanyFinancials> financials = Caffeine.newBuilder().build();
    private final Cache<String, CompanyFinancials> staleFinancials = Caffeine.newBuilder().build();
    private final Cache<String, StockHistory> histories = Caffeine.newBuilder().build();
    private final Cache<String, StockHistory> staleHistories = Caffeine.newBuilder().build();
    private final PersistentCacheStore persistentCache = mock(PersistentCacheStore.class);
    private final VercelSnapshotMirror snapshotMirror = mock(VercelSnapshotMirror.class);
    private final SupportedStockCatalog supportedStocks = new SupportedStockCatalog(
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    private final StockService service = new StockService(new ProviderRouter(provider, supportedStocks), new TickerNormalizer(), quotes, staleQuotes,
            fundamentals, staleFundamentals, financials, staleFinancials, histories, staleHistories, persistentCache, snapshotMirror, new UsMarketCalendar());

    @Test
    void cachesQuoteAndFundamentalsIndependently() {
        when(provider.fetchQuote("NVDA")).thenReturn(quote());
        when(provider.fetchFundamentals("NVDA")).thenReturn(fundamentals("NVDA"));

        assertThat(service.getOverview(" nvda ").ticker()).isEqualTo("NVDA");
        assertThat(service.getOverview("NVDA").ticker()).isEqualTo("NVDA");
        verify(provider, times(1)).fetchQuote("NVDA");
        verify(provider, times(1)).fetchFundamentals("NVDA");
    }

    @Test
    void returnsMarkedStaleOverviewWhenProviderFails() {
        when(provider.fetchQuote("AAPL")).thenReturn(quote()).thenThrow(new StockProviderException("offline"));
        when(provider.fetchFundamentals("AAPL")).thenReturn(fundamentals("AAPL"));
        service.getOverview("AAPL");
        quotes.invalidate("AAPL");

        StockOverview result = service.getOverview("AAPL");

        assertThat(result.stale()).isTrue();
        assertThat(result.companyName()).isEqualTo("AAPL Inc.");
    }

    @Test
    void cachesFiveYearHistoryAndMarksStaleFallback() {
        StockHistory history = history("MSFT");
        when(provider.fetchHistory("MSFT")).thenReturn(history).thenThrow(new ProviderRateLimitException());
        when(provider.fetchFundamentals("MSFT")).thenReturn(fundamentals("MSFT"));
        assertThat(service.getHistory("MSFT", "5y").stale()).isFalse();
        histories.invalidate("MSFT");

        assertThat(service.getHistory("MSFT", "5y").stale()).isTrue();
        verify(provider, times(2)).fetchHistory("MSFT");
    }

    @Test
    void coalescesConcurrentHistoryLoadsForTheSameTicker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(provider.fetchHistory("JPM")).thenAnswer(ignored -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return history("JPM");
        });
        when(provider.fetchFundamentals("JPM")).thenReturn(fundamentals("JPM"));
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<StockHistory>> futures = IntStream.range(0, 6)
                .mapToObj(ignored -> executor.submit(() -> service.getHistory("JPM", "5y"))).toList();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        for (Future<StockHistory> future : futures) assertThat(future.get().ticker()).isEqualTo("JPM");
        executor.shutdownNow();

        verify(provider, times(1)).fetchHistory("JPM");
        verify(provider, times(0)).fetchFundamentals("JPM");
    }

    @Test
    void cachesAnnualFinancialsIndependently() {
        CompanyFinancials value = new CompanyFinancials("NVDA", "USD", List.of(
                new AnnualFinancial(LocalDate.parse("2025-12-31"), "2025", 100.0, 30.0,
                        20.0, 2.0, 18.0, 0.1, 0.2)), 40.0, 10.0, 0.25,
                "FMP", Instant.parse("2026-09-14T01:30:00Z"), LocalDate.parse("2025-12-31"), false);
        when(provider.fetchFinancials("NVDA")).thenReturn(value);

        assertThat(service.getFinancials("NVDA").annual()).hasSize(1);
        assertThat(service.getFinancials("NVDA").annual()).hasSize(1);
        verify(provider, times(1)).fetchFinancials("NVDA");
    }

    @Test
    void rejectsShorterHistoryRangesBeforeCallingProvider() {
        assertThatThrownBy(() -> service.getHistory("AAPL", "1y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client-side");
    }

    private StockQuote quote() {
        return new StockQuote(100.0, 1.0, 1.0, 12_000_000L, 20.0, 5.0, 110.0, 70.0, Instant.parse("2026-09-14T01:30:00Z"));
    }

    private CompanyFundamentals fundamentals(String ticker) {
        return new CompanyFundamentals(ticker + " Inc.", "USD", 1_000_000L, 20.0, 5.0,
                null, 110.0, 70.0, Instant.parse("2026-09-14T01:00:00Z"),
                "NASDAQ", "Technology", "Software", "Description", "CEO", 1000L,
                "City, US", "https://example.com", 0.12, 0.20, 100_000.0);
    }

    private StockHistory history(String ticker) {
        return new StockHistory(ticker, "USD", "5y", "weekly", List.of(
                new PricePoint(LocalDate.parse("2021-09-14"), 40.0),
                new PricePoint(LocalDate.parse("2026-09-12"), 100.0)),
                Instant.parse("2026-09-14T01:30:00Z"), false);
    }
}
