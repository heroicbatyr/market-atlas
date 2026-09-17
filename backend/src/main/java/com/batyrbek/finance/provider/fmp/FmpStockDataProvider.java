package com.batyrbek.finance.provider.fmp;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.batyrbek.finance.dto.AnnualFinancial;
import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.dto.CompanyFundamentals;
import com.batyrbek.finance.dto.PricePoint;
import com.batyrbek.finance.dto.StockHistory;
import com.batyrbek.finance.dto.StockQuote;
import com.batyrbek.finance.exception.ProviderRateLimitException;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.batyrbek.finance.exception.StockProviderException;
import com.batyrbek.finance.provider.StockDataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Maps FMP's stable endpoints into provider-neutral dashboard models. */
@Component
public class FmpStockDataProvider implements StockDataProvider {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private final WebClient webClient;
    private final String apiKey;

    public FmpStockDataProvider(WebClient stockWebClient, @Value("${stock.provider.api-key:}") String apiKey) {
        this.webClient = stockWebClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public StockQuote fetchQuote(String ticker) {
        JsonNode quote = first(request("quote", ticker), ticker);
        return new StockQuote(number(quote, "price"), number(quote, "change"),
                number(quote, "changesPercentage", "changePercentage"), longNumber(quote, "volume"),
                number(quote, "pe", "peRatio"), number(quote, "eps"),
                number(quote, "yearHigh", "fiftyTwoWeekHigh"), number(quote, "yearLow", "fiftyTwoWeekLow"),
                Instant.now(), epochDate(quote, "timestamp"));
    }

    @Override
    public CompanyFundamentals fetchFundamentals(String ticker) {
        JsonNode profile = first(request("profile", ticker), ticker);
        JsonNode metrics = firstOrEmpty(request("key-metrics-ttm", ticker));
        JsonNode incomeResponse = request("income-statement", ticker);
        JsonNode income = firstOrEmpty(incomeResponse);
        Long marketCap = longNumber(profile, "marketCap", "mktCap");
        Double revenueGrowth = growth(incomeResponse, "revenue");
        Double netMargin = firstNumber(number(metrics, "netProfitMarginTTM"),
                ratio(number(income, "netIncome"), number(income, "revenue")));
        Double freeCashFlow = firstNumber(number(metrics, "freeCashFlowTTM"),
                product(number(metrics, "freeCashFlowYieldTTM"), marketCap == null ? null : marketCap.doubleValue()));
        return new CompanyFundamentals(text(profile, "companyName", "name"), text(profile, "currency"), marketCap,
                inverse(number(metrics, "earningsYieldTTM")),
                number(income, "epsDiluted", "eps"), number(profile, "dividendYield"), null, null, Instant.now(),
                text(profile, "exchangeShortName", "exchange"), text(profile, "sector"), text(profile, "industry"),
                text(profile, "description"), text(profile, "ceo"), longNumber(profile, "fullTimeEmployees", "employees"),
                headquarters(profile), text(profile, "website"), revenueGrowth, netMargin, freeCashFlow);
    }

    @Override
    public CompanyFinancials fetchFinancials(String ticker) {
        JsonNode incomeResponse = statement("income-statement", ticker);
        if (!incomeResponse.isArray() || incomeResponse.isEmpty()) throw new StockNotFoundException(ticker);
        JsonNode cashFlowResponse = statement("cash-flow-statement", ticker);
        JsonNode balanceResponse = statement("balance-sheet-statement", ticker);
        Map<String, JsonNode> cashByPeriod = byPeriod(cashFlowResponse);
        List<JsonNode> incomeRows = new ArrayList<>();
        incomeResponse.forEach(incomeRows::add);
        incomeRows.sort(Comparator.comparing(FmpStockDataProvider::dateOf, Comparator.nullsLast(Comparator.naturalOrder())));
        List<AnnualFinancial> annual = new ArrayList<>();
        Double previousRevenue = null;
        for (JsonNode row : incomeRows) {
            LocalDate date = dateOf(row);
            if (date == null) continue;
            Double revenue = number(row, "revenue");
            JsonNode cash = cashByPeriod.getOrDefault(periodKey(row), emptyNode());
            annual.add(new AnnualFinancial(date, text(row, "fiscalYear", "calendarYear"), revenue,
                    number(row, "operatingIncome"), number(row, "netIncome"), number(row, "epsDiluted", "eps"),
                    number(cash, "freeCashFlow"), growth(revenue, previousRevenue),
                    ratio(number(row, "netIncome"), revenue)));
            previousRevenue = revenue;
        }
        if (annual.isEmpty()) throw new StockNotFoundException(ticker);
        JsonNode latestBalance = latest(balanceResponse);
        Double debt = number(latestBalance, "totalDebt");
        Double equity = number(latestBalance, "totalStockholdersEquity", "totalEquity");
        AnnualFinancial latest = annual.getLast();
        return new CompanyFinancials(ticker, text(incomeRows.getLast(), "reportedCurrency", "currency"), List.copyOf(annual),
                number(latestBalance, "cashAndCashEquivalents", "cashAndShortTermInvestments"), debt,
                ratio(debt, equity), "FMP", Instant.now(), latest.date(), false);
    }

    @Override
    public StockHistory fetchHistory(String ticker) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate cutoff = today.minusYears(5);
        JsonNode history = request("historical-price-eod/full", ticker, cutoff, today);
        if (!history.isArray() || history.isEmpty()) throw new StockNotFoundException(ticker);
        TreeMap<LocalDate, Double> points = new TreeMap<>();
        for (JsonNode point : history) {
            try {
                LocalDate date = LocalDate.parse(point.path("date").asText());
                Double close = number(point, "close");
                if (!date.isBefore(cutoff) && close != null) points.put(date, close);
            } catch (RuntimeException ignored) {
                // A malformed provider record must not discard an otherwise useful history series.
            }
        }
        if (points.isEmpty()) throw new StockNotFoundException(ticker);
        List<PricePoint> normalized = points.entrySet().stream()
                .map(entry -> new PricePoint(entry.getKey(), entry.getValue())).toList();
        return new StockHistory(ticker, "USD", "5y", "daily", normalized, Instant.now(), false);
    }

