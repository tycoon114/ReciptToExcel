package kr.local.receipts;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * H2 장부 테이블을 JDBC로 읽고 씁니다.
 * SQL의 물음표에 값을 바인딩하며, version 조건으로 오래된 화면의 수정 요청을 거절합니다.
 */
@Repository
public class LedgerRepository {
    private final JdbcTemplate db;
    // DB 열 이름과 응답 모델의 필드를 한곳에서 대응시킵니다. 미확인 금액은 0이 아닌 null입니다.
    private static final RowMapper<Expense> MAPPER =
            (r, n) ->
                    new Expense(
                            r.getLong("id"),
                            r.getString("receipt_date"),
                            r.getString("vendor"),
                            r.getString("category"),
                            r.getString("description"),
                            r.getObject("amount", Long.class),
                            r.getObject("supply_amount", Long.class),
                            r.getObject("vat_amount", Long.class),
                            r.getString("payment_method"),
                            r.getString("owner_name"),
                            r.getString("department"),
                            r.getString("note"),
                            r.getString("status"),
                            r.getString("analysis_source"),
                            r.getString("analysis_message"),
                            r.getString("ocr_text"),
                            r.getString("image_path"),
                            r.getString("image_hash"),
                            r.getString("original_name"),
                            r.getString("created_at"),
                            r.getInt("version"),
                            r.getString("approval_number"));

    public LedgerRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** 기본 장부 목록을 가져옵니다. 상세 조회 조건은 서비스에서 적용합니다. */
    public List<Expense> all() {
        return db.query(
                "SELECT * FROM expenses ORDER BY receipt_date DESC NULLS LAST, id DESC", MAPPER);
    }

