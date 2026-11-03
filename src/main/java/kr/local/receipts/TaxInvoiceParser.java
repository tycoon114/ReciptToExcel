package kr.local.receipts;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** 별지 제14호의 공급자/공급받는 자·합계·품목 표를 일반 영수증과 분리해 읽습니다. */
final class TaxInvoiceParser {
    private static final String MONEY="(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)";
    private static final Pattern NUMBER=Pattern.compile("(?<![0-9])(?:[0-9]{3}-[0-9]{2}-[0-9]{5}|[0-9]{10})(?![0-9])");
    private static final String NOTICE="세금계산서 인식 제안입니다. 공급자·공급받는 자와 품목을 원본 대조하세요. 단가·공급가액은 공제세액이 아니며, 세액도 공제 확정은 아닙니다.";
    private TaxInvoiceParser() {}

    static boolean isInvoice(String text) { return Objects.toString(text,"").lines().anyMatch(TaxInvoiceParser::title); }
    private static boolean title(String line) {
        return compact(line).matches("(?:전자|수정|전자수정|수정전자)?세금계산서(?:\\(.*\\)|공급.*|$)");
    }

    static ReceiptAnalyzer.Analysis parse(String raw,String hint,List<WindowsOcr.Row> rows) {
        return parse(raw,hint,rows,false);
    }

    /** 전용 등록 폼에서는 제목 OCR이 실패해도 세금계산서 검토 폼을 유지하며 누락 안내를 남깁니다. */
    static ReceiptAnalyzer.Analysis parseForced(String raw,String hint,List<WindowsOcr.Row> rows) {
        return parse(raw,hint,rows,true);
    }

