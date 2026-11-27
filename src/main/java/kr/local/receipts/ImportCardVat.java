package kr.local.receipts;

import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 새 카드 내역에 기존 조회·검토 규칙을 적용하며 실제/테스트 조회 출처를 거래별로 보존합니다. */
@Service
public class ImportCardVat {
    private final ImportAnalysis analysis;
    private final NtsBusinessLookup nts;
    private final ObjectMapper json;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ImportCardVat(ImportAnalysis analysis, NtsBusinessLookup nts, ObjectMapper json) {
        this(analysis, nts, json, Clock.systemUTC());
    }
    ImportCardVat(ImportAnalysis analysis, NtsBusinessLookup nts, ObjectMapper json, Clock clock) {
        this.analysis = analysis; this.nts = nts; this.json = json; this.clock = clock;
    }

    public synchronized List<ImportAnalysis.Document> review(String mode) throws IOException {
        if (!Set.of("LIVE", "TEST").contains(mode)) throw ReceiptService.bad("실제 조회 또는 테스트 조회를 선택해 주세요.");
        var documents = analysis.list().stream().filter(document -> document.file().kind() == ImportFiles.Kind.CARD_PDF
                && !document.analysis().rows().isEmpty()).toList();
        if (documents.isEmpty()) throw ReceiptService.bad("먼저 카드 자료를 분석해 주세요.");
        var numbers = documents.stream().flatMap(document -> document.analysis().rows().stream())
                .map(row -> number(row.businessNumber())).filter(ReceiptMetadataParser::validBusinessNumber).distinct().toList();
        var lookups = new HashMap<String, NtsBusinessLookup.Result>();
        var failures = new HashMap<String, String>();
        if (mode.equals("TEST")) {
            try (var input = new ClassPathResource("samples/import-nts-test.json").getInputStream()) {
                var samples = json.readTree(input);
                for (String number : numbers) {
                    var sample = samples.path(number);
                    String type = sample.path("taxTypeCode").asText("");
                    lookups.put(number, new NtsBusinessLookup.Result(number, type.isEmpty() ? "NOT_FOUND" : "FOUND",
                            type.isEmpty() ? "" : "01", type.isEmpty() ? "조회되지 않음" : "계속사업자",
                            type, sample.path("taxType").asText("테스트 응답 없음"), "", "",
                            sample.path("invoiceAppliedOn").asText(""), "", Instant.now(clock).toString()));
                }
            }
        } else {
            for (int start = 0; start < numbers.size(); start += 100) {
                var batch = numbers.subList(start, Math.min(start + 100, numbers.size()));
                try { lookups.putAll(nts.requestBatch(batch)); }
                catch (org.springframework.web.server.ResponseStatusException e) {
                    for (String number : batch) failures.put(number, Objects.toString(e.getReason(), "국세청 조회 실패"));
                }
            }
        }
        for (var document : documents) {
            var reviews = new ArrayList<ImportAnalysis.CardReview>();
            for (var row : document.analysis().rows()) {
                String number = number(row.businessNumber());
                String error = failures.getOrDefault(number, "");
                var lookup = lookups.get(number);
                // 실패 시 같은 번호의 이전 실제 조회 사본만 남깁니다. 테스트 응답을 실제 조회로 승격하지 않습니다.
                if (lookup == null && mode.equals("LIVE") && !error.isEmpty()) {
                    lookup = document.analysis().cardReviews().stream().filter(previous -> previous.mode().equals("LIVE")
                            && number.equals(previous.businessNumber()) && previous.lookup() != null)
                            .map(ImportAnalysis.CardReview::lookup).findFirst().orElse(null);
                }
                var review = evaluate(row, lookup, error);
                Long candidate = review.code().equals("CANDIDATE") ? row.vat() : null;
                reviews.add(new ImportAnalysis.CardReview(row.page(), row.line(), row.sheet(), number, mode, lookup,
                        review, candidate, Instant.now(clock).toString(), error));
            }
            analysis.saveCardReviews(document.analysis(), reviews);
        }
        return analysis.list();
    }

    private static VatDeductionReview.Result evaluate(ImportAnalysis.Transaction row, NtsBusinessLookup.Result lookup, String failure) {
        if (!failure.isEmpty()) return VatDeductionReview.review("최근 조회 실패 · " + failure
                + (lookup == null ? "" : " · 이전 실제 조회 사본을 표시합니다."), "LOOKUP_FAILED");
        if (!ReceiptMetadataParser.validBusinessNumber(row.businessNumber()))
            return VatDeductionReview.review("거래처 사업자번호가 없거나 형식이 올바르지 않습니다.", "NUMBER_UNKNOWN");
        if (row.amount() != null && row.amount() < 0 || row.type().contains("취소"))
            return VatDeductionReview.review("취소·환불 내역입니다. 원거래의 세액과 연결해 검토하세요.", "REVERSAL");
        if (!"KRW".equals(row.currency())) return VatDeductionReview.review("통화가 원화로 확인되지 않았습니다.", "CURRENCY_UNKNOWN");
        if (!row.warning().isBlank()) return VatDeductionReview.review("추출 확인사항 해결 후 검토 · " + row.warning(), "EXTRACTION_REVIEW");
        String purpose = Objects.toString(row.description(), "");
        return VatDeductionReview.evaluate(new VatDeductionReview.Input(row.date(), row.party(), ReceiptAnalyzer.classify(purpose),
                purpose, row.amount(), row.supply(), row.vat(), "법인카드", "", "", "NONE", row.businessNumber(), lookup, null));
    }
    private static String number(String value) { return Objects.toString(value, "").replace("-", "").strip(); }
}
