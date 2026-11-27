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
                    new Object[]{"부가세", "카드 내역에 기존 공제 검토 규칙 적용 · 공제 후보는 세무사 확인 필요"},
                    new Object[]{"테스트 조회", "조회구분이 테스트 조회인 행은 가상 응답을 사용한 개발 자료"},
                    new Object[]{"빈칸", "확인하지 못했거나 원본에 없는 값 · 0원과 구분"},
                    new Object[]{"세금계산서", "매출·매입 미지정 문서는 거래 상대방 확인 필요 · 당사 사업자번호 출력 제외"},
                    new Object[]{"영수증 사진", "선택 자료 · 사진 연결 기능 준비 중"},
                    new Object[]{"처리현황", "미분석·일부 추출·실패한 페이지를 확인하세요. 날짜 미확인 행은 월 필터 시 제외됩니다."}));
            var cards = new ArrayList<Object[]>();
            var accounts = new ArrayList<Object[]>();
            var invoices = new ArrayList<Object[]>();
            var states = new ArrayList<Object[]>();
            var combined = new ArrayList<Object[]>();
            var vatDetails = new ArrayList<Object[]>();
            long cardCount = 0, vatTotal = 0, candidateTotal = 0, excludedTotal = 0;
            long missingVat = 0, unknownCurrency = 0, pending = 0, tested = 0, live = 0, reviewed = 0;
            for (var document : documents) {
                var file = document.file(); var result = document.analysis();
                var taxByRow = new HashMap<String, ImportAnalysis.CardReview>();
                for (var tax : result.cardReviews()) taxByRow.put(reviewKey(tax.page(), tax.line(), tax.sheet()), tax);
                long count = result.rows().stream().filter(row -> included(row, month)).count();
                states.add(new Object[]{file.name(), label(file.kind()), status(result.status()), count, result.analyzedAt(),
                        "", "", file.id()});
                for (var issue : result.issues()) states.add(new Object[]{file.name(), label(file.kind()), "확인 필요", null,
                        result.analyzedAt(), issue.sheet() != null && !issue.sheet().isBlank() ? issue.sheet() : issue.page() == 0 ? null : issue.page(), issue.message(), file.id()});
                for (var row : result.rows()) {
                    if (!included(row, month)) continue;
                    var cardTax = taxByRow.get(reviewKey(row.page(), row.line(), row.sheet()));
                    var tax = taxCells(cardTax, file.kind() == ImportFiles.Kind.CARD_PDF);
                    if (file.kind() == ImportFiles.Kind.CARD_PDF) {
                        cardCount++;
                        boolean krw = "KRW".equals(row.currency());
                        if (!krw) unknownCurrency++;
                        else if (row.vat() == null) missingVat++;
                        else vatTotal += row.vat();
                        if (cardTax == null) pending++;
                        else {
                            reviewed++;
                            if ("TEST".equals(cardTax.mode())) tested++; else live++;
                            if (krw && cardTax.candidateVat() != null) candidateTotal += cardTax.candidateVat();
                            if (krw && "NON_DEDUCTIBLE".equals(cardTax.review().code()) && row.vat() != null)
                                excludedTotal += row.vat();
                            if ("REVIEW".equals(cardTax.review().code())) pending++;
                        }
                        vatDetails.add(new Object[]{row.date(), row.party(), row.businessNumber(), tax[0], row.amount(),
                                row.vat(), tax[4], tax[3], tax[5], tax[1], row.currency(), file.name(), location(row)});
                    }
                    combined.add(append(new Object[]{row.date(), label(file.kind()), row.party(), row.amount(), row.supply(), row.vat(),
                            row.deposit(), row.withdrawal(), row.balance(), row.type(), row.reference(), row.originalReference(),
                            row.businessNumber(), row.time(), row.currency(), row.description(), row.warning(), file.name(), location(row), file.id()}, tax));
                    if (file.kind() == ImportFiles.Kind.CARD_PDF) cards.add(append(new Object[]{row.date(), row.time(), row.type(),
                            row.party(), row.businessNumber(), row.reference(), row.originalReference(), row.amount(), row.supply(), row.vat(),
                            row.currency(), row.description(), row.warning(), file.name(), location(row), row.line(), file.id()}, tax));
                    if (file.kind() == ImportFiles.Kind.ACCOUNT_PDF) accounts.add(new Object[]{row.date(), row.time(), row.party(),
                            row.deposit(), row.withdrawal(), row.balance(), row.currency(), row.description(), row.warning(), file.name(), location(row), row.line(), file.id()});
                    if (file.kind() == ImportFiles.Kind.TAX_INVOICE) invoices.add(new Object[]{row.type(), row.date(), row.party(),
                            row.businessNumber(), row.reference(), row.originalReference(), row.supply(), row.vat(), row.amount(), row.description(),
                            row.warning(), file.name(), location(row), file.id()});
                }
            }
            combined.sort(Comparator.comparing((Object[] row) -> row[0].toString().isEmpty() ? "9999-99-99" : row[0].toString())
                    .thenComparing(row -> row[1].toString()).thenComparing(row -> row[17].toString()));
            write(book, "통합내역", new String[]{"거래일 / 작성일", "자료 종류", "거래처", "총액 / 이용금액", "공급가액", "부가세", "계좌 입금액", "계좌 출금액", "계좌 잔액", "거래 구분", "승인 / 문서번호", "원승인 / 원문서번호", "거래처 사업자번호", "시각", "통화", "적요 / 품목", "추출 확인사항", "원본 파일", "원본 위치", "자료 ID", "과세유형", "조회구분", "조회일시", "부가세 검토", "공제 후보 세액", "검토 사유", "검토 규칙버전"}, combined);
            var summary = new ArrayList<Object[]>(List.of(
                    new Object[]{"조회 범위", month.isEmpty() ? "등록 자료 전체" : month, "거래일 기준 · 카드 내역만 집계"},
                    new Object[]{"조회 출처", "테스트 " + tested + "건 / 실제 " + live + "건", tested > 0 ? "테스트 응답 포함 · 실제 신고·제출용 금액이 아닙니다." : "실제 조회 여부와 검토 사유를 거래별로 확인하세요."},
                    new Object[]{"카드 거래", cardCount, "월 범위 내 등록 카드 내역 건수"},
                    new Object[]{"카드 부가세 합계 (원)", cardCount == 0 || cardCount == unknownCurrency + missingVat ? null : vatTotal, "원화로 확인된 원본 기재 세액 · 취소·검토 필요 포함"},
                    new Object[]{"공제 후보 세액 (원)", reviewed == 0 ? null : candidateTotal, "현재 카드 검토 규칙의 공제 후보 합계 · 최종 환급액 아님"},
                    new Object[]{"불공제 분류 세액 (원)", reviewed == 0 ? null : excludedTotal, "불공제로 분류된 원화 카드 세액 · 최종 판단은 검토 필요"},
                    new Object[]{"검토 필요 / 미조회 (건)", pending, "세액·사업자번호·용도 등 추가 확인 대상"},
                    new Object[]{"원화 세액 미기재 (건)", missingVat, "부가세 합계에 포함되지 않음 · 0원과 구분"},
                    new Object[]{"원화 미확인 (건)", unknownCurrency, "금액 집계 제외 · 통화 확인 필요"},
                    new Object[]{"최종 납부 / 환급 예상액", "산정 전", "매출세액·매입 공제 검토·증빙 중복 제거 등을 반영한 최종 계산은 아직 제공하지 않습니다."},
                    new Object[]{"세금계산서", "요약 합산 제외", "카드와 중복 공제를 방지하기 위해 거래 연결·공제 검토 전에는 합산하지 않습니다."}));
            summary.addAll(registrationRows(documents, month));
            write(book, "부가세 요약", new String[]{"항목", "금액 / 건수", "산정 범위 / 안내"}, summary);
            vatDetails.sort(Comparator.comparing(row -> Objects.toString(row[0], "")));
            write(book, "카드 부가세 검토", new String[]{"거래일", "거래처", "사업자번호", "과세유형", "이용금액", "원본 부가세", "공제 후보 세액", "부가세 검토", "검토 사유", "조회구분", "통화", "원본 파일", "원본 위치"}, vatDetails);
            book.setSheetOrder("부가세 요약", 0);
            book.setSheetOrder("카드 부가세 검토", 1);
            book.setSheetOrder("통합내역", 2);
            book.setActiveSheet(0); book.setSelectedTab(0);
            write(book, "카드내역", new String[]{"사용일자", "시각", "구분", "가맹점", "거래처 사업자번호", "승인번호", "원승인번호", "이용금액", "공급가액", "부가세", "통화", "적요", "추출 확인사항", "원본 파일", "페이지 / 시트", "원본 행", "자료 ID", "과세유형", "조회구분", "조회일시", "부가세 검토", "공제 후보 세액", "검토 사유", "검토 규칙버전"}, cards);
            write(book, "계좌입출금", new String[]{"거래일자", "시각", "거래 상대방", "입금액", "출금액", "거래 후 잔액", "통화", "적요", "추출 확인사항", "원본 파일", "페이지 / 시트", "원본 행", "자료 ID"}, accounts);
            write(book, "세금계산서", new String[]{"매출·매입", "작성일자", "거래처", "거래처 사업자번호", "문서 번호", "원문서 번호", "공급가액", "부가세", "합계금액", "품목·수정 사유", "추출 확인사항", "원본 파일", "페이지 / 시트", "자료 ID"}, invoices);
            write(book, "처리현황", new String[]{"원본 파일", "자료 종류", "분석 상태", "범위 내 추출 건수", "분석일시", "확인 페이지", "안내", "자료 ID"}, states);
            book.write(out);
            return out.toByteArray();
        }
    }

    static boolean hasTransactions(List<ImportAnalysis.Document> documents, String month) {
        return documents.stream().filter(document -> document.file().kind() != ImportFiles.Kind.RECEIPT_PHOTO)
                .anyMatch(document -> document.analysis().rows().stream().anyMatch(row -> included(row, month)));
    }

    private static List<Object[]> registrationRows(List<ImportAnalysis.Document> documents, String month) {
        var values = new ArrayList<Object[]>();
        values.add(new Object[]{"자료 등록 현황", "등록 자료 없음 ≠ 실제 거래 없음", "파일이 있어도 미분석·날짜 미확인 내역은 선택 월에서 빠질 수 있습니다."});
        for (var kind : ImportFiles.Kind.values()) {
            var files = documents.stream().filter(document -> document.file().kind() == kind).toList();
            long count = files.stream().flatMap(document -> document.analysis().rows().stream()).filter(row -> included(row, month)).count();
            long selectedFiles = files.stream().filter(document -> document.analysis().rows().stream().anyMatch(row -> included(row, month))).count();
            long unchecked = files.stream().filter(document -> document.analysis().analyzedAt().isEmpty()
                    || !document.analysis().status().equals("READY")
                    || document.analysis().rows().stream().anyMatch(row -> row.date().isEmpty())).count();
            boolean photo = kind == ImportFiles.Kind.RECEIPT_PHOTO;
            String state = files.isEmpty() ? "등록 자료 없음" : photo ? "등록됨 · 월 연결 전" : count > 0
                    ? count + "건 · 해당 파일 " + selectedFiles + "개" : month.isEmpty() ? "추출 내역 없음" : "선택 월 추출 내역 없음";
            String note = "전체 등록 " + files.size() + "개 · " + (photo ? "선택 자료 · 거래일 연결 전으로 월별 구분하지 않음"
                    : "거래일·작성일 기준 · 분석 대기·확인 필요 " + unchecked + "개 (전체 등록 기준)");
            values.add(new Object[]{"자료 현황 · " + label(kind), state, note});
        }
        return values;
    }

    static boolean included(ImportAnalysis.Transaction row, String month) { return month.isEmpty() || row.date().startsWith(month + "-"); }
    private static String reviewKey(int page, int line, String sheet) { return page + "/" + line + "/" + Objects.toString(sheet, ""); }
    private static Object[] taxCells(ImportAnalysis.CardReview tax, boolean card) {
        if (!card) return new Object[]{"", "", "", "", null, "", ""};
        if (tax == null) return new Object[]{"미조회", "미조회", "", "확인 필요", null, "카드 과세유형·부가세 검토를 실행해 주세요.", ""};
        return new Object[]{tax.lookup() == null ? "미조회" : tax.lookup().taxType(), tax.mode().equals("TEST") ? "테스트 조회" : "국세청 실제 조회",
                tax.lookup() == null ? tax.checkedAt() : tax.lookup().checkedAt(), tax.review().label(), tax.candidateVat(),
                tax.review().reason(), tax.review().ruleVersion()};
    }
    private static Object[] append(Object[] row, Object[] extra) {
        var result = Arrays.copyOf(row, row.length + extra.length);
        System.arraycopy(extra, 0, result, row.length, extra.length);
        return result;
    }
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
            sheet.setColumnWidth(i, (headings[i].contains("안내") || headings[i].contains("확인사항") || headings[i].equals("내용") || headings[i].equals("산정 범위 / 안내") || headings[i].equals("검토 사유") ? 65 : 24) * 256);
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
