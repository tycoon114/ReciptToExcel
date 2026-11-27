package kr.local.receipts;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ImportCardVatTest {
    @TempDir Path data;
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-27T03:00:00Z"), ZoneOffset.UTC);
    private ImportFiles.Entry sample(ImportFiles files) throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("카드 검토 샘플");
            sheet.createRow(0).createCell(0).setCellValue("개발용 가상 샘플 · 단위 원");
            String[] headers = {"사용일자", "가맹점명", "거래처사업자번호", "이용금액", "공급가액", "부가세", "적요", "구분"};
            var header = sheet.createRow(1);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            Object[][] values = {
                {"2026-11-03", "가상식당", "000-00-00015", 61000, 55455, 5545, "직원 점심 식사", "사용"},
                {"2026-11-04", "가상카페", "000-00-00048", 11000, 10000, 1000, "직원 회의 커피", "사용"},
                {"2026-11-05", "가상통신", "000-00-00034", 110000, 100000, 10000, "사무실 통신요금", "사용"},
                {"2026-11-06", "가상면세", "000-00-00053", 22000, 20000, 2000, "사무용품 구입", "사용"},
                {"2026-11-07", "가상문구", "000-00-00029", 33000, 30000, 3000, "", "사용"},
                {"2026-11-08", "번호 미확인", "", 11000, 10000, 1000, "사무용품 구입", "사용"},
                {"2026-11-09", "가상식당", "000-00-00015", -61000, -55455, -5545, "직원 점심 식사", "취소"}
            };
            for (int i = 0; i < values.length; i++) {
                var row = sheet.createRow(i + 2);
                for (int j = 0; j < values[i].length; j++) {
                    var cell = row.createCell(j);
                    if (values[i][j] instanceof Number number) cell.setCellValue(number.doubleValue());
                    else cell.setCellValue(values[i][j].toString());
                }
            }
            book.write(out);
            String output = System.getProperty("import.vat-sample-output");
            if (output != null) {
                Files.createDirectories(Path.of(output));
                Files.write(Path.of(output).resolve("카드_부가세_입력샘플.xlsx"), out.toByteArray());
            }
            return files.upload(ImportFiles.Kind.CARD_PDF, new MockMultipartFile("file", "가상카드_부가세.xlsx", "application/octet-stream", out.toByteArray()));
        }
    }

    @Test void testLookupUsesExistingRulesAndExportsClearlyMarkedCandidates() throws Exception {
        var files = new ImportFiles(json, data.toString()); var analysis = new ImportAnalysis(files, json, data.toString());
        var file = sample(files); analysis.analyze(file.id());
        var service = new ImportCardVat(analysis, new NtsBusinessLookup(null, json, ""), json, clock);
        var reviews = service.review("TEST").get(0).analysis().cardReviews();
        assertEquals(7, reviews.size());
        assertEquals(5545L, reviews.get(0).candidateVat());
        assertEquals("NON_DEDUCTIBLE", reviews.get(1).review().code());
        assertEquals("07", reviews.get(2).lookup().taxTypeCode()); assertEquals(10000L, reviews.get(2).candidateVat());
        assertEquals("EXEMPT_CONFLICT", reviews.get(3).review().rule());
        assertEquals("PURPOSE_UNKNOWN", reviews.get(4).review().rule());
        assertEquals("NUMBER_UNKNOWN", reviews.get(5).review().rule());
        assertEquals("REVERSAL", reviews.get(6).review().rule());
        assertTrue(reviews.subList(3, 7).stream().allMatch(row -> row.candidateVat() == null));
        assertTrue(reviews.stream().allMatch(row -> row.mode().equals("TEST")));
        byte[] bytes = ImportWorkbook.create(new ImportAnalysis(files, json, data.toString()).list(), "");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheet("통합내역");
            assertEquals("부가가치세 일반과세자", sheet.getRow(1).getCell(20).getStringCellValue());
            assertEquals("테스트 조회", sheet.getRow(1).getCell(21).getStringCellValue());
            assertEquals(5545d, sheet.getRow(1).getCell(24).getNumericCellValue());
            assertFalse(sheet.getRow(1).getCell(25).getStringCellValue().isBlank());
        }
        String output = System.getProperty("import.vat-sample-output");
        if (output != null) Files.write(Path.of(output).resolve("통합내역_카드부가세_테스트조회.xlsx"), bytes);
        analysis.analyze(file.id()); assertTrue(analysis.list().get(0).analysis().cardReviews().isEmpty());
    }

    @Test void liveFailuresNeverPromoteTestResultsAndBatchDeduplicatesNumbers() throws Exception {
        var files = new ImportFiles(json, data.toString()); var analysis = new ImportAnalysis(files, json, data.toString());
        var file = sample(files); analysis.analyze(file.id());
        var failing = new ImportCardVat(analysis, new NtsBusinessLookup(null, json, ""), json, clock);
        failing.review("TEST");
        var failed = failing.review("LIVE").get(0).analysis().cardReviews();
        assertNull(failed.get(0).lookup()); assertNull(failed.get(0).candidateVat());
        assertEquals("LOOKUP_FAILED", failed.get(0).review().rule());
        var fake = new NtsBusinessLookup(null, json, "unit-test-key") {
            @Override Map<String, Result> requestBatch(List<String> numbers) {
                assertEquals(5, numbers.size());
                var result = new HashMap<String, Result>();
                for (String number : numbers) result.put(number, new Result(number, "FOUND", "01", "계속사업자", "01",
                        "부가가치세 일반과세자", "", "", "", "", Instant.now(clock).toString()));
                return result;
            }
        };
        new ImportCardVat(analysis, fake, json, clock).review("LIVE");
        var retry = failing.review("LIVE").get(0).analysis().cardReviews().get(0);
        assertNotNull(retry.lookup()); assertEquals("LIVE", retry.mode()); assertNull(retry.candidateVat());
        assertTrue(retry.review().reason().contains("이전 실제 조회"));
    }
}