    private static ReceiptAnalyzer.Analysis parse(String raw,String hint,List<WindowsOcr.Row> rows,boolean forced) {
        String text=Normalizer.normalize(Objects.toString(raw,""),Normalizer.Form.NFKC);
        List<WindowsOcr.Row> layout=arrangedRows(rows==null?List.of():rows);
        boolean recognized=isInvoice(text)||layout.stream().anyMatch(r->title(r.text()));
        if(!forced&&!recognized)return null;
        var warnings=new ArrayList<String>();
        if(!recognized)warnings.add("세금계산서 제목을 인식하지 못했습니다. 선택한 증빙 종류와 원본을 대조하세요.");
        if(Math.max(text.lines().filter(TaxInvoiceParser::title).count(),layout.stream().filter(r->title(r.text())).count())>1) {
            var invoice=new TaxInvoice("","","","한 페이지에 세금계산서가 여러 장 있습니다. 보관용 사본도 합산하지 않습니다. 한 장씩 잘라 등록하세요.",List.of());
            return analysis(text,hint,"","",null,null,null,null,invoice);
        }
        var lines=text.lines().toList();
        // 좌우 사업자 정보는 좌표로 분리합니다. 좌표가 없으면 명시된 역할 표기만 사용합니다.
        String supplier=roleText(lines,"공급자"),buyer=roleText(lines,"공급받는자");
        WindowsOcr.Row numberRow=layout.stream().filter(r->numbers(r.text()).size()==2).findFirst().orElse(null);
        if(numberRow!=null) {
            var spans=spans(numberRow,Pattern.compile("[0-9]{3}-[0-9]{2}-[0-9]{5}|[0-9]{10}"));
            if(spans.size()==2) {
                double split=(spans.get(0).right()+spans.get(1).left())/2;
                StringBuilder left=new StringBuilder(),right=new StringBuilder();
                for(var row:layout) {
                    if(row.top()+2<numberRow.top())continue;
                    String c=compact(row.text());
                    if(c.contains("공급가액")||c.contains("작성")||c.contains("품목"))break;
                    left.append(cell(row,Double.NEGATIVE_INFINITY,split)).append('\n');
                    right.append(cell(row,split,Double.POSITIVE_INFINITY)).append('\n');
                }
                supplier=left.toString();buyer=right.toString();
            }
        }
        String business=business(supplier),buyerBusiness=business(buyer);
        String vendor=labelText(supplier,"상\\s*호(?:\\s*\\(법인명\\))?|법\\s*인\\s*명");
        String buyerName=labelText(buyer,"상\\s*호(?:\\s*\\(법인명\\))?|법\\s*인\\s*명");
        String address=labelText(supplier,"사\\s*업\\s*장\\s*주\\s*소|주\\s*소");
        if(business.isBlank())warnings.add("공급자 사업자번호를 구분하지 못했습니다. 공급받는 자 번호로 대신하지 않습니다.");
        String date=writtenDate(text),issue=match(text,"(?:승\\s*인\\s*번\\s*호|일\\s*련\\s*번\\s*호)\\h*[:：]?\\h*([0-9][0-9-]{3,39})");
        Long supply=labelMoney(text,"공\\s*급\\s*가\\s*액(?:\\s*합\\s*계)?"),
                tax=labelMoney(text,"(?:부\\s*가\\s*(?:가\\s*치\\s*)?세|세\\s*액)(?:\\s*합\\s*계)?"),
                amount=labelMoney(text,"합\\s*계\\s*금\\s*액|총\\s*금\\s*액");
        var items=new ArrayList<TaxInvoice.Item>();
        for(int n=0;n<layout.size();n++) {
            var row=layout.get(n);
            if(compact(row.text()).contains("품목") && compact(row.text()).contains("단가")) {
                var columns=columns(row,List.of("월","일","품목","규격","수량","단가","공급가액","세액","비고"));
                if(columns.containsKey("품목")&&columns.containsKey("공급가액")&&columns.containsKey("세액")) {
                    for(int j=n+1;j<layout.size();j++) {
                        var data=layout.get(j);String c=compact(data.text());
                        if(c.contains("합계금액")||c.contains("이금액")||title(data.text()))break;
                        String name=value(data,columns,"품목");
                        if(name.isBlank())continue;
                        BigDecimal quantity=decimal(value(data,columns,"수량")),unit=decimal(value(data,columns,"단가"));
                        Long itemSupply=money(value(data,columns,"공급가액")),itemTax=money(value(data,columns,"세액"));
                        if(quantity==null&&unit==null&&itemSupply==null&&itemTax==null)continue;
                        if(items.size()==100){warnings.add("품목은 최대 100줄까지 읽습니다.");break;}
                        items.add(new TaxInvoice.Item("",ReceiptAnalyzer.cut(name,200),ReceiptAnalyzer.cut(value(data,columns,"규격"),100),quantity,unit,itemSupply,itemTax));
                    }
                }
            } else if(compact(row.text()).contains("공급가액")&&compact(row.text()).contains("세액")&&!compact(row.text()).contains("품목")) {
                // 작성일/공급가액/세액의 제목 다음 숫자 행은 같은 열 위치끼리만 연결합니다.
                var columns=columns(row,List.of("작성","공급가액","세액","비고"));
                for(int j=n+1;j<Math.min(layout.size(),n+4);j++) {
                    var data=layout.get(j);if(compact(data.text()).contains("품목"))break;
                    Long a=money(value(data,columns,"공급가액")),b=money(value(data,columns,"세액"));
                    if(a!=null) supply=agree(supply,a,warnings,"공급가액");
                    if(b!=null) tax=agree(tax,b,warnings,"세액");
                    if(date==null)date=dateCell(value(data,columns,"작성"));
                }
            } else if(compact(row.text()).contains("합계금액")&&amount==null && n+1<layout.size()) {
                var columns=columns(row,List.of("합계금액","현금","수표","어음","외상미수금"));
                amount=money(value(layout.get(n+1),columns,"합계금액"));
            }
        }
        // 구획이 명시된 텍스트 표도 읽습니다. 공백 개수로 불분명한 숫자들을 추측해 나누지는 않습니다.
        if(items.isEmpty())items.addAll(delimitedItems(lines));
        if(items.isEmpty())warnings.add("품목 표를 확실하게 읽지 못했습니다. 단가·수량·공급가액·세액을 직접 확인하세요.");
        if(amount==null && supply!=null && tax!=null) {
            amount=supply+tax;
            warnings.add("총금액은 명시된 공급가액과 세액을 합산한 값입니다. 합계금액 원본을 대조하세요.");
        }
        if(supply!=null&&tax!=null&&amount!=null&&supply+tax!=amount)warnings.add("문서 공급가액·세액과 합계금액이 다릅니다.");
        if(compact(text).matches("(?s).*수정(?:전자)?세금계산서.*")
                || Pattern.compile("(?:공\\s*급\\s*가\\s*액|세\\s*액|합\\s*계\\s*금\\s*액)\\h*[:：]?\\h*[-△]\\h*[0-9]").matcher(text).find()
                || text.lines().anyMatch(line->line.strip().matches("[-△]\\h*[0-9,]+")))
            warnings.add("수정·음수 세금계산서는 일반 지출로 확정하지 말고 별도 세무 검토하세요.");
        var invoice=new TaxInvoice(issue,buyerBusiness,buyerName,String.join(" ",warnings),items);
        return analysis(text,hint,vendor,business,date,supply,tax,amount,invoice,address);
    }

