package kr.local.receipts;

import java.time.LocalDate;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Extracts labeled monetary values, keeping quantities, list prices and points separate. */
final class ReceiptTextParser {
    private static final Pattern NUMBERS =
            Pattern.compile(
                    "(?<![0-9A-Za-z,.])(?:[0-9]{1,3}(?:\\h*,\\h*[0-9]{3})+|[0-9]+)(?![0-9A-Za-z,.])");
    private static final String PAYMENT =
            "(?:실\\s*결\\s*제\\s*금\\s*액|결\\s*[제재]\\s*(?:대\\s*상\\s*)?금\\s*액|(?:카\\s*드|가\\s*드|신\\s*용\\s*카\\s*드)\\s*결\\s*[제재세지](?:[lI|])?|(?:카\\s*드|가\\s*드)\\s*매\\s*출|승\\s*인\\s*금\\s*액|총\\s*결\\s*제\\s*금\\s*액)";
    private static final String TOTAL =
            "(?:합\\s*계(?:\\s*[금급]\\s*액)?|총\\s*금\\s*액|받을\\s*금\\s*액|청\\s*구\\s*금\\s*액)";
    private static final String SUPPLY =
            "(?:공\\s*급\\s*가(?:\\s*액)?|과\\s*세\\s*(?:금\\s*액|물\\s*품)|부\\s*가\\s*세\\s*과\\s*세\\s*물\\s*품\\s*[가기]\\s*액|판\\s*매\\s*금\\s*액)";
    private static final String VAT = "(?:부\\s*가\\s*세|부\\s*가\\s*가\\s*치\\s*세)";

    private record Candidate(long value, int priority) {}

    private record Total(Long amount, String warning) {}

    static ReceiptAnalyzer.Analysis parse(String original, String purpose) {
        String text = Normalizer.normalize(Objects.toString(original, ""), Normalizer.Form.NFKC).replace('\u00a0', ' '),
                hint = Objects.toString(purpose, "");
        String vendor = merchant(text), date = null;
        var dates =
                Pattern.compile("(?<!\\d)(20\\d{2})[./년\\- ]*([01]\\d)[./월\\- ]*([0-3]\\d)(?!\\d)|(20\\d{2})[./년\\- ]+([01]?\\d)[./월\\- ]+([0-3]?\\d)")
                        .matcher(text);
        while (dates.find() && date == null)
            try {
                int offset = dates.group(1) == null ? 3 : 0;
                date =
                        LocalDate.of(
                                        Integer.parseInt(dates.group(1 + offset)),
                                        Integer.parseInt(dates.group(2 + offset)),
                                        Integer.parseInt(dates.group(3 + offset)))
                                .toString();
            } catch (Exception ignored) {
            }
        Total total = total(text);
        Long supply = tax(text, SUPPLY), vat = tax(text, VAT);
        String category = ReceiptAnalyzer.classify(text + " " + hint);
        String compact = text.replaceAll("\\s", "");
        String payment = compact.contains("카드") ? "카드" : compact.contains("현금") ? "현금" : "미확인";
        String description = hint.isBlank() && category.equals("도서인쇄비") ? "도서 구입" : hint;
        return new ReceiptAnalyzer.Analysis(
                date,
                vendor,
                category,
                description,
                total.amount(),
                supply,
                vat,
                payment,
                description,
                "OCR_RULES",
                "규칙 기반 제안입니다. 업무 목적을 확인해 주세요. " + total.warning(),
                text);
    }

    static int score(ReceiptAnalyzer.Analysis a) {
        int score =
                (a.date() != null ? 4 : 0)
                        + (!a.vendor().isBlank() ? 5 : 0)
                        + (a.amount() != null ? 9 : 0);
        if (a.message().contains("반복 금액")) score -= 2;
        if (a.supply() != null) score++;
        if (a.vat() != null) score++;
        if (a.amount() != null
                && a.supply() != null
                && a.vat() != null
                && a.supply() + a.vat() == a.amount()) score += 3;
        if (!a.category().equals("미분류")) score++;
        return score;
    }

