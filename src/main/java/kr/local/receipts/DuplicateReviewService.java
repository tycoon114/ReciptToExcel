package kr.local.receipts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** 다른 파일 형식의 영수증을 거래 정보·이미지로 비교하고, 사용자 판단을 근거와 함께 보존합니다. */
@Service
public class DuplicateReviewService {
    private final JdbcTemplate db;
    private final LedgerRepository repo;
    private final Path images;
    public record Match(Expense expense, String reason, String strength, String signature, String decision) {}
    public record Report(Expense expense, List<Match> matches) {}
    private record Evidence(String date, Long amount, String merchant, String business, String approval,
                            boolean cancellation, String visual) {}
    private record Basis(String reason, String strength) {}
    private record Previous(String signature, String decision, String decidedAt) {}

    public DuplicateReviewService(JdbcTemplate db, LedgerRepository repo, @Value("${app.data-dir}") String data) {
        this.db = db; this.repo = repo; images = Path.of(data).resolve("images").toAbsolutePath().normalize();
    }

    /** 이미지 캐시는 파일 등록·회전 시에만 다시 계산합니다. 기존 증빙도 처음 비교할 때 지문만 보완합니다. */
    public void invalidateImage(long id) { db.update("UPDATE expenses SET visual_fingerprint=NULL WHERE id=?", id); }

    /** 호출하는 변경 작업의 트랜잭션이 끝날 때까지 비교·등록 순서를 보장합니다. */
    public void lock() { db.queryForObject("SELECT id FROM receipt_duplicate_lock WHERE id=1 FOR UPDATE",Integer.class); }

    private Map<Long,String> visuals(List<Expense> expenses) {
        Map<Long,String> hashes = new HashMap<>();
        db.query("SELECT id,visual_fingerprint FROM expenses", r -> {
            hashes.put(r.getLong(1), r.getString(2));
        });
        for (var e : expenses) if (e.imagePath() != null && hashes.get(e.id()) == null) {
            String hash = "";
            Path image = images.resolve(e.imagePath()).normalize();
            if (image.startsWith(images) && Files.isRegularFile(image))
                try { hash = ReceiptSimilarity.fingerprint(image); }
                catch (Exception ignored) { /* 이미지 비교 실패 시 거래 정보 비교는 계속합니다. */ }
            hashes.put(e.id(), hash);
            db.update("UPDATE expenses SET visual_fingerprint=? WHERE id=?", hash, e.id());
        }
        return hashes;
    }

    /** 등록·수정·삭제 후 후보를 갱신합니다. 확정한 기존 거래를 새 업로드 때문에 변경하지 않습니다. */
    @Transactional
    public void refresh() {
        lock();
        var all = repo.all(); var hashes = visuals(all);
        Map<String,Previous> previous = new HashMap<>();
        db.query("SELECT expense_id,candidate_id,signature,decision,decided_at FROM receipt_duplicate_matches", r -> {
            previous.put(r.getLong(1)+":"+r.getLong(2), new Previous(r.getString(3),r.getString(4),r.getString(5)));
        });
        for (var current : all) {
            if ("EXCLUDED".equals(current.duplicateState()) || "CONFIRMED".equals(current.status())) continue;
            db.update("DELETE FROM receipt_duplicate_matches WHERE expense_id=?", current.id());
            boolean pending = false;
            if (current.imagePath() != null) for (var existing : all) {
                if (existing.id() >= current.id() || existing.imagePath() == null || "EXCLUDED".equals(existing.duplicateState())) continue;
                Basis basis = compare(evidence(current, hashes.get(current.id())), evidence(existing, hashes.get(existing.id())));
                if (basis == null) continue;
                String signature = signature(current, existing, hashes);
                Previous old = previous.get(current.id()+":"+existing.id());
                boolean distinct = old != null && signature.equals(old.signature()) && old.decision().equals("DISTINCT");
                db.update("INSERT INTO receipt_duplicate_matches(expense_id,candidate_id,signature,reason,strength,decision,decided_at) VALUES(?,?,?,?,?,?,?)",
                        current.id(), existing.id(), signature, basis.reason(), basis.strength(), distinct ? "DISTINCT" : "PENDING", distinct ? old.decidedAt() : null);
                if (!distinct) pending = true;
            }
            String state = pending ? "SUSPECTED" : "NONE";
            if (!state.equals(current.duplicateState()))
                db.update("UPDATE expenses SET duplicate_state=?,version=version+1 WHERE id=?", state, current.id());
        }
    }

