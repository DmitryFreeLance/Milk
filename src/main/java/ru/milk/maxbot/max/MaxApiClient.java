package ru.milk.maxbot.max;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.milk.maxbot.util.Jsons;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class MaxApiClient {
    private static final Logger log = LoggerFactory.getLogger(MaxApiClient.class);
    private static final String API_BASE = "https://platform-api.max.ru";

    private final HttpClient httpClient;
    private final String token;

    public MaxApiClient(String token) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.token = token;
    }

    public JsonNode getUpdates(Long marker, int timeoutSeconds, List<String> types) {
        List<String> params = new ArrayList<>();
        params.add("limit=100");
        params.add("timeout=" + timeoutSeconds);
        if (marker != null) {
            params.add("marker=" + marker);
        }
        if (types != null && !types.isEmpty()) {
            params.add("types=" + urlEncode(String.join(",", types)));
        }
        String uri = API_BASE + "/updates" + (params.isEmpty() ? "" : "?" + String.join("&", params));
        HttpRequest request = baseRequest(URI.create(uri))
                .timeout(Duration.ofSeconds(Math.max(timeoutSeconds + 20L, 45L)))
                .GET()
                .build();
        return sendJson(request);
    }

    public JsonNode sendToUser(long userId, OutgoingMessage message) {
        String uri = API_BASE + "/messages?user_id=" + userId;
        return sendMessage(uri, message);
    }

    public JsonNode sendToChat(long chatId, OutgoingMessage message) {
        String uri = API_BASE + "/messages?chat_id=" + chatId;
        return sendMessage(uri, message);
    }

    public void answerCallback(String callbackId, String notification) {
        String uri = API_BASE + "/answers?callback_id=" + urlEncode(callbackId);
        ObjectNode body = Jsons.object();
        if (notification != null && !notification.isBlank()) {
            body.put("notification", notification);
        }
        HttpRequest request = baseRequest(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.write(body)))
                .build();
        sendJson(request);
    }

    public JsonNode uploadLocalFile(Path path, String type) {
        try {
            return uploadBytes(Files.readAllBytes(path), path.getFileName().toString(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file for upload: " + path, e);
        }
    }

    public JsonNode uploadBytes(byte[] bytes, String filename, String type) {
        JsonNode uploadInfo = requestUploadUrl(type);
        String uploadUrl = uploadInfo.path("url").asText();
        String boundary = "----MaxBotBoundary" + System.nanoTime();
        byte[] body = MultipartBodyBuilder.build(boundary, filename, bytes);

        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return sendJson(request);
    }

    private JsonNode requestUploadUrl(String type) {
        String uri = API_BASE + "/uploads?type=" + urlEncode(type);
        HttpRequest request = baseRequest(URI.create(uri))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return sendJson(request);
    }

    private JsonNode sendMessage(String uri, OutgoingMessage message) {
        ObjectNode body = Jsons.object();
        body.put("text", message.text());
        body.put("notify", message.notifyRecipients());
        if (message.attachments() != null) {
            body.set("attachments", message.attachments());
        }
        if (message.format() != null && !message.format().isBlank()) {
            body.put("format", message.format());
        }
        HttpRequest request = baseRequest(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.write(body)))
                .build();
        return containsFileAttachment(message) ? sendJsonWithAttachmentRetry(request) : sendJson(request);
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(95))
                .header("Authorization", token)
                .header("Accept", "application/json");
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("MAX API error " + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return Jsons.object();
            }
            return Jsons.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("MAX API communication error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MAX API request interrupted", e);
        }
    }

    private JsonNode sendJsonWithAttachmentRetry(HttpRequest request) {
        int maxAttempts = 8;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return sendJson(request);
            } catch (IllegalStateException e) {
                if (!isAttachmentNotReadyError(e) || attempt == maxAttempts) {
                    throw e;
                }
                sleepQuietly(300L * attempt);
            }
        }
        throw new IllegalStateException("Attachment retry loop exited unexpectedly");
    }

    private boolean containsFileAttachment(OutgoingMessage message) {
        if (message.attachments() == null) {
            return false;
        }
        for (JsonNode attachment : message.attachments()) {
            if ("file".equalsIgnoreCase(attachment.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAttachmentNotReadyError(IllegalStateException error) {
        String message = error.getMessage();
        return message != null && message.contains("attachment.not.ready");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting attachment processing", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class MultipartBodyBuilder {
        private MultipartBodyBuilder() {
        }

        static byte[] build(String boundary, String filename, byte[] data) {
            String prefix = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"data\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            String suffix = "\r\n--" + boundary + "--\r\n";

            byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
            byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[prefixBytes.length + data.length + suffixBytes.length];
            System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
            System.arraycopy(data, 0, result, prefixBytes.length, data.length);
            System.arraycopy(suffixBytes, 0, result, prefixBytes.length + data.length, suffixBytes.length);
            return result;
        }
    }
}
