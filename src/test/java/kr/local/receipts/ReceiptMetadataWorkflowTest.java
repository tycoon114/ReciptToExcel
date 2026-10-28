package kr.local.receipts;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 OCR·외부 통신 없이 임시 DB에서 새 필드의 저장·재인식·엑셀·기장 사본 연결만 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceiptMetadataWorkflowTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:h2:mem:receipt-metadata;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir", () -> data.toString());
        p.add("app.ollama-enabled", () -> "false");
        p.add("app.sheets-id", () -> ""); p.add("app.sheets-credentials", () -> "");
        p.add("spring.config.import", () -> "optional:classpath:metadata-test-unused.properties");
    }
    @Autowired ReceiptAnalyzer analyzer;
    @Autowired LedgerRepository repo;
    @Autowired ReceiptService receipts;
    @Autowired BookkeepingService bookkeeping;

    @Test void metadataSurvivesEditingRereadingExportAndBookkeepingSnapshot() throws Exception {
        String address = "서울특별시 강남구 테스트로 123";
        var a = analyzer.fromText("상호: 가상식당\n사업자등록번호: 123-45-67891\n"
                + "거래일시: 2026-10-28 13:04:09\n주소: " + address + "\n합계 9000\n카드", "직원 점심");
        Expense first = repo.insert(a, null, null, "metadata-synthetic");
        assertEquals("123-45-67891", first.businessNumber()); assertEquals("13:04:09", first.receiptTime());
        assertEquals(address, first.vendorAddress());
        Expense saved = receipts.save(first.id(), input(first, "1234567891", "13:04:09", address));
        assertEquals("123-45-67891", saved.businessNumber());
        assertEquals(1, receipts.filter(null, null, "1234567891", "CONFIRMED", "dateDesc").size());

        try (var book = new XSSFWorkbook(new ByteArrayInputStream(new ExcelExporter().export(List.of(saved), false)))) {
            var ledger = book.getSheet("지출장부");
            assertEquals("사업자등록번호", ledger.getRow(3).getCell(16).getStringCellValue());
            assertEquals("123-45-67891", ledger.getRow(4).getCell(16).getStringCellValue());
            assertEquals("13:04:09", ledger.getRow(4).getCell(17).getStringCellValue());
            assertEquals("@", ledger.getRow(4).getCell(16).getCellStyle().getDataFormatString());
            assertEquals("거래처주소", ledger.getRow(3).getCell(ledger.getRow(3).getLastCellNum() - 1).getStringCellValue());
            assertEquals(address, ledger.getRow(4).getCell(18).getStringCellValue());
        }
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> receipts.save(saved.id(), input(saved, "123-45-67890", "13:04:09", address)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> receipts.save(saved.id(), input(saved, "123-45-67891", "24:00", address)));
        var reread = analyzer.fromText("상호: 가상식당\n사업자번호: 123-45-67891\n"
                + "2026-10-28 14:05\n주소: 서울 강남구 예시로 456\n합계 9000", "직원 점심");
        var changed = repo.reanalyze(saved.id(), saved.version(), reread, false);
        assertEquals("14:05", changed.receiptTime()); assertEquals("서울 강남구 예시로 456", changed.vendorAddress());
        changed = receipts.save(changed.id(), input(changed, changed.businessNumber(), changed.receiptTime(), changed.vendorAddress()));
        var entry = bookkeeping.post(changed.id(), new BookkeepingService.PostInput(changed.version(), "2026-10-28",
                BookkeepingService.CREDIT_ACCOUNTS.get(0), "TOTAL_EXPENSE", "검토자", "직원 점심")).entry();
        assertEquals(changed.businessNumber(), entry.businessNumber()); assertEquals("14:05", entry.receiptTime());
        assertEquals(changed.vendorAddress(), entry.vendorAddress());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(new BookkeepingExporter().export(List.of(entry))))) {
            var journal = book.getSheet("분개장");
            assertEquals("거래처주소", journal.getRow(3).getCell(14).getStringCellValue());
            assertEquals(changed.vendorAddress(), journal.getRow(4).getCell(14).getStringCellValue());
        }
    }

    @Test void missingMetadataRemainsOptional() throws Exception {
        var empty = receipts.manual();
        var saved = receipts.save(empty.id(), input(empty, "", "", ""));
        assertEquals("CONFIRMED", saved.status()); assertEquals("", saved.businessNumber());
        assertEquals("", saved.receiptTime()); assertEquals("", saved.vendorAddress());
    }

    private ExpenseInput input(Expense e, String number, String time, String address) {
        return new ExpenseInput("2026-10-28", "가상식당", "복리후생비", "직원 점심", 9000L, null, null,
                "카드", "검토자", "", "", "CONFIRMED", e.version(), e.approvalNumber(), number, time, address);
    }
}
