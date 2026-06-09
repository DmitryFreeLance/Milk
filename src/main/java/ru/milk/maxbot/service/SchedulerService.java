package ru.milk.maxbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.milk.maxbot.config.AppConfig;
import ru.milk.maxbot.domain.BotUser;
import ru.milk.maxbot.repository.BotRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SchedulerService {
    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final AppConfig config;
    private final BotRepository repository;
    private final BotService botService;
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    public SchedulerService(AppConfig config, BotRepository repository, BotService botService) {
        this.config = config;
        this.repository = repository;
        this.botService = botService;
    }

    public void start() {
        executorService.scheduleAtFixedRate(this::safeRun, 20, 60, TimeUnit.SECONDS);
    }

    private void safeRun() {
        try {
            runDigestTask();
        } catch (Exception e) {
            log.error("Scheduled digest task failed", e);
        }
    }

    private void runDigestTask() {
        LocalDateTime now = LocalDateTime.now(config.zoneId());
        if (now.toLocalTime().isBefore(config.shiftSummaryTime())) {
            return;
        }
        LocalDate date = now.toLocalDate();
        for (BotUser user : repository.listReportRecipients()) {
            if (repository.isDailyDigestAlreadySent(date, user.id())) {
                continue;
            }
            botService.sendDailyDigestToUser(user, date);
            repository.markDailyDigestSent(date, user.id());
        }
    }
}
