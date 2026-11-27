package kr.local.receipts;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ImportAnalysisTest {
    @TempDir Path data;
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    private ImportFiles files() throws Exception { return new ImportFiles(json, data.toString()); }
    private byte[] sample(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/import-statements/" + name)) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    private ImportFiles.Entry upload(ImportFiles files, ImportFiles.Kind kind, String name) throws Exception {
        return files.upload(kind, new MockMultipartFile("file", name, "application/pdf", sample(name)));
    }

    @Test void sampleTransactionsPersistAndExportWithNumericAmountsAndTextIdentifiers() throws Exception {
        var files = files(); var service = new ImportAnalysis(files, json, data.toString());
        var card = upload(files, ImportFiles.Kind.CARD_PDF, "01_card_transactions.pdf");
        var bank = upload(files, ImportFiles.Kind.ACCOUNT_PDF, "02_account_transactions.pdf");
        var invoice = upload(files, ImportFiles.Kind.TAX_INVOICE, "03_tax_invoices.pdf");
        var cards = service.analyze(card.id());
        var banks = service.analyze(bank.id());
        var invoices = service.analyze(invoice.id());
        assertEquals("READY", cards.status(), cards.issues().toString());
        assertEquals("READY", banks.status(), banks.issues().toString());
        assertEquals("READY", invoices.status(), invoices.issues().toString());
        assertEquals(8, cards.rows().size()); assertEquals(13, banks.rows().size()); assertEquals(6, invoices.rows().size());
        assertEquals(281000L, cards.rows().stream().mapToLong(ImportAnalysis.Transaction::amount).sum());
        assertEquals(5545L, cards.rows().get(0).vat()); assertEquals("샘플식당", cards.rows().get(0).party());
        assertEquals(-220000L, cards.rows().get(6).amount());
        assertEquals(4348000L, banks.rows().get(12).balance());
        assertEquals("매출", invoices.rows().get(3).type());
        assertEquals(-220000L, invoices.rows().get(5).amount());
        assertEquals("SAMPLE-202611-I05", invoices.rows().get(5).originalReference());
        service.analyze(card.id());
        var restored = new ImportAnalysis(files, json, data.toString()).list();
        assertEquals(27, restored.stream().mapToInt(document -> document.analysis().rows().size()).sum());
        byte[] workbook = ImportWorkbook.create(restored, "");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            assertEquals(5, book.getNumberOfSheets());
            assertEquals(8, book.getSheet("카드내역").getLastRowNum());
            assertEquals(13, book.getSheet("계좌입출금").getLastRowNum());
            assertEquals(6, book.getSheet("세금계산서").getLastRowNum());
            var row = book.getSheet("카드내역").getRow(1);
            assertEquals(CellType.STRING, row.getCell(5).getCellType());
            assertEquals("91000001", row.getCell(5).getStringCellValue());
            assertEquals(61000d, row.getCell(7).getNumericCellValue());
            assertEquals(5545d, row.getCell(9).getNumericCellValue());
        }
    }

    @Test void monthFilterUsesEachDocumentsTransactionDate() throws Exception {
        var files = files(); var service = new ImportAnalysis(files, json, data.toString());
        var bank = upload(files, ImportFiles.Kind.ACCOUNT_PDF, "02_account_transactions.pdf");
        service.analyze(bank.id());
        for (var entry : Map.of("2026-11", 11, "2026-12", 2, "2026-10", 0).entrySet()) {
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(ImportWorkbook.create(service.list(), entry.getKey())))) {
                assertEquals(entry.getValue(), book.getSheet("계좌입출금").getLastRowNum());
            }
        }
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> ImportAnalysis.month("2026-13"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.analyze("../wrong"));
    }

    @Test void unsupportedPagesRemainVisibleAndDoNotDiscardSuccessfulPages() throws Exception {
        byte[] mixed;
        try (var pdf = Loader.loadPDF(sample("01_card_transactions.pdf"));
             var other = Loader.loadPDF(sample("03_tax_invoices.pdf")); var out = new ByteArrayOutputStream()) {
            pdf.importPage(other.getPage(0)); pdf.save(out); mixed = out.toByteArray();
        }
        var files = files(); var service = new ImportAnalysis(files, json, data.toString());
        var file = files.upload(ImportFiles.Kind.CARD_PDF, new MockMultipartFile("file", "mixed.pdf", "application/pdf", mixed));
        var result = service.analyze(file.id());
        assertEquals("REVIEW", result.status()); assertEquals(8, result.rows().size());
        assertEquals(2, result.issues().get(0).page());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(ImportWorkbook.create(service.list(), "")))) {
            assertEquals(8, book.getSheet("카드내역").getLastRowNum());
            assertEquals(2, book.getSheet("처리현황").getLastRowNum());
        }
    }

    @Test void photoIsOptionalAndNeverReportedAsAnExtractionFailure() throws Exception {
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(30, 30, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        var files = files(); var service = new ImportAnalysis(files, json, data.toString());
        var photo = files.upload(ImportFiles.Kind.RECEIPT_PHOTO, new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray()));
        assertEquals("PHOTO_PENDING", service.analyze(photo.id()).status());
        assertTrue(service.list().get(0).analysis().issues().isEmpty());
    }
}
