package kr.local.receipts;

import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 합성 자료로 분류 예외와 엑셀 집계·서식을 확인하며 실제 납세 자료는 사용하지 않습니다. */
class VatDeductionReviewTest {
    static class Fixture {
        String category="복리후생비", purpose="직원 점심", note="", date="2026-10-29", payment="법인카드", ocr="";
        Long supply=10000L, vat=1000L;
        NtsBusinessLookup.Result lookup=lookup("01","","");
        Expense expense(){return new Expense(1L,date,"가상식당",category,purpose,11000L,supply,vat,payment,"검토자","",note,
                "CONFIRMED","OCR_RULES","",ocr,"synthetic.png",null,"synthetic.png","2026-10-29T03:00:00Z",0,"00001234",
                null,null,null,"NONE","123-45-67891","12:00","서울 강남구 가상로 123",lookup);}
    }
    static NtsBusinessLookup.Result lookup(String type,String changed,String invoice){
        return new NtsBusinessLookup.Result("1234567891","FOUND","01","계속사업자",type,"과세유형 "+type,"",changed,invoice,"","2026-10-29T04:00:00Z");
    }
    static String code(Fixture f){return f.expense().vatReview().code();}

    @Test void entertainmentIsNotACandidateEvenWithGeneralSupplier(){
        var f=new Fixture();f.category="접대비";
        assertEquals("NON_DEDUCTIBLE",code(f));assertEquals("VAT39_1_6",f.expense().vatReview().rule());
        assertEquals(11000L,f.expense().amount());
    }
    @Test void distinguishesSimpleInvoiceSupplierAndExemptSupplier(){
        var f=new Fixture();f.lookup=lookup("02","","");assertEquals("NON_DEDUCTIBLE",code(f));
        f.lookup=lookup("07","","");assertEquals("CANDIDATE",code(f));
        f.lookup=lookup("04","","");assertEquals("REVIEW",code(f));
        f.vat=null;assertEquals("NON_DEDUCTIBLE",code(f));
        f.lookup=lookup("05","","");assertEquals("REVIEW",code(f));
    }
    @ParameterizedTest @ValueSource(strings={"주유", "자동차 정비", "승용차 구입", "개인 생활비", "업무 무관 지출",
            "토지 취득", "면세사업 관련", "공통매입", "택시", "항공권", "미용", "해외 출장", "거래처 식사"})
    void flagsExpensesRequiringAdditionalFacts(String purpose){
        var f=new Fixture();f.purpose=purpose;assertEquals("REVIEW",code(f));
        assertFalse(f.expense().vatReview().reason().isBlank());
    }
    @Test void missingVatIsNotAssumedZeroAndMismatchedTaxIsNotACandidate(){
        var f=new Fixture();f.vat=null;assertEquals("REVIEW",code(f));
        f.vat=0L;f.supply=11000L;assertEquals("NO_VAT",code(f));
        f.vat=2000L;assertEquals("REVIEW",code(f));
    }
    @Test void unknownExpensesAndBookstoreMixturesAreNotDefaultCandidates(){
        var f=new Fixture();f.category="기타경비";assertEquals("REVIEW",code(f));
        f.category="도서인쇄비";assertEquals("REVIEW",code(f));
        f.vat=0L;f.supply=11000L;assertEquals("NO_VAT",code(f));
    }
    @Test void unknownHistoryDoesNotBlockCandidatesButExplicitTransitionDatesStillDo(){
        var f=new Fixture();f.date="2026-10-28";f.lookup=lookup("02","","");
        assertEquals("NON_DEDUCTIBLE",code(f));assertTrue(f.expense().vatReview().reason().contains("현재 조회 기준"));
        f.lookup=lookup("02","2026-10-29","");assertEquals("REVIEW",code(f));
        f.lookup=lookup("07","2026-07-01","2026-07-01");assertEquals("CANDIDATE",code(f));
        f.lookup=lookup("07","2026-07-01","2026-10-29");assertEquals("REVIEW",code(f));
    }
    @ParameterizedTest @ValueSource(strings={"01","07"})
    void previousDayAndOldReceiptsCanBeConditionalCandidates(String type){
        var f=new Fixture();f.lookup=lookup(type,"","");
        for(String date:List.of("2026-10-28","2026-07-01","2025-01-01")){
            f.date=date;var review=f.expense().vatReview();
            assertEquals("CANDIDATE",review.code());assertEquals("CANDIDATE_CURRENT_TYPE",review.rule());
            assertTrue(review.reason().contains("현재 조회 기준"));assertTrue(review.reason().contains("거래 당시 과세유형"));
        }
        f.date="2026-10-30";assertEquals("REVIEW",code(f));
        assertEquals("LOOKUP_BEFORE_TRANSACTION",f.expense().vatReview().rule());
    }
    @Test void pastDateDoesNotBypassMissingTaxOrVehicleChecks(){
        var f=new Fixture();f.date="2026-10-28";f.vat=null;
        assertEquals("TAX_UNKNOWN",f.expense().vatReview().rule());
        f.vat=1000L;f.purpose="주유";assertEquals("VAT39_1_5",f.expense().vatReview().rule());
        f.category="접대비";assertEquals("NON_DEDUCTIBLE",code(f));
    }
    @Test void unqueriedMismatchedAndClosedBusinessesRemainForReview(){
        var f=new Fixture();f.lookup=null;assertEquals("REVIEW",code(f));
        f.lookup=new NtsBusinessLookup.Result("2208162517","FOUND","01","계속사업자","01","일반","","","","","2026-10-29T04:00:00Z");
        assertEquals("REVIEW",code(f));
        f.lookup=new NtsBusinessLookup.Result("1234567891","FOUND","03","폐업자","01","일반","2026-10-29","","","","2026-10-29T04:00:00Z");
        assertEquals("REVIEW",code(f));
    }
    @Test void personalCardIsNotPrivateExpenseAndCashRequiresEvidence(){
        var f=new Fixture();f.payment="개인카드";assertEquals("CANDIDATE",code(f));
        f.payment="현금";assertEquals("REVIEW",code(f));
        f.ocr="현금영수증\n지출증빙";assertEquals("CANDIDATE",code(f));
        f.payment="계좌이체";f.ocr="전자세금계산서";assertEquals("CANDIDATE",code(f));
    }
    @Test void serializesReadOnlyReviewAndPreservesSnapshot(){
        var e=new Fixture().expense();var json=tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals("CANDIDATE",json.readTree(json.writeValueAsString(e)).path("vatReview").path("code").asText());
        assertEquals(e.vatReview(),VatDeductionReview.restored(VatDeductionReview.stored(e.vatReview())));
        assertEquals("REVIEW",VatDeductionReview.legacy(null).code());
    }
    @Test void frontColumnsColorsAndTotalsUseTheCorrectFields()throws Exception{
        var candidate=new Fixture();var blocked=new Fixture();blocked.category="접대비";
        var unknown=new Fixture();unknown.lookup=null;var zero=new Fixture();zero.vat=0L;zero.supply=11000L;
        var rows=List.of(candidate.expense(),blocked.expense(),unknown.expense(),zero.expense());
        assertEquals(3,ExcelExporter.column("부가세 공제 검토"));assertEquals(4,ExcelExporter.column("공제 검토 사유"));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new ExcelExporter().export(rows,false)))){
            var sheet=book.getSheet("지출장부");
            assertEquals("공제 후보",sheet.getRow(4).getCell(3).getStringCellValue());
            assertEquals("불공제 검토",sheet.getRow(5).getCell(3).getStringCellValue());
            assertEquals("확인 필요",sheet.getRow(6).getCell(3).getStringCellValue());
            assertEquals("세액 없음",sheet.getRow(7).getCell(3).getStringCellValue());
            assertNotEquals(sheet.getRow(4).getCell(3).getCellStyle().getIndex(),sheet.getRow(5).getCell(3).getCellStyle().getIndex());
            assertEquals(44000,sheet.getRow(9).getCell(ExcelExporter.column("총금액(원)")).getNumericCellValue());
            var summary=book.getSheet("계정별집계");
            assertEquals(33000,summary.getRow(4).getCell(2).getNumericCellValue());
            assertEquals(11000,summary.getRow(4+ReceiptAnalyzer.CATEGORIES.indexOf("접대비")).getCell(2).getNumericCellValue());
            assertEquals("거래처주소",sheet.getRow(3).getCell(ExcelExporter.HEADERS.size()-1).getStringCellValue());
            assertNotNull(book.getSheet("부가세검토안내"));
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new ExcelExporter().export(List.of(),true)))){
            assertEquals("@",book.getSheet("지출장부").getRow(4).getCell(ExcelExporter.column("승인번호")).getCellStyle().getDataFormatString());
        }
    }
}
