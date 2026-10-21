package kr.local.receipts;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import java.io.*;
import java.time.LocalDate;
import java.util.*;

/** 경비 기장 보조 자료입니다. 활성 분개와 취소 이력을 나누고, 기초 잔액 없는 발생 합계를 잔액으로 표시하지 않습니다. */
@Service
public class BookkeepingExporter {
    /** 전달한 전표의 활성 분개와 취소 이력을 각각 출력하며, 취소 금액을 합산하지 않습니다. */
    public byte[] export(List<BookkeepingService.Entry> entries) throws IOException {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            CellStyle header = book.createCellStyle();
            var font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(font); header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = book.createCellStyle(); money.setDataFormat(book.createDataFormat().getFormat("#,##0"));
            CellStyle date = book.createCellStyle(); date.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            var journal = sheet(book, "분개장", "기장 완료 전표만 포함 · 취소 전표 제외 · 원 단위 · 경비 지출 기장 보조 자료",
                    List.of("전표번호","기장일","거래일","영수증ID","거래처","적요","구분","계정과목","차변(원)","대변(원)","기장자"), header);
            var totals = new TreeMap<String,long[]>();
            int row = 4; long debit = 0, credit = 0;
            for (var entry : entries) {
                if (!entry.status().equals("POSTED")) continue;
                for (var line : entry.lines()) {
                    boolean dr = line.side().equals("차변");
                    write(journal, row++, List.of(entry.entryNumber(), LocalDate.parse(entry.entryDate()),
                            LocalDate.parse(entry.receiptDate()), entry.expenseId(), entry.vendor(), entry.memo(),
                            line.side(), line.account(), dr ? line.amount() : 0L, dr ? 0L : line.amount(), entry.postedBy()), money, date);
                    long[] sum = totals.computeIfAbsent(line.account(), ignored -> new long[2]);
                    sum[dr ? 0 : 1] += line.amount();
                    if (dr) debit += line.amount(); else credit += line.amount();
                }
            }
            journal.setAutoFilter(new CellRangeAddress(3,Math.max(3,row-1),0,10));
            write(journal, row+1, List.of("합계","","","","","","","",debit,credit,""), money, date);
            var accounts = sheet(book, "계정별발생", "활성 전표의 차변·대변 발생 합계 · 기초 잔액·매출·결산 미포함 · 잔액이나 재무제표가 아닙니다.",
                    List.of("계정과목","차변 발생액(원)","대변 발생액(원)"), header);
            row = 4;
            for (var entry : totals.entrySet()) write(accounts,row++,List.of(entry.getKey(),entry.getValue()[0],entry.getValue()[1]),money,date);
            accounts.setAutoFilter(new CellRangeAddress(3,Math.max(3,row-1),0,2));
            write(accounts,row+1,List.of("합계",debit,credit),money,date);
            var cancelled = sheet(book, "기장취소이력", "취소 전표 원본을 보존한 검토 자료 · 분개장과 발생 합계에서는 제외됩니다.",
                    List.of("전표번호","기장일","영수증ID","거래처","적요","원전표 금액(원)","기장자","취소자","취소시각(UTC)","취소사유"),header);
            row = 4;
            for (var entry : entries) if (entry.status().equals("CANCELLED"))
                write(cancelled,row++,List.of(entry.entryNumber(),LocalDate.parse(entry.entryDate()),entry.expenseId(),entry.vendor(),
                        entry.memo(),entry.totalAmount(),entry.postedBy(),entry.cancelledBy(),entry.cancelledAt(),entry.cancelReason()),money,date);
            cancelled.setAutoFilter(new CellRangeAddress(3,Math.max(3,row-1),0,9));
            book.write(output);
            return output.toByteArray();
        }
    }

    private Sheet sheet(Workbook book, String title, String note, List<String> headers, CellStyle style) {
        var sheet = book.createSheet(title);
        sheet.createRow(0).createCell(0).setCellValue(title);
        sheet.createRow(1).createCell(0).setCellValue(note);
        var row = sheet.createRow(3);
        for (int i=0;i<headers.size();i++) {
            var cell = row.createCell(i); cell.setCellValue(headers.get(i)); cell.setCellStyle(style);
            sheet.setColumnWidth(i, (i==0 || headers.get(i).contains("적요") || headers.get(i).contains("사유") ? 34 : 22)*256);
        }
        sheet.createFreezePane(0,4); sheet.setDisplayGridlines(false);
        sheet.setAutoFilter(new CellRangeAddress(3,3,0,headers.size()-1));
        return sheet;
    }

    /** 입력 문구는 수식으로 해석하지 않고 문자열 셀로 저장합니다. 날짜와 금액만 실제 값 형식을 사용합니다. */
    private void write(Sheet sheet, int index, List<?> values, CellStyle money, CellStyle date) {
        var row = sheet.createRow(index);
        for (int i=0;i<values.size();i++) {
            var cell = row.createCell(i); Object value = values.get(i);
            if (value instanceof Number n) { cell.setCellValue(n.doubleValue()); cell.setCellStyle(money); }
            else if (value instanceof LocalDate d) { cell.setCellValue(d.atStartOfDay()); cell.setCellStyle(date); }
            else cell.setCellValue(Objects.toString(value,""));
        }
    }
}
