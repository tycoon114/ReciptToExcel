package kr.local.receipts;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.LocalDate;
import java.util.*;

@Service
public class ExcelExporter {
    public static final List<String> HEADERS =
            List.of(
                    "관리ID", "지출일자", "거래처", "계정과목", "적요", "총금액(원)", "공급가액(원)", "부가세(원)", "결제수단",
                    "사용자", "부서", "비고", "검토상태", "분석방식", "원본파일명");

    public byte[] export(List<Expense> expenses, boolean template) throws IOException {
        try (var book = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var summary = book.createSheet("계정별집계");
            var ledger = book.createSheet("지출장부");
            CellStyle title = style(book, 18, true, "172A46", false),
                    header = style(book, 10, true, "FFFFFF", true),
                    text = style(book, 10, false, "172A46", false);
            CellStyle money = book.createCellStyle();
            money.cloneStyleFrom(text);
            money.setDataFormat(book.createDataFormat().getFormat("#,##0;[Red](#,##0);–"));
            CellStyle date = book.createCellStyle();
            date.cloneStyleFrom(text);
            date.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle editable = book.createCellStyle();
            editable.cloneStyleFrom(text);
            editable.setFont(font(book, 10, false, "1D4ED8"));
            CellStyle wrap = book.createCellStyle();
            wrap.cloneStyleFrom(text);
            wrap.setWrapText(true);
            put(ledger, 0, 0, template ? "경비 지출장부 · 입력 양식" : "경비 지출장부", title);
            ledger.addMergedRegion(new CellRangeAddress(0, 0, 0, 14));
            ledger.getRow(0).setHeightInPoints(34);
            put(
                    ledger,
                    1,
                    0,
                    "KRW · "
                            + (template
                                    ? "파란 글씨 칸에 입력 · 미확인 세액은 빈칸 · 세액 합계는 입력값만 합산"
                                    : "확정 건 · 조회 조건과 정렬 적용 · 미확인 세액은 빈칸 · 세액 합계는 입력값만 합산"),
                    text);
            ledger.addMergedRegion(new CellRangeAddress(1, 1, 0, 14));
            for (int c = 0; c < HEADERS.size(); c++) put(ledger, 3, c, HEADERS.get(c), header);
            int count = template ? 200 : Math.max(1, expenses.size()), lastExcel = count + 4;
            for (int i = 0; i < count; i++) {
                int r = i + 4;
                for (int c = 0; c < 15; c++) put(ledger, r, c, "", template ? editable : text);
                ledger.getRow(r).setHeightInPoints(30);
                for (int c : new int[] {5, 6, 7}) ledger.getRow(r).getCell(c).setCellStyle(money);
                ledger.getRow(r).getCell(1).setCellStyle(date);
                ledger.getRow(r).getCell(4).setCellStyle(wrap);
                ledger.getRow(r).getCell(11).setCellStyle(wrap);
                if (i < expenses.size()) {
                    Expense e = expenses.get(i);
                    var values = values(e);
                    for (int c = 0; c < 15; c++) {
                        var cell = ledger.getRow(r).getCell(c);
                        Object v = values.get(c);
                        if (c == 1 && e.receiptDate() != null)
                            cell.setCellValue(LocalDate.parse(e.receiptDate()).atStartOfDay());
                        else if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                        else cell.setCellValue(Objects.toString(v, ""));
                    }
                }
            }
            int total = count + 5;
            put(ledger, total, 4, "합계", header);
            for (int c : new int[] {5, 6, 7}) {
                var cell = put(ledger, total, c, "", money);
                cell.setCellFormula(
                        "SUM("
                                + CellReference.convertNumToColString(c)
                                + "5:"
                                + CellReference.convertNumToColString(c)
                                + lastExcel
                                + ")");
            }
            ledger.setAutoFilter(new CellRangeAddress(3, count + 3, 0, 14));
            ledger.createFreezePane(4, 4);
            int[] widths = {10, 14, 24, 16, 34, 19, 19, 17, 15, 14, 16, 40, 14, 18, 32};
            for (int c = 0; c < widths.length; c++) ledger.setColumnWidth(c, widths[c] * 256);
            var validation = ledger.getDataValidationHelper();
            for (var spec :
                    List.of(
                            Map.entry(3, ReceiptAnalyzer.CATEGORIES),
                            Map.entry(8, ReceiptAnalyzer.PAYMENTS))) {
                var constraint =
                        validation.createExplicitListConstraint(
                                spec.getValue().toArray(String[]::new));
                var rule =
                        validation.createValidation(
                                constraint,
                                new CellRangeAddressList(
                                        4, Math.max(203, count + 3), spec.getKey(), spec.getKey()));
                rule.setShowErrorBox(true);
                rule.createErrorBox("입력 확인", "목록의 값을 선택해 주세요.");
                ledger.addValidationData(rule);
            }
            put(summary, 0, 0, "경비 지출 집계", title);
            summary.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            summary.getRow(0).setHeightInPoints(34);
            put(
                    summary,
                    1,
                    0,
                    "단위: 원 · "
                            + (template
                                    ? "지출장부 입력값을 합산합니다."
                                    : "확정된 " + expenses.size() + "건 · 지출장부와 같은 범위입니다."),
                    text);
            summary.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
            put(summary, 3, 0, "계정과목", header);
            put(summary, 3, 1, "건수", header);
            put(summary, 3, 2, "총금액(원)", header);
            for (int i = 0; i < ReceiptAnalyzer.CATEGORIES.size(); i++) {
                int r = i + 4;
                put(summary, r, 0, ReceiptAnalyzer.CATEGORIES.get(i), text);
                put(summary, r, 1, "", money)
                        .setCellFormula(
                                "COUNTIF('지출장부'!$D$5:$D$" + lastExcel + ",A" + (r + 1) + ")");
                put(summary, r, 2, "", money)
                        .setCellFormula(
                                "SUMIF('지출장부'!$D$5:$D$"
                                        + lastExcel
                                        + ",A"
                                        + (r + 1)
                                        + ",'지출장부'!$F$5:$F$"
                                        + lastExcel
                                        + ")");
                summary.getRow(r).setHeightInPoints(26);
            }
            int summaryTotal = ReceiptAnalyzer.CATEGORIES.size() + 5;
            int lastCategory = ReceiptAnalyzer.CATEGORIES.size() + 4;
            put(summary, summaryTotal, 0, "합계", header);
            put(summary, summaryTotal, 1, "", money)
                    .setCellFormula("SUM(B5:B" + lastCategory + ")");
            put(summary, summaryTotal, 2, "", money)
                    .setCellFormula("SUM(C5:C" + lastCategory + ")");
            put(
                    summary,
                    summaryTotal + 3,
                    0,
                    "기준: Microsoft 경비 보고서의 날짜·금액·분류·증빙 구조를 바탕으로 재구성",
                    text);
            summary.addMergedRegion(new CellRangeAddress(summaryTotal + 3, summaryTotal + 3, 0, 5));
            put(
                    summary,
                    summaryTotal + 4,
                    0,
                    "https://excel.cloud.microsoft/create/en/expense-report-templates/",
                    text);
            summary.addMergedRegion(new CellRangeAddress(summaryTotal + 4, summaryTotal + 4, 0, 5));
            summary.setColumnWidth(0, 26 * 256);
            summary.setColumnWidth(1, 14 * 256);
            summary.setColumnWidth(2, 24 * 256);
            summary.createFreezePane(0, 4);
            for (var sheet : List.of(summary, ledger)) {
                sheet.setDisplayGridlines(false);
                sheet.setFitToPage(true);
                sheet.getPrintSetup().setLandscape(true);
                sheet.getPrintSetup().setFitWidth((short) 1);
                sheet.getPrintSetup().setFitHeight((short) 0);
            }
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.write(out);
            return out.toByteArray();
        }
    }

