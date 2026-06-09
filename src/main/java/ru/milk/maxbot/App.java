package ru.milk.maxbot;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.milk.maxbot.config.AppConfig;
import ru.milk.maxbot.db.Database;
import ru.milk.maxbot.max.MaxApiClient;
import ru.milk.maxbot.repository.BotRepository;
import ru.milk.maxbot.service.BotService;
import ru.milk.maxbot.service.ExcelReportService;
import ru.milk.maxbot.service.PhotoService;
import ru.milk.maxbot.service.ReportService;
import ru.milk.maxbot.service.SchedulerService;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.util.List;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final int NETWORK_ERROR_STREAK_BEFORE_MARKER_RESET = 20;

    public static void main(String[] args) throws InterruptedException {
        AppConfig config = AppConfig.load();
        Database database = new Database(config);
        database.init();

        MaxApiClient maxApiClient = new MaxApiClient(config.botToken());
        BotRepository repository = new BotRepository(database);
        PhotoService photoService = new PhotoService(config, maxApiClient);
        ExcelReportService excelReportService = new ExcelReportService();
        ReportService reportService = new ReportService(repository, excelReportService);
        BotService botService = new BotService(config, repository, maxApiClient, photoService, reportService);
        SchedulerService schedulerService = new SchedulerService(config, repository, botService);
        schedulerService.start();

        Long marker = null;
        int transientNetworkErrorStreak = 0;
        log.info("MAX Milk Bot started. Long polling is enabled.");

        while (true) {
            try {
                JsonNode updatesResponse = maxApiClient.getUpdates(
                        marker,
                        config.longPollTimeoutSeconds(),
                        List.of("bot_started", "bot_added", "message_created", "message_callback")
                );
                if (updatesResponse.hasNonNull("marker")) {
                    marker = updatesResponse.get("marker").asLong();
                }
                JsonNode updates = updatesResponse.path("updates");
                if (updates.isArray()) {
                    for (JsonNode update : updates) {
                        botService.processUpdate(update);
                    }
                }
                transientNetworkErrorStreak = 0;
            } catch (Exception e) {
                if (isHttpTimeout(e)) {
                    log.debug("Long polling request timed out, retrying immediately");
                    continue;
                }
                if (isTransientNetworkError(e)) {
                    transientNetworkErrorStreak++;
                    if (transientNetworkErrorStreak == 1 || transientNetworkErrorStreak % 10 == 0) {
                        log.warn("Transient network error while polling (streak={}): {}", transientNetworkErrorStreak, rootCauseMessage(e));
                    }
                    if (transientNetworkErrorStreak >= NETWORK_ERROR_STREAK_BEFORE_MARKER_RESET) {
                        marker = null;
                        transientNetworkErrorStreak = 0;
                        log.warn("Reset polling marker after repeated transient network errors");
                    }
                    Thread.sleep(1_000);
                    continue;
                }
                log.error("Long polling loop failed, retrying shortly", e);
                Thread.sleep(3_000);
            }
        }
    }

    private static boolean isHttpTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTransientNetworkError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketException || current instanceof EOFException || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        Throwable last = error;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        return last == null ? "unknown" : String.valueOf(last.getMessage());
    }
}
