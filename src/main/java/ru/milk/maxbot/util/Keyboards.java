package ru.milk.maxbot.util;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public final class Keyboards {
    private Keyboards() {
    }

    public static ArrayNode inline(List<ObjectNode> buttons) {
        if (buttons == null || buttons.isEmpty()) {
            return null;
        }
        ArrayNode rows = Jsons.array();
        for (ObjectNode button : buttons) {
            ArrayNode row = Jsons.array();
            row.add(button);
            rows.add(row);
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
        button.put("type", "callback");
        button.put("text", text);
        button.put("payload", payload);
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
        button.put("payload", payload);
        return button;
    }
}
