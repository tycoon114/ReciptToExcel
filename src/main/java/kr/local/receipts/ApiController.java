package kr.local.receipts;

import jakarta.validation.Valid;

import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final LedgerRepository repo;
    private final ReceiptService service;
    private final ReceiptAnalyzer analyzer;
    private final ExcelExporter excel;
    private final GoogleSheetsSync sheets;

    public ApiController(
            LedgerRepository repo,
            ReceiptService service,
            ReceiptAnalyzer analyzer,
            ExcelExporter excel,
            GoogleSheetsSync sheets) {
        this.repo = repo;
        this.service = service;
        this.analyzer = analyzer;
        this.excel = excel;
        this.sheets = sheets;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "categories",
                ReceiptAnalyzer.CATEGORIES,
                "payments",
                ReceiptAnalyzer.PAYMENTS,
                "aiEnabled",
                analyzer.enabled(),
                "aiModel",
                analyzer.model(),
                "sheetsConfigured",
                sheets.configured(),
                "sheetsUrl",
                sheets.link());
    }

    @GetMapping("/expenses")
    public List<Expense> list(
            @RequestParam(defaultValue = "") String month,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "dateDesc") String sort) {
        return service.filter(month, category, query, status, sort);
    }

    @PostMapping(value = "/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReceiptService.UploadResult upload(
            @RequestParam MultipartFile file, @RequestParam(defaultValue = "") String hint)
            throws Exception {
        return service.upload(file, hint);
    }

    @PostMapping("/expenses")
    public Expense manual() {
        return service.manual();
    }

    @PutMapping(value = "/expenses/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Expense save(@PathVariable long id, @Valid @RequestBody ExpenseInput input) {
        return service.save(id, input);
    }

    @DeleteMapping("/expenses/{id}")
    public Map<String, Boolean> delete(@PathVariable long id) throws Exception {
        service.delete(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/expenses/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new FileSystemResource(service.image(id)));
    }

    public record PhotoAction(
            @jakarta.validation.constraints.Min(0) int version,
            @jakarta.validation.constraints.Min(-1) @jakarta.validation.constraints.Max(1)
                    Integer quarterTurns,
            @jakarta.validation.constraints.Size(max = 500) String hint) {}

    @PostMapping(value = "/expenses/{id}/rotate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Expense rotate(@PathVariable long id, @Valid @RequestBody PhotoAction action)
            throws Exception {
        if (action.quarterTurns() == null) throw ReceiptService.bad("회전 방향을 선택해 주세요.");
        return service.rotate(id, action.version(), action.quarterTurns());
    }

    @PostMapping(value = "/expenses/{id}/reanalyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Expense reanalyze(@PathVariable long id, @Valid @RequestBody PhotoAction action) {
        return service.reanalyze(id, action.version(), action.hint());
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "") String month,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "dateDesc") String sort)
            throws Exception {
        return download(
                excel.export(service.filter(month, category, query, "CONFIRMED", sort), false),
                "expense-ledger.xlsx");
    }

    @GetMapping("/template.xlsx")
    public ResponseEntity<byte[]> template() throws Exception {
        return download(excel.export(List.of(), true), "expense-template.xlsx");
    }

    @PostMapping("/sheets/sync")
    public Map<String, Object> sync() throws Exception {
        return sheets.sync(service.filter("", "", "", "CONFIRMED", "dateAsc"));
    }

    private ResponseEntity<byte[]> download(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }
}
