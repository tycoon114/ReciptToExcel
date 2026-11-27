package kr.local.receipts;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** 열 제목과 PDF 글자 좌표로 읽습니다. 지원하지 않는 배치는 페이지 단위로 안내합니다. */
final class ImportPdfParser {
    record Parsed(List<ImportAnalysis.Transaction> rows, List<ImportAnalysis.Issue> issues) {}
    private record Column(String key, double left) {}
    private static final Pattern DATE = Pattern.compile("(?<![0-9])([0-9]{4})[-./년]\\s*([0-9]{1,2})[-./월]\\s*([0-9]{1,2})(?:일)?(?![0-9])");
    private static final Pattern BUSINESS = Pattern.compile("[0-9]{3}-[0-9]{2}-[0-9]{5}|(?<![0-9])[0-9]{10}(?![0-9])");
    private static final Map<String, List<String>> HEADERS = Map.ofEntries(
            Map.entry("date", List.of("사용일자/시각", "사용일자", "이용일자", "승인일자", "거래일자", "거래일시")),
            Map.entry("time", List.of("승인시간", "거래시간", "거래시각")),
            Map.entry("party", List.of("가맹점/사업자번호", "이용가맹점명", "가맹점명", "거래상대방", "거래처명")),
            Map.entry("business", List.of("가맹점사업자등록번호", "가맹점사업자번호", "사업자등록번호", "사업자번호")),
            Map.entry("amount", List.of("이용금액", "승인금액", "합계금액", "거래금액")),
            Map.entry("supply", List.of("공급가액")), Map.entry("vat", List.of("부가가치세", "부가세", "세액")),
            Map.entry("deposit", List.of("입금액", "입금금액")), Map.entry("withdrawal", List.of("출금액", "출금금액")),
            Map.entry("balance", List.of("거래후잔액", "잔액")), Map.entry("type", List.of("이용구분", "거래구분", "구분")),
            Map.entry("reference", List.of("승인번호")), Map.entry("original", List.of("원승인번호", "원거래승인번호")),
            Map.entry("description", List.of("적요", "기재내용", "거래기록사항")), Map.entry("currency", List.of("통화")));

    static Parsed parse(ImportFiles.Kind kind, PdfPageText.Page page, int number) {
        return parse(kind, page, number, "");
    }

    static Parsed parse(ImportFiles.Kind kind, PdfPageText.Page page, int number, String direction) {
        if (kind == ImportFiles.Kind.TAX_INVOICE) return invoice(page, number, direction);
        var rows = new ArrayList<ImportAnalysis.Transaction>();
        var issues = new ArrayList<ImportAnalysis.Issue>();
        List<Column> columns = List.of();
        boolean recognized = false;
        int line = 0;
        for (var row : page.rows()) {
            line++;
            var candidate = columns(row);
            var keys = candidate.stream().map(Column::key).toList();
            boolean header = keys.contains("date") && (kind == ImportFiles.Kind.CARD_PDF
                    ? keys.contains("amount") : keys.contains("deposit") && keys.contains("withdrawal"));
            if (header) { columns = candidate; recognized = true; continue; }
            if (columns.isEmpty()) continue;
            if (compact(text(row.words())).matches("(?:합계|입금합계|출금합계|기말잔액|가상샘플).*")) continue;
            var cells = cells(row, columns);
            String rawDate = cells.getOrDefault("date", "");
            if (!rawDate.matches(".*[0-9].*")) continue;
            String date = date(rawDate);
            if (date.isEmpty()) {
                issues.add(new ImportAnalysis.Issue(number, line + "행 거래일을 읽지 못했습니다. 원본을 확인해 주세요."));
                continue;
            }
            var transaction = tableRow(kind, cells, number, line, currency(page.text()));
            rows.add(transaction);
            if (!transaction.warning().isEmpty()) issues.add(new ImportAnalysis.Issue(number, line + "행 " + transaction.warning()));
        }
        if (!recognized) issues.add(new ImportAnalysis.Issue(number, "거래일·금액 열 제목을 찾지 못했습니다. 이 PDF의 표 배치 확인이 필요합니다."));
        else if (rows.isEmpty()) issues.add(new ImportAnalysis.Issue(number, "열 제목은 확인했으나 거래 행을 추출하지 못했습니다."));
        return new Parsed(List.copyOf(rows), List.copyOf(issues));
    }


