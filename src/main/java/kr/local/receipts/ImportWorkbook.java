package kr.local.receipts;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 원본 종류별 추출 내역을 한 파일에 담습니다. 증빙 간 중복 제거 전의 대조용 자료입니다. */
final class ImportWorkbook {
    static byte[] create(List<ImportAnalysis.Document> documents, String month) throws IOException {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            write(book, "안내", new String[]{"항목", "내용"}, List.of(
                    new Object[]{"조회 범위", month.isEmpty() ? "등록 자료 전체" : month + " · 자료별 거래일/작성일 기준"},
                    new Object[]{"자료 성격", "PDF·엑셀 원본에서 추출한 대조용 내역 · 원본 확인 후 사용"},
                    new Object[]{"통합내역", "카드·계좌·세금계산서를 날짜순으로 모은 내역 · 자료별 시트는 개발 대조용"},
                    new Object[]{"합산 기준", "카드·계좌·세금계산서 간 거래 연결과 중복 제거는 후속 단계"},
                    new Object[]{"부가세", "표시 세액은 원본 추출값 · 공제 판정 및 환급 계산 연결 전"},
                    new Object[]{"빈칸", "확인하지 못했거나 원본에 없는 값 · 0원과 구분"},
                    new Object[]{"세금계산서", "매출·매입 미지정 문서는 거래 상대방 확인 필요 · 당사 사업자번호 출력 제외"},
                    new Object[]{"영수증 사진", "선택 자료 · 사진 연결 기능 준비 중"},
                    new Object[]{"처리현황", "미분석·일부 추출·실패한 페이지를 확인하세요. 날짜 미확인 행은 월 필터 시 제외됩니다."}));
            var cards = new ArrayList<Object[]>();
            var accounts = new ArrayList<Object[]>();
            var invoices = new ArrayList<Object[]>();
            var states = new ArrayList<Object[]>();
            var combined = new ArrayList<Object[]>();
            for (var document : documents) {
                var file = document.file(); var result = document.analysis();
                long count = result.rows().stream().filter(row -> included(row, month)).count();
                states.add(new Object[]{file.name(), label(file.kind()), status(result.status()), count, result.analyzedAt(),
                        "", "", file.id()});
                for (var issue : result.issues()) states.add(new Object[]{file.name(), label(file.kind()), "확인 필요", null,
                        result.analyzedAt(), issue.sheet() != null && !issue.sheet().isBlank() ? issue.sheet() : issue.page() == 0 ? null : issue.page(), issue.message(), file.id()});
                for (var row : result.rows()) {
                    if (!included(row, month)) continue;
                    combined.add(new Object[]{row.date(), label(file.kind()), row.party(), row.amount(), row.supply(), row.vat(),
                            row.deposit(), row.withdrawal(), row.balance(), row.type(), row.reference(), row.originalReference(),
                            row.businessNumber(), row.time(), row.currency(), row.description(), row.warning(), file.name(), location(row), file.id()});
                    if (file.kind() == ImportFiles.Kind.CARD_PDF) cards.add(new Object[]{row.date(), row.time(), row.type(),
                            row.party(), row.businessNumber(), row.reference(), row.originalReference(), row.amount(), row.supply(), row.vat(),
                            row.currency(), row.description(), row.warning(), file.name(), location(row), row.line(), file.id()});
                    if (file.kind() == ImportFiles.Kind.ACCOUNT_PDF) accounts.add(new Object[]{row.date(), row.time(), row.party(),
                            row.deposit(), row.withdrawal(), row.balance(), row.currency(), row.description(), row.warning(), file.name(), location(row), row.line(), file.id()});
                    if (file.kind() == ImportFiles.Kind.TAX_INVOICE) invoices.add(new Object[]{row.type(), row.date(), row.party(),
                            row.businessNumber(), row.reference(), row.originalReference(), row.supply(), row.vat(), row.amount(), row.description(),
                            row.warning(), file.name(), location(row), file.id()});
                }
            }
            combined.sort(Comparator.comparing((Object[] row) -> row[0].toString().isEmpty() ? "9999-99-99" : row[0].toString())
                    .thenComparing(row -> row[1].toString()).thenComparing(row -> row[17].toString()));
            write(book, "통합내역", new String[]{"거래일 / 작성일", "자료 종류", "거래처", "총액 / 이용금액", "공급가액", "부가세", "계좌 입금액", "계좌 출금액", "계좌 잔액", "거래 구분", "승인 / 문서번호", "원승인 / 원문서번호", "거래처 사업자번호", "시각", "통화", "적요 / 품목", "추출 확인사항", "원본 파일", "원본 위치", "자료 ID"}, combined);
            book.setSheetOrder("통합내역", 0); book.setActiveSheet(0); book.setSelectedTab(0);
            write(book, "카드내역", new String[]{"사용일자", "시각", "구분", "가맹점", "거래처 사업자번호", "승인번호", "원승인번호", "이용금액", "공급가액", "부가세", "통화", "적요", "추출 확인사항", "원본 파일", "페이지 / 시트", "원본 행", "자료 ID"}, cards);
            write(book, "계좌입출금", new String[]{"거래일자", "시각", "거래 상대방", "입금액", "출금액", "거래 후 잔액", "통화", "적요", "추출 확인사항", "원본 파일", "페이지 / 시트", "원본 행", "자료 ID"}, accounts);
            write(book, "세금계산서", new String[]{"매출·매입", "작성일자", "거래처", "거래처 사업자번호", "문서 번호", "원문서 번호", "공급가액", "부가세", "합계금액", "품목·수정 사유", "추출 확인사항", "원본 파일", "페이지 / 시트", "자료 ID"}, invoices);
            write(book, "처리현황", new String[]{"원본 파일", "자료 종류", "분석 상태", "범위 내 추출 건수", "분석일시", "확인 페이지", "안내", "자료 ID"}, states);
            book.write(out);
            return out.toByteArray();
        }
    }

    static boolean included(ImportAnalysis.Transaction row, String month) { return month.isEmpty() || row.date().startsWith(month + "-"); }
    private static Object location(ImportAnalysis.Transaction row) {
        return row.sheet() == null || row.sheet().isBlank() ? row.page() : row.sheet() + " · " + row.line() + "행";
    }
    static String label(ImportFiles.Kind kind) {
        return switch (kind) { case CARD_PDF -> "법인카드"; case ACCOUNT_PDF -> "계좌 입출금"; case TAX_INVOICE -> "세금계산서"; case RECEIPT_PHOTO -> "영수증 사진"; };
    }
    static String status(String code) {
        return switch (code) { case "READY" -> "추출 완료"; case "REVIEW" -> "원본 확인 필요"; case "FAILED" -> "분석 실패"; case "PHOTO_PENDING" -> "사진 처리 대기"; default -> "분석 대기"; };
    }
    private static void write(Workbook book, String name, String[] headings, List<Object[]> values) {
        var sheet = book.createSheet(name);
        var header = book.createCellStyle(); var font = book.createFont(); font.setBold(true); header.setFont(font);
        header.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var numeric = book.createCellStyle(); numeric.setDataFormat(book.createDataFormat().getFormat("#,##0;[Red]-#,##0"));
        var wrap = book.createCellStyle(); wrap.setWrapText(true); wrap.setVerticalAlignment(VerticalAlignment.TOP);
        var first = sheet.createRow(0); first.setHeightInPoints(28);
        for (int i = 0; i < headings.length; i++) {
            first.createCell(i).setCellValue(headings[i]); first.getCell(i).setCellStyle(header);
            sheet.setColumnWidth(i, (headings[i].contains("안내") || headings[i].contains("확인사항") || headings[i].equals("내용") ? 65 : 24) * 256);
        }
        int index = 1;
        for (var value : values) {
            var row = sheet.createRow(index++); row.setHeightInPoints(36);
            for (int i = 0; i < value.length; i++) {
                var cell = row.createCell(i);
                if (value[i] instanceof Number number) { cell.setCellValue(number.doubleValue()); cell.setCellStyle(numeric); }
                else { cell.setCellValue(value[i] == null ? "" : value[i].toString()); cell.setCellStyle(wrap); }
            }
        }
        sheet.createFreezePane(name.equals("통합내역") ? 3 : 0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, index - 1), 0, headings.length - 1));
    }
}
