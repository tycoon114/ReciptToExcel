package kr.local.receipts;

import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;

/** XLS/XLSX의 표시값과 열 제목을 읽으며 수식은 파일에 저장된 계산 결과만 사용합니다. */
final class ImportExcelParser {
    static ImportPdfParser.Parsed parse(ImportFiles.Kind kind, Path original, String direction) throws Exception {
        var transactions = new ArrayList<ImportAnalysis.Transaction>();
        var issues = new ArrayList<ImportAnalysis.Issue>();
        try (var input = Files.newInputStream(original); var book = WorkbookFactory.create(input)) {
            var formatter = new DataFormatter(Locale.KOREA);
            formatter.setUseCachedValuesForFormulaCells(true);
            for (int index = 0; index < book.getNumberOfSheets(); index++) {
                var sheet = book.getSheetAt(index);
                try {
                    readSheet(kind, sheet, index + 1, formatter, direction, transactions, issues);
                } catch (Exception e) {
                    issues.add(new ImportAnalysis.Issue(index + 1, "시트를 읽지 못했습니다. 셀 형식과 열 제목을 확인해 주세요.", sheet.getSheetName()));
                }
            }
        }
        return new ImportPdfParser.Parsed(List.copyOf(transactions), List.copyOf(issues));
    }

    private static void readSheet(ImportFiles.Kind kind, Sheet sheet, int sheetNumber, DataFormatter formatter,
            String direction, List<ImportAnalysis.Transaction> transactions, List<ImportAnalysis.Issue> issues) {
        Map<String, Integer> columns = Map.of();
        String currency = "";
        boolean recognized = false;
        int count = transactions.size();
        for (var source : sheet) {
            if (source.getLastCellNum() > 1000) {
                issues.add(new ImportAnalysis.Issue(sheetNumber, (source.getRowNum() + 1) + "행의 열 수가 1,000개를 초과했습니다.", sheet.getSheetName()));
                continue;
            }
            var candidate = new LinkedHashMap<String, Integer>();
            var values = new ArrayList<String>();
            boolean duplicateHeader = false;
            for (int i = 0; i < source.getLastCellNum(); i++) {
                String value = value(source.getCell(i), formatter);
                values.add(value);
                String key = ImportPdfParser.headerKey(value, kind);
                if (!key.isEmpty() && candidate.putIfAbsent(key, i) != null) duplicateHeader = true;
            }
            if (values.stream().allMatch(String::isBlank)) continue;
            if (source.getRowNum() < 20 && !ImportPdfParser.currency(String.join(" ", values)).isEmpty()) currency = "KRW";
            if (header(kind, candidate)) {
                if (duplicateHeader) {
                    issues.add(new ImportAnalysis.Issue(sheetNumber, (source.getRowNum() + 1) + "행에 의미가 겹치는 열 제목이 있습니다. 열 구분 확인이 필요합니다.", sheet.getSheetName()));
                    columns = Map.of();
                } else { columns = candidate; recognized = true; }
                continue;
            }
            if (columns.isEmpty()) continue;
            String first = values.stream().filter(value -> !value.isBlank()).findFirst().orElse("").replaceAll("\\s", "");
            if (Set.of("합계", "총계", "소계", "입금합계", "출금합계").contains(first)) continue;
            var cells = new HashMap<String, String>();
            boolean formula = false;
            for (var entry : columns.entrySet()) {
                var cell = source.getCell(entry.getValue());
                String value = value(cell, formatter);
                if ("date".equals(entry.getKey()) && cell != null && numeric(cell) && DateUtil.isCellDateFormatted(cell))
                    value = cell.getLocalDateTimeCellValue().toLocalDate().toString();
                cells.put(entry.getKey(), value);
                if (cell != null && cell.getCellType() == CellType.FORMULA) formula = true;
            }
            if (List.of("amount", "supply", "vat", "deposit", "withdrawal").stream()
                    .allMatch(key -> cells.getOrDefault(key, "").isBlank())) continue;
            ImportAnalysis.Transaction row;
            if (kind == ImportFiles.Kind.TAX_INVOICE) {
                var parsed = ImportPdfParser.invoiceFields(invoiceFields(cells), sheetNumber, direction);
                row = parsed.rows().get(0);
                row = new ImportAnalysis.Transaction(sheetNumber, source.getRowNum() + 1, row.date(), row.time(), row.party(),
                        row.businessNumber(), row.type(), row.reference(), row.originalReference(), row.amount(), row.supply(), row.vat(),
                        null, null, null, row.description(), row.currency(), row.warning());
            } else {
                row = ImportPdfParser.tableRow(kind, cells, sheetNumber, source.getRowNum() + 1, currency);
            }
            row = row.inSheet(sheet.getSheetName());
            if (formula) {
                String warning = (row.warning().isEmpty() ? "" : row.warning() + " / ") + "수식 셀의 저장된 결과 사용 · 원본 계산 결과 확인 필요";
                row = new ImportAnalysis.Transaction(row.page(), row.line(), row.date(), row.time(), row.party(), row.businessNumber(),
                        row.type(), row.reference(), row.originalReference(), row.amount(), row.supply(), row.vat(), row.deposit(),
                        row.withdrawal(), row.balance(), row.description(), row.currency(), warning, row.sheet());
            }
            transactions.add(row);
            if (!row.warning().isEmpty()) issues.add(new ImportAnalysis.Issue(sheetNumber,
                    (source.getRowNum() + 1) + "행 " + row.warning(), sheet.getSheetName()));
        }
        if (!recognized) issues.add(new ImportAnalysis.Issue(sheetNumber, "거래일·금액 열 제목을 찾지 못했습니다. 시트 양식을 확인해 주세요.", sheet.getSheetName()));
        else if (transactions.size() == count) issues.add(new ImportAnalysis.Issue(sheetNumber, "거래 열은 확인했으나 내역을 찾지 못했습니다.", sheet.getSheetName()));
    }