    static boolean needsAmountRecovery(String line) {
        return Pattern.compile(PAYMENT + "|" + TOTAL).matcher(line).find()
                && !line.replaceAll("\\s", "").matches(".*(?:과세|면세|부가세|포인트|할인|쿠폰|적립).*" )
                && total(line).amount() == null;
    }

    static String recoveredAmount(String line, long amount) {
        var label = Pattern.compile(PAYMENT + "|" + TOTAL).matcher(line);
        return label.find() ? line.substring(0, label.end()) + " : " + amount : line;
    }

    private static Total total(String text) {
        List<Candidate> candidates = new ArrayList<>();
        collect(text, PAYMENT, 100, candidates, false);
        collect(text, "결\\s*[제재세]", 90, candidates, true);
        collect(text, TOTAL, 80, candidates, true);
        collect(text, "(?:신\\s*용\\s*카\\s*드|카\\s*드)", 60, candidates, true);
        if (book(text)) corroborateFadedPayment(text, candidates);
        if (!candidates.isEmpty()) {
            int priority = candidates.stream().mapToInt(Candidate::priority).max().orElse(0);
            Set<Long> top = new LinkedHashSet<>();
            for (var c : candidates) if (c.priority() == priority) top.add(c.value());
            if (top.size() > 1)
                return new Total(null, "동일 우선순위의 결제 금액이 서로 다릅니다. 원본을 확인해 총금액을 입력해 주세요.");
            long amount = top.iterator().next();
            boolean conflict = candidates.stream().anyMatch(c -> c.value() != amount);
            return new Total(
                    amount,
                    (priority == 95 ? "결제 표기 일부가 흐려 권수·합계 행과 대조했습니다. " : "")
                            + (conflict ? "결제 금액과 다른 합계 후보가 있습니다. 원본을 대조해 주세요." : ""));
        }
        // A book receipt may have faded summary labels. Require a unique repeated summary value,
        // never the largest item price, a quantity, ISBN, a points balance or a calculated guess.
        if (book(text)) {
            Map<Long, Integer> counts = new LinkedHashMap<>();
            for (String line : text.lines().toList())
                if (!line.replaceAll("\\s","").matches(".*(?:포인트|적립|정가|할인|쿠폰).*") && (bare(line) || line.matches("^[^0-9]{1,3}\\h+[0-9][0-9,\\h]*원?$"))) {
                    List<Long> amounts = numbers(line);
                    if (amounts.isEmpty()) continue;
                    long amount = amounts.get(amounts.size() - 1);
                    if (amounts.size() <= 2
                            && (amounts.size() == 1 || amounts.get(0) < 100)
                            && amount >= 100
                            && amount <= 999999999999L) counts.merge(amount, 1, Integer::sum);
                }
            var repeated = counts.entrySet().stream().filter(e -> e.getValue() >= 2).toList();
            if (repeated.size() == 1)
                return new Total(
                        repeated.get(0).getKey(), "총액 표기가 흐려 요약 영역의 반복 금액을 제안했습니다. 원본 대조가 필요합니다.");
        }
        return new Total(null, "결제 총액을 확실하게 찾지 못했습니다. 원본을 확인해 주세요.");
    }

