package kr.local.receipts;

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
        int version) {}
