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

/**
 * 영수증 등록·회전·재분석·검토·확정의 업무 흐름을 조정합니다.
 * 사진 파일과 DB의 관계를 관리하고, 확정 전 검증 및 중복·동시 수정 검사를 수행합니다.
 */
@Service
public class ReceiptService {
    private final LedgerRepository repo;
    private final ReceiptAnalyzer analyzer;
    private final ReceiptImages photos;
    private final MerchantDirectory merchants;
    private final PdfDocuments pdfs;
    private final DuplicateReviewService duplicates;
    private final Path images;

    /** 스프링이 필요한 객체와 데이터 폴더 설정을 주입하며, 증빙을 보관할 images 하위 폴더를 준비합니다. */
    public ReceiptService(
            LedgerRepository repo,
            ReceiptAnalyzer analyzer,
            ReceiptImages photos,
            MerchantDirectory merchants,
            PdfDocuments pdfs, DuplicateReviewService duplicates,
            @Value("${app.data-dir}") String data)
            throws Exception {
        this.repo = repo;
        this.analyzer = analyzer;
        this.photos = photos;
        this.merchants = merchants;
        this.pdfs = pdfs;
        this.duplicates = duplicates;
        images = Path.of(data).resolve("images").toAbsolutePath().normalize();
        Files.createDirectories(images);
    }

    /** 등록된 내역과 중복 여부입니다. 중복 사진은 다시 분석하거나 새 행을 만들지 않습니다. */
    public record UploadResult(Expense expense, boolean duplicate, List<DuplicateReviewService.Match> duplicateMatches) {}

    private UploadResult uploadResult(Expense expense, boolean exactDuplicate) {
        return new UploadResult(repo.get(expense.id()), exactDuplicate, duplicates.report(expense.id()).matches().stream()
                .filter(m -> m.decision().equals("PENDING")).toList());
    }

    /** PDF 원본 준비와 페이지 처리를 나누어 브라우저에 현재 페이지와 개별 실패를 표시합니다. */
    public synchronized PdfDocuments.Document preparePdf(MultipartFile file) throws Exception { return pdfs.prepare(file); }

    /** 같은 원본 PDF의 같은 페이지는 회전·재인식·사용자 수정 여부와 무관하게 중복으로 처리합니다. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized UploadResult uploadPdfPage(String pdfHash, int pageNumber, String hint) throws Exception {
        duplicates.lock();
        if (hint == null || hint.length() > 500) throw bad("업무 목적은 500자 이하로 입력해 주세요.");
        var document = pdfs.get(pdfHash);
        if (pageNumber < 1 || pageNumber > document.pageCount()) throw bad("PDF 페이지 번호를 확인해 주세요.");
        String pageHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("PDF:"+pdfHash+":"+pageNumber).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var existing = repo.byHash(pageHash);
        if (existing.isPresent()) return uploadResult(existing.get(), true);
        Path path = images.resolve(UUID.randomUUID()+".png");
        try {
            var rendered = pdfs.render(pdfHash, pageNumber);
            try { if (!ImageIO.write(rendered, "png", path.toFile())) throw bad("PDF 페이지를 사진으로 저장하지 못했습니다."); }
            finally { rendered.flush(); }
            var analysis = analyzer.analyze(path, hint);
            String name = document.originalName()+" ["+pageNumber+"/"+document.pageCount()+"쪽]";
            Expense saved = repo.insert(analysis,path.getFileName().toString(),pageHash,name,
                    pdfHash,pageNumber,document.pageCount());
            duplicates.refresh();
            return uploadResult(saved,false);
        } catch (Exception error) { Files.deleteIfExists(path); throw error; }
    }

    /** 성공한 페이지가 하나라도 있으면 원본을 유지합니다. 실패한 페이지만 재시도할 때 같은 원본을 다시 선택하면 됩니다. */
    public synchronized void finishPdf(String hash) throws Exception { pdfs.removeIfUnused(hash); }

    public Path originalPdf(long id) {
        String hash = repo.get(id).pdfHash();
        if (hash == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"PDF로 등록한 영수증이 아닙니다.");
        return pdfs.original(hash);
    }

