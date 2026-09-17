package com.batyrbek.finance.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.batyrbek.finance.dto.MarketInstrument;
import com.batyrbek.finance.dto.SupportedStock;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.batyrbek.finance.exception.UnsupportedInstrumentException;
import com.batyrbek.finance.provider.massive.MassiveStockDataProvider;
import com.batyrbek.finance.validation.TickerNormalizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

@Service
public class MarketInstrumentService {
    private final SupportedStockCatalog curated;
    private final DiscoveredInstrumentCatalogService discovered;
    private final MassiveStockDataProvider broaderProvider;
    private final TickerNormalizer tickerNormalizer;
    private final Cache<String, Boolean> unresolved = Caffeine.newBuilder()
            .maximumSize(500).expireAfterWrite(Duration.ofHours(1)).build();

    public MarketInstrumentService(SupportedStockCatalog curated,
                                   DiscoveredInstrumentCatalogService discovered,
                                   MassiveStockDataProvider broaderProvider,
                                   TickerNormalizer tickerNormalizer) {
        this.curated = curated;
        this.discovered = discovered;
        this.broaderProvider = broaderProvider;
        this.tickerNormalizer = tickerNormalizer;
    }

    public MarketInstrument resolve(String rawSymbol) {
        String symbol = tickerNormalizer.normalize(rawSymbol);
        SupportedStock known = curated.find(symbol).orElse(null);
        if (known != null) return fromCurated(known);
        MarketInstrument saved = discovered.find(symbol).orElse(null);
        if (saved != null) return saved;
        if (unresolved.getIfPresent(symbol) != null) throw new StockNotFoundException(symbol);
        try {
            return discovered.remember(broaderProvider.resolveInstrument(symbol));
        } catch (StockNotFoundException | UnsupportedInstrumentException exception) {
            unresolved.put(symbol, true);
            throw exception;
        }
    }

    public List<MarketInstrument> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank() || query.length() > 80) {
            throw new IllegalArgumentException("Search must contain between 1 and 80 characters.");
        }
        String normalized = query.toUpperCase(Locale.ROOT);
        Map<String, MarketInstrument> results = new LinkedHashMap<>();
        curated.all().stream().map(this::fromCurated)
                .filter(item -> matches(item, normalized)).forEach(item -> results.put(item.symbol(), item));
        discovered.all().stream().filter(item -> matches(item, normalized))
                .forEach(item -> results.putIfAbsent(item.symbol(), item));
        if (broaderProvider.isEnabled() && results.isEmpty()) {
            for (MarketInstrument item : broaderProvider.searchInstruments(query)) {
                results.putIfAbsent(item.symbol(), item);
                if (results.size() == 8) break;
            }
        }
        return new ArrayList<>(results.values()).stream()
                .sorted(Comparator.comparing((MarketInstrument item) -> !item.symbol().equals(normalized))
                        .thenComparing(item -> !item.symbol().startsWith(normalized))
                        .thenComparing(MarketInstrument::symbol))
                .limit(8).toList();
    }

    public List<MarketInstrument> discovered() { return discovered.all(); }

    private boolean matches(MarketInstrument item, String query) {
        return item.symbol().startsWith(query) || item.name().toUpperCase(Locale.ROOT).contains(query);
    }

    private MarketInstrument fromCurated(SupportedStock stock) {
        return new MarketInstrument(stock.symbol(), stock.name(), "common_stock", true,
                null, null, "FMP", null, List.of("1W", "1M", "6M", "YTD", "1Y", "5Y"),
                new MarketInstrument.Capabilities(stock.fmp().overview(), stock.fmp().history(),
                        stock.fmp().financials(), true), true);
    }
}