    static ImportAnalysis.Transaction tableRow(ImportFiles.Kind kind, Map<String, String> cells, int number, int line, String defaultCurrency) {
        String rawDate = cells.getOrDefault("date", "");
        String date = date(rawDate);
        var warnings = new ArrayList<String>();
        if (date.isEmpty()) warnings.add("거래일 미확인");
        Long amount = money(cells.get("amount")), supply = money(cells.get("supply")), vat = money(cells.get("vat"));
        Long deposit = money(cells.get("deposit")), withdrawal = money(cells.get("withdrawal")), balance = money(cells.get("balance"));
        if (kind == ImportFiles.Kind.CARD_PDF && amount == null) warnings.add("이용금액 미확인");
        if (kind == ImportFiles.Kind.ACCOUNT_PDF && deposit == null && withdrawal == null) warnings.add("입출금액 미확인");
        for (String key : List.of("amount", "supply", "vat", "deposit", "withdrawal", "balance")) {
            String value = cells.getOrDefault(key, "");
            if (!value.isBlank() && money(value) == null && !value.equals("-")) warnings.add(key + " 금액 형식 확인 필요");
        }
        String type = cells.getOrDefault("type", "");
        if (kind == ImportFiles.Kind.CARD_PDF && type.contains("취소")) {
            amount = negative(amount); supply = negative(supply); vat = negative(vat);
        }
        String party = cells.getOrDefault("party", "");
        String business = findBusiness(cells.getOrDefault("business", ""));
        if (business.isEmpty()) business = findBusiness(party);
        party = BUSINESS.matcher(party).replaceAll("").strip();
        String time = cells.getOrDefault("time", "");
        var clock = Pattern.compile("[0-9]{2}:[0-9]{2}(?::[0-9]{2})?").matcher(rawDate + " " + time);
        time = clock.find() ? clock.group() : "";
        String currency = cells.getOrDefault("currency", "");
        if (currency.isBlank()) currency = defaultCurrency;
        if (currency.isBlank()) warnings.add("통화 미확인 · 월별 원화 합계에서 제외");
        if (amount != null && supply != null && vat != null && supply + vat != amount) warnings.add("공급가액·부가세·총액 불일치");
        String warning = String.join(" / ", warnings);
        return new ImportAnalysis.Transaction(number, line, date, time, party, business, type,
                cells.getOrDefault("reference", ""), cells.getOrDefault("original", ""),
                amount, supply, vat, deposit, withdrawal, balance, cells.getOrDefault("description", ""), currency, warning);
    }

    private static Parsed invoice(PdfPageText.Page page, int number, String selectedDirection) {
        var fields = new LinkedHashMap<String, String>();
        double split = Double.NaN;
        for (var row : page.rows()) {
            String text = compact(text(row.words()));
            if (text.equals("항목내용")) {
                split = position(row, "내용");
                continue;
            }
            if (Double.isNaN(split)) continue;
            final double boundary = split - 3;
            String label = compact(text(row.words().stream().filter(w -> w.left() < boundary).toList()));
            String value = text(row.words().stream().filter(w -> w.left() >= boundary).toList());
            if (!label.isEmpty()) fields.put(label, value);
        }
        if (fields.isEmpty()) {
            // 기존 정형 세금계산서 파서를 재사용합니다. 매출·매입은 사용자가 명시하기 전까지 미지정입니다.
            var parsed = TaxInvoiceParser.parseForced(page.text(), "", page.rows());
            if (parsed == null || parsed.date() == null || parsed.amount() == null)
                return new Parsed(List.of(), List.of(new ImportAnalysis.Issue(number, "세금계산서 날짜·합계를 읽지 못했습니다. 문서 양식을 확인해 주세요.")));
            boolean purchase = "매입".equals(selectedDirection);
            boolean sale = "매출".equals(selectedDirection);
            var invoice = parsed.taxInvoice();
            String party = purchase ? parsed.vendor() : sale && invoice != null ? invoice.buyerName() : "";
            String business = purchase ? parsed.businessNumber() : sale && invoice != null ? invoice.buyerBusinessNumber() : "";
            var row = new ImportAnalysis.Transaction(number, 1, parsed.date(), "", party, business,
                    purchase || sale ? selectedDirection : "미지정",
                    parsed.taxInvoice() == null ? "" : parsed.taxInvoice().issueNumber(), "", parsed.amount(), parsed.supply(), parsed.vat(),
                    null, null, null, "", "KRW", "매출·매입 및 거래 상대방 확인 필요");
            return new Parsed(List.of(row), List.of(new ImportAnalysis.Issue(number, row.warning())));
        }
        return invoiceFields(fields, number, selectedDirection);
    }

