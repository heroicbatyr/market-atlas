package com.batyrbek.finance.provider.fmp;

import java.time.LocalDate;
import java.time.ZoneOffset;

import com.batyrbek.finance.exception.ProviderRateLimitException;
import com.batyrbek.finance.exception.StockNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FmpStockDataProviderTest {
    @Test
    void mapsQuoteAndFundamentalsFromFmpResponses() {
        FmpStockDataProvider provider = providerFor(url -> {
            if (url.contains("/profile")) return "[{\"companyName\":\"NVIDIA Corporation\",\"currency\":\"USD\",\"marketCap\":4500000000000,\"exchangeShortName\":\"NASDAQ\",\"sector\":\"Technology\",\"industry\":\"Semiconductors\",\"ceo\":\"Jensen Huang\",\"fullTimeEmployees\":36000,\"city\":\"Santa Clara\",\"country\":\"US\",\"website\":\"https://nvidia.com\",\"description\":\"Computing company\"}]";
            if (url.contains("key-metrics-ttm")) return "[{\"earningsYieldTTM\":0.023364,\"netProfitMarginTTM\":0.55,\"freeCashFlowYieldTTM\":0.02}]";
            if (url.contains("income-statement")) return "[{\"date\":\"2025-12-31\",\"revenue\":1200,\"netIncome\":600,\"epsDiluted\":4.3},{\"date\":\"2024-12-31\",\"revenue\":1000}]";
            return "[{\"price\":184.21,\"change\":3.33,\"changePercentage\":1.84,\"volume\":52000000,\"yearHigh\":212.19,\"yearLow\":86.62}]";
        });

        var quote = provider.fetchQuote("NVDA");
        assertThat(quote.price()).isEqualTo(184.21);
        assertThat(quote.volume()).isEqualTo(52_000_000L);
        var fundamentals = provider.fetchFundamentals("NVDA");
        assertThat(fundamentals.companyName()).isEqualTo("NVIDIA Corporation");
        assertThat(fundamentals.marketCap()).isEqualTo(4500000000000L);
        assertThat(fundamentals.peRatio()).isCloseTo(42.8, org.assertj.core.data.Offset.offset(0.01));
        assertThat(fundamentals.eps()).isEqualTo(4.3);
        assertThat(fundamentals.exchange()).isEqualTo("NASDAQ");
        assertThat(fundamentals.headquarters()).isEqualTo("Santa Clara, US");
        assertThat(fundamentals.revenueGrowth()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(fundamentals.netMargin()).isEqualTo(0.55);
        assertThat(fundamentals.freeCashFlow()).isEqualTo(90_000_000_000.0);
    }

    @Test
    void mapsAnnualStatementsIntoNormalizedFinancials() {
        FmpStockDataProvider provider = providerFor(url -> {
            if (url.contains("income-statement")) return "[{\"date\":\"2025-12-31\",\"fiscalYear\":\"2025\",\"reportedCurrency\":\"USD\",\"revenue\":1200,\"operatingIncome\":400,\"netIncome\":300,\"epsDiluted\":3.0},{\"date\":\"2024-12-31\",\"fiscalYear\":\"2024\",\"reportedCurrency\":\"USD\",\"revenue\":1000,\"operatingIncome\":300,\"netIncome\":200,\"epsDiluted\":2.0}]";
            if (url.contains("cash-flow-statement")) return "[{\"date\":\"2025-12-31\",\"fiscalYear\":\"2025\",\"freeCashFlow\":250},{\"date\":\"2024-12-31\",\"fiscalYear\":\"2024\",\"freeCashFlow\":170}]";
            return "[{\"date\":\"2025-12-31\",\"fiscalYear\":\"2025\",\"cashAndCashEquivalents\":500,\"totalDebt\":240,\"totalStockholdersEquity\":800}]";
        });

        var financials = provider.fetchFinancials("NVDA");

        assertThat(financials.source()).isEqualTo("FMP");
        assertThat(financials.annual()).hasSize(2);
        assertThat(financials.annual().getLast().revenueGrowth()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(financials.annual().getLast().freeCashFlow()).isEqualTo(250.0);
        assertThat(financials.debtToEquity()).isEqualTo(0.3);
        assertThat(financials.dataAsOf()).isEqualTo(LocalDate.parse("2025-12-31"));
    }

    @Test
    void mapsDailyFiveYearHistory() {
        LocalDate recent = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate older = LocalDate.now(ZoneOffset.UTC).minusYears(2);
        FmpStockDataProvider provider = providerFor(url -> "[{\"date\":\"" + recent + "\",\"close\":184.21},{\"date\":\"" + older + "\",\"close\":80.00}]");
        var history = provider.fetchHistory("NVDA");
        assertThat(history.resolution()).isEqualTo("daily");
        assertThat(history.points()).hasSize(2);
        assertThat(history.points().getFirst().date()).isEqualTo(older);
        assertThat(history.points().getLast().close()).isEqualTo(184.21);
    }

    @Test
    void classifiesProviderMessagesAsRateLimits() {
        FmpStockDataProvider provider = providerFor(url -> "{\"message\":\"Limit reached\"}");
        assertThatThrownBy(() -> provider.fetchQuote("NVDA")).isInstanceOf(ProviderRateLimitException.class);
    }

    @Test
    void classifiesEmptyArraysAsUnknownTickers() {
        FmpStockDataProvider provider = providerFor(url -> "[]");
        assertThatThrownBy(() -> provider.fetchQuote("NOPE")).isInstanceOf(StockNotFoundException.class);
    }

    private static FmpStockDataProvider providerFor(ResponseBody responseBody) {
        ExchangeFunction exchange = request -> Mono.just(response(request, responseBody.forUrl(request.url().toString())));
        return new FmpStockDataProvider(WebClient.builder().exchangeFunction(exchange).build(), "test-key");
    }

    private static ClientResponse response(ClientRequest request, String body) {
        return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build();
    }

    private interface ResponseBody { String forUrl(String url); }
}
