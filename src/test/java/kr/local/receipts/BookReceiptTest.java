package kr.local.receipts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;

/** 도서 영수증의 권수·정가·실결제액·포인트 혼동과 사업자번호 보정의 재발을 검사합니다. */
class BookReceiptTest {
    @ParameterizedTest
    @CsvSource({"1,11900", "1,25700", "1,38000", "2,41900", "2,43700"})
    // 합계 행의 권수와 가격을 붙여 잘못된 큰 금액을 만들지 않는지 여러 금액으로 확인합니다.
    void summaryQuantityIsNeverConcatenatedWithThePrice(int quantity, long amount) {
        String digits = String.format(Locale.US, "%,d", amount);
        var a =
                ReceiptTextParser.parse(
                        "상호 : 예시문고* 대표 : 예시\n"
                                + "일자 : 2024/06/07\n"
                                + "도서명 수량 정가 금액\n"
                                + "교재 1 60,000(5) 57,000\n"
                                + "카 드 결 제 : "
                                + digits
                                + "\n합 계 : "
                                + quantity
                                + " "
                                + digits,
                        "");
        assertEquals(amount, a.amount());
        assertEquals("예시문고", a.vendor());
        assertEquals("도서인쇄비", a.category());
        assertEquals("도서 구입", a.description());
    }

    @Test
    // 정가·과세 소계·포인트보다 명시된 최종 결제액을 우선하는지 확인합니다.
    void discountedPaymentBeatsListPriceExemptTaxSubtotalAndPoints() {
        var a =
                ReceiptTextParser.parse(
                        "KYOBO 교보문고\n"
                                + "도서 정가 60,000\n"
                                + "결 제 금 액 50,000\n"
                                + "합계 50,000\n"
                                + "부가세 면세 합계금액 50,000\n"
                                + "부가세 과세 합계금액 0\n"
                                + "고객 포인트 100,000",
                        "");
        assertEquals(50000L, a.amount());
        assertNull(a.vat());
    }

    @Test
    // 반복 금액 보완은 유일한 요약 값일 때만 제안하고 원본 대조 근거를 안내하는지 확인합니다.
    void fadedBookSummaryNeedsUniqueRepetitionAndShowsItsBasis() {
        var a =
                ReceiptTextParser.parse(
                        "교보문고\n도서명\n*9780000000000 도서\n2\n50,000\n0\n50,000\n고객 포인트 : 1,000", "");
        assertEquals(50000L, a.amount());
        assertTrue(a.message().contains("반복 금액"));
        assertNull(
                ReceiptTextParser.parse("교보문고\n도서 1 90,000\n1,000\n포인트 1,000", "").amount(),
                "Never use the biggest item or a single unlabeled value");
        assertNull(
                ReceiptTextParser.parse("교보문고\n10,000\n10,000\n20,000\n20,000", "").amount(),
                "Ambiguous repeated values require human review");
    }

    @Test
    // 짧은 OCR 잡음이 있어도 상품 가격 하나만으로 합계를 확정하지 않는지 확인합니다.
    void shortOcrDebrisMayPrecedeARepeatedSummaryButDoesNotMakeAnItemATotal() {
        assertEquals(
                50000L,
                ReceiptTextParser.parse("교보문고\n도서명\nOli 50,000\n0\n승인정보\n50,000\n고객 포인트 1,000", "")
                        .amount());
        assertNull(ReceiptTextParser.parse("교보문고\n도서 1 18,000\n포인트 1,000", "").amount());
    }
    // 포인트 잔액의 반복을 도서 결제 총액으로 오해하지 않는지 확인합니다.
    @Test void repeatedPointBalanceDoesNotBecomeTheBookTotal() {
        assertNull(ReceiptTextParser.parse("교보문고\n포인트 1,000\n1,000","").amount());
    }

    @Test
    // 지점명과 괄호 오인식 때문에 음식점을 도서로 분류하지 않는지 확인합니다.
    void branchLocationAndBracketOcrDoNotTurnMealsIntoBooks() {
        var a =
                ReceiptTextParser.parse(
                        "[매장명」 광양불고기(수서점)\n[주 소] 서울 강남구\n2026-10-12\n합계 금액 53,000", "");
        assertEquals("광양불고기(수서점)", a.vendor());
        assertEquals("복리후생비", a.category());
        assertEquals(53000L, a.amount());
        assertFalse(ReceiptTextParser.book("정가 5,000\n가게(수서점)"));
        assertTrue(ReceiptTextParser.book("교보문고(수서점)\n도서명"));
    }

    @Test
    // 짧은 결제 표기는 지원하면서 권수·포인트·깨진 소수는 총액에서 제외하는지 확인합니다.
    void truncatedPaymentLabelStillDoesNotReadTheQuantityOrPoints() {
        var a = ReceiptTextParser.parse("상호:예시문고\n도서명\n결 제 38,000\n1 383000\n적립 포인트 결제 1,000", "");
        assertEquals(38000L, a.amount());
        assertNull(ReceiptTextParser.parse("도서명\n합계 : 1", "").amount());
        assertNull(
                ReceiptTextParser.parse("결제금액 123.45", "").amount(),
                "Decimal fragments must not become a smaller integer total");
    }

