package kr.local.receipts;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> known(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", e.getReason() == null ? "요청을 처리할 수 없습니다." : e.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation() {
        return ResponseEntity.badRequest()
                .body(Map.of("message", "입력 길이와 금액을 확인해 주세요. 금액은 0 이상의 원 단위 정수입니다."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> size() {
        return ResponseEntity.status(413).body(Map.of("message", "10MB 이하의 JPG/PNG 사진을 선택해 주세요."));
    }

    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    public ResponseEntity<?> malformed() {
        return ResponseEntity.badRequest()
                .body(Map.of("message", "요청 형식이 올바르지 않습니다. 입력값을 확인해 주세요."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) {
        log.error("Receipt request failed ({})", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(Map.of("message", "처리 중 오류가 발생했습니다. 파일과 설정을 확인한 뒤 다시 시도해 주세요."));
    }
}
