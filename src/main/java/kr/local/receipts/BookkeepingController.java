package kr.local.receipts;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.core.io.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 영수증 검토·확정 API와 분리된 기장 API입니다. 모든 변경 요청에는 기존 로컬 요청 보호가 적용됩니다. */
@RestController
@RequestMapping("/api/bookkeeping")
public class BookkeepingController {
    private final BookkeepingService service;
    private final BookkeepingExporter exporter;
    public BookkeepingController(BookkeepingService service, BookkeepingExporter exporter) {
        this.service=service; this.exporter=exporter;
    }

    /** 화면의 대변 선택 목록과 서버 검증 목록을 일치시킵니다. */
    @GetMapping("/config")
    public Map<String,Object> config() {
        return Map.of("creditAccounts",BookkeepingService.CREDIT_ACCOUNTS);
    }

    /** 기장 완료와 취소 이력을 함께 조회하며, 월 조건은 기장일에 적용합니다. */
    @GetMapping("/entries")
    public List<BookkeepingService.Entry> list(@RequestParam(defaultValue="") String month,
            @RequestParam(defaultValue="") String query) { return service.list(month,query); }

    /** 현재 영수증이 아니라 해당 전표가 기장될 때 연결한 증빙 사진을 엽니다. */
    @GetMapping("/entries/{id}/image")
    public ResponseEntity<Resource> evidence(@PathVariable long id) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(new FileSystemResource(service.evidence(id)));
    }

    /** 사용자가 확인한 확정 영수증으로 새 전표를 만들고 갱신된 영수증 버전을 함께 반환합니다. */
    @PostMapping(value="/expenses/{id}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public BookkeepingService.ActionResult post(@PathVariable long id, @Valid @RequestBody BookkeepingService.PostInput input) {
        return service.post(id,input);
    }

    /** 삭제 대신 취소 담당자·시각·사유를 보존하고, 원본 정정과 재기장이 가능하도록 전표를 취소합니다. */
    @PostMapping(value="/entries/{id}/cancel",consumes=MediaType.APPLICATION_JSON_VALUE)
    public BookkeepingService.ActionResult cancel(@PathVariable long id, @Valid @RequestBody BookkeepingService.CancelInput input) {
        return service.cancel(id,input);
    }

    /** 분개장은 기장일 기준으로 필터링합니다. 취소 전표는 별도 이력 시트에만 출력합니다. */
    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue="") String month,
            @RequestParam(defaultValue="") String query) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"bookkeeping-journal.xlsx\"")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(exporter.export(service.list(month,query)));
    }
}
