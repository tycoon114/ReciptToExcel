package kr.local.receipts;

/** 등록 화면에서 선택한 증빙 종류입니다. AUTO는 이전 클라이언트의 자동 판별 요청을 호환합니다. */
public enum EvidenceKind {
    AUTO, RECEIPT, TAX_INVOICE;

    static EvidenceKind parse(String value) {
        if(value==null||value.isBlank())return AUTO;
        try {return valueOf(value);}catch(IllegalArgumentException error) {
            throw ReceiptService.bad("등록할 증빙 종류를 영수증 또는 세금계산서로 선택해 주세요.");
        }
    }
}
