package ru.milk.maxbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.milk.maxbot.config.AppConfig;
import ru.milk.maxbot.db.Database;
import ru.milk.maxbot.domain.BotUser;
import ru.milk.maxbot.domain.Farm;
import ru.milk.maxbot.domain.MilkReceipt;
import ru.milk.maxbot.domain.ReceivingPoint;
import ru.milk.maxbot.repository.BotRepository;

import java.io.InputStream;
import java.nio.file.Files;
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

    @Test
    void dailyDigestHighlightsPointsAndShowsReceivingEmployeeName() {
        createReceipt(berezniki, 3_000, 3.40, 3.10);

        String report = reportService.buildDailyDigest(date);

        assertTrue(report.contains("По пунктам:\n\n• *Большая Арать* — "));
        assertTrue(report.contains("3000.0 кг\n\n  Сотрудник на приёмке: Тест Админ\n\n  • Березники"));
    }

    @Test
    void excelPointReportContainsReceiptsAndSummariesByDayAndFarm() throws Exception {
        createReceipt(berezniki, date.minusDays(1), 1_000, 3.20, 3.00);
        createReceipt(berezniki, date, 3_567, 3.50, 3.10);
        createReceipt(berezniki, date, 3_789, 3.30, 3.10);
        Farm yazykovo = repository.listFarms(true).stream()
                .filter(item -> item.name().equals("Языково"))
                .findFirst()
                .orElseThrow();
        createReceipt(yazykovo, date, 500, 3.60, 3.20);

        ReceivingPoint otherPoint = repository.listPoints().stream()
                .filter(item -> item.id() != point.id())
                .findFirst()
                .orElseThrow();
        repository.createReceipt(
                admin.id(), otherPoint.id(), berezniki.id(), "Без секции", date,
                9_999, 4.00, 4.00, 9_999,
                null, null, null, null, "MISSING", null, null
        );

        Path report = reportService.buildExcelPointReport(point.id(), date.minusDays(1), date);

        try (InputStream input = Files.newInputStream(report);
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            assertEquals(2, workbook.getNumberOfSheets());
            XSSFSheet intake = workbook.getSheet("Приёмки");
            XSSFSheet summary = workbook.getSheet("Сводка");

            assertEquals("Дата", intake.getRow(3).getCell(0).getStringCellValue());
            assertEquals("Колхоз", intake.getRow(3).getCell(1).getStringCellValue());
            assertEquals(4, intake.getLastRowNum() - 3);
            assertEquals(1_000, intake.getRow(4).getCell(2).getNumericCellValue(), 0.001);
            assertEquals("27.07.2026", intake.getRow(4).getCell(0).getStringCellValue());

            RowValues total = aggregateRow(summary, "Итого за период", 2);
            assertEquals(4, total.records());
            assertEquals(8_856, total.weight(), 0.001);

            RowValues bereznikiTotal = findAggregateRow(summary, "Березники");
            assertEquals(3, bereznikiTotal.records());
            assertEquals(8_356, bereznikiTotal.weight(), 0.001);
            assertEquals((1_000 * 3.20 + 3_567 * 3.50 + 3_789 * 3.30) / 8_356,
                    bereznikiTotal.fat(), 0.0001);

            RowValues yazykovoTotal = findAggregateRow(summary, "Языково");
            assertEquals(1, yazykovoTotal.records());
            assertEquals(500, yazykovoTotal.weight(), 0.001);
        }
    }

    @Test
    void excelFarmReportContainsFarmReceiptsFromAllPointsAndCharts() throws Exception {
        createReceipt(berezniki, date, 1_000, 3.20, 3.00);
        ReceivingPoint otherPoint = repository.listPoints().stream()
                .filter(item -> item.id() != point.id())
                .findFirst()
                .orElseThrow();
        repository.createReceipt(
                admin.id(), otherPoint.id(), berezniki.id(), "Без секции", date,
                2_000, 3.40, 3.10, 2_000,
                null, null, null, null, "MISSING", null, null
        );
        Farm yazykovo = repository.listFarms(true).stream()
                .filter(item -> item.name().equals("Языково"))
                .findFirst()
                .orElseThrow();
        createReceipt(yazykovo, date, 9_999, 4.00, 4.00);

        Path report = reportService.buildExcelFarmReport(berezniki.id(), date, date);

        try (InputStream input = Files.newInputStream(report);
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            assertEquals(3, workbook.getNumberOfSheets());
            XSSFSheet data = workbook.getSheet("Данные");
            XSSFSheet summary = workbook.getSheet("Общие данные");
            XSSFSheet charts = workbook.getSheet("Графики");

            assertEquals(2, data.getLastRowNum() - 3);
            assertEquals("Пункт", data.getRow(3).getCell(1).getStringCellValue());
            assertEquals("Секция", data.getRow(3).getCell(2).getStringCellValue());
            assertTrue(data.getRow(4).getCell(1).getStringCellValue().equals(point.name())
                    || data.getRow(5).getCell(1).getStringCellValue().equals(point.name()));
            assertTrue(data.getRow(4).getCell(1).getStringCellValue().equals(otherPoint.name())
                    || data.getRow(5).getCell(1).getStringCellValue().equals(otherPoint.name()));

            assertEquals(2, summary.getRow(4).getCell(1).getNumericCellValue(), 0.001);
            assertEquals(3_000, summary.getRow(4).getCell(2).getNumericCellValue(), 0.001);
            RowValues periodTotal = aggregateRow(summary, "Итого за период", 2, 1);
            assertEquals(2, periodTotal.records());
            assertEquals(3_000, periodTotal.weight(), 0.001);
            assertEquals((1_000 * 3.20 + 2_000 * 3.40) / 3_000, periodTotal.fat(), 0.0001);
            assertEquals(3, charts.getDrawingPatriarch().getCharts().size());
        }
    }

    private MilkReceipt createReceipt(Farm farm, double weight, double fat, double protein) {
        return createReceipt(farm, date, weight, fat, protein);
    }

    private MilkReceipt createReceipt(Farm farm, LocalDate deliveryDate, double weight, double fat, double protein) {
        return repository.createReceipt(
                admin.id(),
                point.id(),
                farm.id(),
                "Без секции",
                deliveryDate,
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

    private RowValues aggregateRow(XSSFSheet sheet, String sectionTitle, int valueRowOffset) {
        return aggregateRow(sheet, sectionTitle, valueRowOffset, 0);
    }

    private RowValues aggregateRow(XSSFSheet sheet, String sectionTitle, int valueRowOffset, int startColumn) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            if (sheet.getRow(rowIndex) != null
                    && sheet.getRow(rowIndex).getCell(0) != null
                    && sheet.getRow(rowIndex).getCell(0).getCellType() == CellType.STRING
                    && sectionTitle.equals(sheet.getRow(rowIndex).getCell(0).getStringCellValue())) {
                return valuesFromRow(sheet, rowIndex + valueRowOffset, startColumn);
            }
        }
        throw new AssertionError("Section not found: " + sectionTitle);
    }

    private RowValues findAggregateRow(XSSFSheet sheet, String label) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            if (sheet.getRow(rowIndex) != null
                    && sheet.getRow(rowIndex).getCell(0) != null
                    && sheet.getRow(rowIndex).getCell(0).getCellType() == CellType.STRING
                    && label.equals(sheet.getRow(rowIndex).getCell(0).getStringCellValue())) {
                return valuesFromRow(sheet, rowIndex, 1);
            }
        }
        throw new AssertionError("Aggregate row not found: " + label);
    }

    private RowValues valuesFromRow(XSSFSheet sheet, int rowIndex, int startColumn) {
        return new RowValues(
                (long) sheet.getRow(rowIndex).getCell(startColumn).getNumericCellValue(),
                sheet.getRow(rowIndex).getCell(startColumn + 1).getNumericCellValue(),
                sheet.getRow(rowIndex).getCell(startColumn + 2).getNumericCellValue(),
                sheet.getRow(rowIndex).getCell(startColumn + 3).getNumericCellValue()
        );
    }

    private record RowValues(long records, double weight, double fat, double protein) {
    }
}
