package com.batyrbek.finance.provider.sec;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import com.batyrbek.finance.dto.CompanyEarnings;
import com.batyrbek.finance.dto.QuarterlyEarnings;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;

/** Reported quarter facts only; estimates and expected dates require a separate source. */
public final class SecQuarterlyEarningsMapper {
    private SecQuarterlyEarningsMapper() {}

    private static final List<String> REVENUE = List.of("RevenueFromContractWithCustomerExcludingAssessedTax",
            "Revenues", "SalesRevenueNet", "RevenueFromContractWithCustomerIncludingAssessedTax",
            "RevenuesNetOfInterestExpense");
    private static final List<String> EPS = List.of("EarningsPerShareDiluted", "EarningsPerShareBasic");
    private static final List<String> NET_INCOME = List.of("NetIncomeLoss", "ProfitLoss");

    public static CompanyEarnings map(String ticker, long cik, JsonNode companyFacts) {
        JsonNode gaap = companyFacts.path("facts").path("us-gaap");
        TreeMap<LocalDate, Fact> anchors = new TreeMap<>();
        for (String tag : REVENUE) {
            for (Fact fact : facts(gaap, tag, "USD")) anchors.merge(fact.end(), fact,
                    (old, recent) -> old.filed().isAfter(recent.filed()) ? old : recent);
        }
        if (anchors.isEmpty()) {
            for (String tag : EPS) {
                for (Fact fact : facts(gaap, tag, "USD/shares")) anchors.merge(fact.end(), fact,
                        (old, recent) -> old.filed().isAfter(recent.filed()) ? old : recent);
            }
        }
        if (anchors.isEmpty()) throw new StockNotFoundException(ticker);
        List<QuarterlyEarnings> reported = new ArrayList<>();
        for (Fact anchor : anchors.descendingMap().values().stream().limit(8)
                .sorted(Comparator.comparing(Fact::end).reversed()).toList()) {
            LocalDate end = anchor.end();
            String accession = anchor.accession();
            reported.add(new QuarterlyEarnings(end, anchor.fiscalPeriod(),
                    value(gaap, EPS, "USD/shares", end, accession),
                    value(gaap, REVENUE, "USD", end, accession),
                    value(gaap, NET_INCOME, "USD", end, accession),
                    "https://www.sec.gov/Archives/edgar/data/" + cik + "/"
                            + accession.replace("-", "") + "/" + accession + "-index.htm",
                    anchor.filed()));
        }
        return new CompanyEarnings(ticker, "USD", List.copyOf(reported), null,
                "SEC EDGAR", Instant.now(), false);
    }

    private static Double value(JsonNode gaap, List<String> tags, String unit,
                                LocalDate end, String accession) {
        for (String tag : tags) {
            Fact match = facts(gaap, tag, unit).stream()
                    .filter(fact -> fact.end().equals(end) && fact.accession().equals(accession))
                    .max(Comparator.comparing(Fact::filed)).orElse(null);
            if (match != null) return match.value();
        }
        return null;
    }

    private static List<Fact> facts(JsonNode gaap, String tag, String unit) {
        JsonNode rows = gaap.path(tag).path("units").path(unit);
        if (!rows.isArray()) return List.of();
        List<Fact> result = new ArrayList<>();
        for (JsonNode row : rows) {
            String form = row.path("form").asText("");
            if (!form.equals("10-Q") && !form.equals("10-Q/A")) continue;
            String period = row.path("fp").asText("");
            if (!List.of("Q1", "Q2", "Q3").contains(period)) continue;
            try {
                LocalDate start = LocalDate.parse(row.path("start").asText());
                LocalDate end = LocalDate.parse(row.path("end").asText());
                long days = ChronoUnit.DAYS.between(start, end);
                if (days < 65 || days > 115) continue;
                String accession = row.path("accn").asText("");
                if (accession.isBlank() || !row.path("val").isNumber()) continue;
                result.add(new Fact(end, LocalDate.parse(row.path("filed").asText()),
                        accession, period, row.path("val").doubleValue()));
            } catch (RuntimeException ignored) {
                // Skip bad facts without losing the issuer's other quarters.
            }
        }
        return result;
    }

    private record Fact(LocalDate end, LocalDate filed, String accession,
                        String fiscalPeriod, Double value) {}
}
