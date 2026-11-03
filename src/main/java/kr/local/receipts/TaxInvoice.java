package kr.local.receipts;

import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 세금계산서의 부가 정보입니다. 문서 합계는 기존 공급가액·부가세 필드에, 품목은 별도로 보관합니다. */
public record TaxInvoice(
        @NotNull @Size(max=40) String issueNumber,
        @NotNull @Size(max=12) String buyerBusinessNumber,
        @NotNull @Size(max=200) String buyerName,
        @NotNull @Size(max=2000) String warning,
        @NotNull @Size(max=100) List<@NotNull @Valid Item> items) {
    public TaxInvoice { items = items == null ? List.of() : List.copyOf(items); }

    /** 수량·단가는 소수도 보존합니다. 공급가액·세액은 원 단위이며 빈칸과 0을 구분합니다. */
    public record Item(
            @NotNull @Size(max=10) String date,
            @NotNull @Size(max=200) String name,
            @NotNull @Size(max=100) String specification,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            @DecimalMin("0") @DecimalMax("999999999999") @Digits(integer=12,fraction=6) BigDecimal quantity,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
            @DecimalMin("0") @DecimalMax("999999999999") @Digits(integer=12,fraction=6) BigDecimal unitPrice,
            @Min(0) @Max(999999999999L) Long supplyAmount,
            @Min(0) @Max(999999999999L) Long taxAmount) {}

    private static final tools.jackson.databind.ObjectMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    static String stored(TaxInvoice value) { return value==null?null:JSON.writeValueAsString(value); }
    static TaxInvoice restored(String value) { return value==null||value.isBlank()?null:JSON.readValue(value,TaxInvoice.class); }

    /** 품목 합계는 전부 읽혔을 때만 대조합니다. 누락 세액을 계산하거나 문서 합계를 바꾸지 않습니다. */
    static String mismatch(TaxInvoice invoice, Long supply, Long tax) {
        if(invoice==null||invoice.items().isEmpty())return "";
        if(supply!=null && invoice.items().stream().allMatch(i->i.supplyAmount()!=null)
                && invoice.items().stream().mapToLong(Item::supplyAmount).sum()!=supply)
            return "품목 공급가액 합계와 문서 공급가액이 다릅니다.";
        if(tax!=null && invoice.items().stream().allMatch(i->i.taxAmount()!=null)
                && invoice.items().stream().mapToLong(Item::taxAmount).sum()!=tax)
            return "품목 세액 합계와 문서 세액이 다릅니다.";
        return "";
    }
}
