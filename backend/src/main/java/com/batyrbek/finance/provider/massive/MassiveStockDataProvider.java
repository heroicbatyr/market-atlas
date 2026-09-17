package com.batyrbek.finance.provider.massive;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import com.batyrbek.finance.dto.CompanyFundamentals;
import com.batyrbek.finance.dto.MarketInstrument;
import com.batyrbek.finance.dto.PricePoint;
import com.batyrbek.finance.dto.StockHistory;
import com.batyrbek.finance.dto.StockQuote;
import com.batyrbek.finance.exception.ProviderRateLimitException;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.batyrbek.finance.exception.StockProviderException;
import com.batyrbek.finance.exception.UnsupportedInstrumentException;
import com.batyrbek.finance.provider.StockDataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Broader-market adapter with a strict rolling request budget. */
@Component("massiveStockDataProvider")
public class MassiveStockDataProvider implements StockDataProvider {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private final WebClient webClient;
    private final String apiKey;
    private final boolean enabled;
    private final int requestsPerMinute;
    private final ArrayDeque<Instant> recentRequests = new ArrayDeque<>();

    public MassiveStockDataProvider(@Qualifier("massiveWebClient") WebClient webClient,
                                    @Value("${stock.massive.api-key:}") String apiKey,
                                    @Value("${stock.massive.enabled:false}") boolean enabled,
                                    @Value("${stock.massive.requests-per-minute:5}") int requestsPerMinute) {
        this.webClient = webClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.enabled = enabled;
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
    }

    public boolean isEnabled() { return enabled && !apiKey.isBlank(); }

    public MarketInstrument resolveInstrument(String ticker) {
        JsonNode result = request("/v3/reference/tickers/" + ticker, ticker).path("results");
        if (result.isMissingNode() || result.isNull()) throw new StockNotFoundException(ticker);
        return instrument(result, ticker);
    }

    public List<MarketInstrument> searchInstruments(String query) {
        requireEnabled();
        acquireBudget();
        try {
            JsonNode response = webClient.get().uri(builder -> builder.path("/v3/reference/tickers")
                            .queryParam("search", query).queryParam("active", true)
                            .queryParam("market", "stocks").queryParam("locale", "us")
                            .queryParam("limit", 20).queryParam("sort", "ticker")
                            .queryParam("apiKey", apiKey).build())
                    .retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            if (response == null) throw new StockProviderException("The market-data provider returned an empty response.");
            List<MarketInstrument> matches = new ArrayList<>();
            for (JsonNode row : response.path("results")) {
                try { matches.add(instrument(row, text(row, "ticker"))); }
                catch (StockNotFoundException | UnsupportedInstrumentException ignored) { }
            }
            return List.copyOf(matches);
        } catch (WebClientResponseException exception) {
            throw providerHttpException(exception, query);
        } catch (StockProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new StockProviderException("The market-data provider could not be reached.", exception);
        }
    }

    @Override
    public StockQuote fetchQuote(String ticker) {
        JsonNode bars = request("/v2/aggs/ticker/" + ticker + "/range/1/day/" + LocalDate.now(ZoneOffset.UTC).minusDays(7)
                + "/" + LocalDate.now(ZoneOffset.UTC), ticker);
        JsonNode latest = latestBar(bars, ticker);
        JsonNode prior = priorBar(bars, latest);
        Double price = number(latest, "c");
        Double previousClose = prior == null ? null : number(prior, "c");
        Double change = price == null || previousClose == null ? null : price - previousClose;
        Double changePercent = change == null || previousClose == null || previousClose == 0 ? null : change / previousClose * 100;
        LocalDate priceDate = Instant.ofEpochMilli(latest.path("t").asLong()).atZone(ZoneOffset.UTC).toLocalDate();
        return new StockQuote(price, change, changePercent, longNumber(latest, "v"), null, null, null, null, Instant.now(), priceDate);
    }

    @Override
    public CompanyFundamentals fetchFundamentals(String ticker) {
        JsonNode result = request("/v3/reference/tickers/" + ticker, ticker).path("results");
        if (result.isMissingNode() || result.isNull()) throw new StockNotFoundException(ticker);
        instrument(result, ticker);
        String name = text(result, "name");
        return new CompanyFundamentals(name, text(result, "currency_name"), longNumber(result, "market_cap"),
                null, null, null, null, null, Instant.now(), text(result, "primary_exchange"), null,
                text(result, "sic_description"), text(result, "description"), null, null, null,
                text(result, "homepage_url"), null, null, null);
    }

