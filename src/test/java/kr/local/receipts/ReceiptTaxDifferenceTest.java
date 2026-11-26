package kr.local.receipts;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptTaxDifferenceTest {
    private ReceiptAnalyzer.Analysis analyze(String text, String... alternatives) {
        var json = JsonMapper.builder().build();
        var ocr = new WindowsOcr(json) {
            @Override public Result read(Path image) {
                return new Result(text, "", List.of(alternatives));
            }
        };
        return new ReceiptAnalyzer(ocr, json, false, "http://localhost", "")
                .analyze(Path.of("unused.png"), "", EvidenceKind.RECEIPT);
    }

    @Test void fillsMissingVatAndReportsItsOrigin() {
        var result = analyze("예시식당\n2026-11-26\n카드매출 61,000\n공급가 55,455\n부가세 5,뤄5");
        assertEquals(61000L, result.amount());
        assertEquals(55455L, result.supply());
        assertEquals(5545L, result.vat());
        assertTrue(result.message().contains("계산값"));
        assertTrue(result.text().contains("5,뤄5"));
    }

    @Test void fillsMissingSupply() {
        var result = analyze("합계 61000\n부가세 5545");
        assertEquals(55455L, result.supply());
        assertEquals(5545L, result.vat());
        assertTrue(result.message().contains("공급가액 55455원"));
    }

    @Test void preservesExplicitAmountsAndDoesNotFillTwoMissingFields() {
        assertNull(analyze("합계 61000").vat());
        assertNull(analyze("합계 61000").supply());
        var complete = analyze("합계 61000\n공급가 55455\n부가세 5545");
        assertFalse(complete.message().contains("계산값"));
        assertEquals(5545L, complete.vat());
    }

    @Test void skipsContradictoryAmountsAndDifferentTotals() {
        var inconsistent = analyze("합계 61000\n공급가 70000");
        assertNull(inconsistent.vat());
        assertNull(inconsistent.supply());
        var conflict = analyze("합계 61000\n공급가 55455", "합계 62000\n공급가 55455");
        assertNull(conflict.vat());
    }

    @Test void skipsSeparateChargesAndMissingTotal() {
        for (String label : List.of("면세", "비과세", "봉사료")) {
            assertNull(analyze("합계 61000\n공급가 55455\n" + label + " 3000").vat());
        }
        assertNull(analyze("공급가 55455").vat());
    }

    @Test void handlesExplicitZeroVat() {
        var zero = analyze("합계 61000\n부가세 0");
        assertEquals(61000L, zero.supply());
        assertEquals(0L, zero.vat());
    }
}
