package ru.milk.maxbot.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.milk.maxbot.config.AppConfig;
import ru.milk.maxbot.max.MaxApiClient;
import ru.milk.maxbot.util.Jsons;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;

public class PhotoService {
    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final AppConfig config;
    private final MaxApiClient maxApiClient;
    private final HttpClient httpClient;

    public PhotoService(AppConfig config, MaxApiClient maxApiClient) {
        this.config = config;
        this.maxApiClient = maxApiClient;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public Optional<ProcessedPhoto> processIncomingImage(JsonNode attachment) {
        if (attachment == null || !"image".equalsIgnoreCase(attachment.path("type").asText())) {
            return Optional.empty();
        }

        JsonNode payload = attachment.path("payload");
        String token = textOrNull(payload, "token");
        Integer width = intOrNull(payload, "width", "w");
        Integer height = intOrNull(payload, "height", "h");
        String status = evaluateStatus(width, height);
        JsonNode finalPayload = payload;

        if (config.autoRotatePortraitImages() && width != null && height != null && width < height) {
            Optional<String> sourceUrl = findDownloadUrl(payload);
            if (sourceUrl.isPresent()) {
                try {
                    byte[] original = downloadBytes(sourceUrl.get());
                    byte[] rotated = rotateToLandscapeIfNeeded(original);
                    if (rotated != null) {
                        JsonNode uploadPayload = maxApiClient.uploadBytes(rotated, "rotated.jpg", "image");
                        finalPayload = uploadPayload;
                        token = textOrNull(uploadPayload, "token");
                        BufferedImage rotatedImage = ImageIO.read(new ByteArrayInputStream(rotated));
                        width = rotatedImage == null ? width : rotatedImage.getWidth();
                        height = rotatedImage == null ? height : rotatedImage.getHeight();
                        status = "AUTO_ROTATED";
                    }
                } catch (Exception e) {
                    log.warn("Could not auto-rotate image, continuing with original photo", e);
                }
            }
        }

        if (token == null && finalPayload.hasNonNull("token")) {
            token = finalPayload.get("token").asText();
        }
        return Optional.of(new ProcessedPhoto(token, Jsons.write(finalPayload), width, height, status));
    }

    private String evaluateStatus(Integer width, Integer height) {
        if (width == null || height == null) {
            return "NEEDS_REVIEW";
        }
        if (width < 900 || height < 600) {
            return "NEEDS_REVIEW";
        }
        if (width < height) {
            return "NEEDS_REVIEW";
        }
        return "VALID";
    }

    private Optional<String> findDownloadUrl(JsonNode payload) {
        Iterator<String> fieldNames = payload.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            JsonNode value = payload.get(field);
            if (value.isTextual()) {
                String text = value.asText();
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    return Optional.of(text);
                }
            }
        }
        return Optional.empty();
    }

    private byte[] downloadBytes(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("Failed to download image, status " + response.statusCode());
        }
        return response.body();
    }

    private byte[] rotateToLandscapeIfNeeded(byte[] original) throws Exception {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
        if (source == null) {
            return null;
        }

        int orientation = readExifOrientation(original);
        BufferedImage normalized = applyExifRotation(source, orientation);
        BufferedImage landscape = normalized.getWidth() < normalized.getHeight() ? rotate90(normalized) : normalized;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(landscape, "jpg", baos);
        return baos.toByteArray();
    }

    private int readExifOrientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            return directory == null ? 1 : directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        } catch (Exception e) {
            return 1;
        }
    }

    private BufferedImage applyExifRotation(BufferedImage source, int orientation) {
        return switch (orientation) {
            case 6 -> rotate90(source);
            case 8 -> rotate270(source);
            case 3 -> rotate180(source);
            default -> source;
        };
    }

    private BufferedImage rotate90(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform transform = new AffineTransform();
        transform.translate(source.getHeight(), 0);
        transform.rotate(Math.toRadians(90));
        g.drawImage(source, transform, null);
        g.dispose();
        return result;
    }

    private BufferedImage rotate180(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        AffineTransform transform = new AffineTransform();
        transform.translate(source.getWidth(), source.getHeight());
        transform.rotate(Math.toRadians(180));
        g.drawImage(source, transform, null);
        g.dispose();
        return result;
    }

    private BufferedImage rotate270(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        AffineTransform transform = new AffineTransform();
        transform.translate(0, source.getWidth());
        transform.rotate(Math.toRadians(270));
        g.drawImage(source, transform, null);
        g.dispose();
        return result;
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private Integer intOrNull(JsonNode node, String first, String second) {
        if (node.has(first) && node.get(first).canConvertToInt()) {
            return node.get(first).asInt();
        }
        if (node.has(second) && node.get(second).canConvertToInt()) {
            return node.get(second).asInt();
        }
        return null;
    }

    public record ProcessedPhoto(
            String token,
            String payloadJson,
            Integer width,
            Integer height,
            String status
    ) {
    }
}
