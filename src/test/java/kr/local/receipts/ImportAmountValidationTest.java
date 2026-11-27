package kr.local.receipts;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ImportAmountValidationTest {
    private ImportAnalysis.Transaction row(Long total, Long supply, Long vat) {
        return new ImportAnalysis.Transaction(1, 1, "2026-09-10", "", "검증용", "", "매입", "", "",
                total, supply, vat, null, null, null, "", "KRW", "");
    }
    @Test void distinguishesMissingZeroMismatchAndSignedCancellation() {
        assertEquals("MATCH", row(110000L,100000L,10000L).amountValidation().code());
        assertEquals("MATCH", row(0L,0L,0L).amountValidation().code());
        assertEquals("MISSING", row(110000L,100000L,null).amountValidation().code());
        assertNull(row(null,null,null).amountValidation().difference());
        assertEquals(-10000L,row(120000L,100000L,10000L).amountValidation().difference());
        assertEquals("MISMATCH",row(120000L,100000L,10000L).amountValidation().code());
        var negative=row(-110000L,-100000L,-10000L).amountValidation();
        assertEquals("MATCH",negative.code());assertFalse(negative.notice().isBlank());
        assertEquals("OUT_OF_RANGE",row(1L,Long.MAX_VALUE,1L).amountValidation().code());
    }
    @Test void apiAndWorkbookUseTheSameValidationForBothKindsWithoutChangingAmounts() throws Exception {
        var mapper=JsonMapper.builder().build();var original=row(120000L,100000L,10000L);
        String stored=mapper.writeValueAsString(original);
        assertEquals("MISMATCH",mapper.readTree(stored).path("amountValidation").path("code").asText());
        assertEquals(original,mapper.readValue(stored,ImportAnalysis.Transaction.class));
        var documents=new java.util.ArrayList<ImportAnalysis.Document>();
        for(var kind:List.of(ImportFiles.Kind.CARD_PDF,ImportFiles.Kind.TAX_INVOICE)){
            var file=new ImportFiles.Entry(kind.name(),kind,"검증.xlsx",0,"application/x-receipt-generated-test",0,"", "");
            documents.add(new ImportAnalysis.Document(file,new ImportAnalysis.Result(file.id(),"READY","now",List.of(original),List.of())));
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(ImportWorkbook.create(documents,"2026-09")))){
            for(String name:List.of("통합내역","카드내역","세금계산서")){
                var sheet=book.getSheet(name);int validation=-1;
                for(var cell:sheet.getRow(0))if("금액 검증".equals(cell.getStringCellValue()))validation=cell.getColumnIndex();
                assertTrue(validation>=0);assertEquals("금액 불일치",sheet.getRow(1).getCell(validation).getStringCellValue());
                assertEquals(-10000d,sheet.getRow(1).getCell(validation+1).getNumericCellValue());
            }
        }
        assertEquals(120000L,original.amount());
    }
}
