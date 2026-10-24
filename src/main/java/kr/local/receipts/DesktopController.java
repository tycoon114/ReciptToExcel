package kr.local.receipts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Windows 관리 창만 사용하는 생존 확인·정상 종료 API입니다. 일반 IntelliJ 실행에서는 등록하지 않습니다. */
@RestController
@RequestMapping("/api/desktop")
@ConditionalOnProperty(name="app.desktop-enabled", havingValue="true")
public class DesktopController {
    private final byte[] secret;
    private final ConfigurableApplicationContext context;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public DesktopController(@Value("${app.desktop-token:}") String token, ConfigurableApplicationContext context) {
        if (token.length() < 32) throw new IllegalArgumentException("관리 창의 실행 인증 정보가 없습니다.");
        secret = token.getBytes(StandardCharsets.UTF_8);
        this.context = context;
    }

    /** 같은 포트에 다른 프로그램이 있더라도 이번 실행의 인증값이 맞아야 준비 완료로 판단합니다. */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestHeader(value="X-Receipt-Desktop", required=false) String token) {
        authorize(token);
        return Map.of("application", "ReceiptToExcel", "ready", !stopping.get());
    }

    /** HTTP 응답을 보낸 뒤 스프링을 닫아 DB와 진행 중인 서버 자원을 정상 정리합니다. */
    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestHeader(value="X-Receipt-Desktop", required=false) String token) {
        authorize(token);
        if (stopping.compareAndSet(false, true)) {
            Thread shutdown = new Thread(() -> {
                try { Thread.sleep(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                context.close();
            }, "receipt-desktop-shutdown");
            shutdown.start();
        }
        return Map.of("message", "장부를 정리하고 종료합니다.");
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(secret, token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리 창에서 요청해 주세요.");
    }
}