    @Test
    // 같은 우선순위의 결제 금액이 다르면 빈 금액과 충돌 안내를 반환하는지 확인합니다.
    void conflictingFinalPaymentsAreNotSilentlySelected() {
        var a = ReceiptTextParser.parse("상호:예시문고\n카드결제 10,000\n승인금액 20,000", "");
        assertNull(a.amount());
        assertTrue(a.message().contains("서로 다릅니다"));
    }

    @Test
    // 흐린 앞자리 보완은 별도의 권수·합계와 뒷자리가 맞을 때만 허용하는지 확인합니다.
    void damagedLeadingPaymentDigitNeedsAnIndependentQuantitySummary() {
        assertEquals(
                41900L, ReceiptTextParser.parse("도서명\n가 드 결 제 : 식1,900\n2 41 ,900", "").amount());
        assertNull(ReceiptTextParser.parse("도서명\n가 드 결 제 : 식1,900\n2 42,900", "").amount());
        assertNull(ReceiptTextParser.parse("도서명\n가 드 결 제 : 식1,900", "").amount());
    }

    @Test
    // 확정 상호가 재실행 후에도 같은 사업자번호에만 적용되고 모호한 번호는 학습하지 않는지 확인합니다.
    void confirmedBusinessNumberCorrectsFutureNamesAndPersists(@TempDir Path data)
            throws Exception {
        var mapper = JsonMapper.builder().build();
        var directory = new MerchantDirectory(mapper, data.toString());
        directory.remember("사업자등록번호 : 123-45-67890", "예시문고");
        var reloaded = new MerchantDirectory(mapper, data.toString());
        assertEquals("예시문고", reloaded.resolve("시엽자번호: 123--45-67890\n상호:기금문-고", "오인식"));
        assertEquals("원본", reloaded.resolve("사업자등록번호:123-45-67891", "원본"));
        directory.remember("사업자 123-45-67890\n사업자 987-65-43210", "혼합");
        assertEquals(
                "예시문고",
                directory.resolve("123-45-67890", "원본"),
                "Multiple business IDs must not contaminate the correction dictionary");
    }

    @Test
    // 다른 OCR 후보가 읽은 사업자번호로 선택한 후보의 상호를 보완할 수 있는지 확인합니다.
    void businessIdentityFromAnotherOcrPassCorrectsTheSelectedPass(@TempDir Path data)
            throws Exception {
        var mapper = JsonMapper.builder().build();
        var directory = new MerchantDirectory(mapper, data.toString());
        directory.remember("사업자등록번호:123-45-67890", "예시문고");
        var result =
                new WindowsOcr.Result(
                        "123--45-67890\n도서명\n11,900\n1 11,900",
                        "test",
                        List.of("상호:기금문고\n2024/06/07\n도서명\n가 드 결 제 : 11,900\n합계 1 11,900"));
        var fake =
                new WindowsOcr(mapper) {
                    @Override
                    public Result read(Path p) {
                        return result;
                    }
                };
        var a =
                new ReceiptAnalyzer(
                                fake,
                                mapper,
                                false,
                                "http://127.0.0.1:11434",
                                "gemma3:4b",
                                directory)
                        .analyze(data.resolve("unused"), "");
        assertEquals("예시문고", a.vendor());
        assertEquals(11900L, a.amount());
        assertEquals("도서인쇄비", a.category());
    }

    @Test
    // 도서인쇄비가 엑셀의 분류별 금액과 전체 합계에 모두 포함되는지 확인합니다.
    void bookletExportIncludesItsOwnSummaryAndTheGrandTotal() throws Exception {
        var e =
                new Expense(
                        1L,
                        "2024-06-07",
                        "예시문고",
                        "도서인쇄비",
                        "업무 도서",
                        43700L,
                        null,
                        null,
                        "카드",
                        "",
                        "",
                        "",
                        "CONFIRMED",
                        "OCR_RULES",
                        "",
                        "",
                        null,
                        null,
                        "",
                        "",
                        0);
        byte[] bytes = new ExcelExporter().export(List.of(e), false);
        try (var book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(bytes))) {
            var summary = book.getSheet("계정별집계");
            assertEquals(
                    43700,
                    summary.getRow(4 + ReceiptAnalyzer.CATEGORIES.indexOf("도서인쇄비"))
                            .getCell(2)
                            .getNumericCellValue());
            assertEquals(
                    43700,
                    summary.getRow(ReceiptAnalyzer.CATEGORIES.size() + 5)
                            .getCell(2)
                            .getNumericCellValue());
        }
    }
}
