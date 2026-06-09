package ru.milk.maxbot.service;

import ru.milk.maxbot.domain.Farm;
import ru.milk.maxbot.domain.MilkReceipt;
import ru.milk.maxbot.domain.NamedSummary;
import ru.milk.maxbot.domain.StatsSummary;
import ru.milk.maxbot.repository.BotRepository;
import ru.milk.maxbot.util.Dates;
import ru.milk.maxbot.util.Numbers;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

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

        text.append("Секции и записи:\n");
        for (MilkReceipt receipt : receipts) {
            text.append("• ")
                    .append(receipt.pointName())
                    .append(", секция ")
                    .append(receipt.sectionLabel())
                    .append(" — ")
                    .append(Numbers.oneDecimal(receipt.weightKg())).append(" кг, жир ")
                    .append(Numbers.twoDecimals(receipt.fatPercent())).append("%, белок ")
                    .append(Numbers.twoDecimals(receipt.proteinPercent())).append("%, зачёт ")
                    .append(Numbers.oneDecimal(receipt.creditWeightKg())).append(" кг\n");
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
                        .append(Numbers.oneDecimal(point.summary().totalWeightKg())).append(" кг, зачёт ")
                        .append(Numbers.oneDecimal(point.summary().totalCreditWeightKg())).append(" кг\n"));
        return text.toString();
    }

    public String buildDailyDigest(LocalDate date) {
        StatsSummary total = repository.summarize(date, date, null, null);
        List<NamedSummary> points = repository.summarizeByPoint(date, date);
        return """
                📬 *Сводка за смену*
                Дата: *%s*

                %s

                По пунктам:
                %s
                """.formatted(
                Dates.formatDate(date),
                summaryBlock(total),
                points.stream()
                        .filter(point -> point.summary().recordsCount() > 0)
                        .map(point -> "• %s — %s кг, зачёт %s кг".formatted(
                                point.name(),
                                Numbers.oneDecimal(point.summary().totalWeightKg()),
                                Numbers.oneDecimal(point.summary().totalCreditWeightKg())))
                        .collect(Collectors.joining("\n"))
        );
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
                Зачётный вес: *%s кг*
                Средний жир: *%s%%*
                Средний белок: *%s%%*
                """.formatted(
                summary.recordsCount(),
                Numbers.oneDecimal(summary.totalWeightKg()),
                Numbers.oneDecimal(summary.totalCreditWeightKg()),
                Numbers.twoDecimals(summary.weightedFatPercent()),
                Numbers.twoDecimals(summary.weightedProteinPercent())
        );
    }
}
