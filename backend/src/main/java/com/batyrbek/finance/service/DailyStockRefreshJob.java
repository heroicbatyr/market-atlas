package com.batyrbek.finance.service;

import java.util.Arrays;
import java.util.List;

import com.batyrbek.finance.exception.StockProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyStockRefreshJob {
    private static final Logger log = LoggerFactory.getLogger(DailyStockRefreshJob.class);
    private final StockService stockService;
    private final List<String> tickers;

    public DailyStockRefreshJob(StockService stockService,
                                @Value("${stock.refresh.tickers:NVDA,AAPL,MSFT,JPM}") String tickers) {
        this.stockService = stockService;
        this.tickers = Arrays.stream(tickers.split(",")).map(String::trim)
                .filter(ticker -> !ticker.isBlank()).toList();
    }

    @Scheduled(cron = "${stock.refresh.cron:0 30 22 * * MON-FRI}", zone = "UTC")
    void warmDailyTickerCache() {
        for (String ticker : tickers) {
            try {
                stockService.getOverview(ticker);
                stockService.getHistory(ticker, "5y");
            } catch (StockProviderException exception) {
                log.warn("Daily finance refresh skipped for {} because the provider is unavailable", ticker);
            }
        }
    }
}
