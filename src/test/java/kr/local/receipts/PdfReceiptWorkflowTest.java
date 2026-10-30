package kr.local.receipts;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 사진만 들어 있는 연속 스캔 PDF를 운영 장부와 분리한 실제 HTTP 흐름으로 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PdfReceiptWorkflowTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:pdf-receipts;DB_CLOSE_DELAY=-1");
        p.add("app.data-dir",()->data.toString());p.add("app.ollama-enabled",()->"false");
        p.add("app.sheets-id",()->"");p.add("app.sheets-credentials",()->"");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired PdfDocuments pdfs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @MockitoBean WindowsOcr ocr;
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void defaults() {
        reset(ocr);when(ocr.read(any())).thenReturn(ocrResult());
        db.update("DELETE FROM bookkeeping_entries");db.update("DELETE FROM expenses");db.update("DELETE FROM pdf_documents");
    }
    static WindowsOcr.Result ocrResult() {
        return new WindowsOcr.Result("예시식당\n2026-10-26\n직원 점심\n합계 : 9000\n승인번호 : 00001234\n신용카드", "테스트 OCR");
    }
    HttpResponse<byte[]> request(String method,String path,Object body)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Receipt-Client","local-app");
        if(body==null)builder.method(method,HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode tree(HttpResponse<byte[]> response){return json.readTree(response.body());}
    void ok(HttpResponse<byte[]> response){assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));}
    HttpResponse<byte[]> prepare(byte[] bytes)throws Exception {
        String boundary="ReceiptPdfBoundary";var out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"scan.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/receipts/pdf"))
                .header("X-Receipt-Client","local-app").header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return http.send(request,HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode document(byte[] pdf)throws Exception {var response=prepare(pdf);ok(response);return tree(response);}
    HttpResponse<byte[]> page(JsonNode document,int page)throws Exception {
        return request("POST","/api/receipts/pdf/"+document.path("hash").asText()+"/pages/"+page,Map.of("hint","직원 점심"));
    }
    byte[] scan(int pages,boolean rotated,boolean encrypted)throws Exception {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            for(int i=0;i<pages;i++){
                var page=new PDPage(new PDRectangle(300,500));pdf.addPage(page);if(rotated)page.setRotation(90);
                var image=new BufferedImage(600,1000,BufferedImage.TYPE_INT_RGB);
                var graphics=image.createGraphics();graphics.setColor(Color.WHITE);graphics.fillRect(0,0,600,1000);
                graphics.setColor(Color.BLACK);graphics.setFont(new Font("SansSerif",Font.PLAIN,32));
                graphics.drawString("Receipt page "+(i+1),40,70);graphics.drawString("TOTAL 9,000",40,180);graphics.dispose();
                var embedded=LosslessFactory.createFromImage(pdf,image);image.flush();
                try(var content=new PDPageContentStream(pdf,page)){content.drawImage(embedded,0,0,300,500);}
            }
            if(encrypted){var policy=new StandardProtectionPolicy("owner-password","user-password",new AccessPermission());policy.setEncryptionKeyLength(128);pdf.protect(policy);}
            pdf.save(out);return out.toByteArray();
        }
    }

    @Test void scannedPagesHaveIndependentReceiptsAndStableDuplicateIdentity()throws Exception {
        byte[] bytes=scan(2,false,false);var doc=document(bytes);assertEquals(2,doc.path("pageCount").asInt());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM expenses",Integer.class));
        var first=page(doc,1);ok(first);var second=page(doc,2);ok(second);
        var a=tree(first).path("expense");var b=tree(second).path("expense");assertNotEquals(a.path("id").asLong(),b.path("id").asLong());
        assertEquals("00001234",a.path("approvalNumber").asText());assertEquals(9000,a.path("amount").asLong());
        assertEquals("REVIEW",a.path("status").asText());assertEquals(1,a.path("pdfPage").asInt());assertEquals(2,b.path("pdfPage").asInt());
        assertEquals(2,a.path("pdfPageCount").asInt());assertTrue(a.path("originalName").asText().contains("[1/2쪽]"));
        var same=document(bytes);assertEquals(doc.path("hash").asText(),same.path("hash").asText());
        assertTrue(tree(page(same,1)).path("duplicate").asBoolean());assertTrue(tree(page(same,2)).path("duplicate").asBoolean());
        verify(ocr,times(2)).read(any());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM expenses",Integer.class));
        var original=request("GET","/api/expenses/"+a.path("id").asLong()+"/pdf",null);ok(original);assertArrayEquals(bytes,original.body());
        assertEquals("application/pdf",original.headers().firstValue("content-type").orElse(""));
        assertTrue(original.headers().firstValue("content-disposition").orElse("").startsWith("attachment"));
        assertEquals(400,page(doc,0).statusCode());assertEquals(400,page(doc,3).statusCode());
    }

    @Test void failedPageCanBeRetriedWithoutDuplicatingOtherPages()throws Exception {
        var doc=document(scan(3,false,false));when(ocr.read(any())).thenReturn(ocrResult()).thenThrow(new IllegalStateException("test failure")).thenReturn(ocrResult());
        ok(page(doc,1));assertEquals(500,page(doc,2).statusCode());ok(page(doc,3));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM expenses",Integer.class));
        ok(request("POST","/api/receipts/pdf/"+doc.path("hash").asText()+"/finish",null));assertTrue(Files.exists(pdfs.original(doc.path("hash").asText())));
        assertTrue(tree(page(doc,1)).path("duplicate").asBoolean());ok(page(doc,2));assertTrue(tree(page(doc,3)).path("duplicate").asBoolean());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM expenses",Integer.class));verify(ocr,times(4)).read(any());
    }

    @Test void pdfRotationIsAppliedAndSourceRemainsUntilLastReceiptIsDeleted()throws Exception {
        byte[] bytes=scan(2,true,false);var doc=document(bytes);var one=tree(page(doc,1)).path("expense");var two=tree(page(doc,2)).path("expense");
        long id=one.path("id").asLong();var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(request("GET","/api/expenses/"+id+"/image",null).body()));
        assertTrue(image.getWidth()>image.getHeight(),"PDF의 90도 회전 정보를 렌더링에 반영합니다.");
        ok(request("POST","/api/expenses/"+id+"/rotate",Map.of("version",0,"quarterTurns",1)));assertArrayEquals(bytes,request("GET","/api/expenses/"+id+"/pdf",null).body());
        ok(request("DELETE","/api/expenses/"+id,null));assertTrue(Files.exists(pdfs.original(doc.path("hash").asText())));
        ok(request("DELETE","/api/expenses/"+two.path("id").asLong(),null));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pdf_documents",Integer.class));
        assertThrows(ResponseStatusException.class,()->pdfs.original(doc.path("hash").asText()));
    }

    @Test void invalidEncryptedEmptyAndOverLimitPdfsAreRejectedWithoutReceipts()throws Exception {
        assertEquals(400,prepare("not a PDF".getBytes(StandardCharsets.UTF_8)).statusCode());assertEquals(400,prepare(scan(1,false,true)).statusCode());
        assertEquals(400,prepare(scan(0,false,false)).statusCode());assertEquals(400,prepare(scan(21,false,false)).statusCode());
        var tooLarge=new MockMultipartFile("file","large.pdf","application/pdf",new byte[(int)PdfDocuments.MAX_BYTES+1]);
        assertThrows(ResponseStatusException.class,()->pdfs.prepare(tooLarge));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pdf_documents",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM expenses",Integer.class));verifyNoInteractions(ocr);
    }

    @Test void oversizedPageAndFailedOnlyDocumentAreCleanedUp()throws Exception {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            pdf.addPage(new PDPage(new PDRectangle(50000,100)));pdf.save(out);assertEquals(400,prepare(out.toByteArray()).statusCode());
        }
        var doc=document(scan(1,false,false));when(ocr.read(any())).thenThrow(new IllegalStateException("test failure"));assertEquals(500,page(doc,1).statusCode());
        ok(request("POST","/api/receipts/pdf/"+doc.path("hash").asText()+"/finish",null));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pdf_documents",Integer.class));
        assertEquals(400,request("POST","/api/receipts/pdf/not-a-hash/pages/1",Map.of("hint","")).statusCode());
    }

    @Test void postedPdfReceiptsKeepTheirOriginalAndExportPageIdentity()throws Exception {
        byte[] bytes=scan(1,false,false);var doc=document(bytes);var source=tree(page(doc,1)).path("expense");long id=source.path("id").asLong();
        var input=new ExpenseInput("2026-10-26","예시식당","복리후생비","직원 점심",9000L,null,null,"법인카드","담당자","","","CONFIRMED",0,"00001234");
        var saved=request("PUT","/api/expenses/"+id,input);ok(saved);assertEquals(doc.path("hash").asText(),tree(saved).path("pdfHash").asText());
        ok(request("POST","/api/bookkeeping/expenses/"+id,Map.of("version",1,"entryDate","2026-10-26","creditAccount","미지급금(법인카드)","taxMode","TOTAL_EXPENSE","postedBy","담당자","memo","직원 점심")));
        assertEquals(409,request("DELETE","/api/expenses/"+id,null).statusCode());
        ok(request("POST","/api/receipts/pdf/"+doc.path("hash").asText()+"/finish",null));
        assertTrue(tree(page(doc,1)).path("duplicate").asBoolean());verify(ocr,times(1)).read(any());
        assertArrayEquals(bytes,request("GET","/api/expenses/"+id+"/pdf",null).body());
        var exported=request("GET","/api/export.xlsx",null);ok(exported);
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(exported.body()))){
            assertTrue(workbook.getSheet("지출장부").getRow(4).getCell(ExcelExporter.column("원본파일명")).getStringCellValue().contains("scan.pdf [1/1쪽]"));
        }
    }

    @Test void concurrentRequestsForTheSamePageCreateOneReceipt()throws Exception {
        var doc=document(scan(1,false,false));
        var one=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return page(doc,1);}catch(Exception e){throw new RuntimeException(e);}});
        var two=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return page(doc,1);}catch(Exception e){throw new RuntimeException(e);}});
        var first=one.get(15,java.util.concurrent.TimeUnit.SECONDS);var second=two.get(15,java.util.concurrent.TimeUnit.SECONDS);ok(first);ok(second);
        assertNotEquals(tree(first).path("duplicate").asBoolean(),tree(second).path("duplicate").asBoolean());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM expenses",Integer.class));verify(ocr,times(1)).read(any());
    }

    @Test void hugeEmbeddedImagesAreRejectedBeforeDecoding()throws Exception {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            var page=new PDPage();pdf.addPage(page);page.setResources(new PDResources());
            var image=LosslessFactory.createFromImage(pdf,new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB));
            image.getCOSObject().setInt(org.apache.pdfbox.cos.COSName.WIDTH,10000);
            image.getCOSObject().setInt(org.apache.pdfbox.cos.COSName.HEIGHT,10000);
            page.getResources().put(org.apache.pdfbox.cos.COSName.getPDFName("oversize"),image);
            pdf.save(out);assertEquals(400,prepare(out.toByteArray()).statusCode());verifyNoInteractions(ocr);
        }
    }
}
