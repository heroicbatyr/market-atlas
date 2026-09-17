package com.batyrbek.finance.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.batyrbek.finance.dto.AnnualFinancial;
import com.batyrbek.finance.dto.CompanyFinancials;

/** Keeps filing-backed SEC facts and fills only facts absent from standard XBRL. */
public final class FinancialsMerger {
    private FinancialsMerger() {}

    public static boolean needsFallback(CompanyFinancials sec) {
        if (sec.cashAndEquivalents() == null || sec.totalDebt() == null || sec.debtToEquity() == null) return true;
        return sec.annual().stream().anyMatch(row -> row.revenue() == null || row.operatingIncome() == null
                || row.netIncome() == null || row.eps() == null || row.freeCashFlow() == null);
    }

    public static CompanyFinancials fillMissing(CompanyFinancials sec, CompanyFinancials fallback) {
        Map<String, AnnualFinancial> byYear = new HashMap<>();
        fallback.annual().forEach(row -> byYear.put(row.fiscalYear(), row));
        List<AnnualFinancial> rows = new ArrayList<>();
        boolean filled = false;
        for (AnnualFinancial original : sec.annual()) {
            AnnualFinancial other = byYear.get(original.fiscalYear());
            if (other == null) { rows.add(original); continue; }
            AnnualFinancial merged = new AnnualFinancial(original.date(), original.fiscalYear(),
                    first(original.revenue(), other.revenue()),
                    first(original.operatingIncome(), other.operatingIncome()),
                    first(original.netIncome(), other.netIncome()), first(original.eps(), other.eps()),
                    first(original.freeCashFlow(), other.freeCashFlow()),
                    first(original.revenueGrowth(), other.revenueGrowth()),
                    first(original.netMargin(), other.netMargin()), original.filingUrl(),
                    original.filingForm(), original.filedAt());
            if (!merged.equals(original)) filled = true;
            rows.add(merged);
        }
        Double cash = first(sec.cashAndEquivalents(), fallback.cashAndEquivalents());
        Double debt = first(sec.totalDebt(), fallback.totalDebt());
        Double ratio = first(sec.debtToEquity(), fallback.debtToEquity());
        filled |= !equalsNullable(cash, sec.cashAndEquivalents())
                || !equalsNullable(debt, sec.totalDebt()) || !equalsNullable(ratio, sec.debtToEquity());
        return new CompanyFinancials(sec.symbol(), sec.currency(), List.copyOf(rows), cash, debt, ratio,
                filled ? "SEC EDGAR + FMP" : sec.source(), sec.fetchedAt(), sec.dataAsOf(), sec.stale());
    }

    private static Double first(Double preferred, Double other) { return preferred != null ? preferred : other; }
    private static boolean equalsNullable(Double first, Double second) {
        return first == null ? second == null : first.equals(second);
    }
}