    /** 같은 파일이 동시에 등록되는 것을 줄이기 위해 중복 확인부터 저장까지 순서대로 처리합니다. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized UploadResult upload(MultipartFile file, String hint) throws Exception {
        duplicates.lock();
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
            throw bad("10MB 이하의 JPG 또는 PNG 사진을 선택해 주세요.");
        if (hint.length() > 500) throw bad("업무 목적은 500자 이하로 입력해 주세요.");
        byte[] bytes = file.getBytes();
        // 원본 바이트의 SHA-256으로 중복을 판별하므로 같은 파일명이라도 내용이 다르면 새 사진입니다.
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var existing = repo.byHash(hash);
        if (existing.isPresent()) return uploadResult(existing.get(), true);
        Path path = null;
        try {
            // 촬영 방향을 픽셀에 반영한 증빙 PNG를 저장한 뒤, 그 사진을 분석과 미리보기에 공통 사용합니다.
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
            duplicates.refresh();
            return uploadResult(saved, false);
        } catch (Exception e) {
            // 등록이 끝나지 않은 사진만 되돌려 DB에 연결되지 않은 파일이 쌓이는 것을 줄입니다.
            if (path != null) Files.deleteIfExists(path);
            throw e;
        }
    }

    /** 새 회전 이미지를 먼저 저장하고 DB 연결을 바꾼 뒤 이전 이미지를 정리합니다. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized Expense rotate(long id, int version, int quarterTurns) throws Exception {
        duplicates.lock();
        Expense current = repo.get(id);
        checkVersion(current, version);
        assertNotExcluded(current);
        repo.assertNotPosted(id);
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
            duplicates.invalidateImage(id);
            duplicates.refresh();
            // DB가 완성된 새 이미지를 가리킨 다음에만 이전 이미지를 삭제합니다.
            try {
                if (!repo.isBookkeepingEvidence(old.getFileName().toString())) Files.deleteIfExists(old);
            } catch (Exception ignored) {
                // 이전 사진 정리 실패 때문에 이미 완료된 새 증빙 연결을 되돌리지는 않습니다.
            }
            return repo.get(id);
        } catch (Exception e) {
            Files.deleteIfExists(replacement);
            throw e;
        } finally {
            source.flush();
        }
    }

    /** 최신 사진을 재분석하고 직접 작성한 문맥은 보존합니다. 이전의 잘못된 도서 기본값은 재검토합니다. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized Expense reanalyze(long id, int version, String hint) {
        duplicates.lock();
        Expense current = repo.get(id);
        checkVersion(current, version);
        assertNotExcluded(current);
        repo.assertNotPosted(id);
        String purpose = hint == null ? current.description() : hint;
        if (purpose.length() > 500) throw bad("업무 목적은 500자 이하로 입력해 주세요.");
        // 이전 지점명 오분류가 만든 기본 적요를 사용자 업무 목적으로 재사용하면 같은 오류가 반복됩니다.
        boolean staleBookDefault = "OCR_RULES".equals(current.analysisSource())
                && "도서인쇄비".equals(current.category()) && "도서 구입".equals(current.description())
                && !ReceiptTextParser.book(Objects.toString(current.ocrText(), ""));
        if (staleBookDefault && "도서 구입".equals(purpose.trim())) purpose = "";
        var analysis = analyzer.analyze(image(id), purpose);
        if (analysis.source().equals("OCR_RULES") && analysis.text().isBlank())
            throw bad("문자를 다시 읽지 못했습니다. 기존 입력은 유지됩니다. 사진 방향이나 선명도를 확인해 주세요.");
        repo.reanalyze(id, version, analysis, staleBookDefault && !"도서인쇄비".equals(analysis.category()));
        duplicates.refresh(); return repo.get(id);
    }

    /** 화면을 연 뒤 다른 창에서 바뀐 내역을 덮어쓰지 못하게 저장 버전을 비교합니다. */
    private void checkVersion(Expense current, int version) {
        if (current.version() != version)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "다른 창에서 수정되었습니다. 새로고침 후 다시 열어 주세요.");
    }

    /** 직접 입력 내역은 증빙 파일·해시 없이 고유한 임시 이름으로 식별합니다. */
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
                        "",
                        ""),
                null,
                null,
                "manual-" + UUID.randomUUID());
    }

    /** 분류·날짜·세액을 검증합니다. 필수 항목이 갖춰진 내역만 확정하고 거래처 보정을 기억합니다. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized Expense save(long id, ExpenseInput x) {
        duplicates.lock();
        Expense current = repo.get(id);
        checkVersion(current, x.version());
        assertNotExcluded(current);
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
        // 세액을 추정해 채우지 않습니다. 세 항목을 모두 입력한 경우에만 합계의 일치 여부를 검사합니다.
        if (x.amount() != null
                && x.supplyAmount() != null
                && x.vatAmount() != null
                && x.supplyAmount() + x.vatAmount() != x.amount())
            throw bad("공급가액 + 부가세가 총금액과 다릅니다. 원본을 확인해 주세요.");
        if (x.supplyAmount() != null && x.amount() != null && x.supplyAmount() > x.amount()
                || x.vatAmount() != null && x.amount() != null && x.vatAmount() > x.amount())
            throw bad("공급가액 또는 부가세가 총금액보다 큽니다.");
        // 검토 중에는 미확인 항목을 허용하지만, 엑셀 집계에 들어가는 확정 건은 필수 값을 요구합니다.
        if (x.status().equals("CONFIRMED")
                && (x.receiptDate() == null
                        || x.receiptDate().isBlank()
                        || x.vendor().isBlank()
                        || x.description().isBlank()
                        || x.amount() == null
                        || x.category().equals("미분류")))
            throw bad("확정하려면 날짜·거래처·계정과목·적요·총금액을 입력해 주세요.");
        if (x.status().equals("CONFIRMED")) duplicates.assertConfirmable(current, x);
        Expense updated = repo.update(id, x);
        duplicates.refresh();
        updated = repo.get(id);
        if (updated.status().equals("CONFIRMED"))
            try {
                // 사용자가 확정한 거래처만 기억하며, 사전 저장 실패가 장부 저장 성공을 취소하지는 않습니다.
                merchants.remember(Objects.toString(updated.ocrText(), ""), updated.vendor());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(ReceiptService.class)
                        .warn("거래처 보정 사전을 저장하지 못했습니다. 장부 저장은 완료되었습니다.");
            }
        return updated;
    }

    /** DB의 상대 파일명을 실제 경로로 해석하고 증빙 폴더 밖으로 벗어나는 접근을 차단합니다. */
    public Path image(long id) {
        var e = repo.get(id);
        if (e.imagePath() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부된 사진이 없습니다.");
        Path path = images.resolve(e.imagePath()).normalize();
        if (!path.startsWith(images) || !Files.exists(path))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사진을 찾을 수 없습니다.");
        return path;
    }

    /** 내역 삭제 후 연결 사진을 정리합니다. 파일 삭제 범위는 증빙 폴더 내부로 제한합니다. */
    @org.springframework.transaction.annotation.Transactional
    public synchronized void delete(long id) throws Exception {
        duplicates.lock();
        var e = repo.get(id);
        repo.delete(id);
        duplicates.refresh();
        if (e.imagePath() != null) {
            Path p = images.resolve(e.imagePath()).normalize();
            if (p.startsWith(images)) Files.deleteIfExists(p);
        }
        if (e.pdfHash() != null) try { pdfs.removeIfUnused(e.pdfHash()); }
        catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(ReceiptService.class).warn("영수증 삭제는 완료했으나 연결 없는 PDF 정리에 실패했습니다.");
        }
    }

    /** 장부 조회·엑셀 출력에서 공통으로 사용하는 필터입니다. 미확인 날짜·금액은 정렬 뒤쪽에 둡니다. */
    public List<Expense> filter(
            String month, String category, String query, String status, String sort) {
        if (month != null && !month.isBlank())
            try {
                java.time.YearMonth.parse(month);
            } catch (Exception e) {
                throw bad("조회 월 형식이 올바르지 않습니다.");
            }
        // 검색 대상에는 거래처뿐 아니라 적요·비고·사용자·부서·승인번호도 포함합니다.
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
                .filter(e -> status == null || status.isBlank()
                        || status.equals("SUSPECTED") && e.duplicateState().equals("SUSPECTED")
                        || status.equals("EXCLUDED") && e.duplicateState().equals("EXCLUDED")
                        || e.status().equals(status) && e.duplicateState().equals("NONE"))
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
                                                + e.department() + " " + e.approvalNumber())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(q))
                .sorted(order.thenComparing(Expense::id))
                .toList();
    }

    /** 사용자가 입력이나 설정을 고칠 수 있는 문제를 400 오류로 표현합니다. */
    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void assertNotExcluded(Expense e) {
        if ("EXCLUDED".equals(e.duplicateState())) throw bad("중복 제외된 내역입니다. 제외를 취소한 뒤 수정해 주세요.");
    }
}
