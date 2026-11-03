package kr.local.receipts;

import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** 문서 합계를 중복 집계하지 않도록 세금계산서의 품목별 단가·세액은 별도 시트로 내보냅니다. */
final class TaxInvoiceWorkbook {
    record Source(String reference,String state,long expenseId,String vendor,TaxInvoice invoice) {}
    static void add(Workbook book,List<Source> sources) {
        var invoices=sources.stream().filter(s->s.invoice()!=null).toList();
        if(invoices.isEmpty())return;
        var sheet=book.createSheet("세금계산서품목");
        var header=book.createCellStyle();var font=book.createFont();font.setBold(true);header.setFont(font);
        header.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var numeric=book.createCellStyle();numeric.setDataFormat(book.createDataFormat().getFormat("#,##0.######"));
        var identifier=book.createCellStyle();identifier.setDataFormat(book.createDataFormat().getFormat("@"));
        var wrap=book.createCellStyle();wrap.setWrapText(true);
        String[] headers={"출처","상태","관리ID","공급자","세금계산서번호","공급받는자사업자번호","공급받는자상호","품목번호","품목일자","품목","규격","수량","단가(원)","품목공급가액(원)","품목세액(원)","인식안내"};
        sheet.createRow(0).createCell(0).setCellValue("품목 대조용 · 문서 합계는 지출장부/분개장 참조 · 단가·공급가액은 공제세액이 아님 · 취소 전표는 집계 제외");
        sheet.addMergedRegion(new CellRangeAddress(0,1,0,headers.length-1));sheet.getRow(0).getCell(0).setCellStyle(wrap);
        var title=sheet.createRow(3);for(int i=0;i<headers.length;i++){title.createCell(i).setCellValue(headers[i]);title.getCell(i).setCellStyle(header);sheet.setColumnWidth(i,(i==9?34:i==15?70:22)*256);}
        int row=4;
        for(var source:invoices) {
            var invoice=source.invoice();
            int count=Math.max(1,invoice.items().size());
            for(int i=0;i<count;i++) {
                var item=invoice.items().isEmpty()?null:invoice.items().get(i);
                Object[] values={source.reference(),source.state(),source.expenseId(),source.vendor(),invoice.issueNumber(),invoice.buyerBusinessNumber(),invoice.buyerName(),
                        item==null?null:i+1,item==null?"":item.date(),item==null?"":item.name(),item==null?"":item.specification(),item==null?null:item.quantity(),item==null?null:item.unitPrice(),
                        item==null?null:item.supplyAmount(),item==null?null:item.taxAmount(),invoice.warning()};
                var data=sheet.createRow(row++);data.setHeightInPoints(45);
                for(int j=0;j<values.length;j++) {
                    var cell=data.createCell(j);Object value=values[j];
                    // 엑셀의 유효 숫자 한도를 넘는 소수 단가는 텍스트로 보존해 끝자리가 바뀌지 않게 합니다.
                    if(value instanceof java.math.BigDecimal decimal && decimal.precision()>15){cell.setCellValue(decimal.toPlainString());cell.setCellStyle(identifier);}
                    else if(value instanceof Number n){cell.setCellValue(n.doubleValue());if(j>=11&&j<=14)cell.setCellStyle(numeric);}
                    else {cell.setCellValue(value==null?"":value.toString());cell.setCellStyle(j==4||j==5?identifier:wrap);}
                }
            }
        }
        sheet.createFreezePane(4,4);sheet.setAutoFilter(new CellRangeAddress(3,row-1,0,headers.length-1));
    }
}
