package com.batyrbek.finance.dto;

import java.time.LocalDate;

public record QuarterlyEarnings(
        LocalDate periodEnd, String fiscalPeriod, Double reportedEps,
        Double revenue, Double netIncome, String filingUrl, LocalDate filedAt
) {}