    public static List<Object> values(Expense e) {
        return Arrays.asList(
                e.id(),
                e.receiptDate(),
                e.vendor(),
                e.category(),
                e.description(),
                e.amount(),
                e.supplyAmount(),
                e.vatAmount(),
                e.paymentMethod(),
                e.ownerName(),
                e.department(),
                e.note(),
                e.status().equals("CONFIRMED") ? "확정" : "검토 대기",
                e.analysisSource(),
                e.imagePath() == null ? "" : e.originalName());
    }

    private static Font font(Workbook b, int size, boolean bold, String hex) {
        var f = b.createFont();
        f.setFontName("맑은 고딕");
        f.setFontHeightInPoints((short) size);
        f.setBold(bold);
        ((org.apache.poi.xssf.usermodel.XSSFFont) f)
                .setColor(
                        new org.apache.poi.xssf.usermodel.XSSFColor(
                                java.awt.Color.decode("#" + hex), null));
        return f;
    }

    private static CellStyle style(
            Workbook b, int size, boolean bold, String color, boolean filled) {
        var s = b.createCellStyle();
        s.setFont(font(b, size, bold, color));
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        if (filled) {
            ((org.apache.poi.xssf.usermodel.XSSFCellStyle) s)
                    .setFillForegroundColor(
                            new org.apache.poi.xssf.usermodel.XSSFColor(
                                    java.awt.Color.decode("#172A46"), null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return s;
    }

    private static Cell put(Sheet s, int r, int c, String value, CellStyle style) {
        var row = s.getRow(r);
        if (row == null) row = s.createRow(r);
        var cell = row.createCell(c);
        cell.setCellValue(value);
        cell.setCellStyle(style);
        return cell;
    }
}
