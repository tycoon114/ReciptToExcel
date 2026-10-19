package kr.local.receipts;

import jakarta.servlet.http.*;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

import java.net.URI;
import java.util.Set;

@Configuration
public class LocalWebSecurity implements WebMvcConfigurer {
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
                        response.setHeader("X-Content-Type-Options", "nosniff");
                        response.setHeader("X-Frame-Options", "DENY");
                        response.setHeader("Referrer-Policy", "no-referrer");
                        response.setHeader(
                                "Content-Security-Policy",
                                "default-src 'self'; img-src 'self' blob:; style-src 'self';"
                                        + " script-src 'self'; connect-src 'self'; frame-ancestors"
                                        + " 'none'; base-uri 'none'; form-action 'self'");
                        response.setHeader("Cache-Control", "no-store");
                        String host = request.getServerName();
                        if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(host)) {
                            response.sendError(403);
                            return false;
                        }
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
