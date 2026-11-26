package kr.local.receipts;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 개인 인증키는 쓰기 전용입니다. 응답에는 키 대신 설정 여부와 연결 확인 결과만 반환합니다. */
@RestController
@RequestMapping("/api/nts-settings")
public class NtsSettingsController {
    private final NtsKeySettings settings;
    private final NtsBusinessLookup nts;
    public NtsSettingsController(NtsKeySettings settings, NtsBusinessLookup nts) {
        this.settings = settings; this.nts = nts;
    }
    public record Input(String key, String businessNumber) { }
    @GetMapping public Map<String, Boolean> status() { return Map.of("configured", nts.configured()); }
    @PostMapping public Map<String, Boolean> save(@RequestBody Input input) {
        settings.save(input.key()); return status();
    }
    @PostMapping("/test") public Map<String, Object> test(@RequestBody Input input) {
        String key = input.key() == null || input.key().isBlank() ? settings.key() : NtsKeySettings.validate(input.key());
        String number = input.businessNumber() == null ? "" : input.businessNumber().trim().replace("-", "");
        var result = nts.request(number, key);
        return Map.of("connected", true, "message", "국세청 연결을 확인했습니다. "
                + ("FOUND".equals(result.state()) ? result.taxType() + " / " + result.businessState() : "입력한 번호는 국세청에서 조회되지 않습니다.")
                + " (장부에는 저장하지 않았습니다.)");
    }
}
