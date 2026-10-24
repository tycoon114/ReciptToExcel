package kr.local.receipts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 표기가 없는 식별번호를 오인하지 않고, 승인번호 앞자리 0과 애매한 경우의 빈칸 처리를 확인합니다. */
class ApprovalNumberParserTest {
    @ParameterizedTest
    @ValueSource(strings={"승인번호 : 00001234", "승 인 번 호：００００１２３４", "승인No. 00001234",
            "승인번호\n00001234", "AUTH CODE: 00001234", "Approval No. 00001234", "승인: 00001234"})
    void explicitLabelsPreserveLeadingZeros(String text) {
        assertEquals("00001234",ReceiptTextParser.parse("예시식당\n합계: 9,000\n"+text, "").approvalNumber());
        assertEquals(9000L,ReceiptTextParser.parse("예시식당\n합계: 9,000\n"+text, "").amount());
    }

    @ParameterizedTest
    @ValueSource(strings={"합계 9000\n카드번호 12345678\n거래번호 98765432", "승인금액 9000", "승인일시 2026-10-23",
            "승인번호 ****1234", "승인번호 01234567\n승인번호 07654321", "승인번호\n거래번호 01234567",
            "승인번호 01234567 07654321", "승인번호 2026-10-23", "승인번호 01234567****"})
    void absentMaskedAndConflictingNumbersStayBlank(String text) {
        assertEquals("",ReceiptTextParser.parse(text, "").approvalNumber());
    }

    @Test void alphanumericNumbersAndRepeatedIdenticalNumbersAreSupported() {
        assertEquals("00AB1234",ApprovalNumberParser.parse("승인번호 00AB1234").value());
        assertEquals("00001234",ApprovalNumberParser.parse("승인번호 00001234\n승인번호 00001234").value());
        assertFalse(ApprovalNumberParser.parse("승인번호 01234567\n승인번호 07654321").warning().isBlank());
    }

    @Test void conflictingPreprocessedOcrCandidatesRequireManualConfirmation() {
        var mapper=JsonMapper.builder().build();var ocr=mock(WindowsOcr.class);
        when(ocr.read(any())).thenReturn(new WindowsOcr.Result("예시식당\n합계 9000\n승인번호 00001234", "",
                List.of("예시식당\n합계 9000\n승인번호 00005678")));
        var analyzer=new ReceiptAnalyzer(ocr,mapper,false,"http://127.0.0.1:11434","gemma3:4b");
        var result=analyzer.analyze(Path.of("unused.png"),"");
        assertEquals("",result.approvalNumber());assertTrue(result.message().contains("승인번호가 인식 방식마다"));
    }
}
