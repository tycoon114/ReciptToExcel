package kr.local.receipts;

import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.nio.file.*;

/**
 * 확정한 영수증 지출을 복식 분개 형태로 기록하는 기장 보조 기능입니다.
 * 전체 회계·세무 신고 프로그램이 아니며 매출·결산·카드대금 실제 지급 거래는 별도 범위입니다.
 * 기장 당시 값을 보존하고 취소 사유를 남겨, 원본 변경이나 재전송으로 기장 내역이 조용히 바뀌지 않게 합니다.
 */
@Service
public class BookkeepingService {
    public static final List<String> CREDIT_ACCOUNTS = List.of("미지급금(법인카드)", "미지급금(임직원)", "보통예금", "현금", "미지급금");
    private final JdbcTemplate db;
    private final LedgerRepository receipts;
    private final Path images;

    public BookkeepingService(JdbcTemplate db, LedgerRepository receipts, @Value("${app.data-dir}") String data) {
        this.db = db;
        this.receipts = receipts;
        images = Path.of(data).resolve("images").toAbsolutePath().normalize();
    }

    /** 기장일과 상대계정은 사진만으로 확정하지 않고 사용자가 선택합니다. 담당자는 로그인 인증값이 아닌 입력 기록입니다. */
    public record PostInput(@NotNull @Min(0) Integer version,
            @NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String entryDate,
            @NotBlank @Size(max=80) String creditAccount, @NotBlank String taxMode,
            @NotBlank @Size(max=80) String postedBy, @NotBlank @Size(max=500) String memo) {}

    public record CancelInput(@NotNull @Min(0) Integer version,
            @NotBlank @Size(max=80) String cancelledBy, @NotBlank @Size(max=500) String reason) {}

    /** 차변은 비용·선택한 부가세, 대변은 지급 또는 미지급 계정입니다. 양쪽 합계는 항상 같아야 합니다. */
    public record Line(String side, String account, long amount) {}

    public record Entry(long id, String entryNumber, long expenseId, int expenseVersion, String imagePath,
            String entryDate, String receiptDate, String vendor, String memo, String debitAccount,
            String creditAccount, long totalAmount, long expenseAmount, long vatAmount, String taxMode,
            String postedBy, String postedAt, String status, String cancelledBy, String cancelledAt,
            String cancelReason, String approvalNumber, String businessNumber, String receiptTime, String vendorAddress,
            NtsBusinessLookup.Result businessLookup) {
        public List<Line> lines() {
            List<Line> result = new ArrayList<>();
            if (expenseAmount > 0) result.add(new Line("차변", debitAccount, expenseAmount));
            if (vatAmount > 0) result.add(new Line("차변", "부가세대급금", vatAmount));
            result.add(new Line("대변", creditAccount, totalAmount));
            return List.copyOf(result);
        }
    }

    public record ActionResult(Expense expense, Entry entry) {}

    /** 취소 전표도 이력 조회에 포함하며, 월 필터 기준은 영수증 거래일이 아닌 기장일입니다. */
    @Transactional(readOnly=true)
    public List<Entry> list(String month, String query) {
        if (month != null && !month.isBlank()) try { YearMonth.parse(month); }
        catch (Exception e) { throw ReceiptService.bad("기장 월은 YYYY-MM 형식으로 입력해 주세요."); }
        String search = Objects.toString(query, "").trim().toLowerCase(Locale.ROOT);
        return db.query("SELECT * FROM bookkeeping_entries ORDER BY entry_date DESC, id DESC", (r, n) ->
                new Entry(r.getLong("id"), r.getString("entry_number"), r.getLong("expense_id"),
                        r.getInt("expense_version"), r.getString("image_path"), r.getString("entry_date"), r.getString("receipt_date"),
                        r.getString("vendor"), r.getString("memo"), r.getString("debit_account"),
                        r.getString("credit_account"), r.getLong("total_amount"), r.getLong("expense_amount"),
                        r.getLong("vat_amount"), r.getString("tax_mode"), r.getString("posted_by"),
                        r.getString("posted_at"), r.getString("status"), r.getString("cancelled_by"),
                        r.getString("cancelled_at"), r.getString("cancel_reason"), r.getString("approval_number"),
                        r.getString("business_number"), r.getString("receipt_time"), r.getString("vendor_address"),
                        NtsBusinessLookup.restored(r.getString("business_lookup_json")))).stream()
                .filter(e -> month == null || month.isBlank() || e.entryDate().startsWith(month))
                .filter(e -> (e.entryNumber()+" "+e.vendor()+" "+e.memo()+" "+e.debitAccount()+" "+e.creditAccount()+" "+e.approvalNumber())
                        .toLowerCase(Locale.ROOT).contains(search)).toList();
    }

    public Entry get(long id) {
        return list("", "").stream().filter(e -> e.id() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기장 전표를 찾을 수 없습니다."));
    }

    /** 취소 후 영수증을 회전해도 기장 당시 증빙은 별도 연결로 남아 있습니다. 증빙 폴더 밖의 경로는 허용하지 않습니다. */
    public Path evidence(long id) {
        String name = get(id).imagePath();
        if (name == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"사진 없는 수동 입력 전표입니다.");
        Path image = images.resolve(name).normalize();
        if (!image.startsWith(images) || !Files.isRegularFile(image))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"기장 당시 증빙 사진을 찾을 수 없습니다.");
        return image;
    }

