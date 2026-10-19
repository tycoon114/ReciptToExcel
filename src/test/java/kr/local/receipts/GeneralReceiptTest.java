package kr.local.receipts;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.nio.file.Files;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.List;

class GeneralReceiptTest {
    @Test
    void headerBrandAndSeparateBranchBeatBranchSubstringAndQrDebris() {
        var a = ReceiptTextParser.parse("回•%눔들回\nBURGER KING 들\n발으巫요 回\n[판매영수증]\n수서점\n"
                + "주문번호:818\n2026-10-17\n와퍼 라지세트 1 11,100\n결제금액 11,100", "");
        assertEquals("BURGER KING(수서점)", a.vendor());
        assertEquals("복리후생비", a.category());
        assertEquals(11100L, a.amount());
        assertEquals("", a.description());
        assertEquals("", a.note());
    }

    @Test
    void independentBranchesAndOtherBrandsDoNotImplyBookPurchases() {
        for (String branch : List.of("수서점", "강서점", "서울역점", "분당점", "강남수서점", "부산강서점")) {
            var a = ReceiptTextParser.parse("HAPPY BURGER\n[판매영수증]\n" + branch
                    + "\n치즈버거 1 8,200\n합계:8,200", "");
            assertEquals("HAPPY BURGER(" + branch + ")", a.vendor());
            assertEquals("복리후생비", a.category());
            assertFalse(ReceiptTextParser.book("HAPPY BURGER\n" + branch + "\n치즈버거"));
        }
        assertTrue(ReceiptTextParser.book("작은서점\n합계 8,000"));
        assertTrue(ReceiptTextParser.book("서점\nISBN 9780000000000"));
        assertEquals("접대비", ReceiptTextParser.parse("HAPPY BURGER\n와퍼", "거래처 식사").category());
    }

    @Test
    void retailHeaderExcludesPhoneBusinessIdAndAddressAndUsesPurchasedItems() {
        var a = ReceiptTextParser.parse("example 메트로 수서점 (02)123-4567\n123-45-67890 대표자\n"
                + "서울 강남구 예시로 280\n2026-10-15\n일회용 수세미 50매 1 6,980\n"
                + "종이접시 2,480\n결제대상금액 32,540", "");
        assertEquals("example 메트로 수서점", a.vendor());
        assertEquals("소모품비", a.category());
        assertEquals(32540L, a.amount());
        assertEquals("", ReceiptTextParser.parse("123-45-67890 홍길동\n서울 강남구 예시로 280", "").vendor());
    }

    @ParameterizedTest
    @ValueSource(strings = {"합계 : 61,000", "합   계::61,000", "합계：６１，０００원", "합 계\n61,000",
            "합계 : 6 61,000", "결 제 대 상 금 액 : 61,000", "[카 드 매 출] 61,000",
            "카드결제(IC) 일시불 / 61,000", "가드결지l(IC) 일시불 / 61,000", "신용카드 ] 61,000"})
    void totalAndPaymentLayoutsWorkIndependentlyOfMerchantAndAmount(String total) {
        var a = ReceiptTextParser.parse("예시상점\n20261016 12:49:53\n상품 1 90,000\n" + total
                + "\n공급가:55,455\n부가세:5,545\n승인번호:87654321\n포인트 200,000", "");
        assertEquals(61000L, a.amount());
        assertEquals("2026-10-16", a.date());
        assertEquals(55455L, a.supply());
        assertEquals(5545L, a.vat());
    }

    @ParameterizedTest
    @ValueSource(strings = {"카드번호 4140-1234-5678-9012", "카드 41401234********", "카드 결제 1 11.1예",
            "결제금액 123.45", "합계 할인금액 10,000", "카드결제 12,34", "카드결제 4140 1234 5678 9012", "카드\n55,455\n5,545"})
    void identifiersAndCorruptAmountsAreNeverSilentlyTruncated(String text) {
        assertNull(ReceiptTextParser.parse(text, "").amount());
    }

