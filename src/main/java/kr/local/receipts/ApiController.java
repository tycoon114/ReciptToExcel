package kr.local.receipts;

import jakarta.validation.Valid;

import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 브라우저가 호출하는 /api 경로를 정의합니다.
 * 요청과 응답 형식을 담당하며, 검증·분석·저장 등의 실제 처리는 서비스에 위임합니다.
 */
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

    /** 화면 선택 목록과 연결 여부를 제공합니다. 서비스 계정 키나 비밀번호는 응답하지 않습니다. */
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

    /** 월·분류·상태·검색어·정렬 조건을 적용한 장부 목록을 조회합니다. */
    @GetMapping("/expenses")
    public List<Expense> list(
            @RequestParam(defaultValue = "") String month,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "dateDesc") String sort) {
        return service.filter(month, category, query, status, sort);
    }

    /** 사진과 업무 목적을 받아 분석한 뒤 검토 대기로 등록합니다. 중복이면 기존 내역을 돌려줍니다. */
    @PostMapping(value = "/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReceiptService.UploadResult upload(
            @RequestParam MultipartFile file, @RequestParam(defaultValue = "") String hint)
            throws Exception {
        return service.upload(file, hint);
    }

    /** 영수증 사진 없이 직접 입력할 빈 검토 내역을 만듭니다. */
    @PostMapping("/expenses")
    public Expense manual() {
        return service.manual();
    }

    /** 입력 형식을 검증하고 수정 내용을 저장합니다. 확정에 필요한 업무 검증은 서비스가 수행합니다. */
    @PutMapping(value = "/expenses/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Expense save(@PathVariable long id, @Valid @RequestBody ExpenseInput input) {
        return service.save(id, input);
    }

    /** 선택한 장부 내역과 연결된 증빙 사진을 삭제합니다. */
    @DeleteMapping("/expenses/{id}")
    public Map<String, Boolean> delete(@PathVariable long id) throws Exception {
        service.delete(id);
        return Map.of("deleted", true);
    }

    /** 저장된 증빙 PNG를 반환합니다. 회전한 사진이 캐시에 가려지지 않도록 저장을 금지합니다. */
    @GetMapping("/expenses/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new FileSystemResource(service.image(id)));
    }

    /** 사진 작업 요청입니다. version은 동시 수정 확인, quarterTurns는 좌우 90도 회전, hint는 업무 목적입니다. */
    public record PhotoAction(
            @jakarta.validation.constraints.Min(0) int version,
            @jakarta.validation.constraints.Min(-1) @jakarta.validation.constraints.Max(1)
                    Integer quarterTurns,
            @jakarta.validation.constraints.Size(max = 500) String hint) {}

    /** 입력 버전이 최신인지 확인하고 사진만 회전합니다. 내용 재인식은 별도 요청으로 처리합니다. */
    @PostMapping(value = "/expenses/{id}/rotate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Expense rotate(@PathVariable long id, @Valid @RequestBody PhotoAction action)
            throws Exception {
        if (action.quarterTurns() == null) throw ReceiptService.bad("회전 방향을 선택해 주세요.");
        return service.rotate(id, action.version(), action.quarterTurns());
    }

    /** 저장된 사진을 다시 읽습니다. 결과는 재검토하도록 검토 대기 상태로 돌아갑니다. */
    @PostMapping(value = "/expenses/{id}/reanalyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Expense reanalyze(@PathVariable long id, @Valid @RequestBody PhotoAction action) {
        return service.reanalyze(id, action.version(), action.hint());
    }

    /** 조회 조건과 정렬을 유지하되 확정된 내역만 엑셀로 내보냅니다. */
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

    /** 장부 입력과 계정별 집계 수식이 포함된 빈 엑셀 양식을 내려줍니다. */
    @GetMapping("/template.xlsx")
    public ResponseEntity<byte[]> template() throws Exception {
        return download(excel.export(List.of(), true), "expense-template.xlsx");
    }

    /** 화면의 조회 필터와 관계없이 확정된 전체 장부를 구글 전용 탭에 전송합니다. */
    @PostMapping("/sheets/sync")
    public Map<String, Object> sync() throws Exception {
        return sheets.sync(service.filter("", "", "", "CONFIRMED", "dateAsc"));
    }

    /** 생성한 엑셀 바이트를 파일 다운로드 응답으로 포장합니다. */
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
