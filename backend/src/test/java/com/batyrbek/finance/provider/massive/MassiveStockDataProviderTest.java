package com.batyrbek.finance.provider.massive;

import java.time.Instant;

import com.batyrbek.finance.exception.ProviderRateLimitException;
import com.batyrbek.finance.exception.StockProviderException;
import com.batyrbek.finance.exception.UnsupportedInstrumentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MassiveStockDataProviderTest {
    @Test
    void staysDisabledWhenTheFeatureFlagIsOff() {
        MassiveStockDataProvider provider = new MassiveStockDataProvider(WebClient.create(), "secret", false, 5);

        assertThat(provider.isEnabled()).isFalse();
        assertThatThrownBy(() -> provider.resolveInstrument("TEST"))
                .isInstanceOf(StockProviderException.class).hasMessageContaining("disabled");
    }

    @Test
    void resolvesOnlyAnActiveUsCommonStock() {
        MassiveStockDataProvider provider = providerWith("""
                {"status":"OK","results":{"ticker":"TEST","name":"Test Corporation","market":"stocks",
                "locale":"us","type":"CS","active":true,"primary_exchange":"XNAS","cik":"123456"}}
                """);

        var result = provider.resolveInstrument("TEST");

        assertThat(result.symbol()).isEqualTo("TEST");
        assertThat(result.instrumentType()).isEqualTo("common_stock");
        assertThat(result.availableHistoryRanges()).contains("2Y").doesNotContain("5Y");
        assertThat(result.capabilities().financials()).isTrue();
    }

    @Test
    void rejectsEtfsFromTheUniversalCompanyRoute() {
        MassiveStockDataProvider provider = providerWith("""
                {"status":"OK","results":{"ticker":"FUND","name":"Example Fund","market":"stocks",
                "locale":"us","type":"ETF","active":true,"primary_exchange":"ARCX"}}
                """);

        assertThatThrownBy(() -> provider.resolveInstrument("FUND"))
                .isInstanceOf(UnsupportedInstrumentException.class);
    }

    @Test
    void exposesTheNextLocalBudgetSlot() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("""
                                {"status":"OK","results":{"ticker":"TEST","name":"Test Corporation","market":"stocks",
                                "locale":"us","type":"CS","active":true,"primary_exchange":"XNAS"}}
                                """).build())).build();
        MassiveStockDataProvider provider = new MassiveStockDataProvider(client, "secret", true, 1);
        provider.resolveInstrument("TEST");

        assertThatThrownBy(() -> provider.resolveInstrument("NEXT"))
                .isInstanceOfSatisfying(ProviderRateLimitException.class,
                        exception -> assertThat(exception.retryAfter()).isAfter(Instant.now()));
    }

    @Test
    void preservesUpstreamRetryAfterForHttp429() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "17").build())).build();
        MassiveStockDataProvider provider = new MassiveStockDataProvider(client, "secret", true, 5);
        Instant startedAt = Instant.now();

        assertThatThrownBy(() -> provider.resolveInstrument("TEST"))
                .isInstanceOfSatisfying(ProviderRateLimitException.class, exception ->
                        assertThat(exception.retryAfter()).isBetween(
                                startedAt.plusSeconds(15), startedAt.plusSeconds(20)));
    }

    private MassiveStockDataProvider providerWith(String json) {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body(json).build())).build();
        return new MassiveStockDataProvider(client, "secret", true, 20);
    }
}
