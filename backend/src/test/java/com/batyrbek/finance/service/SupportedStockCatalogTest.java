package com.batyrbek.finance.service;

import com.batyrbek.finance.exception.UnsupportedTickerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedStockCatalogTest {
    private final SupportedStockCatalog catalog = new SupportedStockCatalog(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void loadsTheConfirmedFmpUniverse() {
        assertThat(catalog.all()).hasSize(53);
        assertThat(catalog.find("nvda")).get().extracting(stock -> stock.name())
                .isEqualTo("NVIDIA Corporation");
        assertThat(catalog.all()).extracting(stock -> stock.symbol()).doesNotHaveDuplicates();
    }

    @Test
    void rejectsUnsupportedSymbolsBeforeAProviderCall() {
        assertThatThrownBy(() -> catalog.requireOverview("UNKNOWN"))
                .isInstanceOf(UnsupportedTickerException.class)
                .hasMessageContaining("currently supported research universe");
    }
}
