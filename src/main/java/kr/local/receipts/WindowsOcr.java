package kr.local.receipts;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Component
public class WindowsOcr {
    private final ObjectMapper json;

    public record Result(String text, String message, java.util.List<String> alternatives) {
        public Result(String text, String message) {
            this(text, message, java.util.List.of());
        }
    }

    public WindowsOcr(ObjectMapper json) {
        this.json = json;
    }

    public Result read(Path image) {
        Result original = readNative(image);
        var basic = ReceiptTextParser.parse(original.text(), "");
        if (original.text().isBlank()
                || basic.amount() != null
                        && basic.date() != null
                        && !basic.vendor().isBlank()
                        && !ReceiptTextParser.book(original.text())) return original;
        var candidates = new java.util.ArrayList<String>();
        candidates.add(original.text());
        var prep = new ReceiptOcrImages();
        for (int mode = 0; mode < 3; mode++) {
            Path temp = null;
            try {
                var prepared =
                        mode == 0
                                ? prep.prepare(image, true)
                                : prep.compact(image, true, mode == 2);
                temp = Files.createTempFile("receipt-ocr-prepared-", ".png");
                try {
                    javax.imageio.ImageIO.write(prepared, "png", temp.toFile());
                } finally {
                    prepared.flush();
                }
                var result = readNative(temp);
                if (!result.text().isBlank()) candidates.add(result.text());
                // Only the last candidate tries 180 degrees, for upside-down photos or weak text.
                if (mode == 1
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
                original.message() + " 영수증 영역·명암·문자 두께를 바꾼 인식 결과를 비교했습니다.",
                java.util.List.copyOf(candidates.subList(1, candidates.size())));
    }

    private Result readNative(Path image) {
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
            return new Result(
                    text,
                    text.isBlank()
                            ? "문자를 찾지 못했습니다. 사진을 확인하고 직접 입력해 주세요."
                            : "Windows OCR (" + language + "). 금액·날짜·업무 목적을 원본과 대조해 주세요.");
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
