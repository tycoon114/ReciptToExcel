package kr.local.receipts;

/**
 * DB 한 행과 목록·검토 화면의 응답을 표현하는 변경 불가능한 장부 모델입니다.
 * 금액은 원 단위 정수이며 미확인은 null입니다. REVIEW는 검토 대기, CONFIRMED는 확정 상태입니다.
 * @param id 관리 ID
 * @param receiptDate 영수증 거래일이며 미확인은 null
 * @param vendor 거래처 상호
 * @param category 계정과목 제안 또는 사용자 확정값
 * @param description 지출 목적을 적는 적요
 * @param amount 최종 결제 총액
 * @param supplyAmount 영수증에 명시된 공급가액
 * @param vatAmount 영수증에 명시된 부가세
 * @param paymentMethod 결제수단
 * @param ownerName 지출 사용자
 * @param department 소속 부서
 * @param note 검토 근거와 업무 목적 등의 비고
 * @param status 검토 대기 또는 확정 상태
 * @param analysisSource 수동 입력·기본 OCR·로컬 AI 분석 구분
 * @param analysisMessage 분석 방식과 원본 대조 안내
 * @param ocrText 분석에 사용한 영수증 인식 원문
 * @param imagePath 증빙 폴더 기준 상대 PNG 파일명이며 수동 입력은 null
 * @param imageHash 원본 파일 중복 판별용 SHA-256이며 수동 입력은 null
 * @param originalName 업로드 당시 파일명 또는 수동 입력 식별용 이름
 * @param createdAt 등록 시각이며 영수증 거래일과 구분
 * @param version 동시 수정 방지용 버전으로 저장·회전·재분석 때 증가
 * @param approvalNumber 선택 입력 승인번호이며 앞자리 0을 보존하는 문자열
 */
public record Expense(
        Long id,
        String receiptDate,
        String vendor,
        String category,
        String description,
        Long amount,
        Long supplyAmount,
        Long vatAmount,
        String paymentMethod,
        String ownerName,
        String department,
        String note,
        String status,
        String analysisSource,
        String analysisMessage,
        String ocrText,
        String imagePath,
        String imageHash,
        String originalName,
        String createdAt,
        int version,
        String approvalNumber) {}
