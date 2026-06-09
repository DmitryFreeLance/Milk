package ru.milk.maxbot.domain;

import java.time.Instant;

public record PendingRegistration(
        long id,
        long userId,
        long maxUserId,
        String displayName,
        String phone,
        String requestedRole,
        Long requestedPointId,
        String requestedPointName,
        String comment,
        String status,
        Instant createdAt
) {
}
