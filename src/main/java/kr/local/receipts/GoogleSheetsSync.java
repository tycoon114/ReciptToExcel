package kr.local.receipts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** Writes a complete, idempotent snapshot only to this application's reserved tab. */
@Service
public class GoogleSheetsSync {
    public static final String TAB = "영수증장부_APP";
    private final String id, credentials;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GoogleSheetsSync(
            ObjectMapper json,
            @Value("${app.sheets-id}") String id,
            @Value("${app.sheets-credentials}") String credentials) {
        this.json = json;
        this.id = id;
        this.credentials = credentials;
    }

    public boolean configured() {
        return !id.isBlank() && !credentials.isBlank();
    }

    public String link() {
        return configured() ? "https://docs.google.com/spreadsheets/d/" + id + "/edit" : "";
    }

    public synchronized Map<String, Object> sync(List<Expense> entries) throws Exception {
        if (!configured())
            throw ReceiptService.bad(
                    "Google Sheets가 연결되지 않았습니다. README의 서비스 계정 설정을 완료하거나 XLSX를 가져오기 해 주세요.");
        if (!id.matches("[A-Za-z0-9_-]+")) throw ReceiptService.bad("Google 스프레드시트 ID를 확인해 주세요.");
        String token = token();
        String root = "https://sheets.googleapis.com/v4/spreadsheets/" + id;
        var meta = request("GET", root + "?fields=sheets.properties", token, null);
        boolean found = false;
        for (var sheet : meta.path("sheets"))
            if (TAB.equals(sheet.path("properties").path("title").asText())) found = true;
        if (!found)
            request(
                    "POST",
                    root + ":batchUpdate",
                    token,
                    Map.of(
                            "requests",
                            List.of(
                                    Map.of(
                                            "addSheet",
                                            Map.of("properties", Map.of("title", TAB))))));
        String range = URLEncoder.encode("'" + TAB + "'!A:O", StandardCharsets.UTF_8);
        var old = request("GET", root + "/values/" + range, token, null).path("values");
        // Refuse to replace an unrelated existing tab that happens to share the reserved title.
        if (!old.isMissingNode()
                && old.size() > 0
                && !old.get(0).path(0).asText().equals(ExcelExporter.HEADERS.get(0)))
            throw ReceiptService.bad("영수증장부_APP 탭에 다른 데이터가 있습니다. 해당 탭 이름을 변경한 후 다시 전송해 주세요.");
        int rows = Math.max(old.size(), entries.size() + 1);
        List<List<Object>> values = new ArrayList<>();
        values.add(new ArrayList<>(ExcelExporter.HEADERS));
        for (var e : entries)
            values.add(
                    ExcelExporter.values(e).stream()
                            .map(v -> v == null ? (Object) "" : v)
                            .toList());
        while (values.size() < rows) values.add(new ArrayList<>(Collections.nCopies(15, "")));
        String target = URLEncoder.encode("'" + TAB + "'!A1:O" + rows, StandardCharsets.UTF_8);
        request(
                "PUT",
                root + "/values/" + target + "?valueInputOption=RAW",
                token,
                Map.of(
                        "range",
                        "'" + TAB + "'!A1:O" + rows,
                        "majorDimension",
                        "ROWS",
                        "values",
                        values));
        return Map.of(
                "count",
                entries.size(),
                "url",
                link(),
                "message",
                "확정된 전체 " + entries.size() + "건을 전송했습니다. 전용 탭은 현재 PC 장부와 같게 갱신됩니다.");
    }

    private String token() throws Exception {
        Path path = Path.of(credentials);
        if (!Files.isRegularFile(path)) throw ReceiptService.bad("서비스 계정 JSON 파일 경로를 확인해 주세요.");
        var key = json.readTree(Files.readString(path, StandardCharsets.UTF_8));
        if (!"service_account".equals(key.path("type").asText()))
            throw ReceiptService.bad("서비스 계정 유형의 JSON 키가 필요합니다.");
        long now = Instant.now().getEpochSecond();
        String header = encode(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String claims =
                encode(
                        json.writeValueAsBytes(
                                Map.of(
                                        "iss",
                                        key.path("client_email").asText(),
                                        "scope",
                                        "https://www.googleapis.com/auth/spreadsheets",
                                        "aud",
                                        "https://oauth2.googleapis.com/token",
                                        "iat",
                                        now,
                                        "exp",
                                        now + 3600)));
        String unsigned = header + "." + claims;
        String pem =
                key.path("private_key")
                        .asText()
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        var privateKey =
                KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(unsigned.getBytes(StandardCharsets.UTF_8));
        String assertion = unsigned + "." + encode(signer.sign());
        var req =
                HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                        .timeout(Duration.ofSeconds(25))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="
                                                + URLEncoder.encode(
                                                        assertion, StandardCharsets.UTF_8)))
                        .build();
        var r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200)
            throw ReceiptService.bad("Google 인증에 실패했습니다. 서비스 계정 키와 PC 시간을 확인해 주세요.");
        return json.readTree(r.body()).path("access_token").asText();
    }

    private JsonNode request(String method, String url, String token, Object body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token);
        if (body == null) builder.GET();
        else
            builder.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var r = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300)
            throw ReceiptService.bad(
                    "Google Sheets 요청 실패 (HTTP "
                            + r.statusCode()
                            + "). Sheets API 사용 설정, 문서 ID, 서비스 계정의 편집 공유 권한을 확인해 주세요.");
        return json.readTree(r.body());
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
