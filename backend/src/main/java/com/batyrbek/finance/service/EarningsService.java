package com.batyrbek.finance.service;

import java.time.Duration;

import com.batyrbek.finance.cache.PersistentCacheStore;
import com.batyrbek.finance.dto.CompanyEarnings;
import com.batyrbek.finance.exception.StockProviderException;
import com.batyrbek.finance.provider.sec.SecFinancialsProvider;
import com.batyrbek.finance.validation.TickerNormalizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

@Service
public class EarningsService {
    private static final Duration FRESH = Duration.ofHours(24);
    private static final Duration STALE = Duration.ofDays(180);
    private final SecFinancialsProvider sec;
    private final TickerNormalizer tickerNormalizer;
    private final PersistentCacheStore disk;
    private final Cache<String, CompanyEarnings> fresh = Caffeine.newBuilder()
            .maximumSize(250).expireAfterWrite(FRESH).build();
    private final Cache<String, CompanyEarnings> stale = Caffeine.newBuilder()
            .maximumSize(250).expireAfterWrite(STALE).build();

    public EarningsService(SecFinancialsProvider sec, TickerNormalizer tickerNormalizer,
                           PersistentCacheStore disk) {
        this.sec = sec;
        this.tickerNormalizer = tickerNormalizer;
        this.disk = disk;
    }

    public CompanyEarnings getEarnings(String rawTicker) {
        String ticker = tickerNormalizer.normalize(rawTicker);
        CompanyEarnings previous = fresh.getIfPresent(ticker);
        if (previous == null) previous = disk.read("earnings", ticker, CompanyEarnings.class, STALE).orElse(null);
        boolean newFiling = previous != null && hasNewFiling(ticker, previous);
        if (newFiling) fresh.invalidate(ticker);
        if (!newFiling) {
            CompanyEarnings cached = fresh.getIfPresent(ticker);
            if (cached != null) return cached;
            CompanyEarnings fromDisk = disk.read("earnings", ticker, CompanyEarnings.class, FRESH).orElse(null);
            if (fromDisk != null) {
                stale.put(ticker, fromDisk);
                fresh.put(ticker, fromDisk);
                return fromDisk;
            }
        }
        try {
            return fresh.get(ticker, ignored -> {
                CompanyEarnings loaded = sec.fetchQuarterlyEarnings(ticker);
                stale.put(ticker, loaded);
                disk.write("earnings", ticker, loaded);
                return loaded;
            });
        } catch (StockProviderException exception) {
            CompanyEarnings fallback = stale.getIfPresent(ticker);
            if (fallback == null) fallback = disk.read("earnings", ticker, CompanyEarnings.class, STALE).orElse(null);
            if (fallback != null) return fallback.asStale();
            throw exception;
        }
    }

    private boolean hasNewFiling(String ticker, CompanyEarnings previous) {
        try { return sec.hasNewQuarterlyFiling(ticker, previous); }
        catch (StockProviderException ignored) { return false; }
    }
}
