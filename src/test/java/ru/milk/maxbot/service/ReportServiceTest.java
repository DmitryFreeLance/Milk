package ru.milk.maxbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.milk.maxbot.config.AppConfig;
import ru.milk.maxbot.db.Database;
import ru.milk.maxbot.domain.BotUser;
import ru.milk.maxbot.domain.Farm;
import ru.milk.maxbot.domain.MilkReceipt;
import ru.milk.maxbot.domain.ReceivingPoint;
import ru.milk.maxbot.repository.BotRepository;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {
    @TempDir
    Path tempDir;

    private BotRepository repository;
    private ReportService reportService;
    private BotUser admin;
    private ReceivingPoint point;
    private Farm berezniki;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        AppConfig config = new AppConfig(
                "test-token",
                tempDir.resolve("milk-test.sqlite"),
                ZoneId.of("Asia/Novosibirsk"),
                3.4,
                3.0,
                25,
                LocalTime.of(20, 0),
                true,
                Set.of(1001L)
        );
        Database database = new Database(config);
        database.init();
        repository = new BotRepository(database);
        reportService = new ReportService(repository, new ExcelReportService());
        admin = repository.upsertUser(1001L, null, "admin", "Тест", "Админ", true);
        point = repository.listPoints().stream()
                .filter(item -> item.name().equals("Большая Арать"))
                .findFirst()
                .orElseThrow();
        berezniki = repository.listFarms(true).stream()
                .filter(item -> item.name().equals("Березники"))
                .findFirst()
                .orElseThrow();
        date = LocalDate.of(2026, 7, 28);
    }

    @Test
    void detailedDailyReportListsEveryReceiptForEveryFarm() {
        createReceipt(berezniki, 3_000, 3.40, 3.10);
        createReceipt(berezniki, 3_100, 3.50, 3.20);
        createReceipt(berezniki, 3_200, 3.45, 3.15);
        createReceipt(berezniki, 3_300, 3.35, 3.25);
        Farm yazykovo = repository.listFarms(true).stream()
                .filter(item -> item.name().equals("Языково"))
                .findFirst()
                .orElseThrow();
        createReceipt(yazykovo, 500, 3.60, 3.30);

        String report = reportService.buildDetailedDailyReport(date);

        assertTrue(report.contains("Большая Арать"));
        assertTrue(report.contains("Березники"));
        assertTrue(report.contains("12600.0 кг"));
        assertTrue(report.contains("Языково"));
        assertEquals(5, report.lines().filter(line -> line.contains("• Приёмка")).count());
        assertTrue(report.contains("3000.0 кг, жир 3.40%, белок 3.10%"));
        assertTrue(report.contains("3300.0 кг, жир 3.35%, белок 3.25%"));
    }

    @Test
    void updatingDeliveryDateMovesReceiptToAnotherDailyReport() {
        MilkReceipt receipt = createReceipt(berezniki, 3_000, 3.40, 3.10);
        LocalDate correctedDate = date.minusDays(1);

        repository.updateReceipt(
                receipt.id(),
                admin.id(),
                receipt.farmId(),
                receipt.sectionLabel(),
                correctedDate,
                receipt.weightKg(),
                receipt.fatPercent(),
                receipt.proteinPercent(),
                receipt.creditWeightKg(),
                receipt.photoToken(),
                receipt.photoPayloadJson(),
                receipt.photoWidth(),
                receipt.photoHeight(),
                receipt.photoStatus(),
                receipt.note(),
                receipt.adminOverrideUnlockedUntil()
        );

        assertEquals(correctedDate, repository.findReceiptById(receipt.id()).orElseThrow().deliveryDate());
        assertFalse(reportService.buildDetailedDailyReport(date).contains("Березники"));
        assertTrue(reportService.buildDetailedDailyReport(correctedDate).contains("Березники"));
    }

    private MilkReceipt createReceipt(Farm farm, double weight, double fat, double protein) {
        return repository.createReceipt(
                admin.id(),
                point.id(),
                farm.id(),
                "Без секции",
                date,
                weight,
                fat,
                protein,
                weight,
                null,
                null,
                null,
                null,
                "MISSING",
                null,
                null
        );
    }
}
