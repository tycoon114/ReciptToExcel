package kr.local.receipts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

import javax.imageio.ImageIO;

@Service
public class ReceiptService {
    private final LedgerRepository repo;
    private final ReceiptAnalyzer analyzer;
    private final ReceiptImages photos;
    private final MerchantDirectory merchants;
    private final Path images;

    public ReceiptService(
            LedgerRepository repo,
            ReceiptAnalyzer analyzer,
            ReceiptImages photos,
            MerchantDirectory merchants,
            @Value("${app.data-dir}") String data)
            throws Exception {
        this.repo = repo;
        this.analyzer = analyzer;
        this.photos = photos;
        this.merchants = merchants;
        images = Path.of(data).resolve("images").toAbsolutePath().normalize();
        Files.createDirectories(images);
    }

    public record UploadResult(Expense expense, boolean duplicate) {}

    public synchronized UploadResult upload(MultipartFile file, String hint) throws Exception {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
            throw bad("10MB 이하의 JPG 또는 PNG 사진을 선택해 주세요.");
        if (hint.length() > 500) throw bad("업무 목적은 500자 이하로 입력해 주세요.");
        byte[] bytes = file.getBytes();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var existing = repo.byHash(hash);
        if (existing.isPresent()) return new UploadResult(existing.get(), true);
        Path path = null;
        try {
            var normalized = photos.normalize(bytes);
            String filename = UUID.randomUUID() + ".png";
            path = images.resolve(filename);
            try {
                ImageIO.write(normalized, "png", path.toFile());
            } finally {
                normalized.flush();
            }
            var analysis = analyzer.analyze(path, hint);
            Expense saved =
                    repo.insert(
                            analysis,
                            filename,
                            hash,
                            ReceiptAnalyzer.cut(
                                    Objects.toString(file.getOriginalFilename(), "receipt"), 255));
            return new UploadResult(saved, false);
        } catch (Exception e) {
            if (path != null) Files.deleteIfExists(path);
            throw e;
        }
    }

    public synchronized Expense rotate(long id, int version, int quarterTurns) throws Exception {
        Expense current = repo.get(id);
        checkVersion(current, version);
        Path old = image(id), replacement = images.resolve(UUID.randomUUID() + ".png");
        var source = ImageIO.read(old.toFile());
        if (source == null) throw bad("사진을 읽을 수 없습니다.");
        try {
            var rotated = photos.rotate(source, quarterTurns);
            try {
                ImageIO.write(rotated, "png", replacement.toFile());
            } finally {
                rotated.flush();
            }
            Expense updated = repo.rotate(id, version, replacement.getFileName().toString());
            // Remove only the obsolete image, after the DB points at the complete replacement.
            try {
                Files.deleteIfExists(old);
            } catch (java.io.IOException ignored) {
            }
            return updated;
        } catch (Exception e) {
            Files.deleteIfExists(replacement);
            throw e;
        } finally {
            source.flush();
        }
    }

    public synchronized Expense reanalyze(long id, int version, String hint) {
        Expense current = repo.get(id);
        checkVersion(current, version);
        String purpose = hint == null ? current.description() : hint;
        if (purpose.length() > 500) throw bad("업무 목적은 500자 이하로 입력해 주세요.");
        var analysis = analyzer.analyze(image(id), purpose);
        if (analysis.source().equals("OCR_RULES") && analysis.text().isBlank())
            throw bad("문자를 다시 읽지 못했습니다. 기존 입력은 유지됩니다. 사진 방향이나 선명도를 확인해 주세요.");
        return repo.reanalyze(id, version, analysis);
    }

