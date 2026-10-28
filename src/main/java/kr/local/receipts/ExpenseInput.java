package kr.local.receipts;

import jakarta.validation.constraints.*;

/**
 * 검토 화면의 저장 요청에 허용할 입력 필드를 정의합니다.
 * 길이·필수 여부·금액 범위는 Bean Validation으로 검사하고, 확정 조건·세액 일치는 서비스에서 검사합니다.
 * 분석 원문·증빙 경로·해시·등록 시각은 이 요청으로 수정하지 않습니다.
 */
public record ExpenseInput(
        @Size(max = 10) String receiptDate,
        @NotNull @Size(max = 200) String vendor,
        @NotNull @Size(max = 50) String category,
        @NotNull @Size(max = 500) String description,
        // 세액을 읽지 못하면 null을 허용합니다. 0원과 미확인은 다른 상태입니다.
        @Min(0) @Max(999999999999L) Long amount,
        @Min(0) @Max(999999999999L) Long supplyAmount,
        @Min(0) @Max(999999999999L) Long vatAmount,
        @NotNull @Size(max = 30) String paymentMethod,
        @NotNull @Size(max = 80) String ownerName,
        @NotNull @Size(max = 80) String department,
        @NotNull @Size(max = 1000) String note,
        // 실제 허용 상태와 확정 필수 조건은 ReceiptService.save에서 추가 확인합니다.
        @NotNull String status,
        // 화면을 열었을 때 받은 버전을 보내야 다른 창의 최신 변경을 덮어쓰지 않습니다.
        @Min(0) int version,
        // 승인번호 없는 영수증도 저장·확정할 수 있습니다. 문자열로 받아 앞자리 0을 보존합니다.
        @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9-]*", message = "승인번호는 영문·숫자·하이픈으로 입력해 주세요.")
        String approvalNumber,
        // 새 항목도 선택값입니다. 번호와 시간의 실제 유효성은 서비스에서 검사합니다.
        @Size(max = 12) @Pattern(regexp = "(?:[0-9]{10}|[0-9]{3}-[0-9]{2}-[0-9]{5})?") String businessNumber,
        @Size(max = 8) @Pattern(regexp = "(?:[0-9]{2}:[0-9]{2}(?::[0-9]{2})?)?") String receiptTime,
        @Size(max = 300) String vendorAddress) {
    /** 기존 요청·내부 호출은 새 선택 항목 없이도 사용할 수 있습니다. */
    public ExpenseInput(String receiptDate, String vendor, String category, String description,
            Long amount, Long supplyAmount, Long vatAmount, String paymentMethod, String ownerName,
            String department, String note, String status, int version, String approvalNumber) {
        this(receiptDate, vendor, category, description, amount, supplyAmount, vatAmount, paymentMethod,
                ownerName, department, note, status, version, approvalNumber, "", "", "");
    }
}
