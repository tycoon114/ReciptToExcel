package kr.local.receipts;

import jakarta.validation.constraints.*;

public record ExpenseInput(
        @Size(max = 10) String receiptDate,
        @NotNull @Size(max = 200) String vendor,
        @NotNull @Size(max = 50) String category,
        @NotNull @Size(max = 500) String description,
        @Min(0) @Max(999999999999L) Long amount,
        @Min(0) @Max(999999999999L) Long supplyAmount,
        @Min(0) @Max(999999999999L) Long vatAmount,
        @NotNull @Size(max = 30) String paymentMethod,
        @NotNull @Size(max = 80) String ownerName,
        @NotNull @Size(max = 80) String department,
        @NotNull @Size(max = 1000) String note,
        @NotNull String status,
        @Min(0) int version) {}
