package kr.local.receipts;

import java.time.LocalDate;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/**
 * OCR 텍스트에서 거래처·날짜·금액·결제수단을 추출하는 규칙 모음입니다.
 * 권수·단가·포인트를 총액과 구분하며, 근거가 충돌하거나 부족하면 빈 금액과 검토 안내를 반환합니다.
 */
final class ReceiptTextParser {
    // 앞뒤의 숫자·쉼표·영문과 이어진 일부 조각을 독립된 금액으로 잘라 읽지 않도록 경계를 둡니다.
    private static final Pattern NUMBERS =
            Pattern.compile(
                    "(?<![0-9A-Za-z,.])(?:[0-9]{1,3}(?:\\h*,\\h*[0-9]{3})+|[0-9]+)(?![0-9A-Za-z,.])");
    // 글자 사이 공백과 자주 생기는 OCR 오독을 허용합니다. 결제·합계·세액 표기는 서로 분리합니다.
    private static final String PAYMENT =
            "(?:실\\s*결\\s*제\\s*금\\s*액|결\\s*[제재]\\s*(?:대\\s*상\\s*)?금\\s*액|(?:카\\s*드|가\\s*드|신\\s*용\\s*카\\s*드)\\s*결\\s*[제재세지](?:[lI|])?|(?:카\\s*드|가\\s*드)\\s*매\\s*출|승\\s*인\\s*금\\s*액|총\\s*결\\s*제\\s*금\\s*액)";
    private static final String TOTAL =
            "(?:합\\s*계(?:\\s*[금급]\\s*액)?|총\\s*금\\s*액|받을\\s*금\\s*액|청\\s*구\\s*금\\s*액)";
    private static final String SUPPLY =
            "(?:공\\s*급\\s*가(?:\\s*액)?|과\\s*세\\s*(?:금\\s*액|물\\s*품)|부\\s*가\\s*세\\s*과\\s*세\\s*물\\s*품\\s*[가기]\\s*액|판\\s*매\\s*금\\s*액)";
    private static final String VAT = "(?:부\\s*가\\s*세|부\\s*가\\s*가\\s*치\\s*세)";

    // 우선순위는 표기의 명확성입니다. 실제 결제액 100 > 짧은 결제 표기 90 > 합계 80 > 카드 표기 60입니다.
    // 95는 흐린 결제 표기를 독립된 권수·합계 행과 대조해 보완한 후보에 사용합니다.
    private record Candidate(long value, int priority) {}

    private record Total(Long amount, String warning) {}

