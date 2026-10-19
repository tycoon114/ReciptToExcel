package kr.local.receipts;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Repository
public class LedgerRepository {
    private final JdbcTemplate db;
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
                            r.getInt("version"));

    public LedgerRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Expense> all() {
        return db.query(
                "SELECT * FROM expenses ORDER BY receipt_date DESC NULLS LAST, id DESC", MAPPER);
    }

    public Expense get(long id) {
        return db.query("SELECT * FROM expenses WHERE id=?", MAPPER, id).stream()
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "영수증을 찾을 수 없습니다."));
    }

    public Optional<Expense> byHash(String hash) {
        return db.query("SELECT * FROM expenses WHERE image_hash=?", MAPPER, hash).stream()
                .findFirst();
    }

    public Expense insert(ReceiptAnalyzer.Analysis a, String imagePath, String hash, String name) {
        db.update(
                "INSERT INTO"
                    + " expenses(receipt_date,vendor,category,description,amount,supply_amount,vat_amount,payment_method,note,analysis_source,analysis_message,ocr_text,image_path,image_hash,original_name,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
                Instant.now().toString());
        if (hash != null) return byHash(hash).orElseThrow();
        // Manual entries use their unique generated name as a lookup key, without a shared
        // last-insert-id.
        return db.query("SELECT * FROM expenses WHERE original_name=?", MAPPER, name).get(0);
    }

    public Expense update(long id, ExpenseInput x) {
        int count =
                db.update(
                        "UPDATE expenses SET"
                            + " receipt_date=?,vendor=?,category=?,description=?,amount=?,supply_amount=?,vat_amount=?,payment_method=?,owner_name=?,department=?,note=?,status=?,version=version+1"
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
                        id,
                        x.version());
        if (count == 0) {
            get(id);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "다른 창에서 수정되었습니다. 목록을 새로고침한 뒤 다시 열어 주세요.");
        }
        return get(id);
    }

    public void delete(long id) {
        get(id);
        db.update("DELETE FROM expenses WHERE id=?", id);
    }

    public Expense rotate(long id, int version, String path) {
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

    public Expense reanalyze(long id, int version, ReceiptAnalyzer.Analysis a, boolean clearBookDefault) {
        int count =
                db.update(
                        "UPDATE expenses SET receipt_date=?,vendor=?,category=?,description=CASE"
                            + " WHEN TRIM(description)='' OR (? AND description='도서 구입') THEN ? ELSE description END,note=CASE"
                            + " WHEN TRIM(note)='' OR (? AND note='도서 구입') THEN ? ELSE note"
                            + " END,amount=?,supply_amount=?,vat_amount=?,payment_method=?,analysis_source=?,analysis_message=?,ocr_text=?,status='REVIEW',version=version+1"
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
}
