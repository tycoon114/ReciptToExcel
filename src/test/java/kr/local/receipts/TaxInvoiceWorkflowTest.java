package kr.local.receipts;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 개인 DB·실제 OCR·국세청 API 없이 파일 가져오기부터 품목 수정·기장 사본까지 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class TaxInvoiceWorkflowTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:tax-invoice-workflow;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir",()->data.toString());p.add("app.ollama-enabled",()->"false");
        p.add("app.nts-service-key",()->"");p.add("app.sheets-id",()->"");p.add("app.sheets-credentials",()->"");
        p.add("spring.config.import",()->"optional:classpath:invoice-test-unused.properties");
    }
    @Autowired ReceiptAnalyzer analyzer;
    @Autowired LedgerRepository repo;
    @Autowired ReceiptService receipts;
    @Autowired BookkeepingService bookkeeping;
    @Autowired jakarta.validation.Validator validator;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @MockitoBean WindowsOcr ocr;

    @Test void textPdfUsesOriginalTextAndRetainsSourceWithoutOcr() throws Exception {
        byte[] bytes=textPdf();
        var document=receipts.preparePdf(new MockMultipartFile("file","가상세금계산서.pdf","application/pdf",bytes));
        var result=receipts.uploadPdfPage(document.hash(),1,"사무용품 구입");var e=result.expense();
        assertEquals("가상공급상사",e.vendor());assertEquals("123-45-67891",e.businessNumber());
        assertEquals("220-81-62517",e.taxInvoice().buyerBusinessNumber());assertEquals(165000,e.amount());
        assertEquals(150000,e.supplyAmount());assertEquals(15000,e.vatAmount());assertEquals("2026-11-02",e.receiptDate());
        assertEquals(2,e.taxInvoice().items().size());assertNotNull(e.imagePath());assertArrayEquals(bytes,Files.readAllBytes(receipts.originalPdf(e.id())));
        assertTrue(receipts.uploadPdfPage(document.hash(),1,"").duplicate());verifyNoInteractions(ocr);
        var reread=receipts.reanalyze(e.id(),e.version(),"사무용품 구입");assertEquals(2,reread.taxInvoice().items().size());verifyNoInteractions(ocr);
    }

    @Test void photoInvoiceUsesOcrLayoutAndKeepsCorrectParties() throws Exception {
        var rows=TaxInvoiceParserTest.layout();String text="세금계산서\n"+rows.stream().map(WindowsOcr.Row::text).collect(java.util.stream.Collectors.joining("\n"));
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result(text,"가상 OCR",List.of(),rows));
        var image=new java.awt.image.BufferedImage(30,30,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);image.flush();
        var e=receipts.upload(new MockMultipartFile("file","사진.png","image/png",out.toByteArray()),"사무용품").expense();
        assertEquals("가상공급상사",e.vendor());assertEquals(2,e.taxInvoice().items().size());assertEquals(15000,e.vatAmount());
    }

    @Test void editingExportsAndBookkeepingUseStoredInvoiceSnapshot() throws Exception {
        var first=repo.insert(analyzer.fromText(TaxInvoiceParserTest.TEXT,"사무용품"),null,null,"invoice-synthetic");
        var saved=receipts.save(first.id(),input(first,first.taxInvoice(),"CONFIRMED"));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new ExcelExporter().export(List.of(saved),false)))) {
            var ledger=book.getSheet("지출장부");assertEquals("세금계산서",ledger.getRow(4).getCell(ExcelExporter.column("증빙종류")).getStringCellValue());
            assertEquals("00001234",ledger.getRow(4).getCell(ExcelExporter.column("세금계산서번호")).getStringCellValue());
            var items=book.getSheet("세금계산서품목");assertNotNull(items);
            assertEquals(50000,items.getRow(4).getCell(12).getNumericCellValue());assertEquals(2.5,items.getRow(5).getCell(11).getNumericCellValue());
            assertEquals("00001234",items.getRow(4).getCell(4).getStringCellValue());
            assertEquals("거래처주소",ledger.getRow(3).getCell(ExcelExporter.HEADERS.size()-1).getStringCellValue());
        }
        var posted=bookkeeping.post(saved.id(),new BookkeepingService.PostInput(saved.version(),"2026-11-02",
                BookkeepingService.CREDIT_ACCOUNTS.get(0),"TOTAL_EXPENSE","검토자","사무용품")).entry();
        assertEquals(saved.taxInvoice(),posted.taxInvoice());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->receipts.save(saved.id(),input(repo.get(saved.id()),saved.taxInvoice(),"REVIEW")));
        var cancelled=bookkeeping.cancel(posted.id(),new BookkeepingService.CancelInput(repo.get(saved.id()).version(),"검토자","품목 정정"));
        var current=cancelled.expense();var old=current.taxInvoice();
        var changed=new TaxInvoice("00009999",old.buyerBusinessNumber(),"변경한 회사",old.warning(),old.items());
        receipts.save(current.id(),input(current,changed,"REVIEW"));
        assertEquals("00001234",bookkeeping.get(posted.id()).taxInvoice().issueNumber());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new BookkeepingExporter().export(List.of(bookkeeping.get(posted.id())))))) {
            assertEquals("00001234",book.getSheet("세금계산서품목").getRow(4).getCell(4).getStringCellValue());
            assertTrue(book.getSheet("세금계산서품목").getRow(4).getCell(1).getStringCellValue().contains("취소"));
        }
    }

    @Test void mismatchesAndInvalidNestedValuesCannotBeConfirmed() {
        var first=repo.insert(analyzer.fromText(TaxInvoiceParserTest.TEXT,"사무용품"),null,null,"invoice-invalid");
        var old=first.taxInvoice();var item=old.items().get(0);
        var changed=new TaxInvoice(old.issueNumber(),old.buyerBusinessNumber(),old.buyerName(),"",List.of(
                new TaxInvoice.Item("",item.name(),"",item.quantity(),item.unitPrice(),999L,10000L)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->receipts.save(first.id(),input(first,changed,"CONFIRMED")));
        var invalid=new TaxInvoice("","","","",List.of(new TaxInvoice.Item("","가상품목","",null,new java.math.BigDecimal("-1"),null,null)));
        assertFalse(validator.validate(input(first,invalid,"REVIEW")).isEmpty());
    }

    @Test void browserDecimalStringsBindAndUnknownBuyerNeedsReview() throws Exception {
        var first=repo.insert(analyzer.fromText(TaxInvoiceParserTest.TEXT,"사무용품"),null,null,"invoice-json");
        String body=json.writeValueAsString(input(first,first.taxInvoice(),"REVIEW")).replace("\"quantity\":2,","\"quantity\":\"2\",");
        var request=json.readValue(body,ExpenseInput.class);assertTrue(validator.validate(request).isEmpty());
        assertEquals(new java.math.BigDecimal("2"),request.taxInvoice().items().get(0).quantity());
        var looked=repo.saveBusinessLookup(first.id(),first.version(),"1234567891",
                new NtsBusinessLookup.Result("1234567891","FOUND","01","계속사업자","01","일반과세자","","","","","2026-11-02T04:00:00Z"));
        assertEquals("CANDIDATE",looked.vatReview().code(),"세금계산서는 카드 결제 표기가 없어도 증빙 후보로 검토합니다.");
        var old=request.taxInvoice();var noBuyer=new TaxInvoice(old.issueNumber(),"",old.buyerName(),"",old.items());
        var saved=receipts.save(first.id(),input(looked,noBuyer,"REVIEW"));assertEquals("INVOICE_BUYER",saved.vatReview().rule());
    }

    @Test void explicitFormTypeSurvivesMissingTitleAndReanalysis() throws Exception {
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result(TaxInvoiceParserTest.TEXT.replace("세 금 계 산 서 (공급받는 자 보관용)","제목을 읽지 못한 문서"),"가상 OCR"));
        var invoice=receipts.upload(photo(31),"사무용품","TAX_INVOICE").expense();
        assertEquals("TAX_INVOICE",invoice.documentType());assertNotNull(invoice.taxInvoice());
        assertTrue(invoice.taxInvoice().warning().contains("제목"));
        var reread=receipts.reanalyze(invoice.id(),invoice.version(),"사무용품");assertEquals("TAX_INVOICE",reread.documentType());
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result(TaxInvoiceParserTest.TEXT,"가상 OCR"));
        var receipt=receipts.upload(photo(32),"사무용품","RECEIPT").expense();
        assertEquals("RECEIPT",receipt.documentType());assertNull(receipt.taxInvoice());
        assertNull(receipts.reanalyze(receipt.id(),receipt.version(),"사무용품").taxInvoice());
    }

    @Test void manualFormsShareLedgerButCanBeFilteredByType() {
        var receipt=receipts.manual("RECEIPT");var invoice=receipts.manual("TAX_INVOICE");
        assertNull(receipt.taxInvoice());assertNotNull(invoice.taxInvoice());assertTrue(invoice.taxInvoice().items().isEmpty());
        assertEquals("MANUAL",invoice.analysisSource());assertTrue(invoice.taxInvoice().warning().isEmpty());
        for(var e:List.of(receipt,invoice))receipts.save(e.id(),new ExpenseInput("2026-11-03","폼분리검증-"+e.documentType(),"소모품비","폼 검증",
                11000L,10000L,1000L,"계좌이체","검토자","","","REVIEW",e.version(),"","","","",e.taxInvoice()));
        assertEquals(2,receipts.filter("","","폼분리검증","","dateDesc","").size());
        assertEquals(1,receipts.filter("","","폼분리검증","","dateDesc","RECEIPT").size());
        assertEquals(1,receipts.filter("","","폼분리검증","","dateDesc","TAX_INVOICE").size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->receipts.manual("INVALID"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->receipts.filter("","","","","dateDesc","INVALID"));
    }

    @Test void sameFileOnAnotherTabDoesNotCreateOrConvertAnExpense() throws Exception {
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result("가상식당\n2026-11-03\n합계 9900\n카드","가상 OCR"));
        var first=receipts.upload(photo(33),"직원 점심","RECEIPT");
        var second=receipts.upload(photo(33),"직원 점심","TAX_INVOICE");
        assertTrue(second.duplicate());assertEquals(first.expense().id(),second.expense().id());assertEquals("RECEIPT",second.expense().documentType());
    }

    private MockMultipartFile photo(int size) throws Exception {
        var image=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);image.flush();
        return new MockMultipartFile("file","form-test.png","image/png",out.toByteArray());
    }
    private ExpenseInput input(Expense e,TaxInvoice invoice,String status) {
        return new ExpenseInput(e.receiptDate(),e.vendor(),"소모품비","업무용 사무용품",e.amount(),e.supplyAmount(),e.vatAmount(),
                "계좌이체","검토자","",e.note(),status,e.version(),e.approvalNumber(),e.businessNumber(),e.receiptTime(),e.vendorAddress(),invoice);
    }
    private byte[] textPdf() throws Exception {
        var fontPath=Path.of(System.getenv().getOrDefault("WINDIR","C:/Windows"),"Fonts","malgun.ttf");
        Assumptions.assumeTrue(Files.isRegularFile(fontPath),"테스트 PDF 생성에는 Windows 한글 글꼴이 필요합니다.");
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(650,350));pdf.addPage(page);
            var font=PDType0Font.load(pdf,fontPath.toFile());
            try(var content=new PDPageContentStream(pdf,page)) {
                content.beginText();content.setFont(font,10);content.newLineAtOffset(20,330);content.showText("세금계산서(공급받는 자 보관용)");content.endText();
                for(var row:TaxInvoiceParserTest.layout())for(var word:row.words()) {
                    content.beginText();content.setFont(font,8);content.newLineAtOffset((float)word.left(),350-(float)word.top());content.showText(word.text());content.endText();
                }
            }
            pdf.save(out);return out.toByteArray();
        }
    }
}
