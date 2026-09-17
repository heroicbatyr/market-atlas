package com.batyrbek.finance.web;

import java.util.List;

import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.dto.InstrumentSearchResponse;
import com.batyrbek.finance.dto.MarketInstrument;
import com.batyrbek.finance.dto.StockHistory;
import com.batyrbek.finance.dto.StockOverview;
import com.batyrbek.finance.service.MarketInstrumentService;
import com.batyrbek.finance.service.StockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class StockController {
    private final StockService stockService;
    private final MarketInstrumentService instruments;

    public StockController(StockService stockService, MarketInstrumentService instruments) {
        this.stockService = stockService;
        this.instruments = instruments;
    }

    @GetMapping("/search")
    public InstrumentSearchResponse search(@RequestParam("q") String query) {
        return InstrumentSearchResponse.from(instruments.search(query));
    }

    @GetMapping("/discovered")
    public List<MarketInstrument> discovered() { return instruments.discovered(); }

    @GetMapping("/{ticker}/instrument")
    public MarketInstrument instrument(@PathVariable String ticker) { return instruments.resolve(ticker); }

    @GetMapping("/{ticker}")
    public StockOverview overview(@PathVariable String ticker) { return stockService.getOverview(ticker); }

    @GetMapping("/{ticker}/financials")
    public CompanyFinancials financials(@PathVariable String ticker) {
        return stockService.getFinancials(ticker);
    }

    @GetMapping("/{ticker}/history")
    public StockHistory history(@PathVariable String ticker, @RequestParam(defaultValue = "5y") String range) {
        return stockService.getHistory(ticker, range);
    }
}