    private static ReceiptAnalyzer.Analysis analysis(String text,String hint,String vendor,String business,String date,Long supply,Long tax,Long amount,TaxInvoice invoice,String... address) {
        String purpose=Objects.toString(hint,"").trim();
        return new ReceiptAnalyzer.Analysis(date,vendor,ReceiptAnalyzer.classify(purpose),purpose,amount,supply,tax,"미확인",purpose,
                "TAX_INVOICE",NOTICE+" "+invoice.warning(),text,"",business,"",address.length==0?"":ReceiptAnalyzer.cut(address[0],300),invoice);
    }

    private static String roleText(List<String> lines,String role) {
        StringBuilder result=new StringBuilder();String current="";
        for(String line:lines) {
            String c=compact(line);
            if(c.startsWith("공급받는자"))current="공급받는자";
            else if(c.startsWith("공급자")&&!c.contains("보관용"))current="공급자";
            else if(c.contains("공급가액")||c.startsWith("작성")||c.contains("품목"))current="";
            if(current.equals(role))result.append(line.replaceFirst("공\\s*급\\s*(?:받\\s*는\\s*)?자\\h*[:：]?","")).append('\n');
        }
        return result.toString();
    }
    private static String business(String text) {
        var numbers=numbers(text);return numbers.size()==1?numbers.get(0):"";
    }
    private static List<String> numbers(String text) {
        var result=new LinkedHashSet<String>();var matcher=NUMBER.matcher(compact(text));
        while(matcher.find())if(ReceiptMetadataParser.validBusinessNumber(matcher.group()))result.add(ReceiptMetadataParser.formatBusinessNumber(matcher.group()));
        return List.copyOf(result);
    }
    private static String labelText(String text,String label) {
        label=label.replace("법인명","법\\s*인\\s*명");
        String value=match(text,"(?:"+label+")\\h*[:：]?\\h*([^\\r\\n]+)");
        return ReceiptAnalyzer.cut(value.replaceFirst("\\h*(?:성\\s*명|대표자|업\\s*태|종\\s*목).*","").trim(),200);
    }
    private static String writtenDate(String text) {
        String value=match(text,"작\\s*성(?:\\s*(?:일자|연월일|일))?\\h*[:：]?\\h*((?:20[0-9]{2})[./년 -]*[0-9]{1,2}[./월 -]*[0-9]{1,2}일?)");
        return dateCell(value);
    }
    private static String dateCell(String value) {
        String digits=compact(value).replaceAll("[./년월일-]","");
        try {if(digits.matches("20[0-9]{6}"))return LocalDate.of(Integer.parseInt(digits.substring(0,4)),Integer.parseInt(digits.substring(4,6)),Integer.parseInt(digits.substring(6))).toString();}
        catch(Exception ignored) { }
        return null;
    }
    private static Long labelMoney(String text,String label) {
        var found=new LinkedHashSet<Long>();
        for(String line:text.lines().toList()) {
            var m=Pattern.compile("^(?:"+label+")\\h*[:：]?\\h*₩?\\h*("+MONEY+")\\h*원?\\h*$").matcher(line.strip());
            if(m.matches())found.add(money(m.group(1)));
        }
        return found.size()==1?found.iterator().next():null;
    }
    private static String match(String text,String regex) {var m=Pattern.compile(regex).matcher(text);return m.find()?m.group(1).trim():"";}
    private static Long agree(Long old,Long next,List<String> warnings,String name) {
        if(old!=null&&!old.equals(next)){warnings.add(name+" 후보가 다릅니다. 원본을 확인하세요.");return null;}return next;
    }
    private static Long money(String value) {
        String clean=compact(value).replace(",","").replace("₩","").replace("원","");
        try {if(clean.matches("[0-9]{1,12}"))return Long.valueOf(clean);}catch(Exception ignored) { }
        return null;
    }
    private static BigDecimal decimal(String value) {
        String clean=compact(value).replace(",","");
        try {if(clean.matches("[0-9]{1,12}(?:\\.[0-9]{1,6})?"))return new BigDecimal(clean);}catch(Exception ignored) { }
        return null;
    }
    private static String compact(String value) {return Objects.toString(value,"").replaceAll("[\\s\\p{Z}]","");}