    private void checkVersion(Expense current, int version) {
        if (current.version() != version)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "다른 창에서 수정되었습니다. 새로고침 후 다시 열어 주세요.");
    }

    public Expense manual() {
        return repo.insert(
                new ReceiptAnalyzer.Analysis(
                        null,
                        "",
                        "미분류",
                        "",
                        null,
                        null,
                        null,
                        "미확인",
                        "",
                        "MANUAL",
                        "직접 입력한 영수증입니다.",
                        ""),
                null,
                null,
                "manual-" + UUID.randomUUID());
    }

    public Expense save(long id, ExpenseInput x) {
        if (!Set.of("REVIEW", "CONFIRMED").contains(x.status())) throw bad("잘못된 검토 상태입니다.");
        if (!ReceiptAnalyzer.CATEGORIES.contains(x.category())
                || !ReceiptAnalyzer.PAYMENTS.contains(x.paymentMethod()))
            throw bad("목록에 있는 계정과목과 결제수단을 선택해 주세요.");
        if (x.receiptDate() != null && !x.receiptDate().isBlank())
            try {
                LocalDate.parse(x.receiptDate());
            } catch (Exception e) {
                throw bad("날짜를 YYYY-MM-DD 형식으로 입력해 주세요.");
            }
        if (x.amount() != null
                && x.supplyAmount() != null
                && x.vatAmount() != null
                && x.supplyAmount() + x.vatAmount() != x.amount())
            throw bad("공급가액 + 부가세가 총금액과 다릅니다. 원본을 확인해 주세요.");
        if (x.supplyAmount() != null && x.amount() != null && x.supplyAmount() > x.amount()
                || x.vatAmount() != null && x.amount() != null && x.vatAmount() > x.amount())
            throw bad("공급가액 또는 부가세가 총금액보다 큽니다.");
        if (x.status().equals("CONFIRMED")
                && (x.receiptDate() == null
                        || x.receiptDate().isBlank()
                        || x.vendor().isBlank()
                        || x.description().isBlank()
                        || x.amount() == null
                        || x.category().equals("미분류")))
            throw bad("확정하려면 날짜·거래처·계정과목·적요·총금액을 입력해 주세요.");
        Expense updated = repo.update(id, x);
        if (updated.status().equals("CONFIRMED"))
            try {
                merchants.remember(Objects.toString(updated.ocrText(), ""), updated.vendor());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(ReceiptService.class)
                        .warn("거래처 보정 사전을 저장하지 못했습니다. 장부 저장은 완료되었습니다.");
            }
        return updated;
    }

    public Path image(long id) {
        var e = repo.get(id);
        if (e.imagePath() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부된 사진이 없습니다.");
        Path path = images.resolve(e.imagePath()).normalize();
        if (!path.startsWith(images) || !Files.exists(path))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사진을 찾을 수 없습니다.");
        return path;
    }

    public synchronized void delete(long id) throws Exception {
        var e = repo.get(id);
        repo.delete(id);
        if (e.imagePath() != null) {
            Path p = images.resolve(e.imagePath()).normalize();
            if (p.startsWith(images)) Files.deleteIfExists(p);
        }
    }

    public List<Expense> filter(
            String month, String category, String query, String status, String sort) {
        if (month != null && !month.isBlank())
            try {
                java.time.YearMonth.parse(month);
            } catch (Exception e) {
                throw bad("조회 월 형식이 올바르지 않습니다.");
            }
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Comparator<Expense> order =
                switch (Objects.toString(sort, "dateDesc")) {
                    case "dateAsc" ->
                            Comparator.comparing(
                                    Expense::receiptDate,
                                    Comparator.nullsLast(Comparator.naturalOrder()));
                    case "amountDesc" ->
                            Comparator.comparing(
                                    Expense::amount,
                                    Comparator.nullsLast(Comparator.reverseOrder()));
                    case "category" ->
                            Comparator.comparing(Expense::category)
                                    .thenComparing(
                                            Expense::receiptDate,
                                            Comparator.nullsLast(Comparator.naturalOrder()));
                    default ->
                            Comparator.comparing(
                                    Expense::receiptDate,
                                    Comparator.nullsLast(Comparator.reverseOrder()));
                };
        return repo.all().stream()
                .filter(
                        e ->
                                month == null
                                        || month.isBlank()
                                        || e.receiptDate() != null
                                                && e.receiptDate().startsWith(month))
                .filter(
                        e ->
                                category == null
                                        || category.isBlank()
                                        || e.category().equals(category))
                .filter(e -> status == null || status.isBlank() || e.status().equals(status))
                .filter(
                        e ->
                                (e.vendor()
                                                + " "
                                                + e.description()
                                                + " "
                                                + e.note()
                                                + " "
                                                + e.ownerName()
                                                + " "
                                                + e.department())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(q))
                .sorted(order.thenComparing(Expense::id))
                .toList();
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
