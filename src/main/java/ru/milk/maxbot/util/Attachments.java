package ru.milk.maxbot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Attachments {
    private Attachments() {
    }

    public static ArrayNode imageWithKeyboard(String imagePayloadJson, ArrayNode keyboard) {
        ArrayNode attachments = Jsons.array();
        if (imagePayloadJson != null && !imagePayloadJson.isBlank()) {
            ObjectNode image = Jsons.object();
            image.put("type", "image");
            JsonNode payload = Jsons.readTree(imagePayloadJson);
            image.set("payload", payload);
            attachments.add(image);
        }
        if (keyboard != null) {
            keyboard.forEach(attachments::add);
        }
        return attachments.isEmpty() ? null : attachments;
    }

    public static ArrayNode fileWithKeyboard(JsonNode payload, ArrayNode keyboard) {
        ArrayNode attachments = Jsons.array();
        if (payload != null && !payload.isMissingNode()) {
            ObjectNode file = Jsons.object();
            file.put("type", "file");
            file.set("payload", payload);
            attachments.add(file);
        }
        if (keyboard != null) {
            keyboard.forEach(attachments::add);
        }
        return attachments.isEmpty() ? null : attachments;
    }
}
