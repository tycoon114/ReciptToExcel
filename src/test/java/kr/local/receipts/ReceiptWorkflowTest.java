package kr.local.receipts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiptWorkflowTest {
    @TempDir static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir", () -> data.toString());
        p.add("app.ollama-enabled", () -> "false");
        p.add("app.sheets-id", () -> "");
        p.add("app.sheets-credentials", () -> "");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean WindowsOcr ocr;

    @BeforeEach
    void defaultOcr() {
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result("", "OCR test double"));
    }

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<byte[]> call(
            String method, String path, Object body, String origin, boolean header)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (header) b.header("X-Receipt-Client", "local-app");
        if (origin != null) b.header("Origin", origin);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else
            b.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    JsonNode tree(HttpResponse<byte[]> r) {
        return json.readTree(r.body());
    }

    @Test
    void reviewConfirmationFilteringExportAndDeletion() throws Exception {
        var draft = call("POST", "/api/expenses", null, null, true);
        assertEquals(200, draft.statusCode());
        long id = tree(draft).path("id").asLong();
        var x = new LinkedHashMap<String, Object>();
        x.put("receiptDate", "2026-10-19");
        x.put("vendor", "검증식당");
        x.put("category", "복리후생비");
        x.put("description", "직원 점심");
        x.put("amount", 22000);
        x.put("supplyAmount", 20000);
        x.put("vatAmount", 2000);
        x.put("paymentMethod", "법인카드");
        x.put("ownerName", "테스트");
        x.put("department", "개발");
        x.put("note", "=HYPERLINK(\"https://example.com\",\"test\")");
        x.put("status", "CONFIRMED");
        x.put("version", 0);
        x.put("amount", 23000);
        assertEquals(400, call("PUT", "/api/expenses/" + id, x, null, true).statusCode());
        x.put("amount", 22000);
        assertEquals(200, call("PUT", "/api/expenses/" + id, x, null, true).statusCode());
        assertEquals(
                409,
                call("PUT", "/api/expenses/" + id, x, null, true).statusCode(),
                "Stale edit must not overwrite newer data");
        var list =
                tree(
                        call(
                                "GET",
                                "/api/expenses?month=2026-10&status=CONFIRMED&query="
                                        + URLEncoder.encode(
                                                "검증식당", java.nio.charset.StandardCharsets.UTF_8),
                                null,
                                null,
                                false));
        assertEquals(1, list.size());
        assertEquals(22000, list.get(0).path("amount").asInt());
        assertEquals(0, tree(call("GET", "/api/expenses?month=2026-09", null, null, false)).size());
        var export = call("GET", "/api/export.xlsx", null, null, false);
        assertEquals(200, export.statusCode());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(export.body()))) {
            assertEquals(2, book.getNumberOfSheets());
            var ledger = book.getSheet("지출장부");
            assertEquals(22000, ledger.getRow(4).getCell(5).getNumericCellValue());
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.STRING,
                    ledger.getRow(4).getCell(11).getCellType(),
                    "Receipt text must never become a formula");
            assertEquals(
                    22000,
                    book.getSheet("계정별집계")
                            .getRow(ReceiptAnalyzer.CATEGORIES.size() + 5)
                            .getCell(2)
                            .getNumericCellValue());
            assertNotNull(ledger.getPaneInformation());
            assertFalse(ledger.getDataValidations().isEmpty());
        }
        x.put("version", 1);
        x.put("status", "REVIEW");
        assertEquals(200, call("PUT", "/api/expenses/" + id, x, null, true).statusCode());
        try (var book =
                new XSSFWorkbook(
                        new ByteArrayInputStream(
                                call("GET", "/api/export.xlsx", null, null, false).body()))) {
            assertEquals(
                    0,
                    book.getSheet("계정별집계")
                            .getRow(ReceiptAnalyzer.CATEGORIES.size() + 5)
                            .getCell(2)
                            .getNumericCellValue());
        }
        assertEquals(200, call("DELETE", "/api/expenses/" + id, null, null, true).statusCode());
    }

    @Test
    void localApiRejectsCrossOriginAndUnmarkedMutations() throws Exception {
        assertEquals(403, call("POST", "/api/expenses", null, null, false).statusCode());
        assertEquals(
                403,
                call("POST", "/api/expenses", null, "https://external.example", true).statusCode());
        assertEquals(400, call("POST", "/api/sheets/sync", null, null, true).statusCode());
    }

    @Test
    void realImageUploadDeduplicatesAndRejectsNonImage() throws Exception {
        var image = new BufferedImage(150, 150, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 150, 150);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        var first = upload(out.toByteArray());
        assertEquals(200, first.statusCode());
        long id = tree(first).path("expense").path("id").asLong();
        assertFalse(tree(first).path("duplicate").asBoolean());
        var second = upload(out.toByteArray());
        assertTrue(tree(second).path("duplicate").asBoolean());
        assertEquals(id, tree(second).path("expense").path("id").asLong());
        assertEquals(
                200, call("GET", "/api/expenses/" + id + "/image", null, null, false).statusCode());
        assertEquals(400, upload("not an image".getBytes()).statusCode());
        assertEquals(200, call("DELETE", "/api/expenses/" + id, null, null, true).statusCode());
        assertFalse(
                Files.exists(
                        data.resolve("images")
                                .resolve(tree(first).path("expense").path("imagePath").asText())));
    }

    @Test
    void rotatedUploadsAndManualRotationUseVersionedImages() throws Exception {
        var input = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(input, "jpeg", bytes);
        var uploaded =
                tree(upload(ReceiptImagesTest.withOrientation(bytes.toByteArray(), 6)))
                        .path("expense");
        long id = uploaded.path("id").asLong();
        var before =
                ImageIO.read(
                        new ByteArrayInputStream(
                                call("GET", "/api/expenses/" + id + "/image", null, null, false)
                                        .body()));
        assertEquals(80, before.getWidth());
        assertEquals(120, before.getHeight());
        var rotated =
                call(
                        "POST",
                        "/api/expenses/" + id + "/rotate",
                        Map.of("version", 0, "quarterTurns", -1),
                        null,
                        true);
        assertEquals(200, rotated.statusCode());
        assertEquals(1, tree(rotated).path("version").asInt());
        var after =
                ImageIO.read(
                        new ByteArrayInputStream(
                                call("GET", "/api/expenses/" + id + "/image", null, null, false)
                                        .body()));
        assertEquals(120, after.getWidth());
        assertEquals(80, after.getHeight());
        assertEquals(
                409,
                call(
                                "POST",
                                "/api/expenses/" + id + "/rotate",
                                Map.of("version", 0, "quarterTurns", 1),
                                null,
                                true)
                        .statusCode());
        assertEquals(
                400,
                call(
                                "POST",
                                "/api/expenses/" + id + "/rotate",
                                Map.of("version", 1, "quarterTurns", 0),
                                null,
                                true)
                        .statusCode());
        assertEquals(200, call("DELETE", "/api/expenses/" + id, null, null, true).statusCode());
    }

    @Test
    void reanalysisPreservesManualContextAndRequiresReviewAgain() throws Exception {
        var image = new BufferedImage(90, 110, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        long id = tree(upload(bytes.toByteArray())).path("expense").path("id").asLong();
        var edit =
                new ExpenseInput(
                        "2026-10-10",
                        "수정 거래처",
                        "복리후생비",
                        "직원 식사",
                        11000L,
                        10000L,
                        1000L,
                        "카드",
                        "사용자",
                        "부서",
                        "보존할 메모",
                        "CONFIRMED",
                        0);
        assertEquals(200, call("PUT", "/api/expenses/" + id, edit, null, true).statusCode());
        when(ocr.read(any()))
                .thenReturn(
                        new WindowsOcr.Result(
                                "[매장명] 테스트 불고기\n"
                                        + "2026-10-12\n"
                                        + "합 계 금 액 53,000\n"
                                        + "부가세 과세물품가액 48,184\n"
                                        + "부 가 세 4,816",
                                "test"));
        var response =
                call(
                        "POST",
                        "/api/expenses/" + id + "/reanalyze",
                        Map.of("version", 1, "hint", "직원 식사"),
                        null,
                        true);
        assertEquals(
                200,
                response.statusCode(),
                new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        var result = tree(response);
        assertEquals("REVIEW", result.path("status").asText());
        assertEquals(53000, result.path("amount").asInt());
        assertEquals("보존할 메모", result.path("note").asText());
        assertEquals("직원 식사", result.path("description").asText());
        assertEquals("사용자", result.path("ownerName").asText());
        assertEquals(2, result.path("version").asInt());
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result("", "unavailable"));
        assertEquals(
                400,
                call("POST", "/api/expenses/" + id + "/reanalyze", Map.of("version", 2), null, true)
                        .statusCode());
        assertEquals(200, call("DELETE", "/api/expenses/" + id, null, null, true).statusCode());
    }

    HttpResponse<byte[]> upload(byte[] bytes) throws Exception {
        String boundary = "receipt-test-boundary";
        var body = new ByteArrayOutputStream();
        body.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"receipt.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes());
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes());
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/receipts"))
                        .header("X-Receipt-Client", "local-app")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
