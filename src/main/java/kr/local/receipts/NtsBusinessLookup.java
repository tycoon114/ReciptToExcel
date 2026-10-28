package kr.local.receipts;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** 국세청 사업자 상태조회 전용 연결입니다. 과세유형을 조회하며 환급·매입세액 공제 여부를 판정하지 않습니다. */
@Service
public class NtsBusinessLookup {
    private static final URI ENDPOINT = URI.create("https://api.odcloud.kr/api/nts-businessman/v1/status");
    private static final ObjectMapper STORAGE_JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private final LedgerRepository repo;
    private final ObjectMapper json;
    private final String key;
    private final URI endpoint;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 조회 번호와 조회 시점을 함께 보관하여 다른 사업자나 과거 거래의 확정 정보로 오해하지 않게 합니다. */
    public record Result(String businessNumber, String state, String businessStateCode, String businessState,
            String taxTypeCode, String taxType, String closedOn, String taxTypeChangedOn,
            String invoiceAppliedOn, String previousTaxType, String checkedAt) {}

    @org.springframework.beans.factory.annotation.Autowired
    public NtsBusinessLookup(LedgerRepository repo, ObjectMapper json,
            @Value("${app.nts-service-key:}") String key) {
        this(repo, json, key, ENDPOINT, Clock.systemUTC());
    }

    /** 테스트에서만 루프백 가짜 API와 고정 시계를 주입합니다. 운영 주소는 설정이나 요청으로 변경하지 않습니다. */
    NtsBusinessLookup(LedgerRepository repo, ObjectMapper json, String key, URI endpoint, Clock clock) {
        this.repo = repo; this.json = json; this.key = Objects.toString(key, "").trim();
        if (!ENDPOINT.equals(endpoint) && !("http".equals(endpoint.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost())))
            throw new IllegalArgumentException("국세청 조회 주소를 확인해 주세요.");
        this.endpoint = endpoint; this.clock = clock;
    }

    public boolean configured() { return !key.isBlank(); }

    /** 네트워크 대기 중 DB 잠금을 잡지 않습니다. 저장 시 다시 버전·사업자번호·기장 여부를 확인합니다. */
    public Expense lookup(long id, int version) {
        Expense source = repo.get(id);
        if (source.version() != version) throw conflict();
        if ("EXCLUDED".equals(source.duplicateState())) throw ReceiptService.bad("중복 제외를 취소한 뒤 조회해 주세요.");
        repo.assertNotPosted(id);
        String number = source.businessNumber().replace("-", "");
        if (!ReceiptMetadataParser.validBusinessNumber(number))
            throw ReceiptService.bad("판매점 사업자등록번호를 원본과 대조하고 먼저 저장해 주세요.");
        if (!configured()) throw ReceiptService.bad("국세청 인증키가 없습니다. 연결 안내에 따라 개인 설정 파일에 키를 넣고 앱을 재시작해 주세요.");
        Result result = request(number);
        return repo.saveBusinessLookup(id, version, number, result);
    }

    /** 요청에는 사업자번호만 담습니다. 인증키와 외부 응답 본문·요청 URL을 오류 응답이나 로그로 출력하지 않습니다. */
    Result request(String number) {
        if (!configured()) throw ReceiptService.bad("국세청 인증키를 설정해 주세요.");
        if (!ReceiptMetadataParser.validBusinessNumber(number)) throw ReceiptService.bad("사업자등록번호를 확인해 주세요.");
        try {
            // 포털의 Encoding/Decoding 키를 모두 받아 한 번만 URL 인코딩합니다. + 문자를 공백으로 바꾸지 않습니다.
            String rawKey = key.contains("%") ? URLDecoder.decode(key, StandardCharsets.UTF_8) : key;
            String encoded = URLEncoder.encode(rawKey, StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create(endpoint + "?serviceKey=" + encoded + "&returnType=JSON"))
                    .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("b_no", List.of(number)))))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            {
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw failure("국세청 인증에 실패했습니다. 인증키와 해당 API 활용신청 승인 여부를 확인해 주세요.");
                if (response.statusCode() == 429) throw failure("국세청 조회 한도에 도달했습니다. 잠시 후 다시 조회해 주세요.");
                if (response.statusCode() != 200) throw failure("국세청 조회 서비스에 연결하지 못했습니다. 기존 조회 결과는 유지됩니다.");
                byte[] bytes = response.body();
                if (bytes.length > 65536) throw failure("국세청 응답 형식을 확인하지 못했습니다. 다시 조회해 주세요.");
                var root = json.readTree(bytes);
                if (!"OK".equals(root.path("status_code").asText()))
                    throw failure("국세청 조회가 승인되지 않았습니다. 인증키·활용신청·호출 한도를 확인해 주세요.");
                var entries = root.path("data");
                if (!entries.isArray() || entries.size() != 1 || !number.equals(entries.get(0).path("b_no").asText()))
                    throw failure("조회한 번호와 국세청 응답이 일치하지 않아 저장하지 않았습니다.");
                var item = entries.get(0);
                String stateCode = value(item, "b_stt_cd", 2), taxCode = value(item, "tax_type_cd", 2);
                String state = value(item, "b_stt", 40), tax = value(item, "tax_type", 200);
                String checked = Instant.now(clock).toString();
                if (stateCode.isBlank() && taxCode.isBlank() && tax.contains("등록되지 않은"))
                    return new Result(number, "NOT_FOUND", "", "조회되지 않음", "", "조회되지 않음", "", "", "", "", checked);
                if (!Set.of("01", "02", "03").contains(stateCode) || state.isBlank()
                        || !taxCode.matches("[0-9]{2}") || tax.isBlank())
                    throw failure("국세청 응답에 과세유형이 없거나 불완전하여 저장하지 않았습니다.");
                // 02와 07을 합치지 않습니다. 07은 세금계산서 발급사업자인 간이과세자입니다.
                return new Result(number, "FOUND", stateCode, state, taxCode, tax,
                        date(item, "end_dt"), date(item, "tax_type_change_dt"), date(item, "invoice_apply_dt"),
                        value(item, "rbf_tax_type", 200), checked);
            }
        } catch (ResponseStatusException e) { throw e; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw failure("국세청 조회가 중단되었습니다. 다시 시도해 주세요.");
        } catch (Exception e) {
            throw failure("국세청 조회에 실패했습니다. 인터넷·인증키·서비스 상태를 확인해 주세요. 기존 조회 결과는 유지됩니다.");
        }
    }

    private static String value(JsonNode node, String name, int max) {
        var value = node.path(name);
        if (!value.isString() || value.asText().length() > max) return "";
        return value.asText().strip();
    }
    private static String date(JsonNode node, String name) {
        String value = value(node, name, 8);
        if (!value.matches("[0-9]{8}")) return "";
        try { return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).toString(); }
        catch (Exception e) { return ""; }
    }

    /** 저장하는 사본에는 API 인증키·원문 응답을 포함하지 않습니다. */
    static String stored(Result result) { return result == null ? null : STORAGE_JSON.writeValueAsString(result); }
    static Result restored(String value) {
        return value == null || value.isBlank() ? null : STORAGE_JSON.readValue(value, Result.class);
    }
    static String taxLabel(Result result) { return result == null ? "미조회" : result.taxType(); }
    static String stateLabel(Result result) { return result == null ? "미조회" : result.businessState(); }
    static String checkedAt(Result result) { return result == null ? "" : result.checkedAt(); }
    private static ResponseStatusException failure(String message) { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "내역이 변경되었습니다. 다시 열고 조회해 주세요."); }
}