    static Parsed invoiceFields(Map<String, String> fields, int number, String selectedDirection) {
        String direction = Objects.toString(selectedDirection, "");
        if (direction.isBlank()) direction = fields.getOrDefault("구분", "미지정");
        String role = "매입".equals(direction) ? "공급자" : "매출".equals(direction) ? "공급받는자" : "";
        String date = date(fields.getOrDefault("작성일자", ""));
        Long supply = money(fields.get("공급가액")), vat = money(fields.get("부가세")), amount = money(fields.get("합계금액"));
        var warnings = new ArrayList<String>();
        if (role.isEmpty()) warnings.add("매출·매입 구분 미확인");
        if (date.isEmpty() || amount == null) warnings.add("작성일자·합계금액 미확인");
        if (supply == null || vat == null) warnings.add("공급가액·세액 미확인");
        if (amount != null && supply != null && vat != null && supply + vat != amount) warnings.add("공급가액·부가세·총액 불일치");
        String correction = fields.getOrDefault("수정사유", "");
        String original = fields.getOrDefault("원문서번호", "");
        if ("해당없음".equals(compact(original))) original = "";
        String warning = String.join(" / ", warnings);
        var row = new ImportAnalysis.Transaction(number, 1, date, "", fields.getOrDefault(role + "상호", ""),
                findBusiness(fields.getOrDefault(role + "사업자번호", "")), direction,
                fields.getOrDefault("문서번호", ""), original, amount, supply, vat, null, null, null,
                fields.getOrDefault("품목", "") + (correction.isBlank() || "해당없음".equals(compact(correction)) ? "" : " / " + correction), "KRW", warning);
        return new Parsed(List.of(row), warning.isEmpty() ? List.of() : List.of(new ImportAnalysis.Issue(number, warning)));
    }


    static String currency(String context) {
        return Pattern.compile("단위[:：()]*원|\\(원\\)|KRW").matcher(compact(context)).find() ? "KRW" : "";
    }

    static String headerKey(String label, ImportFiles.Kind kind) {
        String normalized = compact(label).replaceAll("[()（）]", "").replaceAll("(?:KRW|원)$", "");
        if (kind == ImportFiles.Kind.TAX_INVOICE) {
            var invoiceHeaders = Map.ofEntries(
                Map.entry("작성일자", "date"), Map.entry("작성일", "date"), Map.entry("작성연월일", "date"),
                Map.entry("공급자상호", "supplierName"), Map.entry("공급자상호법인명", "supplierName"),
                Map.entry("공급자사업자등록번호", "supplierBusiness"), Map.entry("공급자등록번호", "supplierBusiness"),
                Map.entry("공급받는자상호", "buyerName"), Map.entry("공급받는자상호법인명", "buyerName"),
                Map.entry("공급받는자사업자등록번호", "buyerBusiness"), Map.entry("공급받는자등록번호", "buyerBusiness"),
                Map.entry("매출매입구분", "type"), Map.entry("매출·매입", "type"), Map.entry("문서번호", "reference"),
                Map.entry("전자세금계산서승인번호", "reference"), Map.entry("원문서번호", "original"),
                Map.entry("당초승인번호", "original"), Map.entry("수정사유", "correction"), Map.entry("품목", "description"));
            if (invoiceHeaders.containsKey(normalized)) return invoiceHeaders.get(normalized);
        }
        for (var entry : HEADERS.entrySet()) if (entry.getValue().contains(normalized)) return entry.getKey();
        return switch (normalized) {
            case "일자", "거래일", "이용일", "사용일", "매출일자" -> "date";
            case "금액", "총액" -> "amount";
            case "입금", "맡기신금액" -> "deposit";
            case "출금", "찾으신금액" -> "withdrawal";
            case "거래처", "상호", "이용하신곳", "받는분", "보낸분" -> "party";
            case "거래처사업자번호", "거래처사업자등록번호" -> "business";
            case "내용", "비고" -> "description";
            default -> "";
        };
    }

