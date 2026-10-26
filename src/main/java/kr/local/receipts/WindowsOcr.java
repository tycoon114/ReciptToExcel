package kr.local.receipts;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * Windows 내장 OCR를 PowerShell로 호출하는 연결 계층입니다.
 * 전체 사진과 필요한 숫자 영역을 읽고, 인식이 부족하면 전처리 사본의 결과를 추가로 수집합니다.
 */
@Component
public class WindowsOcr {
    private final ObjectMapper json;

    /** 단어의 원문과 사각형입니다. 다른 행의 카드번호·금액을 승인번호로 가져오지 않도록 사용합니다. */
    public record Word(String text, double left, double top, double right, double bottom) {}

    /** OCR 행과 단어 좌표입니다. 기존 행 좌표만 제공하는 호출도 유지합니다. */
    public record Row(String text, double top, double bottom, java.util.List<Word> words) {
        public Row { words = words == null ? java.util.List.of() : java.util.List.copyOf(words); }
        public Row(String text, double top, double bottom) { this(text, top, bottom, java.util.List.of()); }
    }

    /** 원문·안내·전처리 후보·행 위치를 담습니다. 후보 목록은 외부에서 바꾸지 못하도록 복사합니다. */
    public record Result(String text, String message, java.util.List<String> alternatives,
                         java.util.List<Row> rows) {
        public Result {
            alternatives = alternatives == null ? java.util.List.of() : java.util.List.copyOf(alternatives);
            rows = rows == null ? java.util.List.of() : java.util.List.copyOf(rows);
        }
        public Result(String text, String message, java.util.List<String> alternatives) {
            this(text, message, alternatives, java.util.List.of());
        }
        public Result(String text, String message) {
            this(text, message, java.util.List.of());
        }
    }

    public WindowsOcr(ObjectMapper json) {
        this.json = json;
    }

    /** 기본 OCR와 숫자 행 복구를 수행하고, 필요한 경우에만 여러 크기·명암의 인식 결과를 비교합니다. */
    public Result read(Path image) {
        Result original = recoverApprovalRows(image, recoverAmountRows(image, readNative(image)));
        // 핵심 정보가 이미 있는 일반 영수증은 바로 반환하여 반복 인식 비용을 줄입니다. 도서는 보정 후보를 더 비교합니다.
        var basic = ReceiptTextParser.parse(original.text(), "");
        if (original.text().isBlank()
                || basic.amount() != null
                        && basic.date() != null
                        && !basic.vendor().isBlank()
                        && !hasUnreadableApproval(original.text())
                        && !ReceiptTextParser.book(original.text())) return original;
        var candidates = new java.util.ArrayList<String>();
        candidates.add(original.text());
        candidates.addAll(original.alternatives());
        var prep = new ReceiptOcrImages();
        for (int mode = 0; mode < 5; mode++) {
            Path temp = null;
            try {
                // 보정은 임시 사본에만 적용합니다. 축소 → 명암 보정 → 문자 연결 → 필요한 경우 180도 순서입니다.
                var prepared =
                        switch (mode) {
                            case 0 -> prep.resized(image, 1800);
                            case 1 -> prep.resized(image, 1000);
                            case 2 -> prep.prepare(image, true);
                            default -> prep.compact(image, true, mode == 4);
                        };
                temp = Files.createTempFile("receipt-ocr-prepared-", ".png");
                try {
                    javax.imageio.ImageIO.write(prepared, "png", temp.toFile());
                } finally {
                    prepared.flush();
                }
                var result = readNative(temp);
                if (!result.text().isBlank()) candidates.add(result.text());
                // 날짜·금액·상호가 확보되면 비교를 멈추고, 마지막 후보에서만 뒤집힌 사진을 위한 180도를 시도합니다.
                if ((mode == 1 && !ReceiptTextParser.book(original.text()) || mode == 3)
                        && candidates.stream()
                                .anyMatch(
                                        t -> {
                                            var a = ReceiptTextParser.parse(t, "");
                                            return a.date() != null
                                                    && a.amount() != null
                                                    && !a.vendor().isBlank()
                                                    && !hasUnreadableApproval(t);
                                        })) break;
            } catch (Exception ignored) {
                /* 전처리에 실패해도 이미 확보한 원본 OCR 결과는 유지합니다. */
            } finally {
                if (temp != null)
                    try {
                        Files.deleteIfExists(temp);
                    } catch (Exception ignored) {
                    }
            }
        }
        return new Result(
                original.text(),
                original.message() + " 크기 축소·영수증 영역·명암·문자 두께를 바꾼 인식 결과를 비교했습니다.",
                java.util.List.copyOf(candidates.subList(1, candidates.size())), original.rows());
    }

