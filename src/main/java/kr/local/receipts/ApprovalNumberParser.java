package kr.local.receipts;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 승인번호 표기 바로 뒤의 값만 읽습니다. 카드번호·거래번호·금액으로 빈 승인번호를 대신하지 않습니다. */
final class ApprovalNumberParser {
    private static final Pattern LABEL = Pattern.compile(
            "승\\h*인\\h*(?:번\\h*호|N\\h*O\\.?|번호)?(?=\\h|[:：#=•·\\]」】)]|$)"
                    + "|(?<![A-Z])(?:APPROVAL|AUTH(?:ORIZATION)?)\\h*(?:NO\\.?|NUMBER|CODE)(?=\\h|[:：#=\\]」】)]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE = Pattern.compile(
            "^[\\h:：#=•·\\]」】)]*([A-Za-z0-9][A-Za-z0-9-]{2,38}[A-Za-z0-9])(?=$|\\h|[,;\\]」】)])");

    record Result(String value, String warning) {}

    static Result parse(String original) {
        String[] lines = Normalizer.normalize(Objects.toString(original, ""), Normalizer.Form.NFKC).split("\\R");
        Set<String> candidates = new LinkedHashSet<>();
        boolean unreadable = false;
        for (int i = 0; i < lines.length; i++) {
            var label = LABEL.matcher(lines[i]);
            while (label.find()) {
                String tail = lines[i].substring(label.end()).trim();
                // 표기만 있는 줄은 바로 다음 한 줄만 확인합니다. 멀리 있는 다른 식별번호는 가져오지 않습니다.
                if (tail.matches("[:：#=•·\\]」】)\\h]*") && i + 1 < lines.length) tail = lines[i + 1].trim();
                var value = VALUE.matcher(tail);
                if (value.find()) {
                    String candidate = value.group(1);
                    String rest = tail.substring(value.end()).trim();
                    // 숫자 두 개가 나란히 있거나 마스킹된 값은 어느 쪽이 승인번호인지 추측하지 않습니다.
                    if (valid(candidate) && !rest.matches("^[0-9*#].*")) candidates.add(candidate);
                    else unreadable = true;
                } else unreadable = true;
            }
        }
        if (candidates.size() > 1) return new Result("", "승인번호 후보가 여러 개입니다. 원본을 확인해 직접 입력해 주세요.");
        if (candidates.size() == 1 && !unreadable) return new Result(candidates.iterator().next(), "");
        return new Result("", unreadable ? "승인번호 표기를 읽었지만 값을 확실하게 확인하지 못했습니다. 없는 경우 비워 두세요." : "");
    }

    /** OCR 오독 문자를 임의 교정하지 않습니다. AI도 숫자형 응답으로 앞자리 0을 잃은 값은 채택하지 않습니다. */
    static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9-]{2,38}[A-Za-z0-9]")
                && value.matches(".*[0-9].*") && !value.matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}");
    }

    /** 부분 OCR는 명시된 승인번호 표기가 있고 값이 불확실한 행에만 적용합니다. 다른 번호는 대상으로 삼지 않습니다. */
    static boolean needsRecovery(String line) {
        return labelEnd(line) >= 0 && parse(line).value().isBlank();
    }

    /** 첫 글자가 빠지거나 비슷하게 읽힌 표기는 추가 OCR의 계기로만 사용하며, 그 뒤 숫자를 바로 채택하지 않습니다. */
    static boolean needsAnotherPass(String line) {
        int end = labelEnd(line);
        // 인쇄 단계에서 가려진 번호는 반복 OCR로 복원하지 않습니다.
        if (end >= 0 && line.substring(end).matches(".*[*#].*")) return false;
        return needsRecovery(line) || Pattern.compile("(?:[술슬숭]\\h*인\\h*번\\h*호|(?<![가-힣])인\\h*번\\h*호)")
                .matcher(line).find();
    }

    /** 숫자 영역의 위치를 찾을 때 표기 뒤의 단어만 사용하도록 표기 끝 위치를 제공합니다. */
    static int labelEnd(String line) {
        var label = LABEL.matcher(line);
        return label.find() ? label.end() : -1;
    }
}
