package kr.local.receipts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 관리 API는 실행별 비밀값과 기존 로컬 출처 검사를 모두 통과해야 사용할 수 있습니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DesktopControllerTest {
    private static final String TOKEN="desktop-controller-test-token-0000000000";
    @TempDir static Path data;
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:desktop-controller;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir",()->data.toString());p.add("app.desktop-enabled",()->"true");
        p.add("app.desktop-token",()->TOKEN);p.add("app.ollama-enabled",()->"false");
        p.add("app.sheets-id",()->"");p.add("app.sheets-credentials",()->"");
    }
    private HttpResponse<String> request(String method,String endpoint,String token,boolean marked,String origin)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/desktop/"+endpoint));
        if(token!=null)builder.header("X-Receipt-Desktop",token);
        if(marked)builder.header("X-Receipt-Client","local-app");
        if(origin!=null)builder.header("Origin",origin);
        return HttpClient.newHttpClient().send(builder.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void readinessNeedsTheExactSessionToken()throws Exception {
        assertEquals(403,request("GET","status",null,false,null).statusCode());
        assertEquals(403,request("GET","status","different",false,null).statusCode());
        var response=request("GET","status",TOKEN,false,null);assertEquals(200,response.statusCode());
        assertTrue(response.body().contains("ReceiptToExcel"));assertTrue(response.body().contains("true"));
        assertFalse(response.body().contains(TOKEN));
    }
    @Test void untrustedBrowserAndUnauthenticatedStopRequestsCannotStopTheServer()throws Exception {
        assertEquals(403,request("POST","stop","different",true,null).statusCode());
        assertEquals(403,request("POST","stop",TOKEN,false,null).statusCode());
        assertEquals(403,request("POST","stop",TOKEN,true,"https://example.com").statusCode());
        assertEquals(200,request("GET","status",TOKEN,false,null).statusCode());
    }
}