    /** 승인번호가 선택 항목이라는 원칙은 유지하되, 표기가 있는데 못 읽은 경우에는 조기 종료하지 않습니다. */
    private static boolean hasUnreadableApproval(String text) {
        return text.lines().anyMatch(ApprovalNumberParser::needsAnotherPass);
    }

    /** 불확실한 승인번호의 단어 영역만 두 크기로 재인식합니다. 두 결과가 모두 같을 때만 해당 행을 보완합니다. */
    private Result recoverApprovalRows(Path image, Result original) {
        var targets = original.rows().stream().filter(row -> ApprovalNumberParser.needsRecovery(row.text()))
                .limit(2).toList();
        if (targets.isEmpty()) return original;
        java.awt.image.BufferedImage source = null;
        String[] lines = original.text().split("\\R", -1);
        var alternatives = new java.util.ArrayList<>(original.alternatives());
        boolean changed = false;
        try {
            source = javax.imageio.ImageIO.read(image.toFile());
            if (source == null) return original;
            for (var row : targets) {
                int labelEnd = ApprovalNumberParser.labelEnd(row.text());
                String tail = row.text().substring(labelEnd);
                // 마스킹이나 다른 항목 표기가 섞이면 부분 인식으로 임의 복원하지 않습니다.
                if (tail.matches(".*[*#].*") || tail.matches(".*[가-힣].*")) continue;
                int offset = 0;
                var words = new java.util.ArrayList<Word>();
                for (var word : row.words()) {
                    int position = row.text().indexOf(word.text(), offset);
                    if (position < 0) continue;
                    offset = position + word.text().length();
                    if (position >= labelEnd && ApprovalNumberParser.valid(word.text())) words.add(word);
                }
                if (words.size() != 1) continue;
                Word word = words.get(0);
                // OCR 좌표는 정수 범위·원본 안쪽인지 검사하고, 글자 획이 잘리지 않도록 작은 여백을 둡니다.
                if (!Double.isFinite(word.left()) || !Double.isFinite(word.right())
                        || !Double.isFinite(word.top()) || !Double.isFinite(word.bottom())
                        || word.left() < 0 || word.top() < 0 || word.right() > source.getWidth()
                        || word.bottom() > source.getHeight() || word.right() <= word.left()
                        || word.bottom() <= word.top()) continue;
                int left = Math.max(0, (int)Math.floor(word.left()) - 4);
                int top = Math.max(0, (int)Math.floor(word.top()) - 4);
                int right = Math.min(source.getWidth(), (int)Math.ceil(word.right()) + 4);
                int bottom = Math.min(source.getHeight(), (int)Math.ceil(word.bottom()) + 4);
                var values = new java.util.ArrayList<String>();
                for (double scale : new double[]{1, 1.5}) {
                    Path crop = null;
                    var prepared = new java.awt.image.BufferedImage((int)((right-left)*scale)+40,
                            (int)((bottom-top)*scale)+40, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    try {
                        var g = prepared.createGraphics();
                        try {
                            g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, prepared.getWidth(), prepared.getHeight());
                            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                            g.drawImage(source, 20, 20, prepared.getWidth()-20, prepared.getHeight()-20,
                                    left, top, right, bottom, null);
                        } finally { g.dispose(); }
                        crop = Files.createTempFile("receipt-ocr-approval-", ".png");
                        javax.imageio.ImageIO.write(prepared, "png", crop.toFile());
                        String value = readNative(crop).text().trim();
                        if (ApprovalNumberParser.valid(value)) values.add(value);
                    } finally {
                        prepared.flush();
                        if (crop != null) Files.deleteIfExists(crop);
                    }
                }
                int index = original.rows().indexOf(row);
                // 서로 다른 두 번호가 읽히면 두 근거를 모두 넘겨 최종 분석에서 충돌을 안내하고 번호를 비웁니다.
                if (values.size() == 2 && !values.get(0).equals(values.get(1))
                        && index < lines.length && lines[index].equals(row.text())) {
                    for (String value : values) {
                        String[] candidate = lines.clone();
                        candidate[index] = row.text().substring(0, labelEnd) + " : " + value;
                        alternatives.add(String.join("\n", candidate));
                    }
                }
                if (values.size() == 2 && values.get(0).equals(values.get(1))
                        && index < lines.length && lines[index].equals(row.text())) {
                    lines[index] = row.text().substring(0, labelEnd) + " : " + values.get(0);
                    changed = true;
                }
            }
        } catch (Exception ignored) {
            /* 부분 인식 실패 시 원본 결과와 수동 확인 안내를 유지합니다. */
        } finally { if (source != null) source.flush(); }
        return new Result(String.join("\n", lines), original.message()
                + (changed ? " 승인번호 영역을 두 크기로 다시 읽어 일치하는 값을 보완했습니다." : ""),
                alternatives, original.rows());
    }

