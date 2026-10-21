package kr.local.receipts;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.nio.file.*;
import java.util.Map;

/** 문자 파싱과 선택적 로컬 AI 연결 형식을 검증합니다. 실제 AI 모델 대신 로컬 HTTP 응답을 사용합니다. */
class ReceiptAnalyzerTest {
    @Test
    // 공백이 많은 상호·합계·세액 표기를 읽고, 표기가 없는 세액은 추정하지 않는지 확인합니다.
    void spacedPrintedLabelsAndExplicitMerchantAreRecognized() {
        var mapper = JsonMapper.builder().build();
        var analyzer =
                new ReceiptAnalyzer(
                        new WindowsOcr(mapper),
                        mapper,
                        false,
                        "http://127.0.0.1:11434",
                        "gemma3:4b");
        var a =
                analyzer.fromText(
                        "고객용\n"
                                + "[매 장 명] 테스트 불고기\n"
                                + "[매출일] 2026-10-12\n"
                                + "합 계 금 액 53,000\n"
                                + "부가세 과세물품기액 48,184\n"
                                + "부 가 세 4,816\n"
                                + "신용 카드",
                        "");
        assertEquals("테스트 불고기", a.vendor());
        assertEquals("2026-10-12", a.date());
        assertEquals(53000L, a.amount());
        assertEquals(48184L, a.supply());
        assertEquals(4816L, a.vat());
        assertEquals("복리후생비", a.category());
        var missing = analyzer.fromText("합 계 금 액 53,000\n부가세 과세물품가액 48,184\n4,816", "");
        assertNull(
                missing.vat(),
                "An unlabeled number must not be assumed to be VAT or calculated from the total");
    }

    @Test
    // 날짜·금액·업무 목적을 읽되 잘못된 날짜와 승인번호를 거래 정보로 채택하지 않는지 확인합니다.
    void explicitReceiptValuesAreParsedWithoutInventingTax() {
        var mapper = JsonMapper.builder().build();
        var analyzer =
                new ReceiptAnalyzer(
                        new WindowsOcr(mapper),
                        mapper,
                        false,
                        "http://127.0.0.1:11434",
                        "gemma3:4b");
        var a =
                analyzer.fromText(
                        "우리식당\n2026-10-19\n합계 22,000원\n공급가액 20,000원\n부가세 2,000원\n신용카드", "직원 점심");
        assertEquals("2026-10-19", a.date());
        assertEquals(22000L, a.amount());
        assertEquals(20000L, a.supply());
        assertEquals(2000L, a.vat());
        assertEquals("복리후생비", a.category());
        var incomplete = analyzer.fromText("우리식당\n2026-02-31\n승인번호 98765432\n단가 9000원", "");
        assertNull(incomplete.date());
        assertNull(incomplete.amount());
        assertNull(incomplete.vat());
        assertEquals("접대비", ReceiptAnalyzer.classify("식당 거래처 식사"));
        assertEquals("회의비", ReceiptAnalyzer.classify("점심 회의"));
        assertEquals("미분류", ReceiptAnalyzer.classify("내용 불명"));
    }

    @Test
    // 사진과 JSON 응답 요청을 보내는지, AI가 반환한 문자열 세액을 숫자로 강제하지 않는지 확인합니다.
    void localAiUsesVisionPayloadAndRejectsInventedStringAmounts(@TempDir Path temp)
            throws Exception {
        var mapper = JsonMapper.builder().build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/generate",
                exchange -> {
                    var payload = mapper.readTree(exchange.getRequestBody().readAllBytes());
                    assertEquals("gemma3:4b", payload.path("model").asText());
                    assertTrue(payload.path("images").get(0).asText().length() > 0);
                    assertFalse(payload.path("stream").asBoolean());
                    byte[] response =
                            mapper.writeValueAsBytes(
                                    Map.of(
                                            "response",
                                            "{\"date\":\"2026-10-19\",\"vendor\":\"테스트식당\",\"category\":\"복리후생비\",\"amount\":9000,\"vatAmount\":\"818\",\"paymentMethod\":\"카드\"}"));
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            Path image = temp.resolve("image.png");
            Files.write(image, new byte[] {1, 2, 3});
            var analyzer =
                    new ReceiptAnalyzer(
                            new WindowsOcr(mapper),
                            mapper,
                            true,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "gemma3:4b");
            var a = analyzer.analyze(image, "점심");
            assertEquals("OLLAMA", a.source());
            assertEquals(9000L, a.amount());
            assertNull(a.vat());
        } finally {
            server.stop(0);
        }
    }
}
