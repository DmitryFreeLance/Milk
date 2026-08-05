package ru.milk.maxbot.domain;

import java.time.Instant;

public record ReportEmail(
        long id,
        String email,
        Instant createdAt,
        Instant updatedAt
) {
}
