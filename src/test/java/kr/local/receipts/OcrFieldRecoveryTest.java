package kr.local.receipts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** 스캔의 괄호 잡음·상호와 사업자번호 혼합·부분 OCR 충돌이 다시 잘못된 값으로 저장되지 않는지 검증합니다. */
class OcrFieldRecoveryTest {
    @ParameterizedTest
    @ValueSource(strings = {"예시밥집 / 123-45-67890 / 홍길동", "테스트상점 123 - 45 - 67890 대표:김예시",
            "매장명:예시카페 / 123-45-67890 / 이예시"})
    void merchantBeforeBusinessNumberSurvivesEvenWithoutANameLabel(String header) {
        var result = ReceiptTextParser.parse("[ 영 수 증]\n" + header
                + "\n서울 강남구 예시로 280\n2026-10-25\n합계 11000", "");
        String expected = header.startsWith("예시밥집") ? "예시밥집"
                : header.startsWith("테스트") ? "테스트상점" : "예시카페";
        assertEquals(expected, result.vendor());
        assertEquals("", ReceiptTextParser.parse("123-45-67890 / 홍길동\n서울 강남구 예시로 280", "").vendor());
    }

    @Test
    void qrDebrisBesideAnEnglishBrandDoesNotMakeTheTelephoneLabelTheVendor() {
        var a = ReceiptTextParser.parse("HAPPY BURGER 부수수製\n탭 回\n[판매영수증]\n동네점\n"
                + "매장주소:서울 강남구 예시로 280\n사업자번호:123-45-67890 대표:홍길동\n"
                + "* 매장 : 02-1234-5678\n합계 11900", "");
        assertEquals("HAPPY BURGER(동네점)", a.vendor());
        assertEquals("복리후생비", a.category());
        assertEquals("", ReceiptTextParser.parse("* 매장 : 02-1234-5678", "").vendor());
    }

    private WindowsOcr.Result uncertainApproval() {
        return new WindowsOcr.Result("예시식당\n2026-10-25\n합계 11000\n[ 승인번호 1 00005678", "test", List.of(),
                List.of(new WindowsOcr.Row("예시식당", 10, 30), new WindowsOcr.Row("2026-10-25", 40, 60),
                        new WindowsOcr.Row("합계 11000", 80, 100),
                        new WindowsOcr.Row("[ 승인번호 1 00005678", 300, 340,
                                List.of(new WindowsOcr.Word("[", 10, 300, 20, 340),
                                        new WindowsOcr.Word("승인번호", 30, 300, 140, 340),
                                        new WindowsOcr.Word("1", 190, 300, 200, 340),
                                        new WindowsOcr.Word("00005678", 240, 300, 360, 340)))));
    }

    @Test
    void twoMatchingFocusedReadsRecoverTheNumberWithoutChangingTheEvidence(@TempDir Path temp) throws Exception {
        Path image = temp.resolve("receipt.png");
        ImageIO.write(new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        byte[] evidence = Files.readAllBytes(image);
        var mapper = JsonMapper.builder().build();
        int[] reads = {0};
        var ocr = new WindowsOcr(mapper) {
            @Override protected Result readNative(Path file) {
                reads[0]++;
                return file.equals(image) ? uncertainApproval() : new Result("00001234", "crop");
            }
        };
        var result = new ReceiptAnalyzer(ocr, mapper, false, "http://127.0.0.1:11434", "unused").analyze(image, "");
        assertEquals("00001234", result.approvalNumber());
        assertEquals(11000L, result.amount());
        assertEquals(3, reads[0], "일치하면 전체 이미지의 불필요한 추가 OCR를 생략합니다.");
        assertTrue(result.message().contains("승인번호 영역"));
        assertArrayEquals(evidence, Files.readAllBytes(image));
    }

    @Test
    void disagreeingFocusedReadsStayBlankEvenWhenAFullPassFindsOneOfThem(@TempDir Path temp) throws Exception {
        Path image = temp.resolve("receipt.png");
        ImageIO.write(new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        var mapper = JsonMapper.builder().build();
        var ocr = new WindowsOcr(mapper) {
            int reads;
            @Override protected Result readNative(Path file) {
                if (file.equals(image)) return uncertainApproval();
                if (file.getFileName().toString().startsWith("receipt-ocr-approval-"))
                    return new Result(++reads == 1 ? "00001234" : "00005678", "crop");
                return new Result("예시식당\n2026-10-25\n합계 11000\n승인번호 00001234", "scaled");
            }
        };
        var result = new ReceiptAnalyzer(ocr, mapper, false, "http://127.0.0.1:11434", "unused").analyze(image, "");
        assertEquals("", result.approvalNumber());
        assertTrue(result.message().contains("승인번호가 인식 방식마다"));
    }

    @Test
    void absentApprovalDoesNotTriggerPartialOcrAndInconsistentTaxesAreNotInvented(@TempDir Path temp) throws Exception {
        var mapper = JsonMapper.builder().build();
        int[] reads = {0};
        var ocr = new WindowsOcr(mapper) {
            @Override protected Result readNative(Path file) {
                reads[0]++;
                return new Result("예시식당\n2026-10-25\n합계 11000\n카드번호 00001234", "test");
            }
        };
        var result = ocr.read(temp.resolve("unused.png"));
        assertTrue(result.alternatives().isEmpty());
        assertEquals(1, reads[0], "승인번호가 없는 영수증에는 추가 인식 비용을 발생시키지 않습니다.");
        var invalid = ReceiptTextParser.parse("예시식당\n합계 11000\n공급가 10000\n부가세 11000", "");
        assertNull(invalid.supply()); assertNull(invalid.vat());
        assertTrue(invalid.message().contains("세액을 비웠습니다"));
        assertNull(ReceiptTextParser.parse("예시식당\n합계 11000\n부가세 11000", "").vat());
        assertEquals(1000L, ReceiptTextParser.parse("예시식당\n합계 11000\n공급가 10000\n부가세 1000", "").vat());
    }
}
