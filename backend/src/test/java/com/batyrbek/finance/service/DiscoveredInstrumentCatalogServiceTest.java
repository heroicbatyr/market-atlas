package com.batyrbek.finance.service;

import java.util.List;

import com.batyrbek.finance.cache.PersistentCacheStore;
import com.batyrbek.finance.dto.MarketInstrument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveredInstrumentCatalogServiceTest {
    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void preservesResolvedInstrumentsAcrossAServiceRestart() {
        PersistentCacheStore disk = new PersistentCacheStore(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        MarketInstrument instrument = new MarketInstrument("TEST", "Test Corporation", "common_stock",
                true, "XNAS", "123456", "Massive", null, List.of("1Y", "2Y"),
                new MarketInstrument.Capabilities(true, true, true, true), false);
        new DiscoveredInstrumentCatalogService(disk).remember(instrument);

        DiscoveredInstrumentCatalogService restarted = new DiscoveredInstrumentCatalogService(disk);

        assertThat(restarted.find("test")).contains(instrument);
        assertThat(restarted.all()).containsExactly(instrument);
    }
}