    @Test
    void repeatedCardSectionsMustNotReadFollowingTaxAsAnotherPayment() {
        var a = ReceiptTextParser.parse("예시식당\n합계:61,000\n카드::\n55,455\n5,545\n"
                + "공급가:55,455\n부가세:5,545\n[카드종류] 테스트카드\n[승인번호] 87654321\n"
                + "[카드매출]61,000", "");
        assertEquals(61000L, a.amount());
        assertFalse(a.message().contains("서로 다릅니다"));
        assertNull(ReceiptTextParser.parse("예시마트\n부가세\n32,540\n결제대상금액 32,540", "").vat());
    }

    @Test
    void rowRecoveryUsesThePrintedLabeledBandAndKeepsEvidenceUnmodified(@TempDir Path temp) throws Exception {
        Path photo = temp.resolve("receipt.png");
        ImageIO.write(new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB), "png", photo.toFile());
        byte[] before = Files.readAllBytes(photo);
        var mapper = JsonMapper.builder().build();
        var nativeResult = new WindowsOcr.Result("예시식당\n2026-10-16\n[카드매출]\n승인번호 87654321", "test",
                List.of(), List.of(new WindowsOcr.Row("예시식당", 10, 30),
                        new WindowsOcr.Row("2026-10-16", 40, 60),
                        new WindowsOcr.Row("[카드매출]", 300, 340),
                        new WindowsOcr.Row("승인번호 87654321", 400, 430)));
        var ocr = new WindowsOcr(mapper) {
            @Override protected Result readNative(Path p) {
                return p.equals(photo) ? nativeResult : new Result("61,000", "crop");
            }
        };
        var a = new ReceiptAnalyzer(ocr, mapper, false, "http://127.0.0.1:11434", "unused").analyze(photo, "");
        assertEquals(61000L, a.amount());
        assertTrue(a.message().contains("숫자 영역"));
        assertArrayEquals(before, Files.readAllBytes(photo));

        var ambiguous = new WindowsOcr(mapper) {
            @Override protected Result readNative(Path p) {
                return p.equals(photo) ? nativeResult : new Result("61,000\n55,455", "crop");
            }
        };
        var b = new ReceiptAnalyzer(ambiguous, mapper, false, "http://127.0.0.1:11434", "unused").analyze(photo, "");
        assertNull(b.amount(), "An adjacent subtotal in the crop must not be silently selected");
    }

    @Test
    void identicalLabelsOnDifferentRowsRetainTheirConflictingAmounts(@TempDir Path temp) throws Exception {
        Path photo = temp.resolve("receipt.png");
        ImageIO.write(new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB), "png", photo.toFile());
        var mapper = JsonMapper.builder().build();
        var nativeResult = new WindowsOcr.Result("예시식당\n2026-10-16\n[결제금액]\n[결제금액]", "test", List.of(),
                List.of(new WindowsOcr.Row("예시식당", 10, 30), new WindowsOcr.Row("2026-10-16", 40, 60),
                        new WindowsOcr.Row("[결제금액]", 300, 340), new WindowsOcr.Row("[결제금액]", 400, 440)));
        var ocr = new WindowsOcr(mapper) {
            int crops = 0;
            @Override protected Result readNative(Path p) {
                return p.equals(photo) ? nativeResult : new Result(++crops == 1 ? "61,000" : "51,000", "crop");
            }
        };
        var a = new ReceiptAnalyzer(ocr, mapper, false, "http://127.0.0.1:11434", "unused").analyze(photo, "");
        assertNull(a.amount());
        assertTrue(a.message().contains("서로 다릅니다"));
    }

    @Test
    void aCleanScaledPassBeatsCorruptedSingleDigitFromAnEarlierOcrPass() {
        var mapper = JsonMapper.builder().build();
        var ocr = new WindowsOcr(mapper) {
            @Override public Result read(Path path) {
                return new Result("예시식당\n2026-10-16\n카드결제 1 61.0예", "test",
                        List.of("예시식당\n2026-10-16\n[카드매출] 61,000\n공급가:55,455\n부가세:5,545"));
            }
        };
        var a = new ReceiptAnalyzer(ocr, mapper, false, "http://127.0.0.1:11434", "unused")
                .analyze(Path.of("unused"), "");
        assertEquals(61000L, a.amount());
        assertEquals("복리후생비", a.category());
    }
}