    /** 관리 ID에 해당하는 내역이 없으면 404 오류로 응답합니다. */
    public Expense get(long id) {
        return db.query("SELECT * FROM expenses WHERE id=?", MAPPER, id).stream()
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "영수증을 찾을 수 없습니다."));
    }

    /** 같은 원본 사진이 이미 등록되어 있는지 해시로 조회합니다. */
    public Optional<Expense> byHash(String hash) {
        return db.query("SELECT * FROM expenses WHERE image_hash=?", MAPPER, hash).stream()
                .findFirst();
    }

    /** 분석 결과를 새 행으로 저장합니다. 검토 상태·버전·빈 사용자 정보는 스키마 기본값을 사용합니다. */
    public Expense insert(ReceiptAnalyzer.Analysis a, String imagePath, String hash, String name) {
        db.update(
                "INSERT INTO"
                    + " expenses(receipt_date,vendor,category,description,amount,supply_amount,vat_amount,payment_method,note,analysis_source,analysis_message,ocr_text,image_path,image_hash,original_name,created_at,approval_number)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                a.date(),
                a.vendor(),
                a.category(),
                a.description(),
                a.amount(),
                a.supply(),
                a.vat(),
                a.payment(),
                a.note(),
                a.source(),
                a.message(),
                a.text(),
                imagePath,
                hash,
                name,
                Instant.now().toString(),
                a.approvalNumber());
        if (hash != null) return byHash(hash).orElseThrow();
        // 수동 입력은 고유한 임시 이름으로 조회하여 다른 요청의 마지막 삽입 ID와 섞이지 않게 합니다.
        return db.query("SELECT * FROM expenses WHERE original_name=?", MAPPER, name).get(0);
    }

    /** ID와 기존 버전이 모두 맞을 때만 수정하고 버전을 증가시킵니다. */
    @Transactional
    public Expense update(long id, ExpenseInput x) {
        requireEditable(id, false);
        int count =
                db.update(
                        "UPDATE expenses SET"
                            + " receipt_date=?,vendor=?,category=?,description=?,amount=?,supply_amount=?,vat_amount=?,payment_method=?,owner_name=?,department=?,note=?,status=?,approval_number=?,version=version+1"
                            + " WHERE id=? AND version=?",
                        x.receiptDate() == null || x.receiptDate().isBlank()
                                ? null
                                : x.receiptDate(),
                        x.vendor().trim(),
                        x.category(),
                        x.description().trim(),
                        x.amount(),
                        x.supplyAmount(),
                        x.vatAmount(),
                        x.paymentMethod(),
                        x.ownerName().trim(),
                        x.department().trim(),
                        x.note().trim(),
                        x.status(),
                        Objects.toString(x.approvalNumber(), "").trim(),
                        id,
                        x.version());
        // 행이 사라진 경우는 404, 존재하지만 버전이 달라진 경우는 409로 구분합니다.
        if (count == 0) {
            get(id);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "다른 창에서 수정되었습니다. 목록을 새로고침한 뒤 다시 열어 주세요.");
        }
        return get(id);
    }

    /** 존재 여부를 먼저 확인한 뒤 장부 행을 삭제합니다. 파일 정리는 서비스가 담당합니다. */
    @Transactional
    public void delete(long id) {
        requireEditable(id, true);
        db.update("DELETE FROM expenses WHERE id=?", id);
    }

    /** 증빙 파일 연결만 교체하고 버전을 올립니다. 장부의 입력 필드는 그대로 둡니다. */
    @Transactional
    public Expense rotate(long id, int version, String path) {
        requireEditable(id, false);
        int count =
                db.update(
                        "UPDATE expenses SET image_path=?,version=version+1 WHERE id=? AND"
                                + " version=?",
                        path,
                        id,
                        version);
        if (count == 0)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "다른 창에서 수정되었습니다. 새로고침 후 다시 열어 주세요.");
        return get(id);
    }

    /** 재인식 필드를 갱신합니다. 비어 있거나 잘못된 도서 기본값인 적요·비고만 제안으로 대체합니다. */
    @Transactional
    public Expense reanalyze(long id, int version, ReceiptAnalyzer.Analysis a, boolean clearBookDefault) {
        requireEditable(id, false);
        int count =
                db.update(
                        "UPDATE expenses SET receipt_date=?,vendor=?,category=?,description=CASE"
                            + " WHEN TRIM(description)='' OR (? AND description='도서 구입') THEN ? ELSE description END,note=CASE"
                            + " WHEN TRIM(note)='' OR (? AND note='도서 구입') THEN ? ELSE note"
                            + " END,amount=?,supply_amount=?,vat_amount=?,payment_method=?,approval_number=?,analysis_source=?,analysis_message=?,ocr_text=?,status='REVIEW',version=version+1"
                            + " WHERE id=? AND version=?",
                        a.date(),
                        a.vendor(),
                        a.category(),
                        clearBookDefault,
                        a.description(),
                        clearBookDefault,
                        a.note(),
                        a.amount(),
                        a.supply(),
                        a.vat(),
                        a.payment(),
                        a.approvalNumber(),
                        a.source(),
                        a.message(),
                        a.text(),
                        id,
                        version);
        if (count == 0)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "다른 창에서 수정되었습니다. 새로고침 후 다시 열어 주세요.");
        return get(id);
    }

    /** 기장과 수정이 동시에 진행되어도 같은 영수증 행의 잠금을 공유합니다. 호출자는 트랜잭션 안에 있어야 합니다. */
    public Expense lock(long id) {
        return db.query("SELECT * FROM expenses WHERE id=? FOR UPDATE", MAPPER, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "영수증을 찾을 수 없습니다."));
    }

    /** 오래 걸리는 OCR나 이미지 저장 전에 안내합니다. 최종 변경 시에는 행 잠금 후 다시 검사합니다. */
    public void assertNotPosted(long id) {
        if (db.queryForObject("SELECT COUNT(*) FROM bookkeeping_entries WHERE active_expense_id=?",Long.class,id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"기장된 영수증입니다. 기장을 취소한 뒤 수정·회전·재인식할 수 있습니다.");
    }

    /** 취소된 전표라도 기장 당시 사진을 다시 열 수 있도록 참조 중인 파일은 정리하지 않습니다. */
    public boolean isBookkeepingEvidence(String filename) {
        return db.queryForObject("SELECT COUNT(*) FROM bookkeeping_entries WHERE image_path=?",Long.class,filename)>0;
    }

    /** 기장된 원본을 바꾸려면 먼저 전표를 취소해야 하며, 이력이 있는 증빙은 삭제하지 않습니다. */
    private void requireEditable(long id, boolean deleting) {
        lock(id);
        Long count = db.queryForObject(deleting
                ? "SELECT COUNT(*) FROM bookkeeping_entries WHERE expense_id=?"
                : "SELECT COUNT(*) FROM bookkeeping_entries WHERE active_expense_id=?", Long.class, id);
        if (count != null && count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, deleting
                ? "기장 이력이 있는 영수증은 증빙 보존을 위해 삭제할 수 없습니다. 전표를 취소하여 집계에서 제외하세요."
                : "기장된 영수증입니다. 기장을 취소한 뒤 수정·회전·재인식할 수 있습니다.");
    }
}
