package kr.local.receipts;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** 새 자료 화면의 원본 보관소. 등록 완료된 자료만 목록에 노출합니다. */
@Service
public class ImportFiles {
    public enum Kind { CARD_PDF, ACCOUNT_PDF, TAX_INVOICE, RECEIPT_PHOTO }
    public record Entry(String id, Kind kind, String name, long size, String mediaType,
                        int pages, String uploadedAt, String invoiceDirection) {}
    private final Path root;
    private final ObjectMapper json;

    public ImportFiles(ObjectMapper json, @Value("${app.data-dir}") String data) throws IOException {
        this.json = json;
        root = Path.of(data).resolve("imports");
        Files.createDirectories(root);
    }

    public synchronized List<Entry> list() throws IOException {
        var entries = new ArrayList<Entry>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                entries.add(json.readValue(Files.readString(path), Entry.class));
            }
        }
        entries.sort(Comparator.comparing(Entry::uploadedAt).reversed());
        return entries;
    }

    public Entry upload(Kind kind, MultipartFile file) throws IOException {
        return upload(kind, file, "");
    }

    public Entry upload(Kind kind, MultipartFile file, String invoiceDirection) throws IOException {
        if (!Set.of("", "매입", "매출").contains(invoiceDirection)) throw ReceiptService.bad("세금계산서의 매출·매입 구분을 확인해 주세요.");
        if (file.isEmpty() || file.getSize() > 25L * 1024 * 1024)
            throw ReceiptService.bad("파일은 25MB 이하로 선택해 주세요.");
        byte[] bytes = file.getBytes();
        String name = Objects.toString(file.getOriginalFilename(), "자료").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        boolean pdf = name.toLowerCase(Locale.ROOT).endsWith(".pdf");
        boolean excel = name.toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|xls)$");
        int pages = 0;
        String mediaType;
        if (excel) {
            if (kind == Kind.RECEIPT_PHOTO) throw ReceiptService.bad("영수증 사진에는 JPG 또는 PNG를 올려 주세요.");
            try (var book = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
                if (book.getNumberOfSheets() == 0 || book.getNumberOfSheets() > 100)
                    throw ReceiptService.bad("엑셀은 1~100개 시트까지 등록할 수 있습니다.");
                for (var sheet : book) if (sheet.getLastRowNum() >= 100000)
                    throw ReceiptService.bad("엑셀은 시트당 100,000행까지 등록할 수 있습니다.");
            } catch (org.springframework.web.server.ResponseStatusException e) { throw e; }
            catch (Exception e) { throw ReceiptService.bad("엑셀을 읽지 못했습니다. 암호를 해제한 XLSX 또는 XLS 파일인지 확인해 주세요."); }
            mediaType = name.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                    ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.ms-excel";
        } else if (kind == Kind.CARD_PDF || kind == Kind.ACCOUNT_PDF || pdf) {
            if (!pdf || kind == Kind.RECEIPT_PHOTO) throw ReceiptService.bad("이 항목에는 PDF를 올려 주세요.");
            try (var document = Loader.loadPDF(bytes)) {
                if (document.isEncrypted()) throw ReceiptService.bad("암호를 해제한 PDF를 올려 주세요.");
                pages = document.getNumberOfPages();
                if (pages == 0 || pages > 100) throw ReceiptService.bad("PDF는 1~100쪽까지 등록할 수 있습니다.");
                if (kind == Kind.CARD_PDF || kind == Kind.ACCOUNT_PDF) {
                    var stripper = new PDFTextStripper();
                    for (int page = 1; page <= pages; page++) {
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        if (stripper.getText(document).isBlank())
                            throw ReceiptService.bad(page + "쪽에 추출 가능한 글자가 없습니다. 텍스트가 포함된 문서 PDF를 올려 주세요.");
                    }
                }
            } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
                throw ReceiptService.bad("암호를 해제한 PDF를 올려 주세요.");
            } catch (IOException e) {
                throw ReceiptService.bad("읽을 수 없는 PDF입니다. 파일을 확인해 주세요.");
            }
            mediaType = "application/pdf";
        } else {
            if (bytes.length > 10L * 1024 * 1024) throw ReceiptService.bad("사진은 10MB 이하로 선택해 주세요.");
            // 기존 이미지 형식·픽셀 수 검증을 재사용합니다. 원본 파일을 보관합니다.
            try {
                var image = new ReceiptImages().normalize(bytes);
                image.flush();
            } catch (IOException e) {
                throw ReceiptService.bad("읽을 수 없는 사진입니다.");
            } catch (Exception e) {
                throw ReceiptService.bad("JPG 또는 PNG 사진을 확인해 주세요.");
            }
            mediaType = bytes.length > 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xd8
                    ? "image/jpeg" : "image/png";
        }
        String id = UUID.randomUUID().toString();
        var entry = new Entry(id, kind, name, bytes.length, mediaType, pages, Instant.now().toString(),
                kind == Kind.TAX_INVOICE ? invoiceDirection : "");
        Path original = root.resolve(id + ".bin");
        Path temp = Files.createTempFile(root, "pending-", ".tmp");
        try {
            Files.write(original, bytes, StandardOpenOption.CREATE_NEW);
            Files.writeString(temp, json.writeValueAsString(entry));
            Files.move(temp, root.resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE);
            return entry;
        } catch (Exception e) {
            Files.deleteIfExists(original);
            throw e;
        } finally { Files.deleteIfExists(temp); }
    }

    public synchronized Entry get(String id) throws IOException {
        try { UUID.fromString(id); } catch (IllegalArgumentException e) { throw ReceiptService.bad("잘못된 자료 번호입니다."); }
        Path metadata = root.resolve(id + ".json");
        if (!Files.isRegularFile(metadata)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return json.readValue(Files.readString(metadata), Entry.class);
    }
    public synchronized void delete(String id) throws IOException {
        get(id);
        Path metadata = root.resolve(id + ".json");
        Path deleting = root.resolve(id + ".deleting");
        // 목록에서 먼저 숨기고, 원본 삭제 실패 시 등록 상태를 복구합니다.
        Files.move(metadata, deleting, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.deleteIfExists(root.resolve(id + ".bin"));
        } catch (IOException e) {
            Files.move(deleting, metadata, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }
        Files.deleteIfExists(deleting);
    }

    public Path original(String id) throws IOException { get(id); return root.resolve(id + ".bin"); }
}
