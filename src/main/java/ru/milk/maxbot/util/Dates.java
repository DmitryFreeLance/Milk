package ru.milk.maxbot.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class Dates {
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private Dates() {
    }

    public static String formatDate(LocalDate date) {
        return DATE.format(date);
    }

    public static String formatDateTime(Instant instant, ZoneId zoneId) {
        return DATE_TIME.format(LocalDateTime.ofInstant(instant, zoneId));
    }
}