    private record Span(String text,double left,double right) { }
    /** OCR가 표의 각 칸을 별도 행으로 반환해도 같은 높이의 단어를 다시 모아 좌우 열을 연결합니다. */
    private static List<WindowsOcr.Row> arrangedRows(List<WindowsOcr.Row> rows) {
        var words=rows.stream().flatMap(r->r.words().stream()).sorted(Comparator.comparingDouble(w->(w.top()+w.bottom())/2)).toList();
        if(words.isEmpty())return rows;
        var groups=new ArrayList<List<WindowsOcr.Word>>();
        for(var word:words) {
            List<WindowsOcr.Word> group=groups.isEmpty()?null:groups.get(groups.size()-1);
            var first=group==null?null:group.get(0);
            if(first==null||Math.abs((first.top()+first.bottom()-word.top()-word.bottom())/2)>Math.max(3,Math.min(first.bottom()-first.top(),word.bottom()-word.top())*.55)) {
                group=new ArrayList<>();groups.add(group);
            }
            group.add(word);
        }
        var result=new ArrayList<WindowsOcr.Row>();
        for(var group:groups) {
            group.sort(Comparator.comparingDouble(WindowsOcr.Word::left));
            result.add(new WindowsOcr.Row(group.stream().map(WindowsOcr.Word::text).collect(java.util.stream.Collectors.joining(" ")),
                    group.stream().mapToDouble(WindowsOcr.Word::top).min().orElse(0),group.stream().mapToDouble(WindowsOcr.Word::bottom).max().orElse(0),group));
        }
        return result;
    }
    /** 글자 간 공백을 없앤 검색 문자열과 좌표를 대응시켜 인쇄 양식의 띄어 쓴 제목도 찾습니다. */
    private static List<Span> spans(WindowsOcr.Row row,Pattern pattern) {
        StringBuilder text=new StringBuilder();var owners=new ArrayList<WindowsOcr.Word>();
        row.words().stream().sorted(Comparator.comparingDouble(WindowsOcr.Word::left)).forEach(w->{
            for(char c:compact(w.text()).toCharArray()){text.append(c);owners.add(w);}
        });
        var result=new ArrayList<Span>();var m=pattern.matcher(text);
        while(m.find())if(m.start()<owners.size())result.add(new Span(m.group(),owners.get(m.start()).left(),owners.get(m.end()-1).right()));
        return result;
    }
    private static Map<String,double[]> columns(WindowsOcr.Row row,List<String> labels) {
        var anchors=new ArrayList<Span>();for(String label:labels)anchors.addAll(spans(row,Pattern.compile(Pattern.quote(label))));
        anchors.sort(Comparator.comparingDouble(Span::left));var result=new LinkedHashMap<String,double[]>();
        for(int i=0;i<anchors.size();i++) {
            var a=anchors.get(i);double center=(a.left()+a.right())/2;
            double left=i==0?Double.NEGATIVE_INFINITY:((anchors.get(i-1).left()+anchors.get(i-1).right())/2+center)/2;
            double right=i==anchors.size()-1?Double.POSITIVE_INFINITY:(center+(anchors.get(i+1).left()+anchors.get(i+1).right())/2)/2;
            result.put(a.text(),new double[]{left,right});
        }
        return result;
    }
    private static String value(WindowsOcr.Row row,Map<String,double[]> columns,String name) {
        var bounds=columns.get(name);return bounds==null?"":cell(row,bounds[0],bounds[1]);
    }
    private static String cell(WindowsOcr.Row row,double left,double right) {
        var words=atomicNumbers(row.words()).stream().filter(w->(w.left()+w.right())/2>=left&&(w.left()+w.right())/2<right)
                .sorted(Comparator.comparingDouble(WindowsOcr.Word::left)).toList();
        StringBuilder text=new StringBuilder();WindowsOcr.Word previous=null;
        for(var word:words) {
            if(previous!=null && word.left()-previous.right()>Math.max(1,(word.bottom()-word.top())*.25))text.append(' ');
            text.append(word.text());previous=word;
        }
        return text.toString().trim();
    }
    /** 자리수 칸으로 나뉜 인접 숫자는 한 묶음으로 배정해 열 경계에서 일부 자리만 잘리지 않게 합니다. */
    private static List<WindowsOcr.Word> atomicNumbers(List<WindowsOcr.Word> source) {
        var result=new ArrayList<WindowsOcr.Word>();
        for(var word:source.stream().sorted(Comparator.comparingDouble(WindowsOcr.Word::left)).toList()) {
            var previous=result.isEmpty()?null:result.get(result.size()-1);
            if(previous!=null && previous.text().matches("[0-9,./-]+")&&word.text().matches("[0-9,./-]+")
                    && word.left()-previous.right()<=Math.max(2,(word.bottom()-word.top())*.9)) {
                result.set(result.size()-1,new WindowsOcr.Word(previous.text()+word.text(),previous.left(),Math.min(previous.top(),word.top()),word.right(),Math.max(previous.bottom(),word.bottom())));
            }else result.add(word);
        }
        return result;
    }
    private static List<TaxInvoice.Item> delimitedItems(List<String> lines) {
        var result=new ArrayList<TaxInvoice.Item>();String[] headers=null;
        for(String line:lines) {
            if(!line.contains("|"))continue;
            String[] cells=line.split("\\|",-1);
            if(compact(line).contains("품목")&&compact(line).contains("단가")){headers=Arrays.stream(cells).map(TaxInvoiceParser::compact).toArray(String[]::new);continue;}
            if(headers==null||cells.length!=headers.length)continue;
            var values=new HashMap<String,String>();for(int i=0;i<headers.length;i++)values.put(headers[i],cells[i].trim());
            String name=values.getOrDefault("품목","");if(name.isBlank())continue;
            if(result.size()==100)break;
            result.add(new TaxInvoice.Item("",ReceiptAnalyzer.cut(name,200),ReceiptAnalyzer.cut(values.getOrDefault("규격",""),100),decimal(values.get("수량")),decimal(values.get("단가")),money(values.get("공급가액")),money(values.get("세액"))));
        }
        return result;
    }
}
