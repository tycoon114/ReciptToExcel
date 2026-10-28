package kr.local.receipts;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 키·국세청 통신 없이 공식 응답 형식과 오류 처리를 루프백 HTTP 서버로 확인합니다. */
class NtsBusinessLookupTest {
    HttpServer server;
    int status;
    String response, requestBody, query;
    static final String NUMBER="1234567891", KEY="demo+key/=", NOW="2026-10-28T04:00:00Z";
    @BeforeEach void start() throws Exception {
        status=200;response=body("01", "부가가치세 일반과세자");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/status",exchange->{
            requestBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            query=exchange.getRequestURI().getRawQuery();
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    NtsBusinessLookup client(String key){
        return new NtsBusinessLookup(mock(LedgerRepository.class),JsonMapper.builder().build(),key,
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/status"),Clock.fixed(Instant.parse(NOW),ZoneOffset.UTC));
    }
    static String body(String code,String label){
        return "{\"status_code\":\"OK\",\"request_cnt\":1,\"data\":[{\"b_no\":\""+NUMBER
                +"\",\"b_stt_cd\":\"01\",\"b_stt\":\"계속사업자\",\"tax_type_cd\":\""+code
                +"\",\"tax_type\":\""+label+"\",\"tax_type_change_dt\":\"20260701\",\"invoice_apply_dt\":\"20260701\"}]}";
    }

    @ParameterizedTest @ValueSource(strings={"01","02","03","04","05","06","07"})
    void preservesDistinctOfficialTypes(String code){
        String label=code.equals("07")?"부가가치세 간이과세자(세금계산서 발급사업자)":"공식 과세유형 "+code;
        response=body(code,label);
        var result=client(KEY).request(NUMBER);
        assertEquals(code,result.taxTypeCode());assertEquals(label,result.taxType());
        assertEquals(NOW,result.checkedAt());assertEquals("2026-07-01",result.taxTypeChangedOn());
        assertEquals(NUMBER,result.businessNumber());assertEquals("FOUND",result.state());
        var sent=JsonMapper.builder().build().readTree(requestBody);
        assertEquals(1,sent.size());assertEquals(NUMBER,sent.path("b_no").get(0).asText());
        assertEquals(result,NtsBusinessLookup.restored(NtsBusinessLookup.stored(result)));
        assertFalse(NtsBusinessLookup.stored(result).contains(KEY));
    }

    @Test void acceptsEncodedKeyWithoutDoubleEncoding(){
        client("demo%2Bkey%2F%3D").request(NUMBER);
        assertTrue(query.contains("serviceKey=demo%2Bkey%2F%3D"));assertFalse(query.contains("%252B"));
    }
    @Test void separatesUnregisteredResultFromNetworkFailure(){
        response="{\"status_code\":\"OK\",\"data\":[{\"b_no\":\""+NUMBER+"\",\"b_stt_cd\":\"\",\"tax_type_cd\":\"\",\"tax_type\":\"국세청에 등록되지 않은 사업자등록번호입니다.\"}]}";
        var result=client(KEY).request(NUMBER);
        assertEquals("NOT_FOUND",result.state());assertEquals("조회되지 않음",result.taxType());
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,503})
    void errorsDoNotExposeKeysOrResponseBodies(int code){
        status=code;response="UPSTREAM_SECRET "+KEY;
        var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client(KEY).request(NUMBER));
        assertEquals(502,error.getStatusCode().value());assertFalse(error.getReason().contains(KEY));
        assertFalse(error.getReason().contains("UPSTREAM_SECRET"));assertFalse(error.getReason().contains("serviceKey"));
    }
    @Test void rejectsMissingKeyMismatchedOrIncompleteResponses(){
        assertFalse(client("").configured());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client("").request(NUMBER));
        assertNull(requestBody);
        response=body("01","일반과세자").replace(NUMBER,"2208162517");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client(KEY).request(NUMBER));
        response="{\"status_code\":\"OK\",\"data\":[]}";
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client(KEY).request(NUMBER));
        response="{\"status_code\":\"OK\",\"data\":[{\"b_no\":\""+NUMBER+"\"}]}";
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client(KEY).request(NUMBER));
    }
}
