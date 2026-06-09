package ru.milk.maxbot.domain;

public record BotUser(
        long id,
        long maxUserId,
        Long chatId,
        String username,
        String firstName,
        String lastName,
        String displayName,
        String phone,
        UserRole role,
        Long receivingPointId,
        boolean active,
        boolean dailyDigestEnabled
) {
}
