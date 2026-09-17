package com.batyrbek.finance.provider;

import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.batyrbek.finance.exception.StockProviderException;
import com.batyrbek.finance.provider.massive.MassiveStockDataProvider;
import com.batyrbek.finance.provider.sec.SecFinancialsProvider;
import com.batyrbek.finance.service.MarketInstrumentService;
import com.batyrbek.finance.service.SupportedStockCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ProviderRouter {
    private final StockDataProvider fmpProvider;
    private final SecFinancialsProvider secProvider;
    private final SupportedStockCatalog supportedStocks;
    private final MassiveStockDataProvider massiveProvider;
    private final MarketInstrumentService instruments;

    @Autowired
    public ProviderRouter(@Qualifier("fmpStockDataProvider") StockDataProvider fmpProvider,
                          SecFinancialsProvider secProvider, MassiveStockDataProvider massiveProvider,
                          SupportedStockCatalog supportedStocks, MarketInstrumentService instruments) {
        this.fmpProvider = fmpProvider;
        this.secProvider = secProvider;
        this.supportedStocks = supportedStocks;
        this.massiveProvider = massiveProvider;
        this.instruments = instruments;
    }

    public ProviderRouter(StockDataProvider fmpProvider, SupportedStockCatalog supportedStocks) {
        this(fmpProvider, null, null, supportedStocks, null);
    }

    public StockDataProvider forOverview(String symbol) {
        if (supportedStocks.find(symbol).filter(stock -> stock.fmp().overview()).isPresent()) return fmpProvider;
        requireBroaderInstrument(symbol);
        return massiveProvider;
    }

    public String overviewSource(String symbol) {
        return supportedStocks.find(symbol).filter(stock -> stock.fmp().overview()).isPresent()
                ? "FMP" : "Massive";
    }

    public StockDataProvider forFinancials(String symbol) {
        if (supportedStocks.find(symbol).filter(stock -> stock.fmp().financials()).isPresent()) return fmpProvider;
        requireBroaderInstrument(symbol);
        return massiveProvider;
    }

    public CompanyFinancials fetchFinancials(String symbol) {
        if (secProvider != null) {
            try {
                CompanyFinancials sec = secProvider.fetchFinancials(symbol);
                if (!FinancialsMerger.needsFallback(sec)) return sec;
                if (supportedStocks.find(symbol).filter(stock -> stock.fmp().financials()).isEmpty()) return sec;
                try {
                    return FinancialsMerger.fillMissing(sec, fmpProvider.fetchFinancials(symbol));
                } catch (StockNotFoundException | StockProviderException ignored) {
                    return sec;
                }
            } catch (StockNotFoundException | StockProviderException ignored) {
                // FMP retains coverage when a curated filer has no standard SEC facts or SEC is unavailable.
            }
        }
        if (supportedStocks.find(symbol).filter(stock -> stock.fmp().financials()).isPresent()) {
            return fmpProvider.fetchFinancials(symbol);
        }
        throw new StockNotFoundException(symbol);
    }

    public boolean hasNewAnnualFiling(String symbol, CompanyFinancials current) {
        if (secProvider == null) return false;
        try {
            return secProvider.hasNewAnnualFiling(symbol, current);
        } catch (StockNotFoundException | StockProviderException ignored) {
            return false;
        }
    }

    public StockDataProvider forHistory(String symbol) {
        if (supportedStocks.find(symbol).filter(stock -> stock.fmp().history()).isPresent()) return fmpProvider;
        requireBroaderInstrument(symbol);
        return massiveProvider;
    }

    private void requireBroaderInstrument(String symbol) {
        if (instruments == null || massiveProvider == null) throw new StockNotFoundException(symbol);
        instruments.resolve(symbol);
    }
}
