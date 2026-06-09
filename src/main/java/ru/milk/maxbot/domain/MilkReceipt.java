package ru.milk.maxbot.domain;

import java.time.Instant;
import java.time.LocalDate;

public record MilkReceipt(
        long id,
        String publicId,
        long createdByUserId,
        String createdByName,
        long pointId,
        String pointName,
        long farmId,
        String farmName,
        String sectionLabel,
        LocalDate deliveryDate,
        double weightKg,
        double fatPercent,
        double proteinPercent,
        double creditWeightKg,
        String photoToken,
        String photoPayloadJson,
        Integer photoWidth,
        Integer photoHeight,
        String photoStatus,
        String originalMessageId,
        String note,
        Instant editableUntil,
        Instant adminOverrideUnlockedUntil,
        boolean deleted,
        Instant createdAt,
        Instant updatedAt
) {
}
