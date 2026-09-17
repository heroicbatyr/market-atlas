package com.batyrbek.finance.service;

import java.util.List;

import com.batyrbek.finance.cache.PersistentCacheStore;
import com.batyrbek.finance.dto.MarketInstrument;
import com.batyrbek.finance.provider.massive.MassiveStockDataProvider;
import com.batyrbek.finance.exception.ProviderRateLimitException;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.batyrbek.finance.validation.TickerNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketInstrumentServiceTest {
    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void resolvesPersistsAndReusesABroaderMarketInstrument() {
        MassiveStockDataProvider provider = mock(MassiveStockDataProvider.class);
        MarketInstrument instrument = new MarketInstrument("TEST", "Test Corporation", "common_stock",
                true, "XNAS", "123456", "Massive", null, List.of("1Y", "2Y"),
                new MarketInstrument.Capabilities(true, true, true, true), false);
        when(provider.resolveInstrument("TEST")).thenReturn(instrument);
        PersistentCacheStore disk = new PersistentCacheStore(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        MarketInstrumentService service = new MarketInstrumentService(
                new SupportedStockCatalog(new ObjectMapper().findAndRegisterModules()),
                new DiscoveredInstrumentCatalogService(disk), provider, new TickerNormalizer());

        assertThat(service.resolve("test")).isEqualTo(instrument);
        assertThat(service.resolve("TEST")).isEqualTo(instrument);
        verify(provider, times(1)).resolveInstrument("TEST");
    }

    @Test
    void searchesCuratedCompaniesWithoutAProviderRequest() {
        MassiveStockDataProvider provider = mock(MassiveStockDataProvider.class);
        when(provider.isEnabled()).thenReturn(true);
        PersistentCacheStore disk = new PersistentCacheStore(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        MarketInstrumentService service = new MarketInstrumentService(
                new SupportedStockCatalog(new ObjectMapper().findAndRegisterModules()),
                new DiscoveredInstrumentCatalogService(disk), provider, new TickerNormalizer());

        assertThat(service.search("nvidia")).extracting(MarketInstrument::symbol).containsExactly("NVDA");
        verify(provider, times(1)).isEnabled();
        verify(provider, never()).searchInstruments(anyString());
    }
    @Test
    void doesNotNegativeCacheTemporaryRateLimits() {
        MassiveStockDataProvider provider = mock(MassiveStockDataProvider.class);
        MarketInstrument instrument = new MarketInstrument("WAIT", "Wait Corporation", "common_stock",
                true, "XNYS", "654321", "Massive", null, List.of("1Y", "2Y"),
                new MarketInstrument.Capabilities(true, true, true, true), false);
        when(provider.resolveInstrument("WAIT"))
                .thenThrow(new ProviderRateLimitException())
                .thenReturn(instrument);
        PersistentCacheStore disk = new PersistentCacheStore(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        MarketInstrumentService service = new MarketInstrumentService(
                new SupportedStockCatalog(new ObjectMapper().findAndRegisterModules()),
                new DiscoveredInstrumentCatalogService(disk), provider, new TickerNormalizer());

        assertThatThrownBy(() -> service.resolve("WAIT"))
                .isInstanceOf(ProviderRateLimitException.class);
        assertThat(service.resolve("WAIT")).isEqualTo(instrument);
        verify(provider, times(2)).resolveInstrument("WAIT");
    }

    @Test
    void negativeCachesAnExplicitMissingTicker() {
        MassiveStockDataProvider provider = mock(MassiveStockDataProvider.class);
        when(provider.resolveInstrument("MISSING")).thenThrow(new StockNotFoundException("MISSING"));
        PersistentCacheStore disk = new PersistentCacheStore(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        MarketInstrumentService service = new MarketInstrumentService(
                new SupportedStockCatalog(new ObjectMapper().findAndRegisterModules()),
                new DiscoveredInstrumentCatalogService(disk), provider, new TickerNormalizer());

        assertThatThrownBy(() -> service.resolve("MISSING")).isInstanceOf(StockNotFoundException.class);
        assertThatThrownBy(() -> service.resolve("MISSING")).isInstanceOf(StockNotFoundException.class);
        verify(provider, times(1)).resolveInstrument("MISSING");
    }

}
