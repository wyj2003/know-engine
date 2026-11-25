package know.engine.knowledge;

import know.engine.document.constant.FileType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

final class TableDataReader {
    private static final int MAX_COLUMNS = 100;
    private static final int MAX_CELLS = 500_000;
    private static final int MAX_ROWS = 30_000;

    record DataRow(int number, List<String> cells) { }

    record Table(String sheet, List<String> sheets, List<String> columns, List<DataRow> rows) { }

    private TableDataReader() { }

    static Table read(byte[] bytes, FileType type, String requestedSheet) throws IOException {
        return type == FileType.CSV ? readCsv(bytes, requestedSheet) : readWorkbook(bytes, requestedSheet);
    }

    static String decode(byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
                || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF))) {
            charset = StandardCharsets.UTF_16;
        }
        String text = new String(bytes, charset);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static Table readCsv(byte[] bytes, String requestedSheet) throws IOException {
        if (requestedSheet != null && !requestedSheet.isBlank() && !"CSV".equals(requestedSheet)) {
            throw new IllegalArgumentException("找不到指定的工作表");
        }
        try (var parser = CSVFormat.DEFAULT.parse(new StringReader(decode(bytes)))) {
            List<String> columns = List.of();
            List<DataRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                List<String> cells = new ArrayList<>(record.size());
                record.forEach(cells::add);
                if (cells.stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (columns.isEmpty()) {
                    columns = columnNames(cells);
                } else {
                    if (cells.size() > columns.size()) {
                        throw new IllegalArgumentException("CSV 第 " + record.getRecordNumber() + " 条记录超出表头列数");
                    }
                    while (cells.size() < columns.size()) {
                        cells.add("");
                    }
                    addRow(rows, Math.toIntExact(record.getRecordNumber()), cells, columns.size());
                }
            }
            return new Table("CSV", List.of("CSV"), requireColumns(columns), List.copyOf(rows));
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException cause) {
                throw new IllegalArgumentException("CSV 格式有误，请检查分隔符和引号", cause);
            }
            throw e;
        }
    }

    private static Table readWorkbook(byte[] bytes, String requestedSheet) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<String> sheets = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                sheets.add(workbook.getSheetName(index));
            }
            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("工作簿中没有工作表");
            }
            String name = requestedSheet == null || requestedSheet.isBlank() ? sheets.getFirst() : requestedSheet;
            Sheet sheet = workbook.getSheet(name);
            if (sheet == null) {
                throw new IllegalArgumentException("找不到指定的工作表");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> columns = List.of();
            List<DataRow> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getLastCellNum() <= 0) {
                    continue;
                }
                if (columns.isEmpty()) {
                    int width = row.getLastCellNum();
                    checkWidth(width);
                    List<String> header = readCells(row, width, formatter);
                    if (header.stream().anyMatch(value -> !value.isBlank())) {
                        columns = columnNames(header);
                    }
                    continue;
                }
                if (row.getLastCellNum() > columns.size()) {
                    for (int index = columns.size(); index < row.getLastCellNum(); index++) {
                        if (!cellText(row.getCell(index), formatter).isBlank()) {
                            throw new IllegalArgumentException("工作表第 " + (row.getRowNum() + 1) + " 行超出表头列数");
                        }
                    }
                }
                List<String> cells = readCells(row, columns.size(), formatter);
                if (cells.stream().anyMatch(value -> !value.isBlank())) {
                    addRow(rows, row.getRowNum() + 1, cells, columns.size());
                }
            }
            return new Table(name, List.copyOf(sheets), requireColumns(columns), List.copyOf(rows));
        }
    }

    private static List<String> readCells(Row row, int size, DataFormatter formatter) {
        List<String> cells = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            cells.add(cellText(row.getCell(index), formatter));
        }
        return cells;
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString())
                    : NumberToTextConverter.toText(cell.getNumericCellValue());
            default -> "";
        };
    }

    private static List<String> columnNames(List<String> header) {
        checkWidth(header.size());
        var names = new HashSet<String>();
        List<String> result = new ArrayList<>(header.size());
        for (int index = 0; index < header.size(); index++) {
            String base = header.get(index).strip();
            if (base.isEmpty()) {
                base = "列 " + (index + 1);
            }
            String name = base;
            for (int suffix = 2; !names.add(name); suffix++) {
                name = base + " (" + suffix + ")";
            }
            result.add(name);
        }
        return List.copyOf(result);
    }

    private static List<String> requireColumns(List<String> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("表格缺少表头");
        }
        return columns;
    }

    private static void checkWidth(int width) {
        if (width > MAX_COLUMNS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "单个工作表最多支持 100 列");
        }
    }

    private static void addRow(List<DataRow> rows, int number, List<String> cells, int width) {
        if (rows.size() >= MAX_ROWS || (rows.size() + 1) * width > MAX_CELLS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "工作表超过 30,000 行或 500,000 个单元格");
        }
        rows.add(new DataRow(number, List.copyOf(cells)));
    }
}
