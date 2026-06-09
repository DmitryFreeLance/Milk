package ru.milk.maxbot.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record ConversationSession(String state, JsonNode data) {
}
