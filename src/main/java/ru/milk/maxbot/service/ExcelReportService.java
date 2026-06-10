package ru.milk.maxbot.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xddf.usermodel.chart.AxisCrosses;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.MarkerStyle;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.milk.maxbot.domain.Farm;
import ru.milk.maxbot.domain.MilkReceipt;
import ru.milk.maxbot.util.Dates;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExcelReportService {
    public Path buildFarmPeriodReport(Farm farm, LocalDate start, LocalDate end, List<MilkReceipt> receipts) {
        try {
            Path tempFile = Files.createTempFile("milk-report-" + farm.id() + "-", ".xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream outputStream = Files.newOutputStream(tempFile)) {
                XSSFSheet rawSheet = workbook.createSheet("Данные");
                XSSFSheet summarySheet = workbook.createSheet("Общие данные");
                XSSFSheet chartSheet = workbook.createSheet("Графики");

                List<MilkReceipt> orderedReceipts = receipts.stream()
                        .sorted(Comparator.comparing(MilkReceipt::deliveryDate)
                                .thenComparing(MilkReceipt::pointName)
                                .thenComparing(MilkReceipt::sectionLabel)
                                .thenComparing(MilkReceipt::createdAt))
                        .toList();
                List<DailyAggregate> rows = aggregateByDay(orderedReceipts);

                writeRawDataSheet(rawSheet, orderedReceipts);
                writeSummarySheet(summarySheet, rows);
                writeCharts(chartSheet, rows, farm.name(), start, end);

                workbook.write(outputStream);
            }
            return tempFile;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build Excel report", e);
        }
    }

    private void writeRawDataSheet(XSSFSheet sheet, List<MilkReceipt> receipts) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Дата");
        header.createCell(1).setCellValue("Пункт");
        header.createCell(2).setCellValue("Секция");
        header.createCell(3).setCellValue("Вес, кг");
        header.createCell(4).setCellValue("Жир, %");
        header.createCell(5).setCellValue("Белок, %");
        header.createCell(6).setCellValue("Принял");
        header.createCell(7).setCellValue("Статус фото");
        header.createCell(8).setCellValue("Номер записи");

        int rowIndex = 1;
        for (MilkReceipt receipt : receipts) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(receipt.deliveryDate()));
            row.createCell(1).setCellValue(receipt.pointName());
            row.createCell(2).setCellValue(receipt.sectionLabel());
            row.createCell(3).setCellValue(receipt.weightKg());
            row.createCell(4).setCellValue(receipt.fatPercent());
            row.createCell(5).setCellValue(receipt.proteinPercent());
            row.createCell(6).setCellValue(receipt.createdByName());
            row.createCell(7).setCellValue(receipt.photoStatus());
            row.createCell(8).setCellValue(receipt.publicId());
        }

        autoSize(sheet, 9);
    }

    private List<DailyAggregate> aggregateByDay(List<MilkReceipt> receipts) {
        Map<LocalDate, List<MilkReceipt>> grouped = new LinkedHashMap<>();
        for (MilkReceipt receipt : receipts) {
            grouped.computeIfAbsent(receipt.deliveryDate(), ignored -> new ArrayList<>()).add(receipt);
        }

        List<DailyAggregate> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<MilkReceipt>> entry : grouped.entrySet()) {
            double totalWeight = entry.getValue().stream().mapToDouble(MilkReceipt::weightKg).sum();
            double avgFat = totalWeight == 0 ? 0 : entry.getValue().stream().mapToDouble(it -> it.weightKg() * it.fatPercent()).sum() / totalWeight;
            double avgProtein = totalWeight == 0 ? 0 : entry.getValue().stream().mapToDouble(it -> it.weightKg() * it.proteinPercent()).sum() / totalWeight;
            result.add(new DailyAggregate(entry.getKey(), totalWeight, avgFat, avgProtein));
        }
        return result;
    }

    private void writeSummarySheet(XSSFSheet sheet, List<DailyAggregate> rows) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Дата");
        header.createCell(1).setCellValue("Вес, кг");
        header.createCell(2).setCellValue("Жир, %");
        header.createCell(3).setCellValue("Белок, %");

        int rowIndex = 1;
        for (DailyAggregate aggregate : rows) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(aggregate.date()));
            row.createCell(1).setCellValue(aggregate.weightKg());
            row.createCell(2).setCellValue(aggregate.fatPercent());
            row.createCell(3).setCellValue(aggregate.proteinPercent());
        }

        autoSize(sheet, 4);
    }

    private void writeCharts(XSSFSheet chartSheet, List<DailyAggregate> rows, String farmName, LocalDate start, LocalDate end) {
        Row meta = chartSheet.createRow(0);
        meta.createCell(0).setCellValue("Колхоз: " + farmName);
        meta.createCell(1).setCellValue("Период: " + Dates.formatDate(start) + " - " + Dates.formatDate(end));

        if (rows.isEmpty()) {
            Row row = chartSheet.createRow(2);
            row.createCell(0).setCellValue("За выбранный период нет данных для графиков");
            return;
        }

        Row header = chartSheet.createRow(2);
        header.createCell(0).setCellValue("Дата");
        header.createCell(1).setCellValue("Вес, кг");
        header.createCell(2).setCellValue("Жир, %");
        header.createCell(3).setCellValue("Белок, %");

        int rowIndex = 3;
        for (DailyAggregate aggregate : rows) {
            Row row = chartSheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(aggregate.date()));
            row.createCell(1).setCellValue(aggregate.weightKg());
            row.createCell(2).setCellValue(aggregate.fatPercent());
            row.createCell(3).setCellValue(aggregate.proteinPercent());
        }

        XSSFDrawing drawing = chartSheet.createDrawingPatriarch();
        createChart(drawing, chartSheet, 5, 1, 13, 16, "Вес", 1, rows.size());
        createChart(drawing, chartSheet, 13, 1, 21, 16, "Жир", 2, rows.size());
        createChart(drawing, chartSheet, 21, 1, 29, 16, "Белок", 3, rows.size());

        autoSize(chartSheet, 4);
    }

    private void createChart(XSSFDrawing drawing,
                             XSSFSheet sheet,
                             int col1,
                             int row1,
                             int col2,
                             int row2,
                             String title,
                             int valueColumn,
                             int dataRowCount) {
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, col1, row1, col2, row2);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

        int startRow = 3;
        int endRow = startRow + dataRowCount - 1;
        XDDFDataSource<String> dates = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                sheet,
                new org.apache.poi.ss.util.CellRangeAddress(startRow, endRow, 0, 0)
        );
        XDDFNumericalDataSource<Double> values = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                sheet,
                new org.apache.poi.ss.util.CellRangeAddress(startRow, endRow, valueColumn, valueColumn)
        );

        XDDFLineChartData data = (XDDFLineChartData) chart.createData(ChartTypes.LINE, bottomAxis, leftAxis);
        XDDFLineChartData.Series series = (XDDFLineChartData.Series) data.addSeries(dates, values);
        series.setTitle(title, null);
        series.setSmooth(false);
        series.setMarkerStyle(MarkerStyle.CIRCLE);
        chart.plot(data);
    }

    private void autoSize(XSSFSheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private record DailyAggregate(LocalDate date, double weightKg, double fatPercent, double proteinPercent) {
    }
}