    private JsonNode statement(String endpoint, String ticker) {
        requireApiKey();
        try {
            JsonNode response = webClient.get().uri(uriBuilder -> uriBuilder.path("/stable/" + endpoint)
                            .queryParam("symbol", ticker).queryParam("period", "annual").queryParam("limit", 6)
                            .queryParam("apikey", apiKey).build())
                    .retrieve().onStatus(status -> status.value() == 429, rateLimitResponse -> reactor.core.publisher.Mono.just(new ProviderRateLimitException()))
                    .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.createException()
                            .map(exception -> new StockProviderException("The market-data provider is temporarily unavailable.")))
                    .bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            return validate(response, ticker);
        } catch (StockNotFoundException | StockProviderException exception) {
            throw exception;
        } catch (WebClientResponseException.TooManyRequests exception) {
            throw new ProviderRateLimitException();
        } catch (RuntimeException exception) {
            throw new StockProviderException("The market-data provider could not be reached.", exception);
        }
    }

    private JsonNode request(String endpoint, String ticker) {
        return request(endpoint, ticker, null, null);
    }

    private JsonNode request(String endpoint, String ticker, LocalDate from, LocalDate to) {
        requireApiKey();
        try {
            JsonNode response = webClient.get().uri(uriBuilder -> {
                        var builder = uriBuilder.path("/stable/" + endpoint)
                                .queryParam("symbol", ticker).queryParam("apikey", apiKey);
                        if (from != null) builder.queryParam("from", from);
                        if (to != null) builder.queryParam("to", to);
                        return builder.build();
                    }).retrieve().onStatus(status -> status.value() == 429, rateLimitResponse -> reactor.core.publisher.Mono.just(new ProviderRateLimitException()))
                    .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.createException()
                            .map(exception -> new StockProviderException("The market-data provider is temporarily unavailable.")))
                    .bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            return validate(response, ticker);
        } catch (StockNotFoundException | StockProviderException exception) {
            throw exception;
        } catch (WebClientResponseException.TooManyRequests exception) {
            throw new ProviderRateLimitException();
        } catch (WebClientResponseException exception) {
            throw new StockProviderException("The market-data provider is temporarily unavailable.", exception);
        } catch (RuntimeException exception) {
            throw new StockProviderException("The market-data provider could not be reached.", exception);
        }
    }

    private static JsonNode validate(JsonNode response, String ticker) {
        if (response == null) throw new StockProviderException("The market-data provider returned an empty response.");
        if (response.has("Error Message") || response.has("error")) throw new StockNotFoundException(ticker);
        if (response.has("message") || response.has("Information") || response.has("Note")) throw new ProviderRateLimitException();
        return response;
    }

    private static JsonNode first(JsonNode response, String ticker) {
        if (!response.isArray() || response.isEmpty()) throw new StockNotFoundException(ticker);
        return response.get(0);
    }

    private static JsonNode firstOrEmpty(JsonNode response) {
        return response != null && response.isArray() && !response.isEmpty() ? response.get(0) : emptyNode();
    }

    private static JsonNode emptyNode() {
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private void requireApiKey() {
        if (apiKey.isBlank()) throw new StockProviderException("The stock-data service is not configured yet.");
    }

    private static Map<String, JsonNode> byPeriod(JsonNode response) {
        Map<String, JsonNode> values = new HashMap<>();
        if (response != null && response.isArray()) response.forEach(row -> values.put(periodKey(row), row));
        return values;
    }

    private static String periodKey(JsonNode row) {
        String fiscalYear = text(row, "fiscalYear", "calendarYear");
        return fiscalYear != null ? fiscalYear : String.valueOf(dateOf(row));
    }

    private static JsonNode latest(JsonNode response) {
        if (response == null || !response.isArray() || response.isEmpty()) return emptyNode();
        JsonNode result = null;
        LocalDate resultDate = null;
        for (JsonNode row : response) {
            LocalDate date = dateOf(row);
            if (result == null || date != null && (resultDate == null || date.isAfter(resultDate))) {
                result = row;
                resultDate = date;
            }
        }
        return result == null ? emptyNode() : result;
    }

    private static LocalDate dateOf(JsonNode row) {
        String value = text(row, "date");
        if (value == null) {
            String year = text(row, "fiscalYear", "calendarYear");
            value = year == null ? null : year + "-12-31";
        }
        try { return value == null ? null : LocalDate.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Double growth(JsonNode response, String field) {
        if (response == null || !response.isArray() || response.size() < 2) return null;
        List<JsonNode> rows = new ArrayList<>();
        response.forEach(rows::add);
        rows.sort(Comparator.comparing(FmpStockDataProvider::dateOf, Comparator.nullsLast(Comparator.reverseOrder())));
        return growth(number(rows.get(0), field), number(rows.get(1), field));
    }

    private static Double growth(Double current, Double previous) {
        return current == null || previous == null || previous == 0 ? null : current / previous - 1;
    }

    private static String headquarters(JsonNode profile) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of("city", "state", "country")) {
            String value = text(profile, field);
            if (value != null && !parts.contains(value)) parts.add(value);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static LocalDate epochDate(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.canConvertToLong()) return null;
        long epoch = value.longValue();
        if (epoch <= 0) return null;
        return Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static Double number(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) return value.doubleValue();
            String raw = value.asText("").trim();
            if (!raw.isBlank() && !"null".equalsIgnoreCase(raw)) {
                try { return Double.valueOf(raw.replace("%", "")); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private static Double firstNumber(Double preferred, Double fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static Double inverse(Double value) {
        return value == null || value <= 0 ? null : 1 / value;
    }

    private static Double ratio(Double numerator, Double denominator) {
        return numerator == null || denominator == null || denominator == 0 ? null : numerator / denominator;
    }

    private static Double product(Double first, Double second) {
        return first == null || second == null ? null : first * second;
    }

    private static Long longNumber(JsonNode node, String... fields) {
        Double value = number(node, fields);
        return value == null ? null : value.longValue();
    }
}