    /** 영수증 행 잠금과 DB 유일성 제약을 함께 사용하여 재클릭·동시 요청에도 활성 전표가 하나만 생기게 합니다. */
    @Transactional
    public ActionResult post(long expenseId, PostInput input) {
        Expense source = receipts.lock(expenseId);
        if (db.queryForObject("SELECT COUNT(*) FROM bookkeeping_entries WHERE active_expense_id=?", Long.class, expenseId) > 0)
            throw conflict("이미 기장된 영수증입니다. 기존 전표를 확인하세요.");
        version(source, input.version());
        if (!"NONE".equals(source.duplicateState())) throw conflict("중복 의심 또는 중복 제외된 내역입니다. 중복 검토를 먼저 마쳐 주세요.");
        if (!"CONFIRMED".equals(source.status())) throw ReceiptService.bad("영수증 내용을 먼저 저장·확정한 뒤 기장해 주세요.");
        if (source.amount() == null || source.amount() <= 0 || source.receiptDate() == null
                || source.vendor().isBlank() || source.description().isBlank()
                || !ReceiptAnalyzer.CATEGORIES.contains(source.category()) || "미분류".equals(source.category()))
            throw ReceiptService.bad("기장에 필요한 거래일·거래처·계정과목·적요·양수 금액을 확인해 주세요.");
        String date;
        try { date = LocalDate.parse(input.entryDate()).toString(); }
        catch (Exception e) { throw ReceiptService.bad("기장일은 실제 날짜를 YYYY-MM-DD 형식으로 입력해 주세요."); }
        if (!CREDIT_ACCOUNTS.contains(input.creditAccount())) throw ReceiptService.bad("대변 상대계정을 목록에서 선택해 주세요.");
        if (!Set.of("TOTAL_EXPENSE", "SPLIT_VAT").contains(input.taxMode())) throw ReceiptService.bad("부가세 처리 방식을 확인해 주세요.");
        long vat = 0;
        if ("SPLIT_VAT".equals(input.taxMode())) {
            // OCR가 세액을 읽었다는 사실만으로 공제 가능하다고 판단하지 않습니다. 사용자의 별도 선택이 필요합니다.
            if (source.supplyAmount() == null || source.vatAmount() == null || source.supplyAmount() <= 0
                    || source.vatAmount() <= 0 || source.supplyAmount()+source.vatAmount() != source.amount())
                throw ReceiptService.bad("부가세 별도 계상은 공급가액·부가세를 원본과 확인하고 합계가 맞을 때 선택할 수 있습니다.");
            vat = source.vatAmount();
        }
        long expense = source.amount()-vat;
        String temporaryNumber = "PENDING-"+UUID.randomUUID();
        var key = new GeneratedKeyHolder();
        long selectedVat = vat;
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO bookkeeping_entries "
                    + "(entry_number,expense_id,active_expense_id,expense_version,image_path,entry_date,receipt_date,vendor,memo,debit_account,credit_account,total_amount,expense_amount,vat_amount,tax_mode,posted_by,posted_at,approval_number,business_number,receipt_time,vendor_address,business_lookup_json) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
            Object[] values = {temporaryNumber, expenseId, expenseId, source.version(), source.imagePath(), date, source.receiptDate(),
                    source.vendor(), input.memo().trim(), source.category(), input.creditAccount(), source.amount(),
                    expense, selectedVat, input.taxMode(), input.postedBy().trim(), Instant.now().toString(), source.approvalNumber(),
                    source.businessNumber(), source.receiptTime(), source.vendorAddress(), NtsBusinessLookup.stored(source.businessLookup())};
            for (int i=0; i<values.length; i++) statement.setObject(i+1, values[i]);
            return statement;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        String number = "JV-"+date.replace("-", "")+"-"+String.format(Locale.ROOT, "%06d", id);
        db.update("UPDATE bookkeeping_entries SET entry_number=? WHERE id=?", number, id);
        // 기장과 취소도 영수증 버전을 올려 오래된 검토 창에서의 변경 요청을 무효화합니다.
        db.update("UPDATE expenses SET version=version+1 WHERE id=?", expenseId);
        return new ActionResult(receipts.get(expenseId), get(id));
    }

    /** 원전표의 금액·계정은 보존하고 취소자·시각·사유를 기록합니다. 활성 집계에서만 제외됩니다. */
    @Transactional
    public ActionResult cancel(long id, CancelInput input) {
        Entry entry = get(id);
        Expense source = receipts.lock(entry.expenseId());
        version(source, input.version());
        int count = db.update("UPDATE bookkeeping_entries SET status='CANCELLED',active_expense_id=NULL,"
                + "cancelled_by=?,cancelled_at=?,cancel_reason=? WHERE id=? AND status='POSTED'",
                input.cancelledBy().trim(), Instant.now().toString(), input.reason().trim(), id);
        if (count != 1) throw conflict("이미 취소된 전표입니다. 목록을 새로고침해 주세요.");
        db.update("UPDATE expenses SET version=version+1 WHERE id=?", source.id());
        return new ActionResult(receipts.get(source.id()), get(id));
    }

    private void version(Expense expense, Integer version) {
        if (version == null || expense.version() != version) throw conflict("다른 창에서 변경되었습니다. 새로고침한 뒤 다시 기장해 주세요.");
    }

    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