    public Report report(long id) {
        Expense expense = repo.get(id);
        var matches = db.query("SELECT * FROM receipt_duplicate_matches WHERE expense_id=? ORDER BY CASE WHEN decision='PENDING' THEN 0 ELSE 1 END,candidate_id",
                (r,n) -> new Match(repo.get(r.getLong("candidate_id")),r.getString("reason"),r.getString("strength"),r.getString("signature"),r.getString("decision")), id);
        return new Report(expense, matches);
    }

    /** 확정 직전에도 제안된 새 거래값을 비교하여, 폼 수정이나 API 직접 호출로 검토를 우회하지 못하게 합니다. */
    public void assertConfirmable(Expense source, ExpenseInput input) {
        if ("EXCLUDED".equals(source.duplicateState())) throw conflict("중복 제외된 내역입니다. 제외를 취소한 뒤 검토해 주세요.");
        if (source.imagePath() == null) return;
        var all = repo.all(); var hashes = visuals(all);
        Evidence proposed = new Evidence(input.receiptDate(),input.amount(),merchant(input.vendor()),businessNumber(input.businessNumber(), source.ocrText()),
                approval(input.approvalNumber()),cancellation(source.ocrText()),hashes.get(source.id()));
        for (var existing : all) {
            if (existing.id() >= source.id() || existing.imagePath() == null || "EXCLUDED".equals(existing.duplicateState())) continue;
            Evidence other = evidence(existing, hashes.get(existing.id()));
            if (compare(proposed, other) == null) continue;
            String key = hash(proposed.toString()+"\n"+other);
            int approved = db.queryForObject("SELECT COUNT(*) FROM receipt_duplicate_matches WHERE expense_id=? AND candidate_id=? AND signature=? AND decision='DISTINCT'",
                    Integer.class,source.id(),existing.id(),key);
            if (approved == 0) throw conflict("중복 의심 내역이 있습니다. 먼저 검토 대기로 저장하고 원본을 비교하여 ‘별개 거래로 확인’하거나 ‘중복으로 제외’해 주세요.");
        }
    }

    /** 판단 당시 양쪽 버전과 비교 근거가 모두 같아야 결정합니다. 새 후보까지 일괄 면제하지 않습니다. */
    @Transactional
    public Expense decide(long id, int version, long candidateId, int candidateVersion, String signature, String decision) {
        lock();
        Expense current = repo.lock(id);
        if (current.version() != version) throw conflict("내역이 변경되었습니다. 새로고침 후 중복을 다시 확인해 주세요.");
        repo.assertNotPosted(id);
        if (!"REVIEW".equals(current.status()) || "EXCLUDED".equals(current.duplicateState())) throw conflict("검토 대기 상태의 중복 의심 건에서 결정해 주세요.");
        Expense existing = repo.get(candidateId);
        if (existing.version() != candidateVersion) throw conflict("비교 대상이 변경되었습니다. 새로고침 후 다시 비교해 주세요.");
        var match = report(id).matches().stream().filter(m -> m.expense().id().equals(candidateId)
                && m.signature().equals(signature) && m.decision().equals("PENDING")).findFirst()
                .orElseThrow(() -> conflict("중복 후보가 변경되었습니다. 새로고침 후 다시 비교해 주세요."));
        if (decision.equals("DISTINCT")) {
            db.update("UPDATE receipt_duplicate_matches SET decision='DISTINCT',decided_at=? WHERE expense_id=? AND candidate_id=?",
                    Instant.now().toString(), id, candidateId);
            db.update("UPDATE expenses SET version=version+1 WHERE id=?", id);
        } else if (decision.equals("EXCLUDE")) {
            db.update("UPDATE receipt_duplicate_matches SET decision='EXCLUDED',decided_at=? WHERE expense_id=? AND candidate_id=?",
                    Instant.now().toString(),id,candidateId);
            db.update("UPDATE expenses SET duplicate_state='EXCLUDED',status='REVIEW',version=version+1 WHERE id=?", id);
        } else throw ReceiptService.bad("중복 검토 선택을 확인해 주세요.");
        refresh();
        return repo.get(id);
    }

