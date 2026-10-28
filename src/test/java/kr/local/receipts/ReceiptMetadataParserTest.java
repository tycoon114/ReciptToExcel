package kr.local.receipts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 합성 텍스트만 사용해 번호 오인식, 거래 시각과 영업시간 혼동, 주소 줄바꿈을 빠르게 검증합니다. */
class ReceiptMetadataParserTest {
    @ParameterizedTest
    @ValueSource(strings = {"사업자등록번호: 123-45-67891", "[사 업 자 번 호] 1234567891",
            "등록번호\n123 45 67891", "123–45–67891", "사업자번호 : １２３－４５－６７８９１"})
    void recognizesValidSellerNumber(String text) {
        assertEquals("123-45-67891", ReceiptMetadataParser.parse(text).businessNumber());
    }

    @ParameterizedTest
    @ValueSource(strings = {"사업자번호 123-45-67890", "승인번호 1234567891", "카드번호 1234-5678-9100-0000",
            "사업자번호 ***-**-67891", "사업자번호 000-00-00000"})
    void doesNotInventInvalidOrUnlabelledPlainNumbers(String text) {
        assertEquals("", ReceiptMetadataParser.parse(text).businessNumber());
    }

    @Test void leavesDifferentSellerCandidatesForReviewAndIgnoresBuyerNumber() {
        assertTrue(ReceiptMetadataParser.validBusinessNumber("220-81-62517"));
        var multiple = ReceiptMetadataParser.parse("사업자번호 123-45-67891\n사업자번호 220-81-62517");
        assertEquals("", multiple.businessNumber()); assertFalse(multiple.warning().isBlank());
        assertEquals("123-45-67891", ReceiptMetadataParser.parse(
                "판매점 사업자번호 123-45-67891\n공급받는자 사업자번호 220-81-62517").businessNumber());
    }

    @Test void identifiesTransactionTimeWithoutUsingOpeningOrPrintTime() {
        assertEquals("13:04:09", ReceiptMetadataParser.parse(
                "영업시간 09:00~22:00\n거래일시 2026-10-28 오후 1:04:09\n출력시간 14:00:00").receiptTime());
        assertEquals("00:03", ReceiptMetadataParser.parse("승인시간 오전 12:03").receiptTime());
        assertEquals("12:34:56", ReceiptMetadataParser.parse("결제시간 123456").receiptTime());
        assertEquals("12:34", ReceiptMetadataParser.parse("2026/10/28 12:34").receiptTime());
        assertEquals("12:34:56", ReceiptMetadataParser.parse("거래시간:\n12:34:56").receiptTime());
    }

    @ParameterizedTest
    @ValueSource(strings = {"영업시간 09:00~22:00", "거래시간 24:01", "승인시간 12:99", "합계 123456",
            "거래시간 12:34\n승인시간 13:34", "출력일시 2026-10-28 12:34"})
    void leavesInvalidOrConflictingTimeEmpty(String text) {
        assertEquals("", ReceiptMetadataParser.parse(text).receiptTime());
    }

    @Test void readsRoadAddressAndStopsBeforeOtherReceiptFields() {
        assertEquals("서울특별시 강남구 테스트로 123 2층", ReceiptMetadataParser.parse(
                "주 소: 서울특별시 강남구 테스트로 123\n2층\n전화: 02-1234-5678").vendorAddress());
        assertEquals("경기 성남시 분당구 예시로 12", ReceiptMetadataParser.parse(
                "경기 성남시 분당구 예시로 12 TEL:031-123-4567\n9000").vendorAddress());
        assertEquals("서울 강남구 테스트로 123", ReceiptMetadataParser.parse(
                "주소: 서울 강남구 테스트로\n123\n합계 9000").vendorAddress());
        assertEquals("", ReceiptMetadataParser.parse("배송주소: 서울 강남구 테스트로 123").vendorAddress());
        assertEquals("", ReceiptMetadataParser.parse("전화 02-1234-5678\n합계 9000").vendorAddress());
    }

    @Test void mergesMissingMetadataWithoutReplacingTheSelectedAmount() {
        var ocr = org.mockito.Mockito.mock(WindowsOcr.class);
        org.mockito.Mockito.when(ocr.read(org.mockito.ArgumentMatchers.any())).thenReturn(new WindowsOcr.Result(
                "상호: 가상식당\n2026-10-28\n합계 9000\n공급가액 8182\n부가세 818", "합성 OCR",
                java.util.List.of("사업자번호 123-45-67891\n거래시간 13:04:09\n주소: 서울 강남구 테스트로 123")));
        var analyzer = new ReceiptAnalyzer(ocr, tools.jackson.databind.json.JsonMapper.builder().build(), false,
                "http://127.0.0.1:11434", "gemma3:4b");
        var a = analyzer.analyze(java.nio.file.Path.of("synthetic.png"), "직원 점심");
        assertEquals(9000L, a.amount()); assertEquals("123-45-67891", a.businessNumber());
        assertEquals("13:04:09", a.receiptTime()); assertEquals("서울 강남구 테스트로 123", a.vendorAddress());
    }

    @Test void conflictingOcrPassesDoNotSilentlySelectAnIdentifier() {
        var ocr = org.mockito.Mockito.mock(WindowsOcr.class);
        org.mockito.Mockito.when(ocr.read(org.mockito.ArgumentMatchers.any())).thenReturn(new WindowsOcr.Result(
                "사업자번호 123-45-67891\n거래시간 13:04", "합성 OCR",
                java.util.List.of("사업자번호 220-81-62517\n거래시간 14:05")));
        var analyzer = new ReceiptAnalyzer(ocr, tools.jackson.databind.json.JsonMapper.builder().build(), false,
                "http://127.0.0.1:11434", "gemma3:4b");
        var a = analyzer.analyze(java.nio.file.Path.of("synthetic.png"), "");
        assertEquals("", a.businessNumber()); assertEquals("", a.receiptTime());
        assertTrue(a.message().contains("인식 방식마다 달라"));
    }
}