    private static boolean header(ImportFiles.Kind kind, Map<String, Integer> columns) {
        if (!columns.containsKey("date")) return false;
        return switch (kind) {
            case CARD_PDF -> columns.containsKey("amount");
            case ACCOUNT_PDF -> columns.containsKey("deposit") && columns.containsKey("withdrawal");
            case TAX_INVOICE -> columns.containsKey("supply") && columns.containsKey("vat");
            default -> false;
        };
    }

    private static Map<String, String> invoiceFields(Map<String, String> cells) {
        var fields = new HashMap<String, String>();
        var keys = Map.ofEntries(Map.entry("date", "작성일자"), Map.entry("type", "구분"),
                Map.entry("supplierName", "공급자상호"), Map.entry("supplierBusiness", "공급자사업자번호"),
                Map.entry("buyerName", "공급받는자상호"), Map.entry("buyerBusiness", "공급받는자사업자번호"),
                Map.entry("reference", "문서번호"), Map.entry("original", "원문서번호"), Map.entry("amount", "합계금액"),
                Map.entry("supply", "공급가액"), Map.entry("vat", "부가세"), Map.entry("description", "품목"), Map.entry("correction", "수정사유"));
        keys.forEach((key, label) -> { if (cells.containsKey(key)) fields.put(label, cells.get(key)); });
        // '거래처'로 명시한 단일 상대방 열은 매출·매입 양쪽에 같은 후보로 전달합니다.
        if (cells.containsKey("party")) {
            fields.putIfAbsent("공급자상호", cells.get("party"));
            fields.putIfAbsent("공급받는자상호", cells.get("party"));
        }
        if (cells.containsKey("business")) {
            fields.putIfAbsent("공급자사업자번호", cells.get("business"));
            fields.putIfAbsent("공급받는자사업자번호", cells.get("business"));
        }
        return fields;
    }
    private static String value(Cell cell, DataFormatter formatter) { return cell == null ? "" : formatter.formatCellValue(cell).strip(); }
    private static boolean numeric(Cell cell) {
        return cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC;
    }
}
