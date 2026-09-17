package com.batyrbek.finance.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.batyrbek.finance.dto.AnnualFinancial;
import com.batyrbek.finance.dto.CompanyEarnings;
import com.batyrbek.finance.dto.QuarterlyEarnings;
import com.batyrbek.finance.dto.CompanyFundamentals;
import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.dto.PricePoint;
import com.batyrbek.finance.dto.StockHistory;
import com.batyrbek.finance.dto.StockQuote;
import com.batyrbek.finance.exception.ProviderRateLimitException;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.batyrbek.finance.exception.StockProviderException;
import com.batyrbek.finance.provider.StockDataProvider;
import com.batyrbek.finance.provider.massive.MassiveStockDataProvider;
import com.batyrbek.finance.provider.sec.SecFinancialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StockApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "fmpStockDataProvider")
    private StockDataProvider provider;

    @MockitoBean
    private MassiveStockDataProvider massiveProvider;
    @MockitoBean
    private SecFinancialsProvider secProvider;


    @BeforeEach
    void providerResponses() {
        when(provider.fetchQuote("NVDA")).thenReturn(quote(184.21));
        when(provider.fetchQuote("AAPL")).thenReturn(quote(230.10));
        when(provider.fetchFundamentals("NVDA")).thenReturn(fundamentals("NVIDIA Corporation"));
        when(provider.fetchFundamentals("AAPL")).thenReturn(fundamentals("Apple Inc."));
        when(provider.fetchHistory("NVDA")).thenReturn(history("NVDA"));
        when(provider.fetchFinancials("NVDA")).thenReturn(financials("NVDA"));
        when(secProvider.fetchFinancials("NVDA")).thenThrow(new StockNotFoundException("NVDA"));
        when(secProvider.fetchQuarterlyEarnings("NVDA")).thenReturn(new CompanyEarnings("NVDA", "USD",
                List.of(new QuarterlyEarnings(LocalDate.parse("2025-06-30"), "Q2", 0.5, 25.0, 5.0,
                        "https://www.sec.gov/Archives/edgar/data/1/2/3-index.htm", LocalDate.parse("2025-08-01"))), null, "SEC EDGAR", Instant.now(), false));
        when(provider.fetchHistory("AAPL")).thenReturn(history("AAPL"));
        when(provider.fetchQuote("META")).thenThrow(new StockNotFoundException("META"));
        when(provider.fetchQuote("AMZN")).thenThrow(new ProviderRateLimitException());
        when(provider.fetchQuote("TSLA")).thenThrow(new StockProviderException("internal provider detail"));
        when(massiveProvider.resolveInstrument("UNKNOWN")).thenThrow(new StockNotFoundException("UNKNOWN"));
        when(massiveProvider.fetchQuote("UNKNOWN")).thenThrow(new StockNotFoundException("UNKNOWN"));
    }

    @Test
    void servesNormalizedNvdaOverviewWithNarrowCors() throws Exception {
        mockMvc.perform(get("/api/stocks/nvda").header("Origin", "https://batyrbek.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://batyrbek.com"))
                .andExpect(jsonPath("$.ticker").value("NVDA"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.companyName").value("NVIDIA Corporation"))
                .andExpect(jsonPath("$.price").value(184.21));
    }

    @Test
    void servesAaplHistory() throws Exception {
        mockMvc.perform(get("/api/stocks/aapl/history").queryParam("range", "5y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.range").value("5y"))
                .andExpect(jsonPath("$.resolution").value("weekly"))
                .andExpect(jsonPath("$.points.length()").value(2));
    }

    @Test
    void servesNormalizedAnnualFinancials() throws Exception {
        mockMvc.perform(get("/api/stocks/NVDA/financials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("NVDA"))
                .andExpect(jsonPath("$.source").value("FMP"))
                .andExpect(jsonPath("$.annual.length()").value(1))
                .andExpect(jsonPath("$.annual[0].freeCashFlow").value(180.0));
    }

    @Test
    void servesReportedSecEarningsWithoutAnInventedExpectedDate() throws Exception {
        mockMvc.perform(get("/api/stocks/NVDA/earnings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("NVDA"))
                .andExpect(jsonPath("$.source").value("SEC EDGAR"))
                .andExpect(jsonPath("$.reported[0].fiscalPeriod").value("Q2"))
                .andExpect(jsonPath("$.reported[0].reportedEps").value(0.5))
                .andExpect(jsonPath("$.expectedDate").value(org.hamcrest.Matchers.nullValue()));
    }
    @Test
    void searchesTheNormalizedCuratedInstrumentCatalog() throws Exception {
        mockMvc.perform(get("/api/stocks/search").param("q", "nvidia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("resolved"))
                .andExpect(jsonPath("$.results[0].symbol").value("NVDA"))
                .andExpect(jsonPath("$.results[0].instrumentType").value("common_stock"))
                .andExpect(jsonPath("$.results[0].curated").value(true))
                .andExpect(jsonPath("$.results[0].capabilities.history").value(true));
    }


    @Test
    void servesSupportedStockCatalogWithoutProviderCalls() throws Exception {
        mockMvc.perform(get("/api/finance/supported-stocks").header("Origin", "https://batyrbek.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://batyrbek.com"))
                .andExpect(jsonPath("$.length()").value(53))
                .andExpect(jsonPath("$[0].symbol").value("NVDA"))
                .andExpect(jsonPath("$[0].fmp.history").value(true));
    }

    @Test
    void rejectsInvalidTickerAndUnknownCompanyClearly() throws Exception {
        mockMvc.perform(get("/api/stocks/bad$ticker"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TICKER"))
                .andExpect(jsonPath("$.message").value("Ticker must be 1-10 letters, numbers, periods, or hyphens."));

        mockMvc.perform(get("/api/stocks/UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKER_NOT_FOUND"));

        mockMvc.perform(get("/api/stocks/META"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKER_NOT_FOUND"));

        mockMvc.perform(get("/api/stocks/AMZN"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("PROVIDER_RATE_LIMITED"))
                .andExpect(jsonPath("$.resolution").value("rate_limited"))
                .andExpect(jsonPath("$.retryAfter").exists());

        mockMvc.perform(get("/api/stocks/TSLA"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Market data is temporarily unavailable. Please try again later."));

    }


    private StockQuote quote(double price) {
        return new StockQuote(price, 1.2, 0.7, 12_000_000L, 25.0, 5.0, 250.0, 120.0, Instant.parse("2026-09-13T20:00:00Z"));
    }

    private CompanyFundamentals fundamentals(String name) {
        return new CompanyFundamentals(name, "USD", 1_000_000_000L, 25.0, 5.0,
                0.004, 250.0, 120.0, Instant.parse("2026-09-13T19:00:00Z"),
                "NASDAQ", "Technology", "Semiconductors", "Description", "CEO", 1000L,
                "Santa Clara, US", "https://example.com", 0.15, 0.30, 500_000_000.0);
    }

    private CompanyFinancials financials(String ticker) {
        return new CompanyFinancials(ticker, "USD", List.of(
                new AnnualFinancial(LocalDate.parse("2025-12-31"), "2025", 1000.0, 300.0,
                        200.0, 2.0, 180.0, 0.1, 0.2)), 500.0, 200.0, 0.4, "FMP",
                Instant.parse("2026-09-13T20:00:00Z"), LocalDate.parse("2025-12-31"), false);
    }

    private StockHistory history(String ticker) {
        return new StockHistory(ticker, "USD", "5y", "weekly", List.of(
                new PricePoint(LocalDate.parse("2025-09-13"), 120.42),
                new PricePoint(LocalDate.parse("2026-09-12"), 184.21)),
                Instant.parse("2026-09-13T20:00:00Z"), false);
    }
}
