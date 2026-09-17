package com.batyrbek.finance.provider.sec;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.dto.CompanyEarnings;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.batyrbek.finance.exception.StockProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Reads SEC public filings and XBRL company facts; never called by the browser. */
@Component
public class SecFinancialsProvider {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private final WebClient dataClient;
    private final WebClient secClient;
    private final Cache<String, Map<String, Long>> tickerIndex = Caffeine.newBuilder()
            .maximumSize(1).expireAfterWrite(Duration.ofDays(7)).build();
    private final Cache<Long, JsonNode> companyFacts = Caffeine.newBuilder()
            .maximumSize(20).expireAfterWrite(Duration.ofHours(24)).build();
    private final Cache<String, String> latestAnnualFilings = Caffeine.newBuilder()
            .maximumSize(250).expireAfterWrite(Duration.ofHours(24)).build();
    private final Cache<String, Boolean> filingRefreshAttempts = Caffeine.newBuilder()
            .maximumSize(250).expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, String> latestQuarterlyFilings = Caffeine.newBuilder()
            .maximumSize(250).expireAfterWrite(Duration.ofHours(24)).build();
    private final Cache<String, Boolean> quarterlyRefreshAttempts = Caffeine.newBuilder()
            .maximumSize(250).expireAfterWrite(Duration.ofHours(1)).build();
    private long lastRequestNanos;

