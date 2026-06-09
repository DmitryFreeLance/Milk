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

import java.util.List;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

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
            } catch (Exception e) {
                log.error("Long polling loop failed, retrying shortly", e);
                Thread.sleep(3_000);
            }
        }
    }
}
