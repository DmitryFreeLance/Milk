package ru.milk.maxbot.service;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.AxisCrosses;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.MarkerStyle;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
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
import ru.milk.maxbot.domain.ReceivingPoint;
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
import java.util.TreeMap;

public class ExcelReportService {
    private static final int INTAKE_COLUMNS = 5;

    public Path buildPointPeriodReport(ReceivingPoint point,
                                       LocalDate start,
                                       LocalDate end,
                                       List<MilkReceipt> receipts) {
        try {
            Path tempFile = Files.createTempFile("milk-point-report-" + point.id() + "-", ".xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook();
                 OutputStream outputStream = Files.newOutputStream(tempFile)) {
                WorkbookStyles styles = createStyles(workbook);
                List<MilkReceipt> orderedReceipts = receipts.stream()
                        .sorted(Comparator.comparing(MilkReceipt::deliveryDate)
                                .thenComparing(MilkReceipt::farmName)
                                .thenComparing(MilkReceipt::createdAt))
                        .toList();

                writeIntakeSheet(workbook.createSheet("Приёмки"), point, start, end, orderedReceipts, styles);
                writeSummarySheet(workbook.createSheet("Сводка"), point, start, end, orderedReceipts, styles);
                workbook.setActiveSheet(0);
                workbook.write(outputStream);
            }
            return tempFile;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build point Excel report", e);
        }
    }

    public Path buildFarmPeriodReport(Farm farm,
                                      LocalDate start,
                                      LocalDate end,
                                      List<MilkReceipt> receipts) {
        try {
            Path tempFile = Files.createTempFile("milk-farm-report-" + farm.id() + "-", ".xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook();
                 OutputStream outputStream = Files.newOutputStream(tempFile)) {
                WorkbookStyles styles = createStyles(workbook);
                List<MilkReceipt> orderedReceipts = receipts.stream()
                        .sorted(Comparator.comparing(MilkReceipt::deliveryDate)
                                .thenComparing(MilkReceipt::pointName)
                                .thenComparing(MilkReceipt::createdAt))
                        .toList();

                writeFarmDataSheet(workbook.createSheet("Данные"), farm, start, end, orderedReceipts, styles);
                writeFarmSummarySheet(workbook.createSheet("Общие данные"), farm, start, end, orderedReceipts, styles);
                writeFarmChartsSheet(workbook.createSheet("Графики"), farm, start, end, orderedReceipts, styles);
                workbook.setActiveSheet(0);
                workbook.write(outputStream);
            }
            return tempFile;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build farm Excel report", e);
        }
    }

