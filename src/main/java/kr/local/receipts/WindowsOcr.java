package kr.local.receipts;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Component
public class WindowsOcr {
    private final ObjectMapper json;

    public record Row(String text, double top, double bottom) {}

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

    public Result read(Path image) {
        Result original = recoverAmountRows(image, readNative(image));
        var basic = ReceiptTextParser.parse(original.text(), "");
        if (original.text().isBlank()
                || basic.amount() != null
                        && basic.date() != null
                        && !basic.vendor().isBlank()
                        && !ReceiptTextParser.book(original.text())) return original;
        var candidates = new java.util.ArrayList<String>();
        candidates.add(original.text());
        var prep = new ReceiptOcrImages();
        for (int mode = 0; mode < 5; mode++) {
            Path temp = null;
            try {
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
                // Only the last candidate tries 180 degrees, for upside-down photos or weak text.
                if ((mode == 1 && !ReceiptTextParser.book(original.text()) || mode == 3)
                        && candidates.stream()
                                .anyMatch(
                                        t -> {
                                            var a = ReceiptTextParser.parse(t, "");
                                            return a.date() != null
                                                    && a.amount() != null
                                                    && !a.vendor().isBlank();
                                        })) break;
            } catch (Exception ignored) {
                /* Retain the original result if preprocessing fails. */
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
                java.util.List.copyOf(candidates.subList(1, candidates.size())));
    }

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
                    // Keep a value only when this labeled row's crop has one unambiguous amount.
                    int index = original.rows().indexOf(row);
                    if (values.size() == 1 && index < recoveredLines.length
                            && recoveredLines[index].equals(row.text()))
                        recoveredLines[index] = ReceiptTextParser.recoveredAmount(row.text(), values.iterator().next());
                } finally { if (crop != null) Files.deleteIfExists(crop); }
            }
        } catch (Exception ignored) {
            /* A failed crop never discards the full receipt's original OCR. */
        } finally { if (source != null) source.flush(); }
        recovered = String.join("\n", recoveredLines);
        return new Result(recovered, original.message()
                + (recovered.equals(original.text()) ? "" : " 합계·결제 행의 숫자 영역을 따로 읽어 보완했습니다."),
                original.alternatives(), original.rows());
    }

    protected Result readNative(Path image) {
        if (!System.getProperty("os.name").startsWith("Windows"))
            return new Result("", "Windows OCR는 Windows에서 사용할 수 있습니다. 직접 입력하거나 로컬 AI를 연결해 주세요.");
        Path script = null, output = null, log = null;
        Process process = null;
        try {
            script = Files.createTempFile("receipt-ocr-", ".ps1");
            output = Files.createTempFile("receipt-ocr-", ".json");
            log = Files.createTempFile("receipt-ocr-", ".log");
            try (var in = getClass().getResourceAsStream("/scripts/windows-ocr.ps1")) {
                Files.writeString(
                        script,
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
            for (var row : result.path("rows"))
                rows.add(new Row(row.path("text").asText(""), row.path("top").asDouble(), row.path("bottom").asDouble()));
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
