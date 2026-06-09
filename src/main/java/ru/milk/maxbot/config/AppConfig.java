package ru.milk.maxbot.config;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public record AppConfig(
        String botToken,
        Path dbPath,
        ZoneId zoneId,
        double baseFatPercent,
        double baseProteinPercent,
        int longPollTimeoutSeconds,
        LocalTime shiftSummaryTime,
        boolean autoRotatePortraitImages,
        Set<Long> bootstrapAdminUserIds
) {

    public static AppConfig load() {
        String botToken = required("MAX_BOT_TOKEN");
        Path dbPath = Path.of(env("MAX_DB_PATH", "./data/milk-bot.sqlite"));
        ZoneId zoneId = ZoneId.of(env("MILK_BOT_TIMEZONE", ZoneId.systemDefault().getId()));
        double baseFat = Double.parseDouble(env("MILK_BASE_FAT_PERCENT", "3.4"));
        double baseProtein = Double.parseDouble(env("MILK_BASE_PROTEIN_PERCENT", "3.0"));
        int longPollTimeout = Integer.parseInt(env("MAX_LONG_POLL_TIMEOUT_SECONDS", "25"));
        LocalTime shiftSummaryTime = LocalTime.parse(env("MILK_SHIFT_SUMMARY_TIME", "20:00"));
        boolean autoRotate = Boolean.parseBoolean(env("MILK_AUTO_ROTATE_PORTRAIT_IMAGES", "true"));
        Set<Long> adminIds = parseIds(env("BOOTSTRAP_ADMIN_USER_IDS", ""));
        return new AppConfig(
                botToken,
                dbPath,
                zoneId,
                baseFat,
                baseProtein,
                longPollTimeout,
                shiftSummaryTime,
                autoRotate,
                adminIds
        );
    }

    private static Set<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