    @Transactional
    public Expense restore(long id, int version) {
        lock();
        Expense current = repo.lock(id);
        if (current.version() != version) throw conflict("다른 창에서 변경되었습니다. 새로고침해 주세요.");
        if (!"EXCLUDED".equals(current.duplicateState())) throw ReceiptService.bad("중복 제외된 내역이 아닙니다.");
        repo.assertNotPosted(id);
        db.update("UPDATE expenses SET duplicate_state='NONE',status='REVIEW',version=version+1 WHERE id=?",id);
        db.update("DELETE FROM receipt_duplicate_matches WHERE expense_id=?",id);
        refresh(); return repo.get(id);
    }

    private Evidence evidence(Expense e, String visual) {
        return new Evidence(e.receiptDate(),e.amount(),merchant(e.vendor()),businessNumber(e.businessNumber(), e.ocrText()),approval(e.approvalNumber()),cancellation(e.ocrText()),visual);
    }
    private String signature(Expense a, Expense b, Map<Long,String> hashes) {
        return hash(evidence(a,hashes.get(a.id())).toString()+"\n"+evidence(b,hashes.get(b.id())));
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 승인번호 단독·금액 단독으로 중복 처리하지 않습니다. 취소 거래나 다른 사업자는 별도 거래로 취급합니다. */
    private Basis compare(Evidence a, Evidence b) {
        if (a.cancellation() != b.cancellation()) return null;
        if (!a.business().isBlank() && !b.business().isBlank() && !a.business().equals(b.business())) return null;
        boolean date = a.date() != null && !a.date().isBlank() && a.date().equals(b.date());
        boolean amount = a.amount() != null && a.amount() > 0 && a.amount().equals(b.amount());
        boolean merchant = !a.business().isBlank() && a.business().equals(b.business())
                || a.merchant().length() >= 2 && a.merchant().equals(b.merchant());
        boolean approval = !a.approval().isBlank() && a.approval().equals(b.approval());
        boolean visual = ReceiptSimilarity.similar(a.visual(), b.visual());
        var reasons = new ArrayList<String>();
        if (date) reasons.add("거래일 일치"); if (amount) reasons.add("총금액 일치");
        if (merchant) reasons.add("거래처 일치"); if (approval) reasons.add("승인번호 일치");
        if (visual) reasons.add("영수증 이미지 유사");
        if (date && amount && (merchant || approval)) {
            if (!a.approval().isBlank() && !b.approval().isBlank() && !approval) reasons.add("승인번호는 서로 달라 별개 거래일 수 있음");
            return new Basis(String.join(" · ", reasons), approval ? "STRONG" : "CHECK");
        }
        boolean dateConflict = a.date() != null && b.date() != null && !a.date().isBlank() && !b.date().isBlank() && !date;
        boolean amountConflict = a.amount() != null && b.amount() != null && !a.amount().equals(b.amount());
        if (visual && !dateConflict && !amountConflict && (date || amount || merchant || approval))
            return new Basis(String.join(" · ",reasons)+" · 읽히지 않은 항목은 원본으로 확인", "CHECK");
        return null;
    }

    static String merchant(String text) {
        return Normalizer.normalize(Objects.toString(text,""),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("(?:주식회사|유한회사|\\(주\\)|㈜)","").replaceAll("[^\\p{L}\\p{N}]","");
    }
    private static String approval(String text) { return Objects.toString(text,"").trim().toUpperCase(Locale.ROOT); }
    private static String business(String text) {
        var ids = new LinkedHashSet<String>();
        var matcher = Pattern.compile("(?<![0-9])([0-9]{3})[\\h-]+([0-9]{2})[\\h-]+([0-9]{5})(?![0-9])")
                .matcher(Objects.toString(text,""));
        while (matcher.find()) ids.add(matcher.group(1)+matcher.group(2)+matcher.group(3));
        return ids.size() == 1 ? ids.iterator().next() : "";
    }
    /** 저장된 확인 번호를 우선하며 새 항목이 없는 과거 자료는 기존 원문 비교를 유지합니다. */
    private static String businessNumber(String number, String original) {
        return number == null || number.isBlank() ? business(original) : number.replace("-", "");
    }
    private static boolean cancellation(String text) {
        return Objects.toString(text,"").lines().map(s -> s.replaceAll("\\s","").toUpperCase(Locale.ROOT))
                .anyMatch(s -> s.matches("^[\\[【]*(?:매출취소|승인취소|취소전표|반품영수증|취소영수증|CANCELLED|CANCELED|VOID).*|^거래구분[:：]?취소.*"));
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
