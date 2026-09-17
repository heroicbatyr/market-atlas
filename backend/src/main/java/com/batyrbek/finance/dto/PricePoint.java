package com.batyrbek.finance.dto;

import java.time.LocalDate;

public record PricePoint(LocalDate date, Double close) {}
