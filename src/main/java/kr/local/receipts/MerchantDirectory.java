package kr.local.receipts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 정확한 사업자번호를 키로 사용하는 PC 내부 거래처 보정 사전입니다.
 * 사용자가 확정한 상호를 기억하며, 유사한 이름이나 파일명만으로 다른 거래처를 연결하지 않습니다.
 */
@Component
public class MerchantDirectory {
    private final ObjectMapper json;
    private final Path file;
    private final Map<String, String> names = new TreeMap<>();

    public MerchantDirectory(ObjectMapper json, @Value("${app.data-dir}") String data) {
        this.json = json;
        file = Path.of(data).resolve("merchant-rules.json");
        if (Files.isRegularFile(file))
            try {
                var root = json.readTree(Files.readString(file));
                root.properties()
                        .forEach(
                                e -> {
                                    if (e.getKey().matches("[0-9]{10}") && e.getValue().isString())
                                        names.put(e.getKey(), e.getValue().asText());
                                });
            } catch (Exception e) {
                throw new IllegalArgumentException("data/merchant-rules.json 거래처 사전을 확인해 주세요.", e);
            }
    }

    /** 사업자번호의 공백·구분자 차이는 허용하되 숫자 열 자리는 모두 같아야 합니다. 일치가 모호하면 원래 상호를 유지합니다. */
    public synchronized String resolve(String text, String fallback) {
        Set<String> matches = new LinkedHashSet<>();
        for (var entry : names.entrySet()) {
            String n = entry.getKey();
            String pattern =
                    "(?<![0-9])"
                            + n.substring(0, 3)
                            + "[\\h\\-–~•.:]{0,5}"
                            + n.substring(3, 5)
                            + "[\\h\\-–~•.:]{0,5}"
                            + n.substring(5)
                            + "(?![0-9])";
            if (Pattern.compile(pattern).matcher(text).find()) matches.add(entry.getValue());
        }
        return matches.size() == 1 ? matches.iterator().next() : fallback;
    }

    /** 사업자 표기가 있는 행에서 단일 번호가 확인된 경우에만 확정 상호를 파일과 메모리에 반영합니다. */
    public synchronized void remember(String text, String name) throws Exception {
        if (name.isBlank()) return;
        Set<String> ids = new LinkedHashSet<>();
        for (String line : text.lines().toList())
            if (line.contains("사업자") || line.contains("등록번호")) {
                var matcher =
                        Pattern.compile(
                                        "(?<![0-9])([0-9]{3})[\\h\\-–~•.:]+([0-9]{2})[\\h\\-–~•.:]+([0-9]{5})(?![0-9])")
                                .matcher(line);
                while (matcher.find())
                    ids.add(matcher.group(1) + matcher.group(2) + matcher.group(3));
            }
        // 한 영수증에 여러 사업자번호가 있으면 결제대행사와 매장 중 어느 것인지 임의로 학습하지 않습니다.
        if (ids.size() != 1) return;
        String id = ids.iterator().next();
        if (name.equals(names.get(id))) return;
        // 파일 저장이 성공하기 전에는 기존 메모리 사전을 바꾸지 않습니다.
        Map<String, String> updated = new TreeMap<>(names);
        updated.put(id, name);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "merchant-", ".tmp");
        try {
            Files.writeString(
                    temp, json.writerWithDefaultPrettyPrinter().writeValueAsString(updated));
            // 완성된 임시 파일로 원자적 교체를 시도해 저장 도중 사전 파일이 일부만 기록되는 것을 줄입니다.
            try {
                Files.move(
                        temp,
                        file.toAbsolutePath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file.toAbsolutePath(), StandardCopyOption.REPLACE_EXISTING);
            }
            names.clear();
            names.putAll(updated);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
