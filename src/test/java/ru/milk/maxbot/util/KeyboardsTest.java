package ru.milk.maxbot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KeyboardsTest {

    @Test
    void inlineCallbackButtonSendsTextMessageButtonToMaxApi() {
        ArrayNode attachments = Keyboards.inline(List.of(Keyboards.callback("Березники", "admin:record:view:42")));

        JsonNode button = attachments.path(0)
                .path("payload")
                .path("buttons")
                .path(0)
                .path(0);

        assertEquals("message", button.path("type").asText());
        assertEquals("Березники", button.path("text").asText());
        assertFalse(button.has("payload"));
        assertFalse(button.has("_bot_payload"));
    }
}
