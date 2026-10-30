package kr.local.receipts;

import java.time.*;
import java.util.*;

/** 영수증 자료에 근거한 매입세액 검토 제안입니다. 신고·환급 결정이나 비용 인정 여부가 아닙니다. */
public final class VatDeductionReview {
    public static final String VERSION = "2026-10-29-r2";
    public static final String NOTICE = "자동 검토 제안 · 매입자 과세사업 여부·사용 목적·증빙 원본·신고 요건은 세무사 최종 확인 · 환급 확정 아님";
    private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    public record Result(String code, String label, String reason, String rule, String ruleVersion) {}

    public static Result evaluate(Expense e) {
        if ("EXCLUDED".equals(e.duplicateState())) return result("EXCLUDED", "집계 제외", "중복 제외된 영수증입니다.", "DUPLICATE");
        // 계정과목과 실제 지출 목적을 최종 대조해야 합니다. 분류 결과로 원금·세액을 변경하지 않습니다.
        if (Set.of("접대비", "기업업무추진비").contains(s(e.category())))
            return result("NON_DEDUCTIBLE", "불공제 검토", "접대비(기업업무추진비) 관련 매입세액. 실제 목적·계정과목 대조 필요.", "VAT39_1_6");
        if (e.amount() == null || e.amount() <= 0) return review("총금액이 없거나 양수가 아닙니다. 원본·취소 여부를 확인하세요.", "AMOUNT");
        if (e.vatAmount() != null && (e.vatAmount() < 0 || e.vatAmount() > e.amount())
                || e.supplyAmount() != null && (e.supplyAmount() < 0 || e.supplyAmount() > e.amount())
                || e.supplyAmount() != null && e.vatAmount() != null && e.supplyAmount() + e.vatAmount() != e.amount())
            return review("총금액·공급가액·부가세가 모순됩니다. 인식값부터 확인하세요.", "TAX_CONFLICT");
        if (e.vatAmount() != null && e.vatAmount() == 0)
            return result("NO_VAT", "세액 없음", "입력된 부가세가 0원입니다. 면세·영세 등 거래 유형은 별도 확인. 비용 인정 여부와는 다릅니다.", "ZERO_VAT");

        String purpose = (s(e.description()) + " " + s(e.note())).replaceAll("\\s", "");
        String details = purpose + " " + s(e.vendor()).replaceAll("\\s", "");
        if (contains(purpose, "접대", "거래처식사", "고객식사", "거래처선물"))
            return review("적요·비고에 접대성 지출 단서가 있으나 계정과목은 접대비가 아닙니다. 실제 목적과 계정과목을 대조하세요.", "ENTERTAINMENT_CONFLICT");
        if ("도서인쇄비".equals(e.category()))
            return review("도서(면세)·인쇄 용역·문구 등의 과세 여부가 다를 수 있습니다. 서점 이름이나 계정과목만으로 판단하지 말고 품목별 금액을 확인하세요.", "BOOK_PRINT_MIXED");
        // 키워드만으로 개인용·차종·업종별 예외를 확정하지 않고 검토 대상으로 분리합니다.
        if (contains(purpose, "업무무관", "사업무관", "개인생활비", "가사경비", "개인적사용", "개인용구입"))
            return review("사업 무관·가사 지출 가능성: 업무 관련성을 확인하세요. 개인카드 사용만으로 불공제하지 않습니다.", "VAT39_1_4");
        if (contains(details, "주유", "휘발유", "경유", "세차", "차량정비", "자동차정비", "렌터카", "렌트카", "자동차리스", "차량리스", "승용차", "차량구입", "차량임차"))
            return review("차량의 종류·직접 영업용 여부 확인: 일부 승용차 구입·임차·유지비는 불공제이며 업종·차종별 예외가 있습니다.", "VAT39_1_5");
        if (contains(purpose, "면세사업", "과세면세겸영", "공통매입"))
            return review("면세사업 관련 또는 공통매입 가능성: 사업 귀속과 안분 계산을 확인하세요.", "VAT39_1_7_MIXED");
        if (contains(purpose, "토지취득", "토지구입", "토지조성", "택지조성", "건물철거"))
            return review("토지 관련 매입세액·자본적 지출·건물과 토지 구분을 확인하세요.", "VAT39_1_7_LAND");
        if (contains(details, "항공", "승차권", "택시", "KTX", "ktx", "철도", "고속버스", "지하철", "목욕", "이발", "미용", "헤어", "입장권", "동물병원", "성형외과"))
            return review("카드전표 공제 대상 공급자·용역 제한 가능성: 운송수단·업종·증빙 종류를 확인하세요.", "VAT_DECREE88_5");
        if (contains(purpose, "해외", "국외", "수입통관", "사업자등록전", "기부금") || contains(details, "보험료", "우표", "인지대", "제세공과금"))
            return review("국외 거래·수입 증빙·등록 전 매입 또는 특수 거래 여부를 확인하세요. 일반 영수증 규칙만으로 판단하지 않습니다.", "SPECIAL_TRANSACTION");

        var lookup = e.businessLookup();
        if (lookup == null) return review("국세청 과세유형 미조회. 일반·간이·면세 여부와 거래 당시 유형을 확인하세요.", "NO_LOOKUP");
        if (!lookup.businessNumber().equals(s(e.businessNumber()).replace("-", "")))
            return review("조회 사업자번호가 현재 영수증과 다릅니다. 다시 조회하세요.", "NUMBER_MISMATCH");
        if (!"FOUND".equals(lookup.state())) return review("국세청에서 조회되지 않은 번호입니다. 번호·신규 등록 반영 여부를 확인하세요.", "NOT_FOUND");
        if (!"01".equals(lookup.businessStateCode()))
            return review("휴업·폐업 등 사업자 상태 확인 필요. 거래일과 휴·폐업 시점을 대조하며 과거 거래를 일괄 불공제하지 않습니다.", "BUSINESS_STATE");

        LocalDate transaction, checked;
        try {
            transaction = LocalDate.parse(e.receiptDate());
            checked = Instant.parse(lookup.checkedAt()).atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        } catch (Exception error) { return review("거래일 또는 국세청 조회 시점이 명확하지 않습니다.", "DATE_UNKNOWN"); }
        if (transaction.isAfter(checked)) return review("거래일이 국세청 조회일보다 뒤입니다. 거래일을 확인하고 다시 조회하세요.", "LOOKUP_BEFORE_TRANSACTION");
        if (!s(lookup.taxTypeChangedOn()).isBlank()) {
            try {
                if (transaction.isBefore(LocalDate.parse(lookup.taxTypeChangedOn())))
                    return review("거래일이 최근 과세유형 전환일보다 이전입니다. 현재 유형을 소급 적용하지 말고 거래 당시 유형을 확인하세요.", "HISTORICAL_TYPE");
            } catch (Exception error) { return review("과세유형 전환일을 확인할 수 없습니다.", "DATE_UNKNOWN"); }
        }
        // 전환일 미제공은 '변경됨'도 '변경 없음'도 아닙니다. 날짜 차이만으로 후보 검토를 차단하지 않습니다.
        // 현재 조회 기준 제안임을 사유에 남기되 명시된 전환일·적용일과 충돌하는 거래는 계속 별도 검토합니다.
        boolean currentTypeOnly = s(lookup.taxTypeChangedOn()).isBlank() && transaction.isBefore(checked);
        String historyNote = currentTypeOnly
                ? " 현재 조회 기준의 제안이며 전환일이 제공되지 않았습니다. 거래 당시 과세유형은 세무사 확인 필요." : "";
        String type = lookup.taxTypeCode();
        if ("04".equals(type)) {
            if (e.vatAmount() != null && e.vatAmount() > 0)
                return review("면세사업자 조회 결과와 양수 부가세 입력이 충돌합니다. 원본과 거래 당시 유형을 확인하세요.", "EXEMPT_CONFLICT");
            return result("NON_DEDUCTIBLE", "불공제 검토", "면세사업자로 조회되었습니다. 면세사업자 거래의 일반 매입세액 공제와 사업상 비용 인정은 별개입니다."
                    + historyNote, "EXEMPT_SUPPLIER");
        }
        if ("02".equals(type))
            return result("NON_DEDUCTIBLE", "불공제 검토", "세금계산서 발급사업자로 구분되지 않은 간이과세자(02)로 조회되었습니다. 카드·현금영수증 등 증빙과 거래 당시 발급 대상 여부를 확인하세요."
                    + historyNote, "SIMPLE_NON_INVOICE");
        if (!Set.of("01", "07").contains(type)) return review("과세특례·단체 등 기타 과세유형입니다. 개별 세무 처리가 필요합니다.", "OTHER_TYPE");
        if ("07".equals(type) && !s(lookup.invoiceAppliedOn()).isBlank()) {
            try {
                if (transaction.isBefore(LocalDate.parse(lookup.invoiceAppliedOn())))
                    return review("세금계산서 발급 간이과세자이나 거래일이 적용일보다 이전입니다. 당시 발급 대상 여부를 확인하세요.", "INVOICE_APPLY_DATE");
            } catch (Exception error) { return review("세금계산서 적용일이 명확하지 않습니다.", "DATE_UNKNOWN"); }
        }
        if (e.vatAmount() == null || e.supplyAmount() == null)
            return review("공급가액·부가세가 미확인입니다. 없는 세액을 총액에서 계산하거나 0원으로 간주하지 않습니다.", "TAX_UNKNOWN");
        String original = s(e.ocrText()).replaceAll("\\s", "");
        boolean card = Set.of("법인카드", "개인카드", "카드").contains(s(e.paymentMethod()));
        boolean cashReceipt = original.contains("현금영수증") && original.contains("지출증빙");
        boolean invoice = s(e.ocrText()).lines().map(String::strip).anyMatch(line -> line.matches("(?:전자)?세금계산서"));
        if (!card && !cashReceipt && !invoice)
            return review("단순 현금·이체 내역만으로 공제 여부를 정하지 않습니다. 지출증빙용 현금영수증·세금계산서 등 적격 증빙을 확인하세요.", "EVIDENCE_UNKNOWN");
        if (s(e.description()).isBlank() || Set.of("미분류", "기타경비").contains(s(e.category())))
            return review("사용 목적·구체적인 계정과목·개별 거래 성격을 확인하세요. 알 수 없는 지출을 공제 후보로 기본 분류하지 않습니다.", "PURPOSE_UNKNOWN");
        return result("CANDIDATE", "공제 후보", ("07".equals(type) ? "세금계산서 발급 간이과세자(07)" : "일반과세자(01)")
                + "·명시 세액·결제 증빙에 근거한 후보입니다. 매입자 과세사업·업무 관련성·원본 증빙·신고 요건은 세무사 확인."
                + historyNote, currentTypeOnly ? "CANDIDATE_CURRENT_TYPE" : "CANDIDATE_CONDITIONAL");
    }

    public static Result review(String reason, String rule) { return result("REVIEW", "확인 필요", reason, rule); }
    private static Result result(String code, String label, String reason, String rule) { return new Result(code, label, reason, rule, VERSION); }
    private static String s(String value) { return Objects.toString(value, ""); }
    private static boolean contains(String value, String... terms) { return Arrays.stream(terms).anyMatch(value::contains); }
    static String stored(Result result) { return result == null ? null : JSON.writeValueAsString(result); }
    static Result restored(String value) { return value == null || value.isBlank() ? null : JSON.readValue(value, Result.class); }
    static Result legacy(Result result) { return result == null ? review("이전 기장 전표에는 당시 공제 검토 사본이 없습니다. 원본을 별도 확인하세요.", "LEGACY_ENTRY") : result; }
}
