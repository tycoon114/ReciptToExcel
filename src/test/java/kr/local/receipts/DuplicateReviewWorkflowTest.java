package kr.local.receipts;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 실제 사용자의 장부·영수증·외부 API를 사용하지 않고 중복 검토의 등록·확정·제외 흐름을 확인합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DuplicateReviewWorkflowTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:duplicate-review;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir",()->data.toString());p.add("app.ollama-enabled",()->"false");
        p.add("app.sheets-id",()->"");p.add("app.sheets-credentials",()->"");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired DuplicateReviewService duplicates;
    @MockitoBean WindowsOcr ocr;
    final HttpClient http=HttpClient.newHttpClient();
    static String text(String date,long amount,String vendor,String approval,String extra) {
        return vendor+"\n"+date+"\n사업자번호:123-45-67890\n합계 "+amount
                +(approval.isBlank()?"":"\n승인번호 "+approval)+"\n"+extra;
    }
    String normal(){return text("2026-10-27",11000,"테스트식당","00001234","");}
    @BeforeEach void resetData() {
        db.update("DELETE FROM bookkeeping_entries");db.update("DELETE FROM expenses");db.update("DELETE FROM pdf_documents");
        reset(ocr);when(ocr.read(any())).thenReturn(new WindowsOcr.Result(normal(),"합성 OCR"));
    }
    HttpResponse<byte[]> request(String method,String path,Object body)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Receipt-Client","local-app");
        if(body==null)builder.method(method,HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode tree(HttpResponse<byte[]> r){return json.readTree(r.body());}
    JsonNode ok(HttpResponse<byte[]> r){assertEquals(200,r.statusCode(),new String(r.body(),StandardCharsets.UTF_8));return tree(r);}
    JsonNode get(long id)throws Exception{return ok(request("GET","/api/expenses/"+id,null));}
    JsonNode report(long id)throws Exception{return ok(request("GET","/api/expenses/"+id+"/duplicates",null));}
    byte[] image(String format,int variant)throws Exception {
        var image=new BufferedImage(360,640,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,360,640);g.setColor(Color.BLACK);
        for(int i=0;i<10;i++)g.fillRect(35,40+i*48,220-(i%3)*25,5+variant%3);
        g.drawString("SAMPLE "+variant,40,600);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,format,out);image.flush();return out.toByteArray();
    }
    HttpResponse<byte[]> upload(byte[] bytes,String name,String route)throws Exception {
        String boundary="DuplicateReceiptBoundary";var out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);out.write(("\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"hint\"\r\n\r\n직원 점심\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route)).header("X-Receipt-Client","local-app")
                .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode photo(String format,int variant)throws Exception{return ok(upload(image(format,variant),"sample."+format,"/api/receipts"));}
    Map<String,Object> input(JsonNode e,String status) {
        var values=new LinkedHashMap<String,Object>();
        for(String field:List.of("receiptDate","vendor","category","description","amount","supplyAmount","vatAmount","paymentMethod","approvalNumber","ownerName","department","note")) {
            var value=e.path(field);values.put(field,value.isNull()?null:value.isNumber()?value.asLong():value.asText());
        }
        values.put("status",status);values.put("version",e.path("version").asInt());return values;
    }
    JsonNode confirm(JsonNode e)throws Exception{return ok(request("PUT","/api/expenses/"+e.path("id").asLong(),input(e,"CONFIRMED")));}
    JsonNode decision(long id,String action,int index)throws Exception {
        var report=report(id);var e=report.path("expense");var match=report.path("matches").get(index);
        return ok(request("POST","/api/expenses/"+id+"/duplicate-review",Map.of("version",e.path("version").asInt(),
                "candidateId",match.path("expense").path("id").asLong(),"candidateVersion",match.path("expense").path("version").asInt(),
                "signature",match.path("signature").asText(),"decision",action)));
    }
    @Test void confirmedPngAndNewJpegAreSeparateRecordsWithAnExplicitPendingReview()throws Exception {
        var first=confirm(photo("png",1).path("expense"));var second=photo("jpg",1);long id=second.path("expense").path("id").asLong();
        assertFalse(second.path("duplicate").asBoolean());assertEquals("SUSPECTED",second.path("expense").path("duplicateState").asText());
        assertEquals("CONFIRMED",second.path("duplicateMatches").get(0).path("expense").path("status").asText());
        assertTrue(report(id).path("matches").get(0).path("reason").asText().contains("승인번호 일치"));
        assertEquals(first,get(first.path("id").asLong()),"기존 확정 내역은 수정하지 않습니다.");
        assertEquals(409,request("PUT","/api/expenses/"+id,input(get(id),"CONFIRMED")).statusCode());
        assertEquals(1,ok(request("GET","/api/expenses?status=SUSPECTED",null)).size());
        assertEquals(0,ok(request("GET","/api/expenses?status=REVIEW",null)).size());
        assertEquals(1,ok(request("GET","/api/expenses?status=CONFIRMED",null)).size());
        var export=request("GET","/api/export.xlsx",null);assertEquals(200,export.statusCode());
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(export.body()))){
            assertEquals(first.path("id").asLong(),workbook.getSheet("지출장부").getRow(4).getCell(0).getNumericCellValue());
        }
    }
    @Test void distinctDecisionAllowsARealRepeatPurchaseAndSurvivesAContextOnlyEdit()throws Exception {
        confirm(photo("png",2).path("expense"));long id=photo("jpg",2).path("expense").path("id").asLong();
        var separate=decision(id,"DISTINCT",0);assertEquals("NONE",separate.path("duplicateState").asText());
        var values=input(separate,"REVIEW");values.put("note","별개의 정상 반복 구매");
        separate=ok(request("PUT","/api/expenses/"+id,values));assertEquals("NONE",separate.path("duplicateState").asText());
        assertEquals("CONFIRMED",confirm(separate).path("status").asText());
    }
    @Test void exclusionPreservesBothEvidenceFilesAndCanBeRestored()throws Exception {
        var first=confirm(photo("png",3).path("expense"));long id=photo("jpg",3).path("expense").path("id").asLong();
        var excluded=decision(id,"EXCLUDE",0);assertEquals("EXCLUDED",excluded.path("duplicateState").asText());
        assertEquals(200,request("GET","/api/expenses/"+id+"/image",null).statusCode());
        assertEquals("CONFIRMED",get(first.path("id").asLong()).path("status").asText());
        assertEquals(400,request("PUT","/api/expenses/"+id,input(excluded,"CONFIRMED")).statusCode());
        var restored=ok(request("POST","/api/expenses/"+id+"/duplicate-restore",Map.of("version",excluded.path("version").asInt())));
        assertEquals("SUSPECTED",restored.path("duplicateState").asText());
    }
    @Test void exactFileDoesNotCallOcrAgainOrChangeTheConfirmedRecord()throws Exception {
        byte[] bytes=image("png",4);var first=confirm(ok(upload(bytes,"first.png","/api/receipts")).path("expense"));
        var repeated=ok(upload(bytes,"renamed.png","/api/receipts"));assertTrue(repeated.path("duplicate").asBoolean());
        assertEquals(first,repeated.path("expense"));verify(ocr,times(1)).read(any());
    }
    @Test void approvalAloneDifferentDatesAmountsBusinessesAndCancellationDoNotBlock()throws Exception {
        photo("png",5);
        for(String alternative:List.of(text("2026-10-28",11000,"테스트식당","00001234",""),
                text("2026-10-27",12000,"테스트식당","00001234",""),
                normal().replace("123-45-67890","987-65-43210"),normal()+"\n[매출취소]")) {
            when(ocr.read(any())).thenReturn(new WindowsOcr.Result(alternative,"test"));
            assertEquals("NONE",photo("png",alternative.hashCode()).path("expense").path("duplicateState").asText());
        }
    }
    @Test void optionalApprovalAndBusinessNameSpacingStillFindCandidates()throws Exception {
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result(text("2026-10-27",11000,"작은식당","",""),"test"));
        photo("png",6);
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result(text("2026-10-27",11000,"작은 식당","","교환 및 환불은 원본 지참"),"test"));
        var result=photo("jpg",6);assertEquals("SUSPECTED",result.path("expense").path("duplicateState").asText());
        assertEquals("CHECK",result.path("duplicateMatches").get(0).path("strength").asText());
    }
    @Test void changingTradeEvidenceInvalidatesAnEarlierDistinctDecision()throws Exception {
        photo("png",7);long id=photo("jpg",7).path("expense").path("id").asLong();
        var distinct=decision(id,"DISTINCT",0);var edited=input(distinct,"REVIEW");edited.put("approvalNumber","00005678");
        var result=ok(request("PUT","/api/expenses/"+id,edited));assertEquals("SUSPECTED",result.path("duplicateState").asText());
        assertTrue(report(id).path("matches").get(0).path("reason").asText().contains("서로 달라"));
    }
    @Test void staleComparisonCannotApplyToChangedCandidateAndDeletingCandidateClearsPendingReview()throws Exception {
        var first=photo("png",8).path("expense");long firstId=first.path("id").asLong();long id=photo("jpg",8).path("expense").path("id").asLong();
        var report=report(id);var match=report.path("matches").get(0);
        var edited=input(first,"REVIEW");edited.put("note","후보가 변경됨");ok(request("PUT","/api/expenses/"+firstId,edited));
        assertEquals(409,request("POST","/api/expenses/"+id+"/duplicate-review",Map.of("version",report.path("expense").path("version").asInt(),
                "candidateId",firstId,"candidateVersion",first.path("version").asInt(),"signature",match.path("signature").asText(),"decision","DISTINCT")).statusCode());
        ok(request("DELETE","/api/expenses/"+firstId,null));assertEquals("NONE",get(id).path("duplicateState").asText());
    }
    @Test void acceptingOneCandidateDoesNotDismissOtherCandidates()throws Exception {
        photo("png",9);long second=photo("jpg",9).path("expense").path("id").asLong();decision(second,"DISTINCT",0);
        long third=photo("png",10).path("expense").path("id").asLong();assertEquals(2,report(third).path("matches").size());
        var stillPending=decision(third,"DISTINCT",0);assertEquals("SUSPECTED",stillPending.path("duplicateState").asText());
        assertEquals(409,request("PUT","/api/expenses/"+third,input(stillPending,"CONFIRMED")).statusCode());
        assertEquals("NONE",decision(third,"DISTINCT",0).path("duplicateState").asText());
    }
    @Test void editsCannotIntroduceAConfirmedDuplicateWithoutReview()throws Exception {
        photo("png",11);
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result(text("2026-10-28",15000,"다른식당","00005678",""),"test"));
        var other=photo("jpg",11).path("expense");long id=other.path("id").asLong();var edit=input(other,"CONFIRMED");
        edit.put("receiptDate","2026-10-27");edit.put("amount",11000);edit.put("vendor","테스트식당");edit.put("approvalNumber","00001234");
        assertEquals(409,request("PUT","/api/expenses/"+id,edit).statusCode());
        edit.put("status","REVIEW");assertEquals("SUSPECTED",ok(request("PUT","/api/expenses/"+id,edit)).path("duplicateState").asText());
    }
    byte[] pdf(byte[] image)throws Exception {
        try(var document=new PDDocument();var out=new ByteArrayOutputStream()){
            var page=new PDPage(new PDRectangle(360,640));document.addPage(page);
            var raster=ImageIO.read(new ByteArrayInputStream(image));var embedded=LosslessFactory.createFromImage(document,raster);raster.flush();
            try(var content=new PDPageContentStream(document,page)){content.drawImage(embedded,0,0,360,640);}
            document.save(out);return out.toByteArray();
        }
    }
    @Test void jpgAndPdfPageAreComparedAndPdfDuplicatesRemainIdempotent()throws Exception {
        confirm(photo("jpg",12).path("expense"));byte[] bytes=pdf(image("png",12));
        var document=ok(upload(bytes,"scan.pdf","/api/receipts/pdf"));String path="/api/receipts/pdf/"+document.path("hash").asText()+"/pages/1";
        var first=ok(request("POST",path,Map.of("hint","직원 점심")));
        assertEquals("SUSPECTED",first.path("expense").path("duplicateState").asText());
        assertEquals(1,first.path("expense").path("pdfPage").asInt());
        assertArrayEquals(bytes,request("GET","/api/expenses/"+first.path("expense").path("id").asLong()+"/pdf",null).body());
        assertTrue(ok(request("POST",path,Map.of("hint",""))).path("duplicate").asBoolean());verify(ocr,times(2)).read(any());
    }
    @Test void concurrentCrossFormatUploadsCannotBothMissEachOther()throws Exception {
        var png=image("png",13);var jpeg=image("jpg",13);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->ok(upload(png,"same.png","/api/receipts")));
            var b=pool.submit(()->ok(upload(jpeg,"same.jpg","/api/receipts")));
            var states=List.of(a.get(25,TimeUnit.SECONDS).path("expense").path("duplicateState").asText(),
                    b.get(25,TimeUnit.SECONDS).path("expense").path("duplicateState").asText());
            assertTrue(states.contains("SUSPECTED"));assertTrue(states.contains("NONE"));
        } finally { pool.shutdownNow(); }
    }
    @Test void similarReceiptImageCanSupportMissingMerchantButBlankImagesCannot()throws Exception {
        photo("png",14);
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result("2026-10-27\n합계 11000", "부분 누락"));
        var recovered=photo("jpg",14);
        assertEquals("SUSPECTED",recovered.path("expense").path("duplicateState").asText());
        assertTrue(recovered.path("duplicateMatches").get(0).path("reason").asText().contains("이미지 유사"));
        byte[] blank;
        var image=new BufferedImage(360,640,BufferedImage.TYPE_INT_RGB);
        try(var output=new ByteArrayOutputStream()){ImageIO.write(image,"png",output);blank=output.toByteArray();}finally{image.flush();}
        var empty=ok(upload(blank,"blank.png","/api/receipts"));assertEquals("NONE",empty.path("expense").path("duplicateState").asText());
    }
}
