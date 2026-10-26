package kr.local.receipts;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** PDF 원본 보관과 페이지 렌더링을 담당합니다. 텍스트층 유무와 관계없이 보이는 페이지를 OCR에 전달합니다. */
@Service
public class PdfDocuments {
    public static final int MAX_PAGES = 20;
    public static final long MAX_BYTES = 25L * 1024 * 1024;
    private final JdbcTemplate db;
    private final Path directory;

    public record Document(String hash, String originalName, int pageCount) {}

    public PdfDocuments(JdbcTemplate db, @Value("${app.data-dir}") String data) throws IOException {
        this.db = db;
        directory = Path.of(data).resolve("pdf").toAbsolutePath().normalize();
        Files.createDirectories(directory);
    }

    /** 먼저 전체 페이지 수·크기·지원 여부를 확인합니다. 잘못된 파일로 장부 일부가 먼저 생성되지 않습니다. */
    public synchronized Document prepare(MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw ReceiptService.bad("PDF는 25MB 이하로 선택해 주세요.");
        Path temporary = Files.createTempFile(directory, "upload-", ".pdf");
        Path stored = null;
        boolean created = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            var existing = find(hash);
            if (existing.isPresent() && Files.isRegularFile(path(hash))) return existing.get();
            int count;
            try (PDDocument pdf = open(temporary)) {
                count = pdf.getNumberOfPages();
                if (count < 1 || count > MAX_PAGES) throw ReceiptService.bad("PDF는 1~20페이지를 지원합니다. 여러 파일로 나눠 주세요.");
                for (PDPage page : pdf.getPages()) validatePage(page);
            }
            stored = path(hash);
            if (!Files.exists(stored)) { Files.move(temporary, stored); created = true; }
            String name = ReceiptAnalyzer.cut(Objects.toString(file.getOriginalFilename(), "receipt.pdf")
                    .replaceAll("[\\p{Cntrl}]", ""), 200);
            if (name.isBlank()) name = "receipt.pdf";
            if (existing.isEmpty()) db.update("INSERT INTO pdf_documents(hash,original_name,page_count,created_at) VALUES(?,?,?,?)",
                    hash, name, count, Instant.now().toString());
            return get(hash);
        } catch (Exception error) {
            if (created && stored != null) Files.deleteIfExists(stored);
            if (error instanceof IOException) throw ReceiptService.bad("PDF의 페이지를 읽지 못했습니다. 정상 PDF로 다시 저장해 주세요.");
            throw error;
        } finally { Files.deleteIfExists(temporary); }
    }

    public Document get(String hash) {
        return find(hash).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF 원본을 찾을 수 없습니다. 파일을 다시 선택해 주세요."));
    }

    private Optional<Document> find(String hash) {
        validateHash(hash);
        return db.query("SELECT hash,original_name,page_count FROM pdf_documents WHERE hash=?",
                (r,n) -> new Document(r.getString(1),r.getString(2),r.getInt(3)), hash).stream().findFirst();
    }

    public Path original(String hash) {
        get(hash);
        Path file = path(hash);
        if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보관된 PDF 파일이 없습니다.");
        return file;
    }

    /** PDF 자체의 회전 정보는 렌더러가 적용합니다. 큰 스캔은 출력 픽셀 수를 제한해 한 장씩 처리합니다. */
    public BufferedImage render(String hash, int pageNumber) throws Exception {
        Document metadata = get(hash);
        if (pageNumber < 1 || pageNumber > metadata.pageCount()) throw ReceiptService.bad("PDF 페이지 번호를 확인해 주세요.");
        try (PDDocument pdf = open(original(hash))) {
            PDPage page = pdf.getPage(pageNumber - 1);
            validatePage(page);
            var box = page.getCropBox();
            double width = box.getWidth(), height = box.getHeight();
            double scale = Math.min(200.0/72, Math.min(3000/Math.max(width,height), Math.sqrt(6_000_000/(width*height))));
            var renderer = new PDFRenderer(pdf);
            renderer.setSubsamplingAllowed(true);
            return renderer.renderImage(pageNumber - 1, (float)scale, ImageType.RGB);
        } catch (ResponseStatusException e) { throw e; }
        catch (IOException e) { throw ReceiptService.bad("이 PDF 페이지를 이미지로 읽지 못했습니다. 다른 PDF나 JPG로 저장해 다시 시도해 주세요."); }
    }

    /** 마지막 연결 내역을 삭제했거나 등록이 모두 실패한 PDF만 정리합니다. 기장 이력은 영수증 삭제 단계에서 보호됩니다. */
    public synchronized boolean removeIfUnused(String hash) throws IOException {
        validateHash(hash);
        if (db.queryForObject("SELECT COUNT(*) FROM expenses WHERE pdf_hash=?",Long.class,hash) > 0) return false;
        db.update("DELETE FROM pdf_documents WHERE hash=?",hash);
        Files.deleteIfExists(path(hash));
        return true;
    }

    private PDDocument open(Path file) throws IOException {
        try {
            PDDocument document = Loader.loadPDF(file.toFile());
            if (document.isEncrypted()) { document.close(); throw ReceiptService.bad("암호화된 PDF는 지원하지 않습니다. 암호 없이 다시 저장해 주세요."); }
            return document;
        } catch (InvalidPasswordException e) { throw ReceiptService.bad("암호가 걸린 PDF입니다. 암호 없이 다시 저장해 주세요."); }
        catch (IOException e) { throw ReceiptService.bad("손상되었거나 PDF 형식이 아닌 파일입니다. 정상 PDF를 선택해 주세요."); }
    }

    private void validatePage(PDPage page) throws IOException {
        var box = page.getCropBox();
        if (!Float.isFinite(box.getWidth()) || !Float.isFinite(box.getHeight()) || box.getWidth() < 1 || box.getHeight() < 1
                || box.getWidth() > 40_000 || box.getHeight() > 40_000 || !Float.isFinite(page.getUserUnit())
                || page.getUserUnit() <= 0 || page.getUserUnit() > 10)
            throw ReceiptService.bad("PDF 페이지 크기가 올바르지 않거나 너무 큽니다.");
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        checkImages(page.getResources(), visited, 0);
    }

    /** 압축된 거대 이미지와 과도하게 중첩된 폼은 렌더링 전에 제한합니다. PDF의 스크립트나 첨부 파일은 실행하지 않습니다. */
    private void checkImages(PDResources resources, Set<COSDictionary> visited, int depth) throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) return;
        if (depth > 16 || visited.size() > 500) throw ReceiptService.bad("PDF 구조가 너무 복잡합니다. 스캔 페이지로 다시 저장해 주세요.");
        int count = 0;
        for (COSName name : resources.getXObjectNames()) {
            if (++count > 500) throw ReceiptService.bad("한 PDF 페이지의 이미지 수가 너무 많습니다.");
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                long pixels = (long)image.getWidth()*image.getHeight();
                if (image.getWidth() < 1 || image.getHeight() < 1 || pixels > 30_000_000)
                    throw ReceiptService.bad("PDF에 너무 큰 스캔 이미지가 있습니다. 스캔 해상도를 낮춰 주세요.");
                var filters = image.getStream().getFilters();
                if (filters != null && filters.contains(COSName.JPX_DECODE) && !ImageIO.getImageReadersByFormatName("JPEG2000").hasNext())
                    throw ReceiptService.bad("JPEG2000 압축 PDF는 지원하지 않습니다. 일반 PDF 또는 JPG로 다시 저장해 주세요.");
            } else if (object instanceof PDFormXObject form) checkImages(form.getResources(), visited, depth+1);
        }
    }

    private void validateHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw ReceiptService.bad("PDF 식별값이 올바르지 않습니다.");
    }
    private Path path(String hash) { validateHash(hash); return directory.resolve(hash+".pdf"); }
}