    public SecFinancialsProvider(@Value("${stock.sec.user-agent:}") String userAgent) {
        String declaredAgent = userAgent == null ? "" : userAgent.trim();
        if (declaredAgent.isBlank()) throw new IllegalStateException("SEC_USER_AGENT must identify Market Atlas and a contact email");
        this.dataClient = WebClient.builder().baseUrl("https://data.sec.gov")
                .defaultHeader(HttpHeaders.USER_AGENT, declaredAgent)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)).build();
        this.secClient = WebClient.builder().baseUrl("https://www.sec.gov")
                .defaultHeader(HttpHeaders.USER_AGENT, declaredAgent)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
    }

    public CompanyFinancials fetchFinancials(String ticker) {
        Long cik = tickerIndex.get("all", ignored -> loadTickerIndex()).get(ticker.toUpperCase(Locale.ROOT));
        if (cik == null) throw new StockNotFoundException(ticker);
        JsonNode facts = companyFacts.get(cik, ignored -> request(dataClient,
                "/api/xbrl/companyfacts/CIK%010d.json".formatted(cik)));
        return SecCompanyFactsMapper.map(ticker, cik, facts);
    }

    public CompanyEarnings fetchQuarterlyEarnings(String ticker) {
        Long cik = tickerIndex.get("all", ignored -> loadTickerIndex()).get(ticker.toUpperCase(Locale.ROOT));
        if (cik == null) throw new StockNotFoundException(ticker);
        JsonNode facts = companyFacts.get(cik, ignored -> request(dataClient,
                "/api/xbrl/companyfacts/CIK%010d.json".formatted(cik)));
        return SecQuarterlyEarningsMapper.map(ticker, cik, facts);
    }

    public boolean hasNewAnnualFiling(String ticker, CompanyFinancials current) {
        if (current.annual().isEmpty()) return false;
        String currentUrl = current.annual().getLast().filingUrl();
        if (currentUrl == null || !currentUrl.startsWith("https://www.sec.gov/Archives/edgar/")) return false;
        Long cik = tickerIndex.get("all", ignored -> loadTickerIndex()).get(ticker.toUpperCase(Locale.ROOT));
        if (cik == null) return false;
        String latest = latestAnnualFilings.get(ticker, ignored -> loadLatestAnnualAccession(cik));
        if (latest == null || currentUrl.endsWith(latest + "-index.htm")) return false;
        if (filingRefreshAttempts.getIfPresent(ticker) != null) return false;
        companyFacts.invalidate(cik);
        filingRefreshAttempts.put(ticker, true);
        return true;
    }
    public boolean hasNewQuarterlyFiling(String ticker, CompanyEarnings current) {
        if (current.reported().isEmpty()) return false;
        String currentUrl = current.reported().getFirst().filingUrl();
        if (currentUrl == null || !currentUrl.startsWith("https://www.sec.gov/Archives/edgar/")) return false;
        Long cik = tickerIndex.get("all", ignored -> loadTickerIndex()).get(ticker.toUpperCase(Locale.ROOT));
        if (cik == null) return false;
        String latest = latestQuarterlyFilings.get(ticker, ignored -> loadLatestQuarterlyAccession(cik));
        if (latest == null || currentUrl.endsWith(latest + "-index.htm")) return false;
        if (quarterlyRefreshAttempts.getIfPresent(ticker) != null) return false;
        companyFacts.invalidate(cik);
        quarterlyRefreshAttempts.put(ticker, true);
        return true;
    }


    private String loadLatestAnnualAccession(long cik) {
        JsonNode recent = request(dataClient, "/submissions/CIK%010d.json".formatted(cik))
                .path("filings").path("recent");
        JsonNode forms = recent.path("form");
        JsonNode accessions = recent.path("accessionNumber");
        if (!forms.isArray() || !accessions.isArray()) throw new StockProviderException("SEC filing list is unavailable.");
        for (int i = 0; i < Math.min(forms.size(), accessions.size()); i++) {
            String form = forms.get(i).asText();
            if (form.equals("10-K") || form.equals("10-K/A") || form.equals("20-F")
                    || form.equals("20-F/A") || form.equals("40-F") || form.equals("40-F/A"))
                return accessions.get(i).asText();
        }
        return null;
    }
    private String loadLatestQuarterlyAccession(long cik) {
        JsonNode recent = request(dataClient, "/submissions/CIK%010d.json".formatted(cik))
                .path("filings").path("recent");
        JsonNode forms = recent.path("form");
        JsonNode accessions = recent.path("accessionNumber");
        if (!forms.isArray() || !accessions.isArray()) {
            throw new StockProviderException("SEC filing list is unavailable.");
        }
        for (int i = 0; i < Math.min(forms.size(), accessions.size()); i++) {
            String form = forms.get(i).asText();
            if (form.equals("10-Q") || form.equals("10-Q/A")) return accessions.get(i).asText();
        }
        return null;
    }


    private Map<String, Long> loadTickerIndex() {
        JsonNode response = request(secClient, "/files/company_tickers.json");
        if (!response.isObject()) throw new StockProviderException("SEC ticker index is unavailable.");
        Map<String, Long> tickers = new HashMap<>();
        response.elements().forEachRemaining(row -> {
            String symbol = row.path("ticker").asText("").trim().toUpperCase(Locale.ROOT);
            long cik = row.path("cik_str").asLong(-1);
            if (!symbol.isBlank() && cik > 0) tickers.put(symbol, cik);
        });
        if (tickers.isEmpty()) throw new StockProviderException("SEC ticker index is empty.");
        return Map.copyOf(tickers);
    }

    private synchronized JsonNode request(WebClient client, String path) {
        // Stay comfortably below SEC's 10 requests/second fair-access ceiling.
        long elapsed = System.nanoTime() - lastRequestNanos;
        long delay = Duration.ofMillis(250).toNanos() - elapsed;
        if (lastRequestNanos != 0 && delay > 0) {
            try { Thread.sleep(Duration.ofNanos(delay)); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new StockProviderException("SEC request interrupted.", exception);
            }
        }
        lastRequestNanos = System.nanoTime();
        try {
            JsonNode response = client.get().uri(path).retrieve()
                    .onStatus(HttpStatusCode::isError, result -> result.createException()
                            .map(exception -> new StockProviderException("SEC filing data is temporarily unavailable.")))
                    .bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            if (response == null) throw new StockProviderException("SEC returned an empty response.");
            return response;
        } catch (StockProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new StockProviderException("SEC filing data could not be reached.", exception);
        }
    }
}
