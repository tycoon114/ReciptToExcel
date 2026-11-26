package kr.local.receipts;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 가짜 조회와 임시 설정 파일로 저장·연결 확인·로컬 요청 보호를 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class NtsSettingsWorkflowTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->"jdbc:h2:mem:nts-settings;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir",()->data.toString());p.add("app.nts-service-key",()->"");
        p.add("app.ollama-enabled",()->"false");
        p.add("spring.config.import",()->"optional:classpath:unused-nts-settings.properties");
    }
    @LocalServerPort int port;
    @Autowired NtsKeySettings settings;
    @MockitoSpyBean NtsBusinessLookup nts;
    HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> send(String route,String body,boolean header)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route));
        if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(header)b.header("X-Receipt-Client","local-app");
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void saveCheckAndSecurityNeverReturnKey()throws Exception{
        String secret="private-test-key",candidate="unsaved-test-key";
        String saveBody="{\"key\":\""+secret+"\"}";
        assertEquals(403,send("/api/nts-settings",saveBody,false).statusCode());
        assertEquals("",settings.key());
        var saved=send("/api/nts-settings",saveBody,true);
        assertEquals(200,saved.statusCode());assertFalse(saved.body().contains(secret));assertTrue(nts.configured());
        assertEquals(secret,settings.key());
        var status=send("/api/nts-settings",null,false);
        assertTrue(status.body().contains("true"));assertFalse(status.body().contains(secret));
        assertFalse(send("/api/config",null,false).body().contains(secret));
        doReturn(new NtsBusinessLookup.Result("1234567891","NOT_FOUND","","조회되지 않음","","조회되지 않음","","","","","2026-11-26T00:00:00Z"))
            .when(nts).request(eq("1234567891"),anyString());
        var tested=send("/api/nts-settings/test","{\"key\":\""+candidate+"\",\"businessNumber\":\"123-45-67891\"}",true);
        assertEquals(200,tested.statusCode());assertFalse(tested.body().contains(candidate));assertEquals(secret,settings.key());
        verify(nts).request("1234567891",candidate);
        assertEquals(200,send("/api/nts-settings/test","{\"businessNumber\":\"1234567891\"}",true).statusCode());
        verify(nts).request("1234567891",secret);
        assertEquals(400,send("/api/nts-settings","{\"key\":\"bad&key\"}",true).statusCode());
        assertEquals(secret,settings.key());
        assertEquals(403,http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/nts-settings"))
            .header("Content-Type","application/json").header("X-Receipt-Client","local-app").header("Origin","https://example.com")
            .POST(HttpRequest.BodyPublishers.ofString(saveBody)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
