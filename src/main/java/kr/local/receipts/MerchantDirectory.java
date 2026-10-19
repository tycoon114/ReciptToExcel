package kr.local.receipts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** User-local correction dictionary, keyed by an exact business number rather than filename. */
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
        if (ids.size() != 1) return;
        String id = ids.iterator().next();
        if (name.equals(names.get(id))) return;
        Map<String, String> updated = new TreeMap<>(names);
        updated.put(id, name);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "merchant-", ".tmp");
        try {
            Files.writeString(
                    temp, json.writerWithDefaultPrettyPrinter().writeValueAsString(updated));
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
