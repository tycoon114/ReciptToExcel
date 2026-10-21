package kr.local.receipts;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 예외를 화면에서 표시할 수 있는 일관된 JSON 오류 응답으로 바꿉니다.
 * 예상하지 못한 오류의 내부 경로나 스택 정보는 사용자 응답에 노출하지 않습니다.
 */
@RestControllerAdvice
public class ApiErrors {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiErrors.class);

    /** 서비스가 의도적으로 만든 오류 상태와 안내 문구를 그대로 전달합니다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> known(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", e.getReason() == null ? "요청을 처리할 수 없습니다." : e.getReason()));
    }

    /** 필드 길이·필수 여부·금액 범위 검증 실패를 입력 오류로 안내합니다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation() {
        return ResponseEntity.badRequest()
                .body(Map.of("message", "입력 길이와 금액을 확인해 주세요. 금액은 0 이상의 원 단위 정수입니다."));
    }

    /** 스프링이 업로드 크기 제한에서 차단한 요청에 413 응답을 반환합니다. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> size() {
        return ResponseEntity.status(413).body(Map.of("message", "10MB 이하의 JPG/PNG 사진을 선택해 주세요."));
    }

    /** 잘못된 JSON·경로 변수·누락된 요청 항목을 400 응답으로 정리합니다. */
    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    public ResponseEntity<?> malformed() {
        return ResponseEntity.badRequest()
                .body(Map.of("message", "요청 형식이 올바르지 않습니다. 입력값을 확인해 주세요."));
    }

    /** 처리하지 못한 오류는 종류만 로그에 남기고 일반적인 재시도 안내를 반환합니다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) {
        log.error("Receipt request failed ({})", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(Map.of("message", "처리 중 오류가 발생했습니다. 파일과 설정을 확인한 뒤 다시 시도해 주세요."));
    }
}
