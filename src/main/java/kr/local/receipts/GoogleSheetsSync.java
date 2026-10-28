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

/**
 * 확정된 전체 장부를 구글 스프레드시트의 앱 전용 탭에 동기화합니다.
 * 행을 덧붙이는 대신 현재 장부 상태로 갱신하여 재전송 시 중복을 만들지 않습니다.
 * 서비스 계정 설정이 있을 때만 사용하며 다른 이름의 탭은 수정하지 않습니다.
 */
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

    /** 설정 값의 존재 여부입니다. 실제 인증·공유 권한 검증은 전송 요청에서 수행합니다. */
    public boolean configured() {
        return !id.isBlank() && !credentials.isBlank();
    }

    /** 설정된 스프레드시트의 열기 주소를 반환합니다. 연결 설정이 없으면 빈 문자열입니다. */
    public String link() {
        return configured() ? "https://docs.google.com/spreadsheets/d/" + id + "/edit" : "";
    }

    /** 동시 전송을 순서대로 처리하고 전용 탭 생성·기존 데이터 확인·전체 값 갱신을 수행합니다. */
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
        // 새 항목이 전송 범위에서 빠지지 않도록 공유 머리글에서 마지막 열을 계산합니다.
        String lastColumn = org.apache.poi.ss.util.CellReference.convertNumToColString(ExcelExporter.HEADERS.size() - 1);
        String range = URLEncoder.encode("'" + TAB + "'!A:" + lastColumn, StandardCharsets.UTF_8);
        var old = request("GET", root + "/values/" + range, token, null).path("values");
        // 같은 전용 이름의 탭이라도 첫 머리글이 다르면 다른 데이터일 수 있으므로 덮어쓰지 않습니다.
        if (!old.isMissingNode()
                && old.size() > 0
                && !old.get(0).path(0).asText().equals(ExcelExporter.HEADERS.get(0)))
            throw ReceiptService.bad("영수증장부_APP 탭에 다른 데이터가 있습니다. 해당 탭 이름을 변경한 후 다시 전송해 주세요.");
        // 장부 행이 줄어든 경우 이전 전송의 남은 행까지 빈 값으로 덮어 잔여 데이터를 없앱니다.
        int rows = Math.max(old.size(), entries.size() + 1);
        List<List<Object>> values = new ArrayList<>();
        values.add(new ArrayList<>(ExcelExporter.HEADERS));
        for (var e : entries)
            values.add(
                    ExcelExporter.values(e).stream()
                            .map(v -> v == null ? (Object) "" : v)
                            .toList());
        while (values.size() < rows) values.add(new ArrayList<>(Collections.nCopies(ExcelExporter.HEADERS.size(), "")));
        String target = URLEncoder.encode("'" + TAB + "'!A1:" + lastColumn + rows, StandardCharsets.UTF_8);
        // RAW 입력을 사용하여 상호·메모가 수식처럼 보여도 스프레드시트 수식으로 해석하지 않게 합니다.
        request(
                "PUT",
                root + "/values/" + target + "?valueInputOption=RAW",
                token,
                Map.of(
                        "range",
                        "'" + TAB + "'!A1:" + lastColumn + rows,
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

    /** 서비스 계정 개인 키로 유효기간 1시간의 JWT를 서명하고 구글 액세스 토큰으로 교환합니다. */
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
        // 서비스 계정 JSON의 PEM 개인 키를 PKCS#8 RSA 키로 읽어 RS256 서명에 사용합니다.
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

    /** 구글 API에 인증 헤더를 붙이고 제한 시간과 HTTP 오류를 공통 처리합니다. */
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

    /** JWT에 필요한 URL 안전 Base64 형식으로 패딩 없이 인코딩합니다. */
    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
