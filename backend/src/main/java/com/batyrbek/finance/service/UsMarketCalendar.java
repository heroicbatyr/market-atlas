package com.batyrbek.finance.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Computes the next post-close refresh time for the main U.S. equity exchanges. */
@Component
public class UsMarketCalendar {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime PROVIDER_SETTLE_TIME = LocalTime.of(18, 30);

    public Instant nextCheck(Instant now) {
        ZonedDateTime local = now.atZone(NEW_YORK);
        LocalDate date = local.toLocalDate();
        if (!isTradingDay(date) || !local.toLocalTime().isBefore(PROVIDER_SETTLE_TIME)) {
            do { date = date.plusDays(1); } while (!isTradingDay(date));
        }
        return ZonedDateTime.of(date, PROVIDER_SETTLE_TIME, NEW_YORK).toInstant();
    }

    public boolean isTradingDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        Set<LocalDate> holidays = holidays(date.getYear());
        holidays.addAll(holidays(date.getYear() + 1));
        return !holidays.contains(date);
    }

    private Set<LocalDate> holidays(int year) {
        Set<LocalDate> result = new HashSet<>();
        result.add(observed(LocalDate.of(year, Month.JANUARY, 1)));
        result.add(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3));
        result.add(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3));
        result.add(easter(year).minusDays(2));
        result.add(LocalDate.of(year, Month.MAY, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        result.add(observed(LocalDate.of(year, Month.JUNE, 19)));
        result.add(observed(LocalDate.of(year, Month.JULY, 4)));
        result.add(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1));
        result.add(nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4));
        result.add(observed(LocalDate.of(year, Month.DECEMBER, 25)));
        return result;
    }

    private LocalDate observed(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) return date.minusDays(1);
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return date.plusDays(1);
        return date;
    }

    private LocalDate nthWeekday(int year, Month month, DayOfWeek day, int ordinal) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, day));
    }

    // Anonymous Gregorian algorithm.
    private LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }
}
