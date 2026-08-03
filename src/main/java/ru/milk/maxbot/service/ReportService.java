package ru.milk.maxbot.service;

import ru.milk.maxbot.domain.Farm;
import ru.milk.maxbot.domain.MilkReceipt;
import ru.milk.maxbot.domain.NamedSummary;
import ru.milk.maxbot.domain.ReceivingPoint;
import ru.milk.maxbot.domain.StatsSummary;
import ru.milk.maxbot.repository.BotRepository;
import ru.milk.maxbot.util.Dates;
import ru.milk.maxbot.util.Numbers;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ReportService {
    private final BotRepository repository;
    private final ExcelReportService excelReportService;

    public ReportService(BotRepository repository, ExcelReportService excelReportService) {
        this.repository = repository;
        this.excelReportService = excelReportService;
    }

    public String buildFarmDayReport(long farmId, LocalDate date) {
        Farm farm = repository.findFarm(farmId).orElseThrow();
        List<MilkReceipt> receipts = repository.listReceipts(date, date, null, farmId, false);
        StatsSummary summary = repository.summarize(date, date, null, farmId);

        StringBuilder text = new StringBuilder();
        text.append("🌾 *").append(farm.name()).append("* — карточка за ").append(Dates.formatDate(date)).append("\n\n");
        text.append(summaryBlock(summary));
        text.append("\n\n");

        if (receipts.isEmpty()) {
            text.append("За этот день поставок по выбранному колхозу не найдено.");
            return text.toString();
        }

        text.append("Записи:\n");
        for (MilkReceipt receipt : receipts) {
            text.append("• ")
                    .append(receipt.pointName())
                    .append(" — ")
                    .append(Numbers.oneDecimal(receipt.weightKg())).append(" кг, жир ")
                    .append(Numbers.twoDecimals(receipt.fatPercent())).append("%, белок ")
                    .append(Numbers.twoDecimals(receipt.proteinPercent())).append("%\n");
        }
        return text.toString();
    }

    public String buildPointPeriodReport(long pointId, LocalDate start, LocalDate end) {
        String pointName = repository.findPoint(pointId).orElseThrow().name();
        StatsSummary summary = repository.summarize(start, end, pointId, null);
        List<NamedSummary> farms = repository.summarizeByFarm(start, end, pointId).stream()
                .filter(row -> row.summary().recordsCount() > 0)
                .toList();

        StringBuilder text = new StringBuilder();
        text.append("🏭 *").append(pointName).append("*\n");
        text.append("Период: *").append(Dates.formatDate(start)).append(" - ").append(Dates.formatDate(end)).append("*\n\n");
        text.append(summaryBlock(summary)).append("\n\n");
        if (farms.isEmpty()) {
            text.append("За выбранный период поставок не найдено.");
        } else {
            text.append("Разрез по колхозам:\n");
            farms.forEach(row -> text.append("• ").append(row.name()).append(" — ")
                    .append(Numbers.oneDecimal(row.summary().totalWeightKg())).append(" кг, жир ")
                    .append(Numbers.twoDecimals(row.summary().weightedFatPercent())).append("%, белок ")
                    .append(Numbers.twoDecimals(row.summary().weightedProteinPercent())).append("%\n"));
        }
        return text.toString();
    }

    public String buildGlobalPeriodReport(LocalDate start, LocalDate end) {
        StatsSummary total = repository.summarize(start, end, null, null);
        List<NamedSummary> points = repository.summarizeByPoint(start, end);

        StringBuilder text = new StringBuilder();
        text.append("🌍 *Общая сводка по всем пунктам*\n");
        text.append("Период: *").append(Dates.formatDate(start)).append(" - ").append(Dates.formatDate(end)).append("*\n\n");
        text.append(summaryBlock(total)).append("\n\n");
        text.append("По пунктам:\n");
        points.stream()
                .filter(point -> point.summary().recordsCount() > 0)
                .forEach(point -> text.append("• ").append(point.name()).append(" — ")
                        .append(Numbers.oneDecimal(point.summary().totalWeightKg())).append(" кг\n"));
        return text.toString();
    }

    public String buildDailyDigest(LocalDate date) {
        StatsSummary total = repository.summarize(date, date, null, null);
        String pointsBlock = buildDailyDigestPointsBlock(date);
        return """
                📬 *Сводка за смену*
                Дата: *%s*

                %s

                По пунктам:
                %s
                """.formatted(
                Dates.formatDate(date),
                summaryBlock(total),
                pointsBlock
        );
    }

    public String buildDetailedDailyReport(LocalDate date) {
        List<NamedSummary> points = repository.summarizeByPoint(date, date).stream()
                .filter(point -> point.summary().recordsCount() > 0)
                .toList();
        if (points.isEmpty()) {
            return """
                    📊 *Подробный отчёт по приходу*
                    Дата: *%s*

                    Поставок за день не найдено.
                    """.formatted(Dates.formatDate(date));
        }

        StringBuilder text = new StringBuilder();
        text.append("📊 *Подробный отчёт по приходу*\n");
        text.append("Дата: *").append(Dates.formatDate(date)).append("*\n\n");

        for (NamedSummary point : points) {
            text.append("🏭 *").append(point.name()).append("* — ")
                    .append(formatSummaryDetails(point.summary())).append("\n\n");

            List<NamedSummary> farms = repository.summarizeByFarm(date, date, point.id()).stream()
                    .filter(farm -> farm.summary().recordsCount() > 0)
                    .toList();
            for (NamedSummary farm : farms) {
                text.append("🌾 *").append(farm.name()).append("* — ")
                        .append(formatSummaryDetails(farm.summary())).append("\n");

                List<MilkReceipt> receipts = repository.listReceipts(date, date, point.id(), farm.id(), false);
                for (int index = 0; index < receipts.size(); index++) {
                    MilkReceipt receipt = receipts.get(index);
                    text.append("  • ").append(receiptLabel(receipt, index + 1)).append(" — ")
                            .append(formatReceiptDetails(receipt)).append("\n");
                }
                text.append("\n");
            }
        }
        return text.toString().stripTrailing();
    }

    public Path buildExcelPointReport(long pointId, LocalDate start, LocalDate end) {
        ReceivingPoint point = repository.findPoint(pointId).orElseThrow();
        List<MilkReceipt> receipts = repository.listReceipts(start, end, pointId, null, false);
        return excelReportService.buildPointPeriodReport(point, start, end, receipts);
    }

    public Path buildExcelFarmReport(long farmId, LocalDate start, LocalDate end) {
        Farm farm = repository.findFarm(farmId).orElseThrow();
        List<MilkReceipt> receipts = repository.listReceipts(start, end, null, farmId, false);
        return excelReportService.buildFarmPeriodReport(farm, start, end, receipts);
    }

    private String summaryBlock(StatsSummary summary) {
        return """
                Записей: *%d*
                Фактический вес: *%s кг*
                Средний жир: *%s%%*
                Средний белок: *%s%%*
                """.formatted(
                summary.recordsCount(),
                Numbers.oneDecimal(summary.totalWeightKg()),
                Numbers.twoDecimals(summary.weightedFatPercent()),
                Numbers.twoDecimals(summary.weightedProteinPercent())
        );
    }

    private String buildDailyDigestPointsBlock(LocalDate date) {
        StringBuilder text = new StringBuilder();
        List<NamedSummary> points = repository.summarizeByPoint(date, date).stream()
                .filter(point -> point.summary().recordsCount() > 0)
                .toList();

        if (points.isEmpty()) {
            return "Поставок за смену не найдено.";
        }

        for (NamedSummary point : points) {
            List<MilkReceipt> pointReceipts = repository.listReceipts(date, date, point.id(), null, false);
            text.append("\n• *").append(point.name()).append("* — ")
                    .append(Numbers.oneDecimal(point.summary().totalWeightKg())).append(" кг\n\n");
            text.append("  Сотрудник на приёмке: ")
                    .append(formatEmployeeNames(pointReceipts))
                    .append("\n\n");

            repository.summarizeByFarm(date, date, point.id()).stream()
                    .filter(farm -> farm.summary().recordsCount() > 0)
                    .forEach(farm -> text.append("  • ").append(farm.name()).append(" — ")
                            .append(formatSummaryDetails(farm.summary())).append("\n"));
        }

        return text.toString().stripTrailing();
    }

    private String formatEmployeeNames(List<MilkReceipt> receipts) {
        Set<String> names = new LinkedHashSet<>();
        for (MilkReceipt receipt : receipts) {
            String name = receipt.createdByName();
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names.isEmpty() ? "не указан" : String.join(", ", names);
    }

    private String formatSummaryDetails(StatsSummary summary) {
        return "%s кг, жир %s%%, белок %s%%".formatted(
                Numbers.oneDecimal(summary.totalWeightKg()),
                Numbers.twoDecimals(summary.weightedFatPercent()),
                Numbers.twoDecimals(summary.weightedProteinPercent())
        );
    }

    private String formatReceiptDetails(MilkReceipt receipt) {
        return "%s кг, жир %s%%, белок %s%%".formatted(
                Numbers.oneDecimal(receipt.weightKg()),
                Numbers.twoDecimals(receipt.fatPercent()),
                Numbers.twoDecimals(receipt.proteinPercent())
        );
    }

    private String receiptLabel(MilkReceipt receipt, int index) {
        String section = receipt.sectionLabel();
        if (section != null && !section.isBlank() && !"Без секции".equalsIgnoreCase(section)) {
            return section;
        }
        String publicId = receipt.publicId();
        String shortId = publicId == null || publicId.isBlank()
                ? String.valueOf(receipt.id())
                : publicId.substring(Math.max(0, publicId.length() - 3));
        return "Приёмка " + index + " (№" + shortId + ")";
    }
}
