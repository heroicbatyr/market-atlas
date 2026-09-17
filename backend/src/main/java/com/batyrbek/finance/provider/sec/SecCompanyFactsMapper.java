package com.batyrbek.finance.provider.sec;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.batyrbek.finance.dto.AnnualFinancial;
import com.batyrbek.finance.dto.CompanyFinancials;
import com.batyrbek.finance.exception.StockNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;

/** Selects annual facts from a single filing accession for each fiscal period. */
public final class SecCompanyFactsMapper {
    private SecCompanyFactsMapper() {}

    private static final List<String> REVENUE = List.of("RevenueFromContractWithCustomerExcludingAssessedTax",
            "Revenues", "SalesRevenueNet", "RevenueFromContractWithCustomerIncludingAssessedTax",
            "RevenuesNetOfInterestExpense");
    private static final List<String> OPERATING_INCOME = List.of("OperatingIncomeLoss");
    private static final List<String> NET_INCOME = List.of("NetIncomeLoss", "ProfitLoss");
    private static final List<String> EPS = List.of("EarningsPerShareDiluted", "EarningsPerShareBasic");
    private static final List<String> OPERATING_CASH = List.of("NetCashProvidedByUsedInOperatingActivities");
    private static final List<String> CAPEX = List.of("PaymentsToAcquirePropertyPlantAndEquipment");
    private static final List<String> CASH = List.of("CashAndCashEquivalentsAtCarryingValue");
    private static final List<String> DEBT = List.of("LongTermDebtCurrent", "LongTermDebtNoncurrent");
    private static final List<String> EQUITY = List.of("StockholdersEquity");

    public static CompanyFinancials map(String ticker, long cik, JsonNode companyFacts) {
        JsonNode gaap = companyFacts.path("facts").path("us-gaap");
        if (gaap.isMissingNode()) throw new StockNotFoundException(ticker);
        TreeMap<LocalDate, Fact> anchors = new TreeMap<>();
        for (String tag : concat(REVENUE, NET_INCOME)) {
            for (Fact fact : facts(gaap, tag, "USD", true)) {
                anchors.merge(fact.end(), fact, SecCompanyFactsMapper::newer);
            }
        }
        if (anchors.isEmpty()) throw new StockNotFoundException(ticker);

        List<AnnualFinancial> annual = new ArrayList<>();
        Map<String, Fact> latestBalance = new HashMap<>();
        Double previousRevenue = null;
        for (Fact anchor : anchors.descendingMap().values().stream().limit(5).sorted(Comparator.comparing(Fact::end)).toList()) {
            String accession = anchor.accession();
            LocalDate end = anchor.end();
            Double revenue = value(gaap, REVENUE, "USD", end, accession, true);
            Double operatingIncome = value(gaap, OPERATING_INCOME, "USD", end, accession, true);
            Double netIncome = value(gaap, NET_INCOME, "USD", end, accession, true);
            Double eps = value(gaap, EPS, "USD/shares", end, accession, true);
            Double operatingCash = value(gaap, OPERATING_CASH, "USD", end, accession, true);
            Double capex = value(gaap, CAPEX, "USD", end, accession, true);
            Double fcf = operatingCash == null || capex == null ? null : operatingCash - capex;
            Double growth = revenue == null || previousRevenue == null || previousRevenue == 0
                    ? null : revenue / previousRevenue - 1;
            Double margin = netIncome == null || revenue == null || revenue == 0 ? null : netIncome / revenue;
            annual.add(new AnnualFinancial(end, String.valueOf(end.getYear()), revenue, operatingIncome,
                    netIncome, eps, fcf, growth, margin, filingUrl(cik, accession),
                    anchor.form(), anchor.filed()));
            previousRevenue = revenue;
            latestBalance.put("accession", anchor);
        }
        AnnualFinancial latest = annual.getLast();
        String accession = latestBalance.get("accession").accession();
        LocalDate end = latest.date();
        Double cash = value(gaap, CASH, "USD", end, accession, false);
        Double currentDebt = value(gaap, List.of(DEBT.getFirst()), "USD", end, accession, false);
        Double longDebt = value(gaap, List.of(DEBT.getLast()), "USD", end, accession, false);
        Double debt = currentDebt == null && longDebt == null ? null
                : (currentDebt == null ? 0 : currentDebt) + (longDebt == null ? 0 : longDebt);
        Double equity = value(gaap, EQUITY, "USD", end, accession, false);
        Double debtToEquity = debt == null || equity == null || equity == 0 ? null : debt / equity;
        return new CompanyFinancials(ticker, "USD", List.copyOf(annual), cash, debt,
                debtToEquity, "SEC EDGAR", Instant.now(), latest.date(), false);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private static Double value(JsonNode gaap, List<String> tags, String unit, LocalDate end,
                                String accession, boolean annualDuration) {
        for (String tag : tags) {
            Fact match = facts(gaap, tag, unit, annualDuration).stream()
                    .filter(fact -> fact.end().equals(end) && fact.accession().equals(accession))
                    .max(Comparator.comparing(Fact::filed)).orElse(null);
            if (match != null) return match.value();
        }
        return null;
    }

    private static List<Fact> facts(JsonNode gaap, String tag, String unit, boolean annualDuration) {
        JsonNode rows = gaap.path(tag).path("units").path(unit);
        if (!rows.isArray()) return List.of();
        List<Fact> result = new ArrayList<>();
        for (JsonNode row : rows) {
            String form = row.path("form").asText("");
            if (!List.of("10-K", "10-K/A", "20-F", "20-F/A", "40-F", "40-F/A").contains(form)) continue;
            try {
                LocalDate end = LocalDate.parse(row.path("end").asText());
                if (annualDuration) {
                    LocalDate start = LocalDate.parse(row.path("start").asText());
                    long days = ChronoUnit.DAYS.between(start, end);
                    if (days < 330 || days > 400) continue;
                } else if (row.hasNonNull("start")) continue;
                String accession = row.path("accn").asText("");
                if (accession.isBlank() || !row.path("val").isNumber()) continue;
                LocalDate filed = LocalDate.parse(row.path("filed").asText());
                result.add(new Fact(end, filed, accession, form, row.path("val").doubleValue()));
            } catch (RuntimeException ignored) {
                // A malformed fact cannot invalidate other reporting periods.
            }
        }
        return result;
    }

    private static Fact newer(Fact first, Fact second) {
        return first.filed().isAfter(second.filed()) ? first : second;
    }

    private static String filingUrl(long cik, String accession) {
        return "https://www.sec.gov/Archives/edgar/data/" + cik + "/"
                + accession.replace("-", "") + "/" + accession + "-index.htm";
    }

    private record Fact(LocalDate end, LocalDate filed, String accession, String form, Double value) {}
}