    @Override
    public StockHistory fetchHistory(String ticker) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate cutoff = today.minusYears(2);
        JsonNode response = request("/v2/aggs/ticker/" + ticker + "/range/1/day/" + cutoff + "/" + today, ticker);
        TreeMap<LocalDate, Double> points = new TreeMap<>();
        for (JsonNode row : response.path("results")) {
            Double close = number(row, "c");
            if (close == null || !row.has("t")) continue;
            points.put(Instant.ofEpochMilli(row.path("t").asLong()).atZone(ZoneOffset.UTC).toLocalDate(), close);
        }
        if (points.isEmpty()) throw new StockNotFoundException(ticker);
        List<PricePoint> normalized = points.entrySet().stream().map(entry -> new PricePoint(entry.getKey(), entry.getValue())).toList();
        return new StockHistory(ticker, "USD", "2y", "daily", normalized, Instant.now(), false);
    }

    @Override
    public com.batyrbek.finance.dto.CompanyFinancials fetchFinancials(String ticker) {
        throw new StockNotFoundException(ticker);
    }

    private JsonNode request(String path, String ticker) {
        requireEnabled();
        acquireBudget();
        try {
            JsonNode response = webClient.get().uri(builder -> builder.path(path).queryParam("apiKey", apiKey).build())
                    .retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            if (response == null) throw new StockProviderException("The market-data provider returned an empty response.");
            if (response.path("status").asText().equalsIgnoreCase("ERROR")) {
                String message = response.path("error").asText("");
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("rate")) throw new ProviderRateLimitException();
                if (normalized.contains("not found") || normalized.contains("unknown ticker")
                        || normalized.contains("does not exist")) throw new StockNotFoundException(ticker);
                throw new StockProviderException("The market-data provider rejected the request.");
            }
            return response;
        } catch (StockNotFoundException | StockProviderException exception) {
            throw exception;
        } catch (WebClientResponseException exception) {
            throw providerHttpException(exception, ticker);
        } catch (RuntimeException exception) {
            throw new StockProviderException("The market-data provider could not be reached.", exception);
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new StockProviderException("Broader-market search is disabled.");
        if (apiKey.isBlank()) throw new StockProviderException("The broader market-data provider is not configured yet.");
    }

    private synchronized void acquireBudget() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofMinutes(1));
        while (!recentRequests.isEmpty() && recentRequests.peekFirst().isBefore(cutoff)) recentRequests.removeFirst();
        if (recentRequests.size() >= requestsPerMinute) {
            throw new ProviderRateLimitException(recentRequests.peekFirst().plus(Duration.ofMinutes(1)));
        }
        recentRequests.addLast(now);
    }

    private RuntimeException providerHttpException(WebClientResponseException exception, String ticker) {
        if (exception.getStatusCode().value() == 429) {
            return new ProviderRateLimitException(retryAfter(exception.getHeaders()));
        }
        if (exception.getStatusCode().value() == 404) return new StockNotFoundException(ticker);
        return new StockProviderException("The market-data provider could not complete the request.", exception);
    }

    private static Instant retryAfter(HttpHeaders headers) {
        Instant fallback = Instant.now().plus(Duration.ofMinutes(1));
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.now().plusSeconds(Math.max(1, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            try {
                Instant parsed = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return parsed.isAfter(Instant.now()) ? parsed : fallback;
            } catch (DateTimeParseException invalidDate) {
                return fallback;
            }
        }
    }

    private static MarketInstrument instrument(JsonNode row, String fallbackTicker) {
        String symbol = text(row, "ticker");
        if (symbol == null) symbol = fallbackTicker;
        if (symbol == null || text(row, "name") == null) throw new StockNotFoundException(String.valueOf(fallbackTicker));
        String market = text(row, "market");
        String locale = text(row, "locale");
        String type = text(row, "type");
        String exchange = text(row, "primary_exchange");
        boolean active = row.path("active").asBoolean(false);
        if (!active || !"stocks".equalsIgnoreCase(market) || !"us".equalsIgnoreCase(locale)
                || !"CS".equalsIgnoreCase(type)
                || (exchange != null && exchange.toUpperCase(Locale.ROOT).contains("OTC"))) {
            throw new UnsupportedInstrumentException(symbol);
        }
        String cik = text(row, "cik");
        boolean filings = cik != null;
        return new MarketInstrument(symbol.toUpperCase(Locale.ROOT), text(row, "name"), "common_stock",
                true, exchange, cik, "Massive", null, List.of("1W", "1M", "6M", "YTD", "1Y", "2Y"),
                new MarketInstrument.Capabilities(true, true, filings, filings), false);
    }

    private static JsonNode latestBar(JsonNode response, String ticker) {
        JsonNode latest = null;
        for (JsonNode row : response.path("results")) if (latest == null || row.path("t").asLong() > latest.path("t").asLong()) latest = row;
        if (latest == null) throw new StockNotFoundException(ticker);
        return latest;
    }

    private static JsonNode priorBar(JsonNode response, JsonNode latest) {
        JsonNode prior = null;
        for (JsonNode row : response.path("results")) if (row.path("t").asLong() < latest.path("t").asLong()
                && (prior == null || row.path("t").asLong() > prior.path("t").asLong())) prior = row;
        return prior;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private static Double number(JsonNode node, String field) { return node.path(field).isNumber() ? node.path(field).doubleValue() : null; }
    private static Long longNumber(JsonNode node, String field) { return node.path(field).isNumber() ? node.path(field).longValue() : null; }
}
