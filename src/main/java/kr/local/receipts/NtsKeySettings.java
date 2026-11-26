package kr.local.receipts;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.io.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 화면에서 저장한 키만 별도 개인 파일에 보관합니다. 기존 연결 설정 파일은 변경하지 않습니다. */
@Service
public class NtsKeySettings {
    private final Path file;
    private volatile String key;
    @org.springframework.beans.factory.annotation.Autowired
    public NtsKeySettings(@Value("${app.data-dir:data}") String dataDir,
            @Value("${app.nts-service-key:}") String initialKey) throws IOException {
        this(Path.of(dataDir).resolve("nts-settings.properties"), initialKey);
    }
    NtsKeySettings(Path file, String initialKey) throws IOException {
        this.file = file;
        key = initialKey == null ? "" : initialKey.trim();
        if (Files.exists(file)) {
            var p = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
            key = p.getProperty("app.nts-service-key", "").trim();
        }
    }
    String key() { return key; }
    static String validate(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isEmpty() || key.length() > 2048 || !key.matches("[A-Za-z0-9+/=%_-]+"))
            throw ReceiptService.bad("인증키를 확인해 주세요. 공백·줄바꿈 없이 발급받은 키만 입력하세요.");
        return key;
    }
    /** 임시 파일을 완성한 뒤 교체하며 저장 실패 시 현재 사용 중인 키는 유지합니다. */
    public synchronized void save(String value) {
        String next = validate(value);
        Path temporary = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "nts-settings-", ".tmp");
            var p = new Properties(); p.setProperty("app.nts-service-key", next);
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                p.store(writer, "Personal NTS settings - do not share");
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            key = next;
        } catch (IOException e) {
            throw ReceiptService.bad("인증키를 저장하지 못했습니다. 데이터 폴더의 쓰기 권한을 확인해 주세요.");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
