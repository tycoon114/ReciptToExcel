package kr.local.receipts;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 등록 파일별 분석 결과를 원자적으로 교체합니다. 재분석 시 이전 행을 누적하지 않습니다. */
@Service
public class ImportAnalysis {
    public record Transaction(int page, int line, String date, String time, String party,
            String businessNumber, String type, String reference, String originalReference,
            Long amount, Long supply, Long vat, Long deposit, Long withdrawal, Long balance,
            String description, String currency, String warning, String sheet) {
        public Transaction(int page, int line, String date, String time, String party, String businessNumber,
                String type, String reference, String originalReference, Long amount, Long supply, Long vat,
                Long deposit, Long withdrawal, Long balance, String description, String currency, String warning) {
            this(page, line, date, time, party, businessNumber, type, reference, originalReference, amount, supply, vat,
                    deposit, withdrawal, balance, description, currency, warning, "");
        }
        Transaction inSheet(String name) {
            return new Transaction(page, line, date, time, party, businessNumber, type, reference, originalReference,
                    amount, supply, vat, deposit, withdrawal, balance, description, currency, warning, name);
        }
    }
    public record Issue(int page, String message, String sheet) {
        public Issue(int page, String message) { this(page, message, ""); }
    }
    public record Result(String id, String status, String analyzedAt,
            List<Transaction> rows, List<Issue> issues) {}
    public record Document(ImportFiles.Entry file, Result analysis) {}
    private final ImportFiles files;
    private final ObjectMapper json;
    private final Path root;

    public ImportAnalysis(ImportFiles files, ObjectMapper json, @Value("${app.data-dir}") String data) throws IOException {
        this.files = files;
        this.json = json;
        root = Path.of(data).resolve("import-analysis");
        Files.createDirectories(root);
    }

    public synchronized List<Document> list() throws IOException {
        var documents = new ArrayList<Document>();
        for (var file : files.list()) {
            Path path = root.resolve(file.id() + ".json");
            Result result;
            if (Files.exists(path)) {
                result = json.readValue(Files.readString(path), Result.class);
            } else {
                boolean photo = !analyzable(file);
                result = new Result(file.id(), photo ? "PHOTO_PENDING" : "PENDING", "", List.of(), List.of());
            }
            documents.add(new Document(file, result));
        }
        return List.copyOf(documents);
    }

    public synchronized Result analyze(String id) throws IOException {
        var file = files.get(id);
        if (!analyzable(file)) {
            return new Result(id, "PHOTO_PENDING", "", List.of(), List.of());
        }
        var rows = new ArrayList<Transaction>();
        var issues = new ArrayList<Issue>();
        boolean failed = false;
        try {
            if (!"application/pdf".equals(file.mediaType())) {
                var extracted = ImportExcelParser.parse(file.kind(), files.original(id), file.invoiceDirection());
                rows.addAll(extracted.rows()); issues.addAll(extracted.issues());
            } else {
                try (var pdf = Loader.loadPDF(files.original(id).toFile())) {
                    for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                        try {
                            var extracted = ImportPdfParser.parse(file.kind(), PdfPageText.read(pdf, page), page, file.invoiceDirection());
                            rows.addAll(extracted.rows());
                            issues.addAll(extracted.issues());
                        } catch (Exception e) {
                            issues.add(new Issue(page, "페이지 분석 실패. 원본 PDF의 글자와 표 배치를 확인해 주세요."));
                        }
                    }
                }
            }
        } catch (Exception e) {
            failed = true;
            issues.add(new Issue(0, "원본 자료를 읽지 못했습니다. 파일 상태를 확인한 뒤 다시 분석해 주세요."));
        }
        String status = failed ? "FAILED" : issues.isEmpty() ? "READY" : "REVIEW";
        var result = new Result(id, status, Instant.now().toString(), List.copyOf(rows), List.copyOf(issues));
        Path temporary = Files.createTempFile(root, "analysis-", ".tmp");
        try {
            Files.writeString(temporary, json.writeValueAsString(result));
            Files.move(temporary, root.resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return result;
    }

    static boolean analyzable(ImportFiles.Entry file) {
        return Set.of("application/pdf", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").contains(file.mediaType());
    }

    static String month(String value) {
        if (value == null || value.isBlank()) return "";
        try { return YearMonth.parse(value).toString(); }
        catch (Exception e) { throw ReceiptService.bad("조회 월은 YYYY-MM 형식으로 선택해 주세요."); }
    }
}
