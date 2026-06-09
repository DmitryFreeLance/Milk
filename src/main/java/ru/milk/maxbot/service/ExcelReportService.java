package ru.milk.maxbot.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
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
                XSSFSheet dataSheet = workbook.createSheet("Данные");
                XSSFSheet chartSheet = workbook.createSheet("Графики");

                List<DailyAggregate> rows = aggregateByDay(receipts);
                writeDataSheet(dataSheet, rows);
                writeCharts(chartSheet, rows, farm.name(), start, end);

                workbook.write(outputStream);
            }
            return tempFile;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build Excel report", e);
        }
    }

    private List<DailyAggregate> aggregateByDay(List<MilkReceipt> receipts) {
        Map<LocalDate, List<MilkReceipt>> grouped = new LinkedHashMap<>();
        receipts.stream()
                .sorted(Comparator.comparing(MilkReceipt::deliveryDate))
                .forEach(receipt -> grouped.computeIfAbsent(receipt.deliveryDate(), ignored -> new ArrayList<>()).add(receipt));

        List<DailyAggregate> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<MilkReceipt>> entry : grouped.entrySet()) {
            double totalWeight = entry.getValue().stream().mapToDouble(MilkReceipt::weightKg).sum();
            double avgFat = totalWeight == 0 ? 0 : entry.getValue().stream().mapToDouble(it -> it.weightKg() * it.fatPercent()).sum() / totalWeight;
            double avgProtein = totalWeight == 0 ? 0 : entry.getValue().stream().mapToDouble(it -> it.weightKg() * it.proteinPercent()).sum() / totalWeight;
            result.add(new DailyAggregate(entry.getKey(), totalWeight, avgFat, avgProtein));
        }
        return result;
    }

    private void writeDataSheet(XSSFSheet sheet, List<DailyAggregate> rows) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Дата");
        header.createCell(1).setCellValue("Средний вес, кг");
        header.createCell(2).setCellValue("Средний жир, %");
        header.createCell(3).setCellValue("Средний белок, %");

        int rowIndex = 1;
        for (DailyAggregate aggregate : rows) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(aggregate.date()));
            row.createCell(1).setCellValue(aggregate.weightKg());
            row.createCell(2).setCellValue(aggregate.fatPercent());
            row.createCell(3).setCellValue(aggregate.proteinPercent());
        }

        for (int i = 0; i < 4; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void writeCharts(XSSFSheet chartSheet, List<DailyAggregate> rows, String farmName, LocalDate start, LocalDate end) {
        if (rows.isEmpty()) {
            Row row = chartSheet.createRow(0);
            row.createCell(0).setCellValue("За выбранный период нет данных для графика");
            return;
        }

        for (int i = 0; i < rows.size(); i++) {
            Row row = chartSheet.getRow(i);
            if (row == null) {
                row = chartSheet.createRow(i);
            }
            row.createCell(0).setCellValue(Dates.formatDate(rows.get(i).date()));
            row.createCell(1).setCellValue(rows.get(i).weightKg());
            row.createCell(2).setCellValue(rows.get(i).fatPercent());
            row.createCell(3).setCellValue(rows.get(i).proteinPercent());
        }

        XSSFDrawing drawing = chartSheet.createDrawingPatriarch();
        createChart(drawing, chartSheet, 0, 0, 8, 15, farmName + ": средний вес", 1);
        createChart(drawing, chartSheet, 8, 0, 16, 15, farmName + ": средний жир", 2);
        createChart(drawing, chartSheet, 16, 0, 24, 15, farmName + ": средний белок", 3);

        Row titleRow = chartSheet.getRow(rows.size() + 2);
        if (titleRow == null) {
            titleRow = chartSheet.createRow(rows.size() + 2);
        }
        titleRow.createCell(0).setCellValue("Период: " + Dates.formatDate(start) + " - " + Dates.formatDate(end));
        titleRow.createCell(1).setCellValue("Колхоз: " + farmName);
        titleRow.createCell(2).setCellValue("Точек: " + rows.size());
        titleRow.createCell(3).setCellValue("Подсказка: графики строятся по дням.");
    }

    private void createChart(XSSFDrawing drawing, XSSFSheet sheet, int col1, int row1, int col2, int row2, String title, int valueColumn) {
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, col1, row1, col2, row2);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setCrosses(org.apache.poi.xddf.usermodel.chart.AxisCrosses.AUTO_ZERO);

        int lastRow = Math.max(1, sheet.getLastRowNum());
        XDDFDataSource<String> dates = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                sheet,
                new org.apache.poi.ss.util.CellRangeAddress(0, lastRow, 0, 0)
        );
        XDDFNumericalDataSource<Double> values = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                sheet,
                new org.apache.poi.ss.util.CellRangeAddress(0, lastRow, valueColumn, valueColumn)
        );

        XDDFLineChartData data = (XDDFLineChartData) chart.createData(ChartTypes.LINE, bottomAxis, leftAxis);
        XDDFLineChartData.Series series = (XDDFLineChartData.Series) data.addSeries(dates, values);
        series.setTitle(title, null);
        series.setSmooth(false);
        series.setMarkerStyle(org.apache.poi.xddf.usermodel.chart.MarkerStyle.CIRCLE);
        chart.plot(data);
    }

    private record DailyAggregate(LocalDate date, double weightKg, double fatPercent, double proteinPercent) {
    }
}
