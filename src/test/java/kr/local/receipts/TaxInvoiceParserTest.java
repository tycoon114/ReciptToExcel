package kr.local.receipts;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 실물 한 장에만 맞춘 값 대신 공급자/매입자 역할·열 위치·소수 단가·세액 누락을 검증합니다. */
class TaxInvoiceParserTest {
    static final String TEXT="""
            세 금 계 산 서 (공급받는 자 보관용)
            일련번호: 00001234
            공급자
            등록번호: 123-45-67891
            상호(법인명): 가상공급상사
            사업장 주소: 서울 강남구 예시로 123
            공급받는 자
            등록번호: 220-81-62517
            상호(법인명): 가상매입회사
            작성일자: 2026-11-02
            공급가액: 150,000
            세액: 15,000
            품목 | 규격 | 수량 | 단가 | 공급가액 | 세액
            복사용지 | A4 | 2 | 50000 | 100000 | 10000
            인쇄용역 | 출력 | 2.5 | 20000 | 50000 | 5000
            합계금액: 165,000
            """;
    @Test void explicitRolesMultipleItemsAndDecimalsArePreserved() {
        var a=TaxInvoiceParser.parse(TEXT,"사무용품 구입",List.of());
        assertNotNull(a);assertEquals("가상공급상사",a.vendor());assertEquals("123-45-67891",a.businessNumber());
        assertEquals("서울 강남구 예시로 123",a.vendorAddress());assertEquals("2026-11-02",a.date());
        assertEquals(165000,a.amount());assertEquals(150000,a.supply());assertEquals(15000,a.vat());
        assertEquals("00001234",a.taxInvoice().issueNumber());assertEquals("",a.approvalNumber());
        assertEquals("220-81-62517",a.taxInvoice().buyerBusinessNumber());assertEquals("가상매입회사",a.taxInvoice().buyerName());
        assertEquals(2,a.taxInvoice().items().size());assertEquals(new BigDecimal("2.5"),a.taxInvoice().items().get(1).quantity());
        assertEquals(new BigDecimal("50000"),a.taxInvoice().items().get(0).unitPrice());assertEquals("",TaxInvoice.mismatch(a.taxInvoice(),a.supply(),a.vat()));
        assertFalse(a.taxInvoice().warning().contains("음수"),"날짜와 사업자번호의 하이픈은 음수 표시가 아닙니다.");
    }
    @Test void missingTaxNeverBecomesZeroOrTenPercent() {
        var a=TaxInvoiceParser.parse(TEXT.replace("세액: 15,000\n",""),"사무용품",List.of());
        assertNull(a.vat());assertEquals(165000,a.amount());
    }
    @Test void onlyTheExplicitSupplyAndTaxMayBeAddedForTotal() {
        var a=TaxInvoiceParser.parse(TEXT.replace("합계금액: 165,000\n",""),"",List.of());
        assertEquals(165000,a.amount());assertTrue(a.message().contains("합산한 값"));
    }
    @Test void ordinaryReceiptMentionsDoNotBecomeInvoices() {
        assertNull(TaxInvoiceParser.parse("가상식당\n합계 11000\n세금계산서 발행 가능합니다.","",List.of()));
        assertNull(TaxInvoiceParser.parse("거래명세서\n공급가액 10000\n세액 1000","",List.of()));
    }
    @Test void twoCopiesAreNotAddedTogether() {
        var a=TaxInvoiceParser.parse(TEXT+"\n"+TEXT,"",List.of());
        assertNull(a.amount());assertNull(a.supply());assertTrue(a.taxInvoice().items().isEmpty());assertTrue(a.message().contains("여러 장"));
    }
    @Test void missingSupplierNeverUsesBuyerBusinessNumber() {
        var a=TaxInvoiceParser.parse(TEXT.replace("등록번호: 123-45-67891\n",""),"",List.of());
        assertEquals("",a.businessNumber());assertEquals("220-81-62517",a.taxInvoice().buyerBusinessNumber());
    }
    @Test void rowCoordinatesSeparateTheTwoPartiesAndReadColumnValues() {
        var rows=layout();String text="세금계산서(공급받는 자 보관용)\n"+rows.stream().map(WindowsOcr.Row::text).collect(java.util.stream.Collectors.joining("\n"));
        var a=TaxInvoiceParser.parse(text,"사무용품 구매",rows);
        assertEquals("가상공급상사",a.vendor());assertEquals("123-45-67891",a.businessNumber());
        assertEquals("가상매입회사",a.taxInvoice().buyerName());assertEquals("220-81-62517",a.taxInvoice().buyerBusinessNumber());
        assertEquals("2026-11-02",a.date());assertEquals(165000,a.amount());assertEquals(15000,a.vat());
        assertEquals(2,a.taxInvoice().items().size());assertEquals("복사용지",a.taxInvoice().items().get(0).name());
        assertEquals(100000,a.taxInvoice().items().get(0).supplyAmount());
    }
    @Test void separatedDigitsAreNotCutAtAColumnBoundary() {
        var rows=new ArrayList<>(layout());var data=rows.get(4);var words=new ArrayList<WindowsOcr.Word>();
        words.add(data.words().get(0));
        // 공급가액 제목과 세액 제목 사이의 경계 근처에 숫자가 한 자리씩 배치된 종이 양식을 재현합니다.
        String amount="150000";for(int i=0;i<amount.length();i++)words.add(new WindowsOcr.Word(amount.substring(i,i+1),254+i*7,120,259+i*7,130));
        words.add(data.words().get(2));rows.set(4,new WindowsOcr.Row("2026 11 02 1 5 0 0 0 0 15,000",120,130,words));
        String text="세금계산서\n"+rows.stream().map(WindowsOcr.Row::text).collect(java.util.stream.Collectors.joining("\n"));
        var a=TaxInvoiceParser.parse(text,"",rows);assertEquals(150000,a.supply());assertEquals(15000,a.vat());
    }
    @Test void ocrMayReturnEveryCellAsAnIndependentLine() {
        var fragments=layout().stream().flatMap(r->r.words().stream()).map(w->new WindowsOcr.Row(w.text(),w.top(),w.bottom(),List.of(w))).toList();
        String text="세금계산서\n"+fragments.stream().map(WindowsOcr.Row::text).collect(java.util.stream.Collectors.joining("\n"));
        var a=TaxInvoiceParser.parse(text,"",fragments);
        assertEquals("가상공급상사",a.vendor());assertEquals("220-81-62517",a.taxInvoice().buyerBusinessNumber());
        assertEquals(150000,a.supply());assertEquals(15000,a.vat());assertEquals(2,a.taxInvoice().items().size());
    }
    static List<WindowsOcr.Row> layout() {
        return List.of(row(40,"등록번호",20,"123-45-67891",90,"등록번호",310,"220-81-62517",380),
                row(60,"상호(법인명)",20,"가상공급상사",100,"상호(법인명)",310,"가상매입회사",390),
                row(80,"사업장주소",20,"서울강남구예시로123",100,"사업장주소",310,"서울서초구가상로1",390),
                row(100,"작성",20,"공급가액",170,"세액",390,"비고",530),
                row(120,"2026 11 02",20,"150,000",170,"15,000",390),
                row(150,"품목",20,"규격",170,"수량",240,"단가",300,"공급가액",390,"세액",490,"비고",570),
                row(170,"복사용지",20,"A4",170,"2",240,"50,000",300,"100,000",390,"10,000",490),
                row(190,"인쇄용역",20,"출력",170,"2.5",240,"20,000",300,"50,000",390,"5,000",490),
                row(220,"합계금액",20,"현금",170,"수표",300,"어음",390,"외상미수금",490),row(240,"165,000",20));
    }
    static WindowsOcr.Row row(double y,Object... cells) {
        var words=new ArrayList<WindowsOcr.Word>();var text=new ArrayList<String>();
        for(int i=0;i<cells.length;i+=2){String s=(String)cells[i];double x=((Number)cells[i+1]).doubleValue();words.add(new WindowsOcr.Word(s,x,y,x+s.length()*5,y+10));text.add(s);}
        return new WindowsOcr.Row(String.join(" ",text),y,y+10,words);
    }
}
