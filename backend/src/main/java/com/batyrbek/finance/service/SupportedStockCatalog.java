package com.batyrbek.finance.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.batyrbek.finance.dto.SupportedStock;
import com.batyrbek.finance.exception.UnsupportedTickerException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class SupportedStockCatalog {
    private static final String RESOURCE = "fmp-supported-tickers.json";
    private final List<SupportedStock> stocks;
    private final Map<String, SupportedStock> bySymbol;

    public SupportedStockCatalog(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            List<SupportedStock> loaded = objectMapper.readValue(input, new TypeReference<>() {});
            LinkedHashMap<String, SupportedStock> indexed = new LinkedHashMap<>();
            for (SupportedStock stock : loaded) {
                String symbol = stock.symbol().trim().toUpperCase(Locale.ROOT);
                if (indexed.putIfAbsent(symbol, stock) != null) {
                    throw new IllegalStateException("Duplicate supported ticker: " + symbol);
                }
            }
            stocks = List.copyOf(loaded);
            bySymbol = Map.copyOf(indexed);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load " + RESOURCE, exception);
        }
    }

    public List<SupportedStock> all() { return stocks; }

    public Optional<SupportedStock> find(String symbol) {
        return Optional.ofNullable(bySymbol.get(symbol.toUpperCase(Locale.ROOT)));
    }

    public SupportedStock requireOverview(String symbol) {
        SupportedStock stock = find(symbol).orElseThrow(() -> new UnsupportedTickerException(symbol));
        if (!stock.fmp().overview()) throw new UnsupportedTickerException(symbol);
        return stock;
    }

    public SupportedStock requireFinancials(String symbol) {
        SupportedStock stock = find(symbol).orElseThrow(() -> new UnsupportedTickerException(symbol));
        if (!stock.fmp().financials()) throw new UnsupportedTickerException(symbol);
        return stock;
    }

    public SupportedStock requireHistory(String symbol) {
        SupportedStock stock = find(symbol).orElseThrow(() -> new UnsupportedTickerException(symbol));
        if (!stock.fmp().history()) throw new UnsupportedTickerException(symbol);
        return stock;
    }
}