    private static void collect(
            String text,
            String label,
            int priority,
            List<Candidate> candidates,
            boolean excludeTax) {
        String[] lines = text.split("\\R");
        Pattern pattern = Pattern.compile(label);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i], compact = line.replaceAll("\\s", "");
            if (compact.matches(".*(?:포인트|쿠폰|할인|적립|정가|상품단가).*")) continue;
            if (excludeTax
                    && (compact.contains("과세")
                            || compact.contains("면세")
                            || compact.contains("부가세"))) continue;
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                String tail =
                                line.substring(matcher.end()).replaceFirst("^[\\h:：₩•·.\\-\\[\\]()*]+", "");
                // A card payment may put IC/installment metadata before the printed amount.
                tail = tail.replaceFirst("^(?i)(?:IC\\)?\\h*)?(?:일시불|할부\\h*\\d+개월)?[\\h/\\]():：]*", "");
                boolean allowNextLine = priority >= 80 && !label.equals(SUPPLY) && !label.equals(VAT);
                if (allowNextLine && tail.isBlank() && i + 1 < lines.length && bare(lines[i + 1]))
                    tail = lines[i + 1].trim();
                // Never harvest a number from a date, card ID, decimal fragment, or OCR debris.
                if (!tail.matches("(?:[0-9]{1,2}\\h+)?(?:[0-9]{1,3}(?:\\h*,\\h*[0-9]{3})+|[0-9]+)\\h*(?:원|KRW)?[\\h\\]\\)]*")) continue;
                List<Long> amounts = numbers(tail);
                if (amounts.isEmpty()) continue;
                long value = amounts.get(amounts.size() - 1);
                if (priority == 80 && amounts.size() == 1 && value < 100 && !tail.contains("원"))
                    continue;
                candidates.add(new Candidate(value, priority));
            }
        }
    }

    private static void corroborateFadedPayment(String text, List<Candidate> candidates) {
        String[] lines = text.split("\\R");
        Pattern label = Pattern.compile(PAYMENT);
        for (int i = 0; i < lines.length; i++) {
            var matcher = label.matcher(lines[i]);
            if (!matcher.find()) continue;
            String tail = lines[i].substring(matcher.end()).replaceFirst("^[\\h:：₩•·.\\-]+", "");
            if (tail.matches("^[0-9].*") || !tail.matches("[^0-9]{1,3}[0-9].*")) continue;
            List<Long> suffixes = numbers(tail);
            if (suffixes.size() != 1) continue;
            String suffix = Long.toString(suffixes.get(0));
            if (suffix.length() < 3) continue;
            for (int next = i + 1; next < Math.min(lines.length, i + 3); next++) {
                if (!bare(lines[next])) continue;
                var summary = numbers(lines[next]);
                if (summary.size() != 2 || summary.get(0) < 1 || summary.get(0) > 99) continue;
                long value = summary.get(1);
                String digits = Long.toString(value);
                if (value > suffixes.get(0)
                        && digits.length() - suffix.length() <= 2
                        && digits.endsWith(suffix)) candidates.add(new Candidate(value, 95));
            }
        }
    }

    private static Long tax(String text, String label) {
        List<Candidate> values = new ArrayList<>();
        collect(text, label, 100, values, false);
        return values.isEmpty() ? null : values.get(values.size() - 1).value();
    }

    private static boolean bare(String line) {
        return line.matches("[\\h0-9,₩원:：.•]+") && line.matches(".*[0-9].*");
    }

    private static List<Long> numbers(String tail) {
        List<Long> values = new ArrayList<>();
        Matcher matcher = NUMBERS.matcher(tail);
        while (matcher.find())
            try {
                long n = Long.parseLong(matcher.group().replaceAll("[,\\h]", ""));
                if (n <= 999999999999L) values.add(n);
            } catch (NumberFormatException ignored) {
            }
        return values;
    }

    static boolean book(String text) {
        String s =
                text.replaceAll("\\([^\\r\\n)]*점\\)?", "")
                        .replaceAll("\\s", "")
                        .toLowerCase(Locale.ROOT);
        if (s.contains("문고")
                || s.contains("도서명")
                || s.contains("isbn")
                || s.contains("도서구입")
                || s.contains("책구매")) return true;
        // Weak 서점 text must belong to the merchant, not a location or a branch elsewhere.
        String vendor = merchant(text).replaceAll("\\([^)]*점\\)", "")
                .replaceFirst("\\h+[가-힣A-Za-z0-9]{2,}점$", "");
        return vendor.contains("서점")
                && !s.matches(".*(?:버거|burger|와퍼|갈비|제육|보쌈|정식|제면소|수세미|종이접시).*" );
    }

    private static String merchant(String text) {
        Pattern named =
                Pattern.compile(
                        "(?:[\\[「【]\\h*(?:매\\h*장\\h*명|가\\h*맹\\h*점\\h*명)\\h*[\\]」】]|(?:상\\h*호(?:\\h*명)?|매\\h*장\\h*명)\\h*[:：•])[\\h]*(.+)");
        for (String line : text.lines().toList()) {
            var matcher = named.matcher(line);
            if (matcher.find()) {
                String name = cleanMerchant(matcher.group(1));
                if (plausible(name)) return name;
            }
        }
        List<String> lines = text.lines().limit(12).toList();
        String best = "", branch = "";
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            String cleaned = cleanMerchant(lines.get(i));
            if (!plausible(cleaned)) continue;
            if (cleaned.matches(".*[가-힣].*\\d$")) continue;
            if (cleaned.matches("[가-힣A-Za-z0-9]{1,12}점")
                    && !cleaned.matches(".*(?:문고|식당|카페)")
                    && (!cleaned.endsWith("서점") || !best.isBlank())) {
                if (branch.isBlank()) branch = cleaned;
                continue;
            }
            // Short location names can contain 서점, but are not a merchant identity on their own.
            if (cleaned.matches("[가-힣]서점")) {
                if (branch.isBlank()) branch = cleaned;
                continue;
            }
            int score = 30 - i * 3;
            if (cleaned.matches(".*(?:문\\s*고|서\\s*점|식당|카페|제면소|렌탈샵).*")) score += 10;
            if (cleaned.matches("[A-Z][A-Z &'-]{4,}")) score += 8;
            if (score > bestScore) { best = cleaned; bestScore = score; }
        }
        if (!best.isBlank() && !branch.isBlank() && !best.contains(branch)) best += "(" + branch + ")";
        return best;
    }

    private static String cleanMerchant(String text) {
        return ReceiptAnalyzer.cut(
                text.split("(?i)(?:대표(?:이사)?\\h*[:：•]|사업자|\\bTEL\\b|NO[:：]|\\(?0\\d{1,2}\\)?[\\h.·•-]*\\d{3,4}[\\h.·•-]\\d{4})", 2)[0]
                        .replaceAll("^[\\h\\[\\]★☆*•:：]+|[\\h★☆*]+$", "")
                        .replaceAll("\\h+", " ")
                        .replaceFirst("^([A-Z][A-Z &'-]{4,})\\h+[가-힣][\\h•:：]*$", "$1")
                        .trim(),
                200);
    }

    private static boolean plausible(String name) {
        if (name.length() < 2
                || name.length() > 80
                || name.replaceAll("\\s", "")
                        .matches(
                                ".*(?:주소|전화|사업자|등록번호|매출일|거래일|카운터|출력|영수증|교환|환불|대표|고객용|KSNET|KSnET|승인|포상금|국세|정부|공급가액|부가세|합계|단가|수량|금액|고객센터|주문|결제|판매시간|테이블|설문|담당자|일시불|매입사|https?).*"))
            return false;
        if (name.matches(".*\\d{3}-\\d{2}-\\d{5}.*")
                || name.matches(".*[0-9].*[,].*")
                || name.matches(".*(?:지상|지하).*\\d.*")
                || name.matches(".*\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}.*")
                || name.matches(".*(?:특별시|광역시|[가-힣]+구\\h+[가-힣]+로).*")) return false;
        long letters =
                name.codePoints()
                        .filter(
                                c ->
                                        Character.UnicodeBlock.of(c)
                                                        == Character.UnicodeBlock.HANGUL_SYLLABLES
                                                || c >= 'A' && c <= 'Z'
                                                || c >= 'a' && c <= 'z')
                        .count();
        long invalid =
                name.codePoints()
                        .filter(
                                c ->
                                        !(Character.UnicodeBlock.of(c)
                                                        == Character.UnicodeBlock.HANGUL_SYLLABLES
                                                || c < 128
                                                || Character.isWhitespace(c)))
                        .count();
        boolean mixedKoreanName = name.matches("[가-힣][가-힣\\p{IsHan}]{2,}") && letters >= 3;
        return letters >= 2
                && (invalid == 0 || mixedKoreanName)
                && name.chars().filter(c -> "~;=\\'\"<>".indexOf(c) >= 0).count() < 2;
    }
}