    private static List<Column> columns(WindowsOcr.Row row) {
        String text = compact(text(row.words()));
        var result = new ArrayList<Column>();
        var claimed = new HashSet<Integer>();
        // 긴 표제를 먼저 찾아 '원승인번호' 안의 '승인번호' 등을 별도 열로 오인하지 않습니다.
        var aliases = new ArrayList<Map.Entry<String, String>>();
        HEADERS.forEach((key, values) -> values.forEach(value -> aliases.add(Map.entry(key, value))));
        aliases.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getValue().length()).reversed());
        var keys = new HashSet<String>();
        for (var alias : aliases) {
            int start = text.indexOf(alias.getValue());
            if (start < 0 || keys.contains(alias.getKey())) continue;
            boolean overlaps = false;
            for (int i = start; i < start + alias.getValue().length(); i++) if (claimed.contains(i)) overlaps = true;
            if (overlaps) continue;
            result.add(new Column(alias.getKey(), position(row, alias.getValue())));
            keys.add(alias.getKey());
            for (int i = start; i < start + alias.getValue().length(); i++) claimed.add(i);
        }
        result.sort(Comparator.comparingDouble(Column::left));
        return result;
    }

    private static double position(WindowsOcr.Row row, String needle) {
        int start = compact(text(row.words())).indexOf(needle), offset = 0;
        for (var word : row.words()) {
            int length = compact(word.text()).length();
            if (offset + length > start) return word.left();
            offset += length;
        }
        return Double.NaN;
    }

    private static Map<String, String> cells(WindowsOcr.Row row, List<Column> columns) {
        var result = new HashMap<String, String>();
        for (int i = 0; i < columns.size(); i++) {
            double left = columns.get(i).left() - 3;
            double right = i + 1 < columns.size() ? columns.get(i + 1).left() - 3 : Double.POSITIVE_INFINITY;
            result.put(columns.get(i).key(), text(row.words().stream().filter(w -> w.left() >= left && w.left() < right).toList()));
        }
        return result;
    }

    private static String text(List<WindowsOcr.Word> words) {
        var result = new StringBuilder();
        double right = Double.NaN;
        for (var word : words) {
            if (!Double.isNaN(right) && word.left() - right > 1.5) result.append(' ');
            result.append(word.text()); right = word.right();
        }
        return result.toString().strip();
    }
    private static String compact(String value) { return value.replaceAll("\\s", ""); }
    private static Long negative(Long value) { return value == null ? null : -Math.abs(value); }
    private static String findBusiness(String value) { var match = BUSINESS.matcher(value); return match.find() ? match.group() : ""; }
    private static String date(String value) {
        if (value.matches("[0-9]{8}")) value = value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6);
        var match = DATE.matcher(value);
        if (!match.find()) return "";
        try { return LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))).toString(); }
        catch (Exception e) { return ""; }
    }
    private static Long money(String value) {
        if (value == null) return null;
        String normalized = value.replaceAll("[\\s,원₩]", "");
        if (normalized.matches("\\([0-9]+\\)")) normalized = "-" + normalized.substring(1, normalized.length() - 1);
        if (!normalized.matches("-?[0-9]{1,13}")) return null;
        return Long.valueOf(normalized);
    }
}
