package com.batyrbek.finance.validation;

import java.util.Locale;
import java.util.regex.Pattern;

import com.batyrbek.finance.exception.InvalidTickerException;
import org.springframework.stereotype.Component;

@Component
public class TickerNormalizer {
    private static final Pattern VALID_TICKER = Pattern.compile("^[A-Z][A-Z0-9.-]{0,9}$");

    public String normalize(String ticker) {
        String normalized = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        if (!VALID_TICKER.matcher(normalized).matches()) {
            throw new InvalidTickerException("Ticker must be 1-10 letters, numbers, periods, or hyphens.");
        }
        return normalized;
    }
}
