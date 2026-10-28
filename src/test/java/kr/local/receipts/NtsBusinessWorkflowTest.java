package kr.local.receipts;

import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 새 API 경로만 가짜 응답·임시 DB로 확인하며 실제 국세청 인증키는 사용하지 않습니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class NtsBusinessWorkflowTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->"jdbc:h2:mem:nts-workflow;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir",()->data.toString());p.add("app.ollama-enabled",()->"false");
        p.add("app.sheets-id",()->"");p.add("app.sheets-credentials",()->"");
        p.add("app.nts-service-key",()->"test-key-not-real");
        p.add("spring.config.import",()->"optional:classpath:nts-unused.properties");
    }
    @LocalServerPort int port;
    @Autowired ReceiptService receipts;
    @Autowired LedgerRepository repo;
    @Autowired BookkeepingService bookkeeping;
    @Autowired ObjectMapper json;
    @MockitoSpyBean NtsBusinessLookup nts;
    final HttpClient http=HttpClient.newHttpClient();
    static final String NUMBER="1234567891";
    static final NtsBusinessLookup.Result RESULT=new NtsBusinessLookup.Result(NUMBER,"FOUND","01","계속사업자",
            "07","부가가치세 간이과세자(세금계산서 발급사업자)","","2026-07-01","2026-07-01","부가가치세 일반과세자","2026-10-28T04:00:00Z");
    @BeforeEach void mockOnlyExternalRequest(){doReturn(RESULT).when(nts).request(anyString());}
    Expense create(){var e=receipts.manual();return receipts.save(e.id(),input(e,"123-45-67891"));}
    ExpenseInput input(Expense e,String number){return new ExpenseInput("2026-10-28","가상식당","복리후생비","직원 점심",9000L,
            null,null,"카드","검토자","","","CONFIRMED",e.version(),"",number,"12:34","서울 강남구 테스트로 123");}
    HttpResponse<String> lookup(Expense e,boolean header)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/expenses/"+e.id()+"/business-status"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"version\":"+e.version()+"}"));
        if(header)request.header("X-Receipt-Client","local-app");
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void lookupPersistsExportsAndCopiesToBookkeepingWithoutTaxJudgment()throws Exception{
        var e=create();assertEquals(403,lookup(e,false).statusCode());assertEquals(200,lookup(e,true).statusCode());
        var saved=repo.get(e.id());assertEquals(e.version()+1,saved.version());assertEquals("CONFIRMED",saved.status());
        assertEquals("07",saved.businessLookup().taxTypeCode());assertNull(saved.vatAmount());
        assertEquals(9000L,saved.amount());
        var config=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/config")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(json.readTree(config.body()).path("ntsConfigured").asBoolean());assertFalse(config.body().contains("test-key-not-real"));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new ExcelExporter().export(List.of(saved),false)))){
            var ledger=book.getSheet("지출장부");assertEquals(RESULT.taxType(),ledger.getRow(4).getCell(18).getStringCellValue());
            assertEquals(RESULT.businessState(),ledger.getRow(4).getCell(19).getStringCellValue());
            assertEquals(RESULT.checkedAt(),ledger.getRow(4).getCell(20).getStringCellValue());
            assertEquals("거래처주소",ledger.getRow(3).getCell(ExcelExporter.HEADERS.size()-1).getStringCellValue());
        }
        var posted=bookkeeping.post(saved.id(),new BookkeepingService.PostInput(saved.version(),"2026-10-28",
                BookkeepingService.CREDIT_ACCOUNTS.get(0),"TOTAL_EXPENSE","검토자","직원 점심"));
        assertEquals(RESULT,posted.entry().businessLookup());
        assertEquals(409,lookup(posted.expense(),true).statusCode());
        bookkeeping.cancel(posted.entry().id(),new BookkeepingService.CancelInput(posted.expense().version(),"검토자","수정"));
        var changed=receipts.save(saved.id(),input(repo.get(saved.id()),"220-81-62517"));
        assertNull(changed.businessLookup());assertEquals(RESULT,bookkeeping.get(posted.entry().id()).businessLookup());
    }

    @Test void failedAndStaleLookupsKeepPreviousResults()throws Exception{
        var original=create();assertEquals(200,lookup(original,true).statusCode());var saved=repo.get(original.id());
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"조회 실패")).when(nts).request(anyString());
        assertEquals(502,lookup(saved,true).statusCode());assertEquals(RESULT,repo.get(saved.id()).businessLookup());
        assertEquals(saved.version(),repo.get(saved.id()).version());
        doAnswer(call->{receipts.save(saved.id(),input(repo.get(saved.id()),"220-81-62517"));return RESULT;}).when(nts).request(anyString());
        assertEquals(409,lookup(saved,true).statusCode());assertNull(repo.get(saved.id()).businessLookup());
    }

    @Test void unrelatedEditsKeepLookupAndRereadingANewNumberClearsIt(){
        var source=create();var found=nts.lookup(source.id(),source.version());
        var same=receipts.save(found.id(),input(found,NUMBER));assertEquals(RESULT,same.businessLookup());
        var reread=ReceiptTextParser.parse("사업자번호 220-81-62517\n2026-10-28\n상호 가상식당\n합계 9000","직원 점심");
        assertNull(repo.reanalyze(same.id(),same.version(),reread,false).businessLookup());
    }
}
