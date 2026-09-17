package com.batyrbek.finance.service;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsMarketCalendarTest {
    private final UsMarketCalendar calendar = new UsMarketCalendar();

    @Test
    void skipsWeekendsAndObservedMarketHolidays() {
        assertThat(calendar.isTradingDay(LocalDate.parse("2026-07-03"))).isFalse();
        assertThat(calendar.isTradingDay(LocalDate.parse("2026-07-04"))).isFalse();
        assertThat(calendar.isTradingDay(LocalDate.parse("2026-07-06"))).isTrue();
    }

    @Test
    void schedulesTheNextSettledCloseAcrossAWeekend() {
        Instant fridayNight = Instant.parse("2026-09-18T23:00:00Z");
        assertThat(calendar.nextCheck(fridayNight)).isEqualTo(Instant.parse("2026-09-21T22:30:00Z"));
    }
}
