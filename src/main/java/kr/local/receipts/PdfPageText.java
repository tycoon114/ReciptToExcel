package kr.local.receipts;

import java.io.IOException;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.*;

/** 텍스트형 PDF의 글자 위치를 OCR와 같은 행·단어 좌표로 변환합니다. 스캔 PDF는 빈 결과로 OCR에 넘깁니다. */
final class PdfPageText extends PDFTextStripper {
    record Page(String text,List<WindowsOcr.Row> rows) {}
    private final List<WindowsOcr.Word> words=new ArrayList<>();
    private PdfPageText() throws IOException {setSortByPosition(true);}

    static Page read(PDDocument document,int page) throws IOException {
        var reader=new PdfPageText();reader.setStartPage(page);reader.setEndPage(page);
        String text=reader.getText(document);
        if(text.length()>200_000||reader.words.size()>200_000)throw ReceiptService.bad("PDF 한 페이지의 문자 수가 너무 많습니다.");
        var groups=new ArrayList<List<WindowsOcr.Word>>();
        for(var word:reader.words.stream().sorted(Comparator.comparingDouble(WindowsOcr.Word::top).thenComparingDouble(WindowsOcr.Word::left)).toList()) {
            List<WindowsOcr.Word> group=groups.isEmpty()?null:groups.get(groups.size()-1);
            if(group==null || Math.abs(group.get(0).top()-word.top())>Math.max(2,(word.bottom()-word.top())*.45)) {
                group=new ArrayList<>();groups.add(group);
            }
            group.add(word);
        }
        var rows=new ArrayList<WindowsOcr.Row>();
        for(var group:groups) {
            group.sort(Comparator.comparingDouble(WindowsOcr.Word::left));
            rows.add(new WindowsOcr.Row(group.stream().map(WindowsOcr.Word::text).collect(java.util.stream.Collectors.joining(" ")),
                    group.stream().mapToDouble(WindowsOcr.Word::top).min().orElse(0),group.stream().mapToDouble(WindowsOcr.Word::bottom).max().orElse(0),group));
        }
        return new Page(text,List.copyOf(rows));
    }
    @Override protected void writeString(String text,List<TextPosition> positions) throws IOException {
        if(words.size()+positions.size()>200_000)throw new IOException("PDF text limit");
        // PDF의 각 글자 좌표를 유지합니다. 숫자를 전역으로 합치지 않고 해당 표 셀 안에서만 연결합니다.
        for(var p:positions)if(!p.getUnicode().isBlank())words.add(new WindowsOcr.Word(p.getUnicode(),p.getXDirAdj(),p.getYDirAdj(),
                p.getXDirAdj()+p.getWidthDirAdj(),p.getYDirAdj()+p.getHeightDir()));
        super.writeString(text,positions);
    }
}
