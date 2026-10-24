package kr.local.receipts;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayInputStream;

/** 운영 장부와 분리된 메모리 DB에서 기장·취소·원본 보호·동시 요청·엑셀을 실제 HTTP로 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookkeepingWorkflowTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:bookkeeping-tests;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir",()->data.toString());p.add("app.ollama-enabled",()->"false");
        p.add("app.sheets-id",()->"");p.add("app.sheets-credentials",()->"");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired BookkeepingService bookkeeping;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    final HttpClient http=HttpClient.newHttpClient();

    HttpRequest request(String method,String path,Object body,boolean marked) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(marked)builder.header("X-Receipt-Client","local-app");
        return builder.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody()
                :HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    HttpResponse<byte[]> call(String method,String path,Object body)throws Exception {
        return http.send(request(method,path,body,true),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode tree(HttpResponse<byte[]> response){return json.readTree(response.body());}
    void ok(HttpResponse<byte[]> response){assertEquals(200,response.statusCode(),new String(response.body(),java.nio.charset.StandardCharsets.UTF_8));}

    Map<String,Object> edit(JsonNode source,int version) {
        Map<String,Object> input=new LinkedHashMap<>();
        input.put("receiptDate","2026-10-19");input.put("vendor",source.path("vendor").asText("기장 예시"));
        input.put("category","복리후생비");input.put("description","직원 점심");input.put("amount",22000L);
        input.put("supplyAmount",20000L);input.put("vatAmount",2000L);input.put("paymentMethod","법인카드");
        input.put("ownerName","담당자");input.put("department","개발팀");input.put("note","");
        input.put("approvalNumber","00001234");
        input.put("status","CONFIRMED");input.put("version",version);return input;
    }
    JsonNode receipt(boolean knownTax)throws Exception {
        var created=call("POST","/api/expenses",null);ok(created);var draft=tree(created);
        var input=edit(draft,0);input.put("vendor","기장 예시 "+UUID.randomUUID());
        if(!knownTax){input.put("supplyAmount",null);input.put("vatAmount",null);}
        var saved=call("PUT","/api/expenses/"+draft.path("id").asLong(),input);ok(saved);return tree(saved);
    }
    Map<String,Object> postInput(int version,String mode) {
        var input=new LinkedHashMap<String,Object>();input.put("version",version);input.put("entryDate","2026-10-21");
        input.put("creditAccount","미지급금(법인카드)");input.put("taxMode",mode);input.put("postedBy","기장 담당자");input.put("memo","직원 점심");return input;
    }
    JsonNode post(JsonNode receipt,String mode)throws Exception {
        var response=call("POST","/api/bookkeeping/expenses/"+receipt.path("id").asLong(),postInput(receipt.path("version").asInt(),mode));ok(response);return tree(response);
    }
    JsonNode cancel(JsonNode action,String reason)throws Exception {
        var response=call("POST","/api/bookkeeping/entries/"+action.path("entry").path("id").asLong()+"/cancel",
                Map.of("version",action.path("expense").path("version").asInt(),"cancelledBy","정정 담당자","reason",reason));ok(response);return tree(response);
    }

    @Test void postingIsSeparateFromConfirmationAndBothSidesBalance()throws Exception {
        var source=receipt(true);long id=source.path("id").asLong();
        assertTrue(bookkeeping.list("","").stream().noneMatch(e->e.expenseId()==id));
        var result=post(source,"TOTAL_EXPENSE");var entry=result.path("entry");
        assertEquals(2,result.path("expense").path("version").asInt());
        assertEquals("POSTED",entry.path("status").asText());assertEquals("2026-10-19",entry.path("receiptDate").asText());
        assertEquals("2026-10-21",entry.path("entryDate").asText());assertTrue(entry.path("entryNumber").asText().matches("JV-20261021-\\d+"));
        assertEquals(22000,entry.path("expenseAmount").asLong());assertEquals(0,entry.path("vatAmount").asLong());
        var lines=bookkeeping.get(entry.path("id").asLong()).lines();assertEquals(2,lines.size());
        assertEquals(lines.stream().filter(l->l.side().equals("차변")).mapToLong(BookkeepingService.Line::amount).sum(),
                lines.stream().filter(l->l.side().equals("대변")).mapToLong(BookkeepingService.Line::amount).sum());
        assertEquals(409,call("POST","/api/bookkeeping/expenses/"+id,postInput(1,"TOTAL_EXPENSE")).statusCode());
    }

    @Test void vatSeparationNeedsExplicitChoiceAndVerifiedTaxFields()throws Exception {
        var result=post(receipt(true),"SPLIT_VAT");var entry=result.path("entry");
        assertEquals(20000,entry.path("expenseAmount").asLong());assertEquals(2000,entry.path("vatAmount").asLong());
        assertEquals(3,bookkeeping.get(entry.path("id").asLong()).lines().size());
        var unknown=receipt(false);long id=unknown.path("id").asLong();
        assertEquals(400,call("POST","/api/bookkeeping/expenses/"+id,postInput(1,"SPLIT_VAT")).statusCode());
        assertTrue(bookkeeping.list("","").stream().noneMatch(e->e.expenseId()==id));
        assertEquals(22000,post(unknown,"TOTAL_EXPENSE").path("entry").path("expenseAmount").asLong());
    }

    @Test void invalidInputsAndUnreviewedOrZeroExpensesCannotBePosted()throws Exception {
        var draft=call("POST","/api/expenses",null);ok(draft);
        assertEquals(400,call("POST","/api/bookkeeping/expenses/"+tree(draft).path("id").asLong(),postInput(0,"TOTAL_EXPENSE")).statusCode());
        var source=receipt(true);String path="/api/bookkeeping/expenses/"+source.path("id").asLong();
        for(var invalid:List.of(Map.entry("entryDate","2026-02-31"),Map.entry("entryDate","+10000-01-01"),
                Map.entry("creditAccount","미확인"),Map.entry("taxMode","AUTO_DEDUCT"),Map.entry("postedBy"," "),Map.entry("memo",""))){
            var input=postInput(1,"TOTAL_EXPENSE");input.put(invalid.getKey(),invalid.getValue());assertEquals(400,call("POST",path,input).statusCode());
        }
        var missingVersion=postInput(1,"TOTAL_EXPENSE");missingVersion.remove("version");assertEquals(400,call("POST",path,missingVersion).statusCode());
        assertEquals(409,call("POST",path,postInput(99,"TOTAL_EXPENSE")).statusCode());
        assertEquals(403,http.send(request("POST",path,postInput(1,"TOTAL_EXPENSE"),false),HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertEquals(400,call("GET","/api/bookkeeping/entries?month=invalid",null).statusCode());
        var zero=edit(source,1);zero.put("amount",0);zero.put("supplyAmount",null);zero.put("vatAmount",null);
        var saved=call("PUT","/api/expenses/"+source.path("id").asLong(),zero);ok(saved);
        assertEquals(400,call("POST",path,postInput(2,"TOTAL_EXPENSE")).statusCode());
    }

    @Test void postedReceiptCannotBeEditedDeletedRotatedOrReanalyzed()throws Exception {
        var posted=post(receipt(true),"TOTAL_EXPENSE");long id=posted.path("expense").path("id").asLong();
        var input=edit(posted.path("expense"),2);input.put("amount",44000);input.put("supplyAmount",40000);input.put("vatAmount",4000);
        assertEquals(409,call("PUT","/api/expenses/"+id,input).statusCode());
        assertEquals(409,call("DELETE","/api/expenses/"+id,null).statusCode());
        assertEquals(409,call("POST","/api/expenses/"+id+"/reanalyze",Map.of("version",2)).statusCode());
        assertEquals(409,call("POST","/api/expenses/"+id+"/rotate",Map.of("version",2,"quarterTurns",1)).statusCode());
        assertEquals(22000,tree(call("GET","/api/expenses/"+id,null)).path("amount").asLong());
    }

    @Test void cancelKeepsHistoryAndAllowsCorrectionAndNewUniqueVoucher()throws Exception {
        var first=post(receipt(true),"TOTAL_EXPENSE");long id=first.path("expense").path("id").asLong();
        String cancelPath="/api/bookkeeping/entries/"+first.path("entry").path("id").asLong()+"/cancel";
        assertEquals(400,call("POST",cancelPath,Map.of("version",2,"cancelledBy","담당자","reason"," ")).statusCode());
        assertEquals(409,call("POST",cancelPath,Map.of("version",1,"cancelledBy","담당자","reason","정정")).statusCode());
        var cancelled=cancel(first,"계정과 금액 정정");assertEquals("CANCELLED",cancelled.path("entry").path("status").asText());
        assertEquals("계정과 금액 정정",cancelled.path("entry").path("cancelReason").asText());
        assertFalse(cancelled.path("entry").path("cancelledAt").isNull());
        assertEquals(409,call("DELETE","/api/expenses/"+id,null).statusCode());
        var input=edit(cancelled.path("expense"),3);input.put("amount",44000);input.put("supplyAmount",40000);input.put("vatAmount",4000);
        input.put("approvalNumber","00005678");
        var saved=call("PUT","/api/expenses/"+id,input);ok(saved);var second=post(tree(saved),"SPLIT_VAT");
        assertNotEquals(first.path("entry").path("entryNumber").asText(),second.path("entry").path("entryNumber").asText());
        var history=bookkeeping.list("","").stream().filter(e->e.expenseId()==id).toList();assertEquals(2,history.size());
        assertEquals(22000,history.stream().filter(e->e.status().equals("CANCELLED")).findFirst().orElseThrow().totalAmount());
        assertEquals(44000,history.stream().filter(e->e.status().equals("POSTED")).findFirst().orElseThrow().totalAmount());
        assertEquals("00001234",history.stream().filter(e->e.status().equals("CANCELLED")).findFirst().orElseThrow().approvalNumber());
        assertEquals("00005678",history.stream().filter(e->e.status().equals("POSTED")).findFirst().orElseThrow().approvalNumber());
        assertEquals(409,call("POST",cancelPath,Map.of("version",5,"cancelledBy","담당자","reason","이전 전표 재취소")).statusCode());
    }

    @Test void concurrentPostRequestsProduceOneActiveVoucher()throws Exception {
        var source=receipt(true);long id=source.path("id").asLong();
        var futures=new ArrayList<CompletableFuture<HttpResponse<byte[]>>>();
        for(int i=0;i<6;i++)futures.add(http.sendAsync(request("POST","/api/bookkeeping/expenses/"+id,postInput(1,"TOTAL_EXPENSE"),true),HttpResponse.BodyHandlers.ofByteArray()));
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20,TimeUnit.SECONDS);
        assertEquals(1,futures.stream().filter(f->f.join().statusCode()==200).count());
        assertEquals(5,futures.stream().filter(f->f.join().statusCode()==409).count());
        assertEquals(1,bookkeeping.list("","").stream().filter(e->e.expenseId()==id && e.status().equals("POSTED")).count());
    }

    @Test void cancelledVoucherRetainsItsEvidenceWhenReceiptIsRotated()throws Exception {
        var source=receipt(true);long id=source.path("id").asLong();
        String filename=UUID.randomUUID()+".png";
        Path original=data.resolve("images").resolve(filename);
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(40,70,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",original.toFile());
        db.update("UPDATE expenses SET image_path=? WHERE id=?",filename,id);
        source=tree(call("GET","/api/expenses/"+id,null));
        var posted=post(source,"TOTAL_EXPENSE");long entryId=posted.path("entry").path("id").asLong();
        byte[] evidence=call("GET","/api/bookkeeping/entries/"+entryId+"/image",null).body();
        cancel(posted,"사진 방향 정정");
        var rotated=call("POST","/api/expenses/"+id+"/rotate",Map.of("version",3,"quarterTurns",1));ok(rotated);
        assertTrue(java.nio.file.Files.exists(original),"기장 이력이 참조하는 이전 증빙은 삭제하지 않습니다.");
        assertNotEquals(filename,tree(rotated).path("imagePath").asText());
        var retained=call("GET","/api/bookkeeping/entries/"+entryId+"/image",null);ok(retained);
        assertArrayEquals(evidence,retained.body());
        var oldPhoto=javax.imageio.ImageIO.read(new ByteArrayInputStream(retained.body()));
        assertEquals(40,oldPhoto.getWidth());assertEquals(70,oldPhoto.getHeight());
        var current=javax.imageio.ImageIO.read(new ByteArrayInputStream(call("GET","/api/expenses/"+id+"/image",null).body()));
        assertEquals(70,current.getWidth());assertEquals(40,current.getHeight());
    }

    @Test void exportSeparatesCancelledHistoryAndKeepsTextOutOfFormulas()throws Exception {
        var source=receipt(true);var first=post(source,"TOTAL_EXPENSE");var cancelled=cancel(first,"=1+1");
        var input=postInput(3,"SPLIT_VAT");input.put("memo","=HYPERLINK(\"https://example.com\",\"test\")");input.put("entryDate","2026-11-01");
        var second=call("POST","/api/bookkeeping/expenses/"+source.path("id").asLong(),input);ok(second);
        String query=URLEncoder.encode(source.path("vendor").asText(),java.nio.charset.StandardCharsets.UTF_8);
        var exported=call("GET","/api/bookkeeping/export.xlsx?query="+query,null);ok(exported);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(exported.body()))){
            assertEquals(3,book.getNumberOfSheets());var journal=book.getSheet("분개장");
            assertNotNull(journal);assertEquals(20000,journal.getRow(4).getCell(8).getNumericCellValue());
            assertEquals(2000,journal.getRow(5).getCell(8).getNumericCellValue());assertEquals(22000,journal.getRow(6).getCell(9).getNumericCellValue());
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,journal.getRow(4).getCell(5).getCellType());
            assertTrue(journal.getRow(4).getCell(5).getStringCellValue().startsWith("=HYPERLINK"));
            assertEquals(22000,book.getSheet("기장취소이력").getRow(4).getCell(5).getNumericCellValue());
            assertEquals("=1+1",book.getSheet("기장취소이력").getRow(4).getCell(9).getStringCellValue());
            assertEquals("00001234",journal.getRow(4).getCell(11).getStringCellValue());
            assertEquals("00001234",book.getSheet("기장취소이력").getRow(4).getCell(10).getStringCellValue());
        }
        var october=tree(call("GET","/api/bookkeeping/entries?month=2026-10&query="+query,null));assertEquals(1,october.size());
        assertEquals("CANCELLED",october.get(0).path("status").asText());
        var november=tree(call("GET","/api/bookkeeping/entries?month=2026-11&query="+query,null));assertEquals(1,november.size());
        assertEquals("POSTED",november.get(0).path("status").asText());
    }
}