    /** 총액이 누락된 합계·결제 행을 최대 네 개까지 재인식합니다. 행 위치를 유지해 같은 표기도 구분합니다. */
    private Result recoverAmountRows(Path image, Result original) {
        if (ReceiptTextParser.parse(original.text(), "").amount() != null) return original;
        var targets = original.rows().stream()
                .filter(row -> ReceiptTextParser.needsAmountRecovery(row.text())).limit(4).toList();
        if (targets.isEmpty()) return original;
        java.awt.image.BufferedImage source = null;
        String recovered = original.text();
        String[] recoveredLines = original.text().split("\\R", -1);
        try {
            source = javax.imageio.ImageIO.read(image.toFile());
            if (source == null) return original;
            for (var row : targets) {
                Path crop = null;
                try {
                    double height = Math.max(12, row.bottom() - row.top());
                    int top = Math.max(0, (int) Math.floor(row.top() - height * .35));
                    int bottom = Math.min(source.getHeight(), (int) Math.ceil(row.bottom() + height * .35));
                    if (bottom <= top) continue;
                    // 합계 표기의 세로 위치를 기준으로 우측 절반만 잘라 큰 숫자가 주변 표와 섞이는 것을 줄입니다.
                    int left = source.getWidth() / 2;
                    var band = source.getSubimage(left, top, source.getWidth() - left, bottom - top);
                    crop = Files.createTempFile("receipt-ocr-amount-row-", ".png");
                    javax.imageio.ImageIO.write(band, "png", crop.toFile());
                    var money = readNative(crop);
                    var values = new java.util.LinkedHashSet<Long>();
                    for (String line : money.text().lines().toList()) {
                        String number = line.trim();
                        if (!number.matches("(?:[0-9]{1,3}(?:\\h*,\\h*[0-9]{3})+|[0-9]{3,12})\\h*원?")) continue;
                        long value = Long.parseLong(number.replaceAll("[,\\h원]", ""));
                        if (value >= 100 && value <= 999999999999L) values.add(value);
                    }
                    // 숫자 영역에 서로 다른 금액이 여러 개면 보완하지 않습니다. 같은 표기도 원래의 행 번호에만 반영합니다.
                    int index = original.rows().indexOf(row);
                    if (values.size() == 1 && index < recoveredLines.length
                            && recoveredLines[index].equals(row.text()))
                        recoveredLines[index] = ReceiptTextParser.recoveredAmount(row.text(), values.iterator().next());
                } finally { if (crop != null) Files.deleteIfExists(crop); }
            }
        } catch (Exception ignored) {
            /* 부분 인식 실패가 전체 영수증의 OCR 결과를 지우지 않도록 합니다. */
        } finally { if (source != null) source.flush(); }
        recovered = String.join("\n", recoveredLines);
        return new Result(recovered, original.message()
                + (recovered.equals(original.text()) ? "" : " 합계·결제 행의 숫자 영역을 따로 읽어 보완했습니다."),
                original.alternatives(), original.rows());
    }

