package kr.local.receipts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 로컬 영수증 장부의 실행 진입점입니다.
 * 이 패키지 아래의 컨트롤러·서비스·저장소를 검색하고 내장 웹 서버를 시작합니다.
 * 사진 등록 흐름은 {@link ApiController} → {@link ReceiptService} → {@link ReceiptAnalyzer} 순서입니다.
 * 분석 결과는 {@link LedgerRepository}에 저장하고, 확정한 내역은 {@link ExcelExporter}로 내보냅니다.
 */
@SpringBootApplication
public class ReceiptApplication {
    /** IntelliJ 실행 버튼 또는 실행 JAR에서 호출되는 시작 메서드입니다. */
    public static void main(String[] args) {
        SpringApplication.run(ReceiptApplication.class, args);
    }
}