    private void writeFarmDataSheet(XSSFSheet sheet,
                                    Farm farm,
                                    LocalDate start,
                                    LocalDate end,
                                    List<MilkReceipt> receipts,
                                    WorkbookStyles styles) {
        int columns = 8;
        configureSheet(sheet);
        writeTitle(sheet, "Приёмки колхоза: " + farm.name(), columns, styles);
        writePeriod(sheet, start, end, columns, styles);

        Row header = sheet.createRow(3);
        writeHeaders(header, List.of(
                "Дата", "Пункт", "Вес, кг", "Жир, %", "Белок, %", "Принял", "Статус фото", "Номер записи"
        ), styles.header());

        int rowIndex = 4;
        for (MilkReceipt receipt : receipts) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(receipt.deliveryDate()));
            row.getCell(0).setCellStyle(styles.date());
            row.createCell(1).setCellValue(receipt.pointName());
            row.getCell(1).setCellStyle(styles.text());
            row.createCell(2).setCellValue(receipt.weightKg());
            row.getCell(2).setCellStyle(styles.weight());
            row.createCell(3).setCellValue(receipt.fatPercent());
            row.getCell(3).setCellStyle(styles.quality());
            row.createCell(4).setCellValue(receipt.proteinPercent());
            row.getCell(4).setCellStyle(styles.quality());
            row.createCell(5).setCellValue(receipt.createdByName());
            row.getCell(5).setCellStyle(styles.text());
            row.createCell(6).setCellValue(receipt.photoStatus());
            row.getCell(6).setCellStyle(styles.text());
            row.createCell(7).setCellValue(receipt.publicId());
            row.getCell(7).setCellStyle(styles.text());
        }

        if (receipts.isEmpty()) {
            Row empty = sheet.createRow(rowIndex);
            empty.createCell(0).setCellValue("За выбранный период приёмок не найдено");
            empty.getCell(0).setCellStyle(styles.note());
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, columns - 1));
        } else {
            sheet.setAutoFilter(new CellRangeAddress(3, rowIndex - 1, 0, columns - 1));
        }

        sheet.createFreezePane(0, 4);
        int[] widths = {14, 24, 16, 14, 14, 24, 18, 20};
        for (int index = 0; index < widths.length; index++) {
            sheet.setColumnWidth(index, widths[index] * 256);
        }
    }

    private void writeFarmSummarySheet(XSSFSheet sheet,
                                       Farm farm,
                                       LocalDate start,
                                       LocalDate end,
                                       List<MilkReceipt> receipts,
                                       WorkbookStyles styles) {
        configureSheet(sheet);
        writeTitle(sheet, "Дневные итоги: " + farm.name(), styles);
        writePeriod(sheet, start, end, styles);

        Row header = sheet.createRow(3);
        writeHeaders(header, List.of("Дата", "Приёмок", "Вес, кг", "Средний жир, %", "Средний белок, %"), styles.header());

        int rowIndex = 4;
        for (Map.Entry<LocalDate, Aggregate> entry : aggregateByDay(receipts).entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(entry.getKey()));
            row.getCell(0).setCellStyle(styles.date());
            writeAggregateCells(row, 1, entry.getValue(), styles);
        }

        if (receipts.isEmpty()) {
            Row empty = sheet.createRow(rowIndex);
            empty.createCell(0).setCellValue("За выбранный период данных нет");
            empty.getCell(0).setCellStyle(styles.note());
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, INTAKE_COLUMNS - 1));
        }

        sheet.createFreezePane(0, 4);
        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 16 * 256);
        sheet.setColumnWidth(3, 20 * 256);
        sheet.setColumnWidth(4, 22 * 256);
    }

    private void writeFarmChartsSheet(XSSFSheet sheet,
                                      Farm farm,
                                      LocalDate start,
                                      LocalDate end,
                                      List<MilkReceipt> receipts,
                                      WorkbookStyles styles) {
        configureSheet(sheet);
        writeTitle(sheet, "Графики по колхозу: " + farm.name(), 16, styles);
        writePeriod(sheet, start, end, 16, styles);

        Map<LocalDate, Aggregate> daily = aggregateByDay(receipts);
        if (daily.isEmpty()) {
            Row empty = sheet.createRow(3);
            empty.createCell(0).setCellValue("За выбранный период нет данных для графиков");
            empty.getCell(0).setCellStyle(styles.note());
            sheet.addMergedRegion(new CellRangeAddress(3, 3, 0, 4));
            return;
        }

        Row header = sheet.createRow(3);
        writeHeaders(header, List.of("Дата", "Вес, кг", "Жир, %", "Белок, %"), styles.header());
        int rowIndex = 4;
        for (Map.Entry<LocalDate, Aggregate> entry : daily.entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(entry.getKey()));
            row.getCell(0).setCellStyle(styles.date());
            row.createCell(1).setCellValue(entry.getValue().weightKg());
            row.getCell(1).setCellStyle(styles.weight());
            row.createCell(2).setCellValue(entry.getValue().fatPercent());
            row.getCell(2).setCellStyle(styles.quality());
            row.createCell(3).setCellValue(entry.getValue().proteinPercent());
            row.getCell(3).setCellStyle(styles.quality());
        }

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        createChart(drawing, sheet, 5, 3, 14, 17, "Вес по дням, кг", 1, daily.size());
        createChart(drawing, sheet, 5, 18, 14, 32, "Средний жир по дням, %", 2, daily.size());
        createChart(drawing, sheet, 5, 33, 14, 47, "Средний белок по дням, %", 3, daily.size());

        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 16 * 256);
        sheet.setColumnWidth(2, 14 * 256);
        sheet.setColumnWidth(3, 14 * 256);
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

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

        int startRow = 4;
        int endRow = startRow + dataRowCount - 1;
        XDDFDataSource<String> dates = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
                sheet, new CellRangeAddress(startRow, endRow, 0, 0)
        );
        XDDFNumericalDataSource<Double> values = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
                sheet, new CellRangeAddress(startRow, endRow, valueColumn, valueColumn)
        );

        XDDFLineChartData data = (XDDFLineChartData) chart.createData(ChartTypes.LINE, bottomAxis, leftAxis);
        XDDFLineChartData.Series series = (XDDFLineChartData.Series) data.addSeries(dates, values);
        series.setTitle(title, null);
        series.setSmooth(false);
        series.setMarkerStyle(MarkerStyle.CIRCLE);
        chart.plot(data);
    }

    private void writeIntakeSheet(XSSFSheet sheet,
                                  ReceivingPoint point,
                                  LocalDate start,
                                  LocalDate end,
                                  List<MilkReceipt> receipts,
                                  WorkbookStyles styles) {
        configureSheet(sheet);
        writeTitle(sheet, "Приход на пункт: " + point.name(), styles);
        writePeriod(sheet, start, end, styles);

        Row header = sheet.createRow(3);
        writeHeaders(header, List.of("Дата", "Колхоз", "Вес, кг", "Жир, %", "Белок, %"), styles.header());

        int rowIndex = 4;
        for (MilkReceipt receipt : receipts) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(receipt.deliveryDate()));
            row.getCell(0).setCellStyle(styles.date());
            row.createCell(1).setCellValue(receipt.farmName());
            row.getCell(1).setCellStyle(styles.text());
            row.createCell(2).setCellValue(receipt.weightKg());
            row.getCell(2).setCellStyle(styles.weight());
            row.createCell(3).setCellValue(receipt.fatPercent());
            row.getCell(3).setCellStyle(styles.quality());
            row.createCell(4).setCellValue(receipt.proteinPercent());
            row.getCell(4).setCellStyle(styles.quality());
        }

        if (receipts.isEmpty()) {
            Row empty = sheet.createRow(rowIndex);
            empty.createCell(0).setCellValue("За выбранный период приёмок не найдено");
            empty.getCell(0).setCellStyle(styles.note());
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, INTAKE_COLUMNS - 1));
        } else {
            sheet.setAutoFilter(new CellRangeAddress(3, rowIndex - 1, 0, INTAKE_COLUMNS - 1));
        }

        sheet.createFreezePane(0, 4);
        sheet.setColumnWidth(0, 14 * 256);
        sheet.setColumnWidth(1, 28 * 256);
        sheet.setColumnWidth(2, 16 * 256);
        sheet.setColumnWidth(3, 14 * 256);
        sheet.setColumnWidth(4, 14 * 256);
    }

    private void writeSummarySheet(XSSFSheet sheet,
                                   ReceivingPoint point,
                                   LocalDate start,
                                   LocalDate end,
                                   List<MilkReceipt> receipts,
                                   WorkbookStyles styles) {
        configureSheet(sheet);
        writeTitle(sheet, "Сводка по пункту: " + point.name(), styles);
        writePeriod(sheet, start, end, styles);

        Aggregate total = aggregate(receipts);
        writeSectionTitle(sheet, 3, "Итого за период", styles);
        Row totalHeader = sheet.createRow(4);
        writeHeaders(totalHeader, List.of("Приёмок", "Вес, кг", "Средний жир, %", "Средний белок, %"), styles.header());
        writeAggregateRow(sheet.createRow(5), total, styles);

        int rowIndex = 8;
        writeSectionTitle(sheet, rowIndex++, "Суммарно по дням", styles);
        Row dailyHeader = sheet.createRow(rowIndex++);
        writeHeaders(dailyHeader, List.of("Дата", "Приёмок", "Вес, кг", "Средний жир, %", "Средний белок, %"), styles.header());
        for (Map.Entry<LocalDate, Aggregate> entry : aggregateByDay(receipts).entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(Dates.formatDate(entry.getKey()));
            row.getCell(0).setCellStyle(styles.date());
            writeAggregateCells(row, 1, entry.getValue(), styles);
        }

        rowIndex++;
        writeSectionTitle(sheet, rowIndex++, "Суммарно по колхозам", styles);
        Row farmHeader = sheet.createRow(rowIndex++);
        writeHeaders(farmHeader, List.of("Колхоз", "Приёмок", "Вес, кг", "Средний жир, %", "Средний белок, %"), styles.header());
        for (Map.Entry<String, Aggregate> entry : aggregateByFarm(receipts).entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(entry.getKey());
            row.getCell(0).setCellStyle(styles.text());
            writeAggregateCells(row, 1, entry.getValue(), styles);
        }

        sheet.createFreezePane(0, 3);
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 16 * 256);
        sheet.setColumnWidth(3, 20 * 256);
        sheet.setColumnWidth(4, 22 * 256);
    }

    private void writeTitle(XSSFSheet sheet, String title, WorkbookStyles styles) {
        writeTitle(sheet, title, INTAKE_COLUMNS, styles);
    }

    private void writeTitle(XSSFSheet sheet, String title, int columns, WorkbookStyles styles) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(28);
        row.createCell(0).setCellValue(title);
        row.getCell(0).setCellStyle(styles.title());
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, columns - 1));
    }

    private void writePeriod(XSSFSheet sheet, LocalDate start, LocalDate end, WorkbookStyles styles) {
        writePeriod(sheet, start, end, INTAKE_COLUMNS, styles);
    }

    private void writePeriod(XSSFSheet sheet, LocalDate start, LocalDate end, int columns, WorkbookStyles styles) {
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("Период: " + Dates.formatDate(start) + " - " + Dates.formatDate(end));
        row.getCell(0).setCellStyle(styles.meta());
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, columns - 1));
    }

    private void writeSectionTitle(XSSFSheet sheet, int rowIndex, String title, WorkbookStyles styles) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(22);
        row.createCell(0).setCellValue(title);
        row.getCell(0).setCellStyle(styles.section());
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, INTAKE_COLUMNS - 1));
    }

    private void writeHeaders(Row row, List<String> labels, CellStyle style) {
        row.setHeightInPoints(22);
        for (int index = 0; index < labels.size(); index++) {
            row.createCell(index).setCellValue(labels.get(index));
            row.getCell(index).setCellStyle(style);
        }
    }

    private void writeAggregateRow(Row row, Aggregate aggregate, WorkbookStyles styles) {
        writeAggregateCells(row, 0, aggregate, styles);
    }

    private void writeAggregateCells(Row row, int startColumn, Aggregate aggregate, WorkbookStyles styles) {
        row.createCell(startColumn).setCellValue(aggregate.recordsCount());
        row.getCell(startColumn).setCellStyle(styles.integer());
        row.createCell(startColumn + 1).setCellValue(aggregate.weightKg());
        row.getCell(startColumn + 1).setCellStyle(styles.weight());
        row.createCell(startColumn + 2).setCellValue(aggregate.fatPercent());
        row.getCell(startColumn + 2).setCellStyle(styles.quality());
        row.createCell(startColumn + 3).setCellValue(aggregate.proteinPercent());
        row.getCell(startColumn + 3).setCellStyle(styles.quality());
    }

    private Map<LocalDate, Aggregate> aggregateByDay(List<MilkReceipt> receipts) {
        Map<LocalDate, List<MilkReceipt>> grouped = new TreeMap<>();
        receipts.forEach(receipt -> grouped.computeIfAbsent(receipt.deliveryDate(), ignored -> new ArrayList<>()).add(receipt));
        Map<LocalDate, Aggregate> result = new LinkedHashMap<>();
        grouped.forEach((date, rows) -> result.put(date, aggregate(rows)));
        return result;
    }

    private Map<String, Aggregate> aggregateByFarm(List<MilkReceipt> receipts) {
        Map<String, List<MilkReceipt>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        receipts.forEach(receipt -> grouped.computeIfAbsent(receipt.farmName(), ignored -> new ArrayList<>()).add(receipt));
        Map<String, Aggregate> result = new LinkedHashMap<>();
        grouped.forEach((farm, rows) -> result.put(farm, aggregate(rows)));
        return result;
    }

    private Aggregate aggregate(List<MilkReceipt> receipts) {
        double weight = receipts.stream().mapToDouble(MilkReceipt::weightKg).sum();
        double fat = weight == 0 ? 0 : receipts.stream()
                .mapToDouble(receipt -> receipt.weightKg() * receipt.fatPercent())
                .sum() / weight;
        double protein = weight == 0 ? 0 : receipts.stream()
                .mapToDouble(receipt -> receipt.weightKg() * receipt.proteinPercent())
                .sum() / weight;
        return new Aggregate(receipts.size(), weight, fat, protein);
    }

    private void configureSheet(XSSFSheet sheet) {
        sheet.setDisplayGridlines(false);
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setMargin(XSSFSheet.LeftMargin, 0.3);
        sheet.setMargin(XSSFSheet.RightMargin, 0.3);
    }

    private WorkbookStyles createStyles(XSSFWorkbook workbook) {
        CellStyle title = workbook.createCellStyle();
        title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        title.setAlignment(HorizontalAlignment.LEFT);
        title.setVerticalAlignment(VerticalAlignment.CENTER);
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setColor(IndexedColors.WHITE.getIndex());
        title.setFont(titleFont);

        CellStyle meta = workbook.createCellStyle();
        Font metaFont = workbook.createFont();
        metaFont.setItalic(true);
        metaFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        meta.setFont(metaFont);

        CellStyle section = workbook.createCellStyle();
        section.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        section.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font sectionFont = workbook.createFont();
        sectionFont.setBold(true);
        section.setFont(sectionFont);
        section.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle header = workbook.createCellStyle();
        header.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setBorderBottom(BorderStyle.THIN);
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        header.setFont(headerFont);

        CellStyle text = workbook.createCellStyle();
        text.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle date = workbook.createCellStyle();
        date.cloneStyleFrom(text);
        date.setAlignment(HorizontalAlignment.CENTER);

        CellStyle weight = workbook.createCellStyle();
        weight.setAlignment(HorizontalAlignment.RIGHT);
        weight.setDataFormat(workbook.createDataFormat().getFormat("#,##0.0"));

        CellStyle quality = workbook.createCellStyle();
        quality.setAlignment(HorizontalAlignment.RIGHT);
        quality.setDataFormat(workbook.createDataFormat().getFormat("0.00"));

        CellStyle integer = workbook.createCellStyle();
        integer.setAlignment(HorizontalAlignment.RIGHT);
        integer.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

        CellStyle note = workbook.createCellStyle();
        note.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        note.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        note.setAlignment(HorizontalAlignment.CENTER);
        Font noteFont = workbook.createFont();
        noteFont.setItalic(true);
        note.setFont(noteFont);

        return new WorkbookStyles(title, meta, section, header, text, date, weight, quality, integer, note);
    }

    private record Aggregate(long recordsCount, double weightKg, double fatPercent, double proteinPercent) {
    }

    private record WorkbookStyles(
            CellStyle title,
            CellStyle meta,
            CellStyle section,
            CellStyle header,
            CellStyle text,
            CellStyle date,
            CellStyle weight,
            CellStyle quality,
            CellStyle integer,
            CellStyle note
    ) {
    }
}
