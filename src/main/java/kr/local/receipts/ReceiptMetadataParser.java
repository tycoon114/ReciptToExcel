package kr.local.receipts;

import java.text.Normalizer;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;

/** 거래처 식별정보와 거래 시각을 추출합니다. 확인되지 않은 숫자나 주소를 추측해서 만들지 않습니다. */
final class ReceiptMetadataParser {
    record Metadata(String businessNumber, String receiptTime, String vendorAddress, String warning) {}
    private static final Pattern BUSINESS_LABEL = Pattern.compile("사업자(?:등록)?(?:번호|NO)?|등록번호|사업자등록증번호|BIZ(?:NO)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATED_NUMBER = Pattern.compile(
            "(?<![0-9])([0-9]{3})(?:\\h*[-–—~·.:]\\h*|\\h+)([0-9]{2})(?:\\h*[-–—~·.:]\\h*|\\h+)([0-9]{5})(?![0-9])");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("(?<![0-9])([0-9]{10})(?![0-9])");
    private static final Pattern TIME = Pattern.compile(
            "(?<![0-9:])(?:(오전|오후|AM|PM)\\h*)?([0-9]{1,2})\\h*:\\h*([0-9]{2})(?:\\h*:\\h*([0-9]{2}))?(?:\\h*(AM|PM))?(?![0-9:])", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGION = Pattern.compile(
            "(?:서울(?:특별시)?|부산(?:광역시)?|대구(?:광역시)?|인천(?:광역시)?|광주(?:광역시)?|대전(?:광역시)?|울산(?:광역시)?|세종(?:특별자치시)?|경기(?:도)?|강원(?:특별자치도|도)?|충청[남북]도|충[남북]|전[라북남]+(?:특별자치도|도)?|경상[남북]도|경[남북]|제주(?:특별자치도|도)?)\\h+");
    private static final Pattern ADDRESS_STOP = Pattern.compile(
            "(?:TEL|FAX|전화|팩스|사업자|대표자?|등록번호|승인번호|거래일|결제일|합계|공급가|부가세|카드번호)\\h*[:：]?", Pattern.CASE_INSENSITIVE);

    static Metadata parse(String original) {
        String text = Normalizer.normalize(Objects.toString(original, ""), Normalizer.Form.NFKC);
        String[] lines = text.replace('\u00a0', ' ').split("\\R");
        Set<String> labelled = new LinkedHashSet<>(), unlabelled = new LinkedHashSet<>();
        boolean invalid = false;
        for (int i = 0; i < lines.length; i++) {
            String compact = lines[i].replaceAll("\\h", "");
            // 고객 회사·결제대행사 번호를 판매점 번호로 자동 선택하지 않습니다.
            if (compact.matches("(?i).*(?:공급받는|구매자|고객사업자|본사사업자|결제대행|PG사).*") ) continue;
            boolean label = BUSINESS_LABEL.matcher(compact).find();
            String line = lines[i];
            if (label && i + 1 < lines.length && lines[i + 1].matches("[\\h0-9–—~·.:\\-]+"))
                line += " " + lines[i + 1];
            var matcher = SEPARATED_NUMBER.matcher(line);
            while (matcher.find()) {
                String number = matcher.group(1) + matcher.group(2) + matcher.group(3);
                if (validBusinessNumber(number)) (label ? labelled : unlabelled).add(formatBusinessNumber(number));
                else if (label) invalid = true;
            }
            // 구분자 없는 열 자리는 사업자 표기가 있는 행에서만 허용해 승인번호 등과 혼동하지 않습니다.
            if (label) {
                matcher = PLAIN_NUMBER.matcher(line);
                while (matcher.find()) {
                    if (validBusinessNumber(matcher.group(1))) labelled.add(formatBusinessNumber(matcher.group(1)));
                    else invalid = true;
                }
            }
        }
        Set<String> numbers = labelled.isEmpty() ? unlabelled : labelled;
        String warning = numbers.size() > 1 ? "사업자번호 후보가 여러 개여서 원본 확인이 필요합니다."
                : numbers.isEmpty() && invalid ? "사업자번호 검증번호가 맞지 않아 비웠습니다. 원본을 확인해 주세요." : "";
        return new Metadata(single(numbers), time(lines), address(lines), warning);
    }

    /** 검증번호는 OCR 오류 점검용이며 실제 등록·일반/간이 여부를 확인하는 국세청 조회를 대신하지 않습니다. */
    static boolean validBusinessNumber(String value) {
        String digits = Objects.toString(value, "").replace("-", "");
        if (!digits.matches("[0-9]{10}") || digits.matches("([0-9])\\1{9}")) return false;
        int[] weights = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (digits.charAt(i) - '0') * weights[i];
        sum += (digits.charAt(8) - '0') * 5 / 10;
        return (10 - sum % 10) % 10 == digits.charAt(9) - '0';
    }

    static String formatBusinessNumber(String value) {
        String digits = Objects.toString(value, "").replace("-", "");
        return digits.isEmpty() ? "" : digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
    }

    /** 초가 적혀 있지 않은 시각에 임의의 초를 추가하지 않습니다. 잘못된 시각은 빈칸으로 남깁니다. */
    static String normalizedTime(String value) {
        String time = Objects.toString(value, "");
        if (!time.matches("[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")) return "";
        try { LocalTime.parse(time); return time; } catch (Exception ignored) { return ""; }
    }

    private static String time(String[] lines) {
        Map<Integer, Set<String>> candidates = new TreeMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i], compact = line.replaceAll("\\h", "");
            if (compact.matches("(?i).*(?:영업시간|운영시간|상담시간|고객센터|OPEN|CLOSE|출력|인쇄|발행|재발행).*") ) continue;
            int priority = compact.matches(".*(?:거래|결제|승인|판매|매출)(?:일시|시간|시각|일자).*" ) ? 3
                    : line.matches(".*20[0-9]{2}[./년 -].*") ? 2 : 1;
            if (priority == 3 && i + 1 < lines.length && line.strip().matches(".*(?:일시|시간|시각)\\h*[:：]?"))
                line += " " + lines[i + 1];
            var matcher = TIME.matcher(line);
            while (matcher.find()) {
                int hour = Integer.parseInt(matcher.group(2));
                String marker = matcher.group(1) != null ? matcher.group(1) : matcher.group(5);
                if (marker != null) {
                    if (hour < 1 || hour > 12) continue;
                    hour = hour % 12 + (marker.equalsIgnoreCase("PM") || marker.equals("오후") ? 12 : 0);
                }
                String value = String.format(Locale.ROOT, "%02d:%s", hour, matcher.group(3))
                        + (matcher.group(4) == null ? "" : ":" + matcher.group(4));
                value = normalizedTime(value);
                if (!value.isBlank()) candidates.computeIfAbsent(priority, key -> new LinkedHashSet<>()).add(value);
            }
            // 123456 형태는 거래 시각 표기 뒤에 있을 때만 허용합니다. 금액·승인번호를 시간으로 읽지 않습니다.
            var compactTime = Pattern.compile("(?:거래|결제|승인|판매)(?:일시|시간|시각)\\h*[:：]?\\h*([0-9]{2})([0-9]{2})([0-9]{2})(?![0-9])").matcher(line);
            while (compactTime.find()) {
                String value = normalizedTime(compactTime.group(1) + ":" + compactTime.group(2) + ":" + compactTime.group(3));
                if (!value.isBlank()) candidates.computeIfAbsent(3, key -> new LinkedHashSet<>()).add(value);
            }
        }
        return candidates.isEmpty() ? "" : single(candidates.get(Collections.max(candidates.keySet())));
    }

    private static String address(String[] lines) {
        Set<String> labelled = new LinkedHashSet<>(), fallback = new LinkedHashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.replaceAll("\\h", "").matches(".*(?:고객주소|배송|배달|수령|청구지|본사주소).*")) continue;
            var label = Pattern.compile("(?:주\\h*소|소\\h*재\\h*지|ADDRESS)\\h*[:：]?\\h*", Pattern.CASE_INSENSITIVE).matcher(line);
            boolean explicit = label.find();
            if (explicit) line = line.substring(label.end()).strip();
            else {
                var region = REGION.matcher(line);
                if (!region.find()) continue;
                line = line.substring(region.start());
            }
            if (line.isEmpty() && i + 1 < lines.length) line = lines[i + 1].strip();
            line = trimAddress(line);
            if (i + 1 < lines.length && !line.isEmpty()) {
                String next = lines[i + 1].strip();
                // 도로명과 건물번호가 줄바꿈된 경우만 연결합니다. 전화·금액·상품 행은 붙이지 않습니다.
                boolean continuation = !line.matches(".*[0-9].*")
                        ? next.matches("(?:[0-9]{1,5}(?:-[0-9]{1,5})?(?:번지)?(?:\\h+.*)?|.*(?:로|길)\\h*[0-9가-힣()-]*)")
                        : next.matches("(?:[가-힣A-Za-z]+빌딩\\h*)?(?:지하\\h*)?[0-9]{1,4}(?:동|층|호)(?:\\h+.*)?");
                if (continuation && !ADDRESS_STOP.matcher(next).find() && !line.equals(next)) line += " " + next;
            }
            if (line.length() >= 5 && line.length() <= 300 && line.matches(".*[가-힣].*")
                    && line.matches(".*[0-9].*") && (explicit || line.matches(".*(?:로|길|동|읍|면|리)\\h*.*")))
                (explicit ? labelled : fallback).add(line.replaceAll("\\h+", " ").strip());
        }
        return single(labelled.isEmpty() ? fallback : labelled);
    }

    private static String trimAddress(String line) {
        var stop = ADDRESS_STOP.matcher(line);
        return (stop.find() ? line.substring(0, stop.start()) : line).replaceFirst("[\\h,;/|]+$", "").strip();
    }

    private static String single(Set<String> values) { return values.size() == 1 ? values.iterator().next() : ""; }
}