    /** Windows OCR 한 번의 실행을 담당합니다. 테스트에서는 이 메서드를 대체하여 운영체제 의존을 줄입니다. */
    protected Result readNative(Path image) {
        if (!System.getProperty("os.name").startsWith("Windows"))
            return new Result("", "Windows OCR는 Windows에서 사용할 수 있습니다. 직접 입력하거나 로컬 AI를 연결해 주세요.");
        Path script = null, output = null, log = null;
        Process process = null;
        try {
            // 실행 JAR 내부의 스크립트를 임시 파일로 꺼냅니다. 결과 JSON과 프로세스 로그는 분리합니다.
            script = Files.createTempFile("receipt-ocr-", ".ps1");
            output = Files.createTempFile("receipt-ocr-", ".json");
            log = Files.createTempFile("receipt-ocr-", ".log");
            try (var in = getClass().getResourceAsStream("/scripts/windows-ocr.ps1")) {
                Files.writeString(
                        script,
                        // Windows PowerShell 5에서도 한글을 읽도록 임시 스크립트에 UTF-8 BOM을 붙입니다.
                        "\ufeff" + new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);
            }
            String powershell =
                    Path.of(
                                    System.getenv("SystemRoot"),
                                    "System32",
                                    "WindowsPowerShell",
                                    "v1.0",
                                    "powershell.exe")
                            .toString();
            // 경로를 명령 문자열로 합치지 않고 각각 인자로 전달하여 공백·한글 파일명도 처리합니다.
            process =
                    new ProcessBuilder(
                                    powershell,
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-ExecutionPolicy",
                                    "Bypass",
                                    "-File",
                                    script.toString(),
                                    "-ImagePath",
                                    image.toAbsolutePath().toString(),
                                    "-OutputPath",
                                    output.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
            // 한 번의 OCR 실행이 멈춰도 요청을 무한히 기다리지 않게 제한 시간을 둡니다.
            if (!process.waitFor(35, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
                return new Result("", "문자 인식 제한 시간을 초과했습니다. 직접 입력하거나 이미지를 작게 잘라 다시 올려 주세요.");
            }
            if (Files.size(output) == 0)
                return new Result("", "Windows OCR를 실행할 수 없습니다. Windows의 한국어 OCR 언어 팩을 확인해 주세요.");
            var result = json.readTree(Files.readString(output));
            String error = result.path("error").asText("");
            if (!error.isBlank())
                return new Result(
                        "",
                        "Windows OCR 사용 불가: " + error.substring(0, Math.min(300, error.length())));
            String text = result.path("text").asText("");
            String language = result.path("language").asText("");
            var rows = new java.util.ArrayList<Row>();
            for (var row : result.path("rows")) {
                var words = new java.util.ArrayList<Word>();
                for (var word : row.path("words")) words.add(new Word(word.path("text").asText(""),
                        word.path("left").asDouble(), word.path("top").asDouble(),
                        word.path("right").asDouble(), word.path("bottom").asDouble()));
                rows.add(new Row(row.path("text").asText(""), row.path("top").asDouble(), row.path("bottom").asDouble(), words));
            }
            return new Result(
                    text,
                    text.isBlank()
                            ? "문자를 찾지 못했습니다. 사진을 확인하고 직접 입력해 주세요."
                            : "Windows OCR (" + language + "). 금액·날짜·업무 목적을 원본과 대조해 주세요.",
                    java.util.List.of(), rows);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("", "문자 인식이 중단되었습니다.");
        } catch (Exception e) {
            return new Result("", "Windows OCR를 사용할 수 없습니다. 직접 입력하거나 로컬 AI를 연결해 주세요.");
        } finally {
            // 성공·실패·중단 여부와 관계없이 남은 프로세스와 임시 파일을 정리합니다.
            if (process != null && process.isAlive()) process.destroyForcibly();
            for (Path p : new Path[] {script, output, log})
                if (p != null)
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
        }
    }
}
