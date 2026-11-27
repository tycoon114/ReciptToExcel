package kr.local.receipts;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ImportExcelTest {
    @TempDir Path data;
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    private static final String OWN_NUMBER = "999-99-99999";

    private byte[] workbook(ImportFiles.Kind kind, boolean legacy) throws Exception {
        try (Workbook book = legacy ? new HSSFWorkbook() : new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("가상 내역");
            sheet.createRow(0).createCell(0).setCellValue("개발용 가상 샘플 · 단위 원");
            String[] headers = switch (kind) {
                case CARD_PDF -> new String[]{"사용일자", "이용가맹점명", "이용금액(원)", "공급가액", "부가가치세", "승인번호", "거래구분"};
                case ACCOUNT_PDF -> new String[]{"거래일자", "거래처명", "입금액(원)", "출금액(원)", "거래후잔액", "적요"};
                default -> new String[]{"작성일자", "공급자등록번호", "공급자상호", "공급받는자등록번호", "공급받는자상호", "공급가액", "세액", "합계금액", "승인번호"};
            };
            var header = sheet.createRow(2);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            var dateStyle = book.createCellStyle(); dateStyle.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            for (int index = 0; index < 2; index++) {
                var row = sheet.createRow(3 + index);
                row.createCell(0).setCellValue(LocalDateTime.of(2026, index == 0 ? 11 : 12, 3, 0, 0));
                row.getCell(0).setCellStyle(dateStyle);
                Object[] values = switch (kind) {
                    case CARD_PDF -> new Object[]{"가상식당", 61000, 55455, 5545, "00123456", index == 0 ? "사용" : "취소"};
                    case ACCOUNT_PDF -> new Object[]{"가상거래처", index == 0 ? null : 61000, index == 0 ? 61000 : null, index == 0 ? 939000 : 1000000, "테스트 입출금"};
                    default -> new Object[]{"000-00-00015", "가상공급자", OWN_NUMBER, "가상당사", 55455, 5545, 61000, "202611030000000000000001"};
                };
                for (int i = 0; i < values.length; i++) {
                    var cell = row.createCell(i + 1);
                    if (values[i] instanceof Number number) cell.setCellValue(number.doubleValue());
                    else if (values[i] != null) cell.setCellValue(values[i].toString());
                }
            }
            var total = sheet.createRow(5); total.createCell(0).setCellValue("합계"); total.createCell(2).setCellValue(122000);
            book.write(out); return out.toByteArray();
        }
    }
    private ImportFiles.Entry upload(ImportFiles files, ImportFiles.Kind kind, boolean legacy) throws Exception {
        byte[] bytes = workbook(kind, legacy);
        String name = kind.name().toLowerCase(Locale.ROOT) + (legacy ? ".xls" : ".xlsx");
        String sampleDirectory = System.getProperty("import.sample-output");
        if (sampleDirectory != null) {
            Files.createDirectories(Path.of(sampleDirectory));
            Files.write(Path.of(sampleDirectory).resolve(name), bytes);
        }
        return files.upload(kind, new MockMultipartFile("file", name, "application/octet-stream", bytes), kind == ImportFiles.Kind.TAX_INVOICE ? "매입" : "");
    }

    @Test void allThreeKindsSupportXlsAndXlsxWithDatesAmountsAndIdentifiers() throws Exception {
        var files = new ImportFiles(json, data.toString()); var service = new ImportAnalysis(files, json, data.toString());
        for (boolean legacy : new boolean[]{false, true}) {
            for (var kind : new ImportFiles.Kind[]{ImportFiles.Kind.CARD_PDF, ImportFiles.Kind.ACCOUNT_PDF, ImportFiles.Kind.TAX_INVOICE}) {
                var file = upload(files, kind, legacy); var result = service.analyze(file.id());
                assertEquals("READY", result.status(), kind + " " + result.issues());
                assertEquals(2, result.rows().size());
                assertEquals("2026-11-03", result.rows().get(0).date());
                assertEquals("가상 내역", result.rows().get(0).sheet()); assertEquals(4, result.rows().get(0).line());
                if (kind == ImportFiles.Kind.CARD_PDF) {
                    assertEquals("00123456", result.rows().get(0).reference());
                    assertEquals(-61000L, result.rows().get(1).amount());
                }
                if (kind == ImportFiles.Kind.TAX_INVOICE) {
                    assertEquals("가상공급자", result.rows().get(0).party());
                    assertEquals("000-00-00015", result.rows().get(0).businessNumber());
                    assertEquals("202611030000000000000001", result.rows().get(0).reference());
                }
                service.analyze(file.id());
                assertEquals(2, service.list().stream().filter(document -> document.file().id().equals(file.id())).findFirst().orElseThrow().analysis().rows().size());
            }
        }
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(ImportWorkbook.create(service.list(), "")))) {
            assertEquals(6, book.getNumberOfSheets()); assertEquals("통합내역", book.getSheetAt(0).getSheetName());
            assertEquals(12, book.getSheet("통합내역").getLastRowNum());
            assertEquals(4, book.getSheet("카드내역").getLastRowNum());
            assertEquals(4, book.getSheet("계좌입출금").getLastRowNum());
            assertEquals(4, book.getSheet("세금계산서").getLastRowNum());
            for (var sheet : book) for (var row : sheet) for (var cell : row)
                assertNotEquals(OWN_NUMBER, cell.toString(), "당사 사업자번호가 출력되면 안 됩니다.");
            assertEquals(CellType.STRING, book.getSheet("세금계산서").getRow(1).getCell(4).getCellType());
        }
    }

    @Test void mergedOutputAndMonthFiltersIncludePdfAndExcelTogether() throws Exception {
        var files = new ImportFiles(json, data.toString()); var service = new ImportAnalysis(files, json, data.toString());
        try (var input = getClass().getResourceAsStream("/import-statements/01_card_transactions.pdf")) {
            assertNotNull(input);
            var pdf = files.upload(ImportFiles.Kind.CARD_PDF, new MockMultipartFile("file", "card.pdf", "application/pdf", input.readAllBytes()));
            service.analyze(pdf.id());
        }
        service.analyze(upload(files, ImportFiles.Kind.ACCOUNT_PDF, true).id());
        service.analyze(upload(files, ImportFiles.Kind.TAX_INVOICE, false).id());
        for (var range : Map.of("", 12, "2026-11", 10, "2026-12", 2).entrySet()) {
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(ImportWorkbook.create(service.list(), range.getKey())))) {
                var sheet = book.getSheet("통합내역"); assertEquals(range.getValue(), sheet.getLastRowNum());
                String previous = "";
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    String date = sheet.getRow(i).getCell(0).getStringCellValue();
                    assertTrue(date.compareTo(previous) >= 0); previous = date;
                    assertFalse(sheet.getRow(i).getCell(18).toString().isBlank());
                }
            }
        }
    }

    @Test void cachedFormulaResultsAndUnrecognizedSheetsHaveVisibleWarnings() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook(ImportFiles.Kind.CARD_PDF, false))); var out = new ByteArrayOutputStream()) {
            var amount = book.getSheetAt(0).getRow(3).getCell(2); amount.setCellFormula("55455+5545");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.createSheet("안내만 있는 시트").createRow(0).createCell(0).setCellValue("사용 방법");
            book.write(out); bytes = out.toByteArray();
        }
        var files = new ImportFiles(json, data.toString()); var service = new ImportAnalysis(files, json, data.toString());
        var file = files.upload(ImportFiles.Kind.CARD_PDF, new MockMultipartFile("file", "formula.xlsx", "application/octet-stream", bytes));
        var result = service.analyze(file.id());
        assertEquals("REVIEW", result.status()); assertEquals(2, result.rows().size());
        assertEquals(61000L, result.rows().get(0).amount());
        assertTrue(result.rows().get(0).warning().contains("수식"));
        assertTrue(result.issues().stream().anyMatch(issue -> "안내만 있는 시트".equals(issue.sheet())));
    }

    @Test void oldSavedMetadataAndAnalysisLoadWithoutNewFields() throws Exception {
        var files = new ImportFiles(json, data.toString()); var service = new ImportAnalysis(files, json, data.toString());
        var file = upload(files, ImportFiles.Kind.CARD_PDF, false); service.analyze(file.id());
        Path metadata = data.resolve("imports").resolve(file.id() + ".json");
        Files.writeString(metadata, Files.readString(metadata).replaceAll(",\\\"invoiceDirection\\\":\\\"[^\\\"]*\\\"", ""));
        Path saved = data.resolve("import-analysis").resolve(file.id() + ".json");
        Files.writeString(saved, Files.readString(saved).replaceAll(",\\\"sheet\\\":\\\"[^\\\"]*\\\"", ""));
        assertNull(files.get(file.id()).invoiceDirection());
        var restored = new ImportAnalysis(files, json, data.toString()).list();
        assertEquals(2, restored.get(0).analysis().rows().size());
        assertNotNull(ImportWorkbook.create(restored, ""));
    }

    @Test void malformedSpreadsheetsAndExcelInPhotoInputAreRejected() throws Exception {
        var files = new ImportFiles(json, data.toString());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> files.upload(ImportFiles.Kind.CARD_PDF,
                new MockMultipartFile("file", "broken.xlsx", "application/octet-stream", new byte[]{1,2,3})));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> files.upload(ImportFiles.Kind.RECEIPT_PHOTO,
                new MockMultipartFile("file", "card.xls", "application/octet-stream", workbook(ImportFiles.Kind.CARD_PDF, true))));
        assertTrue(files.list().isEmpty());
    }
}
