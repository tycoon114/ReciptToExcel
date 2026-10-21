package kr.local.receipts;

import jakarta.servlet.http.*;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

import java.net.URI;
import java.util.Set;

/**
 * 로컬 전용 웹 화면과 API 요청에 적용할 기본 보호 설정입니다.
 * 허용한 로컬 호스트와 변경 요청의 출처·전용 헤더를 검사합니다. 사용자 로그인 기능은 아닙니다.
 */
@Configuration
public class LocalWebSecurity implements WebMvcConfigurer {
    /** 보안 응답 헤더와 로컬 요청 검사를 모든 MVC 요청 앞에 등록합니다. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler)
                            throws Exception {
                        // 외부 스크립트 로드·다른 사이트의 화면 삽입 등을 제한하고 영수증 응답의 캐시를 막습니다.
                        response.setHeader("X-Content-Type-Options", "nosniff");
                        response.setHeader("X-Frame-Options", "DENY");
                        response.setHeader("Referrer-Policy", "no-referrer");
                        response.setHeader(
                                "Content-Security-Policy",
                                "default-src 'self'; img-src 'self' blob:; style-src 'self';"
                                        + " script-src 'self'; connect-src 'self'; frame-ancestors"
                                        + " 'none'; base-uri 'none'; form-action 'self'");
                        response.setHeader("Cache-Control", "no-store");
                        // 서버의 로컬 바인딩 설정과 별도로 Host에 나타난 이름도 허용 목록과 비교합니다.
                        String host = request.getServerName();
                        if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(host)) {
                            response.sendError(403);
                            return false;
                        }
                        // 저장·삭제·전송 요청은 일반 조회보다 엄격히 검사하여 다른 웹 페이지의 임의 호출을 줄입니다.
                        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                            String origin = request.getHeader("Origin");
                            boolean sameOrigin = true;
                            if (origin != null)
                                try {
                                    var u = URI.create(origin);
                                    int port =
                                            u.getPort() == -1
                                                    ? (u.getScheme().equals("https") ? 443 : 80)
                                                    : u.getPort();
                                    sameOrigin =
                                            u.getHost().equalsIgnoreCase(host)
                                                    && u.getScheme().equals(request.getScheme())
                                                    && port == request.getServerPort();
                                } catch (Exception e) {
                                    sameOrigin = false;
                                }
                            // 전용 헤더는 앱이 보내는 요청 표시이며, 로그인 인증이나 권한 관리 수단을 대신하지 않습니다.
                            if (!sameOrigin
                                    || !"local-app".equals(request.getHeader("X-Receipt-Client"))) {
                                response.sendError(403);
                                return false;
                            }
                        }
                        return true;
                    }
                });
    }
}
