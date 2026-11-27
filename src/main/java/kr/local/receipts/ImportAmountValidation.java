package kr.local.receipts;

import java.util.*;

/** 원본 금액을 변경하지 않는 공급가액·부가세·합계 검증입니다. 공제 여부 판단과 구분합니다. */
public final class ImportAmountValidation {
    private ImportAmountValidation() {}
    public record Result(String code, String label, Long difference, String reason, String notice) {}

    public static Result evaluate(ImportAnalysis.Transaction row) {
        String notice = negative(row.amount()) || negative(row.supply()) || negative(row.vat())
                || Objects.toString(row.type(), "").contains("취소") || Objects.toString(row.description(), "").contains("수정")
                ? "음수·취소·수정 내역은 원거래와 별도 확인하세요. 부호를 유지해 검증했습니다." : "";
        var missing = new ArrayList<String>();
        if (row.supply() == null) missing.add("공급가액");
        if (row.vat() == null) missing.add("부가세");
        if (row.amount() == null) missing.add("합계금액");
        if (!missing.isEmpty()) return new Result("MISSING", "정보 부족", null,
                String.join("·", missing) + " 미기재 · 빈칸을 0원으로 간주하지 않습니다.", notice);
        try {
            long difference = Math.subtractExact(Math.addExact(row.supply(), row.vat()), row.amount());
            return difference == 0 ? new Result("MATCH", "금액 일치", 0L, "공급가액 + 부가세 = 합계금액", notice)
                    : new Result("MISMATCH", "금액 불일치", difference,
                            "공급가액 + 부가세 - 합계금액의 차이가 " + String.format(Locale.ROOT, "%,d", difference) + "입니다. 원본을 확인하세요.", notice);
        } catch (ArithmeticException e) {
            return new Result("OUT_OF_RANGE", "확인 필요", null, "금액이 계산 범위를 초과합니다. 원본과 추출값을 확인하세요.", notice);
        }
    }

    private static boolean negative(Long value) { return value != null && value < 0; }
}
