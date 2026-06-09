package ru.milk.maxbot.util;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public final class Keyboards {
    private static final int COMPACT_TEXT_LIMIT = 14;

    private Keyboards() {
    }

    public static ArrayNode inline(List<ObjectNode> buttons) {
        if (buttons == null || buttons.isEmpty()) {
            return null;
        }
        ArrayNode rows = Jsons.array();

        List<ObjectNode> compactRow = new ArrayList<>(2);
        for (ObjectNode button : buttons) {
            if (isCompact(button)) {
                compactRow.add(button);
                if (compactRow.size() == 2) {
                    rows.add(buildRow(compactRow));
                    compactRow.clear();
                }
            } else {
                if (!compactRow.isEmpty()) {
                    rows.add(buildRow(compactRow));
                    compactRow.clear();
                }
                rows.add(buildRow(List.of(button)));
            }
        }
        if (!compactRow.isEmpty()) {
            rows.add(buildRow(compactRow));
        }

        ObjectNode payload = Jsons.object();
        payload.set("buttons", rows);

        ObjectNode keyboard = Jsons.object();
        keyboard.put("type", "inline_keyboard");
        keyboard.set("payload", payload);

        ArrayNode attachments = Jsons.array();
        attachments.add(keyboard);
        return attachments;
    }

    public static ObjectNode callback(String text, String payload) {
        ObjectNode button = Jsons.object();
        button.put("type", "message");
        button.put("text", text);
        button.put("_bot_payload", payload);
        return button;
    }

    public static ObjectNode contact(String text) {
        ObjectNode button = Jsons.object();
        button.put("type", "request_contact");
        button.put("text", text);
        return button;
    }

    public static ObjectNode message(String text, String payload) {
        ObjectNode button = Jsons.object();
        button.put("type", "message");
        button.put("text", text);
        button.put("_bot_payload", payload);
        return button;
    }

    private static boolean isCompact(ObjectNode button) {
        String type = button.path("type").asText("");
        if (!"callback".equals(type) && !"message".equals(type)) {
            return false;
        }
        String text = button.path("text").asText("");
        return text.codePointCount(0, text.length()) <= COMPACT_TEXT_LIMIT;
    }

    private static ArrayNode buildRow(List<ObjectNode> buttons) {
        ArrayNode row = Jsons.array();
        for (ObjectNode button : buttons) {
            row.add(toTransportButton(button));
        }
        return row;
    }

    private static ObjectNode toTransportButton(ObjectNode source) {
        ObjectNode button = Jsons.object();
        String type = source.path("type").asText("");
        String text = source.path("text").asText("");

        if (!type.isBlank()) {
            button.put("type", type);
        }
        if (!text.isBlank()) {
            button.put("text", text);
        }
        return button;
    }
}
