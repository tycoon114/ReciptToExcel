package kr.local.receipts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private final ImportFiles files;
    private final ImportAnalysis analysis;
    private ImportCardVat cardVat;
    public ImportController(ImportFiles files, ImportAnalysis analysis) { this.files = files; this.analysis = analysis; }
    @org.springframework.beans.factory.annotation.Autowired
    public ImportController(ImportFiles files, ImportAnalysis analysis, ImportCardVat cardVat) {
        this(files, analysis); this.cardVat = cardVat;
    }
    @GetMapping public List<ImportFiles.Entry> list() throws IOException { return files.list(); }
    @PostMapping public ImportFiles.Entry upload(@RequestParam("kind") ImportFiles.Kind kind,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "") String invoiceDirection) throws IOException { return files.upload(kind, file, invoiceDirection); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        analysis.delete(id);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/analysis") public List<ImportAnalysis.Document> analysis() throws IOException { return analysis.list(); }
    @PostMapping("/card-vat") public List<ImportAnalysis.Document> cardVat(@RequestParam(defaultValue = "LIVE") String mode) throws IOException {
        return cardVat.review(mode);
    }
    @PostMapping("/card-vat/test-month") public List<ImportAnalysis.Document> generateTestMonth(@RequestParam String month) throws IOException {
        return cardVat.generateTestMonth(month);
    }
    @PostMapping("/card-vat/clear-test") public List<ImportAnalysis.Document> clearTestReviews() throws IOException {
        return cardVat.clearTestReviews();
    }
    @PostMapping("/{id}/analyze") public ImportAnalysis.Result analyze(@PathVariable String id) throws IOException { return analysis.analyze(id); }
    @GetMapping("/export.xlsx") public ResponseEntity<byte[]> export(@RequestParam(required = false) String month) throws IOException {
        String selected = ImportAnalysis.month(month);
        var documents = analysis.list();
        if (!selected.isEmpty() && !ImportWorkbook.hasTransactions(documents, selected))
            throw ReceiptService.bad("선택한 월의 내역이 0건이라 엑셀을 만들 수 없습니다.");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("추출내역_" + (selected.isEmpty() ? "전체" : selected) + ".xlsx", StandardCharsets.UTF_8).build().toString())
                .body(ImportWorkbook.create(documents, selected));
    }
    @GetMapping("/{id}/original") public ResponseEntity<FileSystemResource> original(@PathVariable String id) throws IOException {
        var entry = files.get(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(entry.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(entry.name(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(files.original(id)));
    }
}
