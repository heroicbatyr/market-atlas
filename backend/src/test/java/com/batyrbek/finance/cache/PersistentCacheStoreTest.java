package com.batyrbek.finance.cache;

import java.time.Duration;
import java.time.Instant;

import com.batyrbek.finance.dto.StockQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentCacheStoreTest {
    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void restoresAValueFromDiskAcrossStoreInstances() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        StockQuote quote = new StockQuote(184.21, 3.33, 1.84, 56_000_000L,
                42.8, 4.3, 212.19, 86.62, Instant.parse("2026-09-13T20:00:00Z"));
        new PersistentCacheStore(objectMapper, temporaryDirectory.toString()).write("quotes", "NVDA", quote);

        PersistentCacheStore restartedStore = new PersistentCacheStore(objectMapper, temporaryDirectory.toString());

        assertThat(restartedStore.read("quotes", "NVDA", StockQuote.class, Duration.ofDays(1)))
                .contains(quote);
    }

    @Test
    void rejectsUnsafePathKeys() {
        PersistentCacheStore store = new PersistentCacheStore(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> store.write("quotes", "../SECRET", new Object()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
