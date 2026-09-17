package com.batyrbek.finance.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.batyrbek.finance.cache.PersistentCacheStore;
import com.batyrbek.finance.dto.DiscoveredInstrumentCatalog;
import com.batyrbek.finance.dto.MarketInstrument;
import org.springframework.stereotype.Service;

/** A small shared catalog of successfully resolved non-curated stocks. */
@Service
public class DiscoveredInstrumentCatalogService {
    private static final Duration RETENTION = Duration.ofDays(3650);
    private static final String NAMESPACE = "instruments";
    private static final String KEY = "ALL";
    private final PersistentCacheStore disk;
    private final Map<String, MarketInstrument> instruments = new LinkedHashMap<>();

    public DiscoveredInstrumentCatalogService(PersistentCacheStore disk) {
        this.disk = disk;
        DiscoveredInstrumentCatalog saved = disk.read(NAMESPACE, KEY,
                DiscoveredInstrumentCatalog.class, RETENTION).orElse(null);
        if (saved != null && saved.instruments() != null) {
            saved.instruments().stream().filter(item -> item != null && item.symbol() != null)
                    .forEach(item -> instruments.put(item.symbol().toUpperCase(Locale.ROOT), item));
        }
    }

    public synchronized Optional<MarketInstrument> find(String symbol) {
        return Optional.ofNullable(instruments.get(symbol.toUpperCase(Locale.ROOT)));
    }

    public synchronized List<MarketInstrument> all() {
        return instruments.values().stream().sorted(Comparator.comparing(MarketInstrument::symbol)).toList();
    }

    public synchronized MarketInstrument remember(MarketInstrument instrument) {
        instruments.put(instrument.symbol().toUpperCase(Locale.ROOT), instrument);
        disk.write(NAMESPACE, KEY, new DiscoveredInstrumentCatalog(List.copyOf(instruments.values())));
        return instrument;
    }
}