    /** 전각 문자와 공백을 정규화한 뒤 각 필드를 추출하여 기본 모드의 분석 제안을 만듭니다. */
    static ReceiptAnalyzer.Analysis parse(String original, String purpose) {
        String text = Normalizer.normalize(Objects.toString(original, ""), Normalizer.Form.NFKC).replace('\u00a0', ' '),
                hint = Objects.toString(purpose, "");
        String vendor = merchant(text), date = null;
        // 구분자 없는 날짜와 일반 날짜를 모두 찾되 LocalDate로 실제 달력에 존재하는 날짜인지 확인합니다.
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
        var approval = ApprovalNumberParser.parse(text);
        Long supply = tax(text, SUPPLY), vat = tax(text, VAT);
        // 기울어진 스캔에서 세액 표기와 옆 행의 총액이 잘못 붙을 수 있습니다. 합계와 모순된 세액은 계산해 채우지 않습니다.
        boolean inconsistentTax = total.amount() != null
                && (supply != null && supply > total.amount()
                        || vat != null && vat > 0 && vat >= total.amount()
                        || supply != null && vat != null && supply + vat != total.amount());
        if (inconsistentTax) { supply = null; vat = null; }
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
                "규칙 기반 제안입니다. 업무 목적을 확인해 주세요. " + total.warning() + " " + approval.warning()
                        + (inconsistentTax ? " 공급가액·부가세가 총액과 맞지 않아 세액을 비웠습니다. 원본을 확인해 주세요." : ""),
                text,
                approval.value());
    }

    /** 전처리 후보 비교용 점수입니다. 확률이나 정확도 백분율이 아니며 금액·날짜·세액 근거를 가산합니다. */
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

    /** 합계·결제 표기는 있으나 총액을 읽지 못한 행만 숫자 영역 재인식 대상으로 고릅니다. */
    static boolean needsAmountRecovery(String line) {
        return Pattern.compile(PAYMENT + "|" + TOTAL).matcher(line).find()
                && !line.replaceAll("\\s", "").matches(".*(?:과세|면세|부가세|포인트|할인|쿠폰|적립).*" )
                && total(line).amount() == null;
    }

    /** 금액 부분 재인식 결과를 해당 행의 표기와 연결합니다. 다른 행의 금액은 건드리지 않습니다. */
    static String recoveredAmount(String line, long amount) {
        var label = Pattern.compile(PAYMENT + "|" + TOTAL).matcher(line);
        return label.find() ? line.substring(0, label.end()) + " : " + amount : line;
    }

    /** 실결제·승인 금액을 합계보다 우선합니다. 같은 우선순위의 금액이 다르면 하나를 임의 선택하지 않습니다. */
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
        // 도서 영수증의 흐린 합계는 요약 영역에서 유일하게 반복되는 값일 때만 제안합니다.
        // 가장 큰 상품 가격·권수·ISBN·포인트를 대신 사용하거나 임의 계산하지 않습니다.
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

    /** 표기 뒤의 금액을 수집합니다. 허용한 수량·금액 형식만 받아 카드 식별 정보가 섞이는 것을 막습니다. */
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
                // 카드 결제 행은 금액 앞에 IC·일시불·할부 정보가 올 수 있어 정해진 형식만 제거합니다.
                tail = tail.replaceFirst("^(?i)(?:IC\\)?\\h*)?(?:일시불|할부\\h*\\d+개월)?[\\h/\\]():：]*", "");
                boolean allowNextLine = priority >= 80 && !label.equals(SUPPLY) && !label.equals(VAT);
                if (allowNextLine && tail.isBlank() && i + 1 < lines.length && bare(lines[i + 1]))
                    tail = lines[i + 1].trim();
                // 날짜·카드 식별값·소수점 조각·깨진 OCR 문자 속 숫자를 금액으로 잘라 가져오지 않습니다.
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

    /** 흐린 도서 결제액의 뒷자리가 인접한 권수·합계와 맞을 때만 독립된 근거로 보완합니다. */
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

    /** 영수증에 명시된 세액만 추출합니다. 총액에서 나누거나 차감해 세액을 계산하지 않습니다. */
    private static Long tax(String text, String label) {
        List<Candidate> values = new ArrayList<>();
        collect(text, label, 100, values, false);
        return values.isEmpty() ? null : values.get(values.size() - 1).value();
    }

    /** 바로 다음 줄에서 금액을 찾을 때 허용할 숫자·통화 기호 중심의 단순 행인지 확인합니다. */
    private static boolean bare(String line) {
        return line.matches("[\\h0-9,₩원:：.•]+") && line.matches(".*[0-9].*");
    }

    /** 허용 범위의 원 단위 정수를 순서대로 추출합니다. 쉼표는 제거하되 서로 다른 숫자를 합치지 않습니다. */
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

    /** 문고·도서명·ISBN 등 근거를 확인하고, 약한 서점 표기는 상호와 실제 품목 문맥을 함께 검사합니다. */
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
        // 약한 서점 표기는 주소나 별도 지점명이 아니라 실제 상호에 포함되는지 확인합니다.
        String vendor = merchant(text).replaceAll("\\([^)]*점\\)", "")
                .replaceFirst("\\h+[가-힣A-Za-z0-9]{2,}점$", "");
        return vendor.contains("서점")
                && !s.matches(".*(?:버거|burger|와퍼|갈비|제육|보쌈|정식|제면소|수세미|종이접시).*" );
    }

    /** 명시된 상호 표기를 우선하고, 없으면 상단 후보의 위치·형태를 비교해 별도 지점명을 연결합니다. */
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
                    && !cleaned.matches(".*(?:문고|식당|카페|상점|음식점|편의점|매점|대리점|판매점)")
                    && (!cleaned.endsWith("서점") || !best.isBlank())) {
                if (branch.isBlank()) branch = cleaned;
                continue;
            }
            // 수서점처럼 짧은 지점명만으로는 상호를 확정하지 않고 상단 브랜드와 연결할 후보로 둡니다.
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

    /** 상호 뒤의 대표자·사업자·전화 정보와 장식 문자를 잘라 거래처 이름 후보를 정리합니다. */
    private static String cleanMerchant(String text) {
        // 상호 / 사업자번호 / 대표자 형식에서는 번호 앞까지만 상호로 사용합니다. 상호 없이 번호부터 시작하면 빈칸입니다.
        text = text.split("(?<![0-9])[0-9]{3}[\\h-]+[0-9]{2}[\\h-]+[0-9]{5}(?![0-9])", 2)[0]
                .replaceFirst("[\\h/|]+$", "");
        // 큰 영문 브랜드 옆 QR 코드가 한자·기호로 붙은 경우, 읽힌 영문 브랜드 부분을 보존합니다.
        var brand = Pattern.compile("^([A-Z][A-Z &'-]{4,})\\h+.*[\\p{IsHan}].*$").matcher(text);
        if (brand.matches()) text = brand.group(1);
        return ReceiptAnalyzer.cut(
                text.split("(?i)(?:대표(?:이사)?\\h*[:：•]|사업자|\\bTEL\\b|NO[:：]|\\(?0\\d{1,2}\\)?[\\h.·•-]*\\d{3,4}[\\h.·•-]\\d{4})", 2)[0]
                        .replaceAll("^[\\h\\[\\]★☆*•:：]+|[\\h★☆*]+$", "")
                        .replaceAll("\\h+", " ")
                        .replaceFirst("^([A-Z][A-Z &'-]{4,})\\h+[가-힣][\\h•:：]*$", "$1")
                        .trim(),
                200);
    }

    /** 영수증 제목·주소·거래 정보·심한 OCR 잡음을 상호 후보에서 제외하는 보조 판정입니다. */
    private static boolean plausible(String name) {
        if (name.length() < 2
                || name.length() > 80
                || name.replaceAll("\\h", "").matches("(?:매장|상호|상호명|상품명|품명|단가|수량)[:：]?")
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
