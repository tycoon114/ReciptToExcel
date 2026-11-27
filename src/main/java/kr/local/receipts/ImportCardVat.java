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
                && !"application/x-receipt-generated-test".equals(document.file().mediaType())
                && !document.analysis().rows().isEmpty()).toList();
        if (documents.isEmpty()) throw ReceiptService.bad("먼저 카드 자료를 분석해 주세요.");
        var numbers = documents.stream().flatMap(document -> document.analysis().rows().stream())
                .map(row -> number(row.businessNumber())).filter(ReceiptMetadataParser::validBusinessNumber).distinct().toList();
        var lookups = new HashMap<String, NtsBusinessLookup.Result>();
        var failures = new HashMap<String, String>();
        if (mode.equals("TEST")) {
            lookups.putAll(testLookups(numbers));
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

    public synchronized List<ImportAnalysis.Document> generateTestMonth(String month) throws IOException {
        String selected = ImportAnalysis.month(month);
        if (selected.isEmpty()) throw ReceiptService.bad("테스트 데이터를 넣을 월을 선택해 주세요.");
        String date = selected + "-10", now = Instant.now(clock).toString();
        var cards = List.of(
                testRow(1, date, "[개발 테스트] 직원식사", "0000000015", "사용", 61000L, 55455L, 5545L, null, null, "직원 점심 식사"),
                testRow(2, date, "[개발 테스트] 사무용품", "0000000029", "사용", 110000L, 100000L, 10000L, null, null, "사무용품 구입"),
                testRow(3, date, "[개발 테스트] 간이과세", "0000000048", "사용", 11000L, 10000L, 1000L, null, null, "직원 회의 커피"),
                testRow(4, date, "[개발 테스트] 용도미확인", "0000000015", "사용", 33000L, 30000L, 3000L, null, null, ""));
        var lookups = testLookups(cards.stream().map(row -> row.businessNumber()).distinct().toList());
        var reviews = new ArrayList<ImportAnalysis.CardReview>();
        for (var row : cards) {
            var lookup = lookups.get(row.businessNumber()); var review = evaluate(row, lookup, "");
            reviews.add(new ImportAnalysis.CardReview(row.page(), row.line(), row.sheet(), row.businessNumber(), "TEST", lookup,
                    review, "CANDIDATE".equals(review.code()) ? row.vat() : null, now, ""));
        }
        var accounts = List.of(
                testRow(1, date, "[개발 테스트] 입금", "", "입금", null, null, null, 330000L, null, "개발 테스트 입금"),
                testRow(2, date, "[개발 테스트] 계좌지급", "", "출금", null, null, null, null, 61000L, "개발 테스트 출금"));
        var invoices = List.of(testRow(1, date, "[개발 테스트] 매입서비스", "0000000034", "매입",
                55000L, 50000L, 5000L, null, null, "개발 테스트 세금계산서"));
        return analysis.saveTestMonth(selected, List.of(
                testDocument(selected, ImportFiles.Kind.CARD_PDF, now, cards, reviews),
                testDocument(selected, ImportFiles.Kind.ACCOUNT_PDF, now, accounts, List.of()),
                testDocument(selected, ImportFiles.Kind.TAX_INVOICE, now, invoices, List.of())));
    }

    private static ImportAnalysis.Transaction testRow(int line, String date, String party, String number, String type,
            Long amount, Long supply, Long vat, Long deposit, Long withdrawal, String description) {
        return new ImportAnalysis.Transaction(1, line, date, "", party, number, type, "TEST-" + line, "", amount,
                supply, vat, deposit, withdrawal, null, description, "KRW", "", "개발 테스트");
    }

    private static ImportAnalysis.Document testDocument(String month, ImportFiles.Kind kind, String now,
            List<ImportAnalysis.Transaction> rows, List<ImportAnalysis.CardReview> reviews) {
        String id = "generated-test-" + month + "-" + kind;
        var entry = new ImportFiles.Entry(id, kind, "[개발 테스트] " + month + " " + ImportWorkbook.label(kind), 0,
                "application/x-receipt-generated-test", 0, now, kind == ImportFiles.Kind.TAX_INVOICE ? "매입" : "");
        return new ImportAnalysis.Document(entry, new ImportAnalysis.Result(id, "READY", now, rows, List.of(), reviews));
    }

    public synchronized List<ImportAnalysis.Document> clearTestReviews() throws IOException {
        // 테스트 조회 실행과 초기화를 직렬화해 늦게 끝난 조회가 결과를 되살리지 않게 합니다.
        return analysis.clearTestReviews();
    }

    private Map<String, NtsBusinessLookup.Result> testLookups(List<String> numbers) throws IOException {
        var results = new HashMap<String, NtsBusinessLookup.Result>();
        try (var input = new ClassPathResource("samples/import-nts-test.json").getInputStream()) {
            var samples = json.readTree(input);
            for (String number : numbers) {
                var sample = samples.path(number);
                String type = sample.path("taxTypeCode").asText("");
                results.put(number, new NtsBusinessLookup.Result(number, type.isEmpty() ? "NOT_FOUND" : "FOUND",
                        type.isEmpty() ? "" : "01", type.isEmpty() ? "조회되지 않음" : "계속사업자",
                        type, sample.path("taxType").asText("테스트 응답 없음"), "", "",
                        sample.path("invoiceAppliedOn").asText(""), "", Instant.now(clock).toString()));
            }
        }
        return results;
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
