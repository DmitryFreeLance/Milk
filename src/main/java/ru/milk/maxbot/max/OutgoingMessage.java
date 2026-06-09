package ru.milk.maxbot.max;

import com.fasterxml.jackson.databind.node.ArrayNode;

public record OutgoingMessage(
        String text,
        ArrayNode attachments,
        boolean notifyRecipients,
        String format
) {
    public static OutgoingMessage markdown(String text, ArrayNode attachments) {
        return new OutgoingMessage(text, attachments, true, "markdown");
    }
}
