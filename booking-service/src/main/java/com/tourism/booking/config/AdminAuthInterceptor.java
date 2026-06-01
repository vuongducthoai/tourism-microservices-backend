package com.tourism.booking.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.feign.dto.UserProfileResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Base64;
import java.util.Set;

/**
 * Guards /api/admin/consultations/** — chỉ ADMIN qua được.
 * Resolve role từ:
 *   1. Header X-User-Role do api-gateway forward sau khi validate JWT thật, hoặc
 *   2. Bearer token trực tiếp:
 *        - JWT thật → đọc realm_access.roles
 *        - dev-token-{ts}-{userId} → gọi iam-service lookup role
 * (Mirror pattern của forum-service AdminAuthInterceptor.)
 */
@Component
@Slf4j
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN");

    private final IamFeignClient iamFeignClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminAuthInterceptor(@Lazy IamFeignClient iamFeignClient) {
        this.iamFeignClient = iamFeignClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String role = normalize(request.getHeader("X-User-Role"));
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            role = normalize(resolveRole(request.getHeader("Authorization")));
        }

        if (role == null || !ALLOWED_ROLES.contains(role)) {
            return deny(response, "Yêu cầu quyền ADMIN để truy cập");
        }

        captureAdmin(request, role);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AdminContext.clear();
    }

    private boolean deny(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
        return false;
    }

    private static String normalize(String role) {
        return role == null ? null : role.trim().toUpperCase();
    }

    private void captureAdmin(HttpServletRequest request, String role) {
        String email = request.getHeader("X-User-Email");
        Integer userId = null;
        String idHeader = request.getHeader("X-User-Id");
        if (idHeader != null && !idHeader.isBlank()) {
            try { userId = Integer.valueOf(idHeader.trim()); } catch (NumberFormatException ignored) {}
        }
        // Fallback: dev-token chứa userId ở cuối
        if (userId == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer dev-token-")) {
                userId = extractDevUserId(auth.substring(7).trim());
            }
        }
        AdminContext.set(userId, email, role);
    }

    private String resolveRole(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7).trim();

        // Dev-token fallback (dev-token-{timestamp}-{userId})
        if (token.startsWith("dev-token-")) {
            Integer userId = extractDevUserId(token);
            if (userId == null) return null;
            try {
                UserProfileResponse user = iamFeignClient.getUserProfile(userId);
                return user != null ? user.getRole() : null;
            } catch (Exception e) {
                log.warn("Không lấy được role của user {} từ iam-service: {}", userId, e.getMessage());
                return null;
            }
        }

        // JWT thật → đọc realm_access.roles
        return extractJwtRole(token);
    }

    private Integer extractDevUserId(String token) {
        String[] segments = token.substring("dev-token-".length()).split("-");
        if (segments.length == 0) return null;
        try {
            return Integer.valueOf(segments[segments.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractJwtRole(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode roles = objectMapper.readTree(payload).path("realm_access").path("roles");
            if (roles.isArray()) {
                for (JsonNode r : roles) {
                    String name = normalize(r.asText());
                    if ("ADMIN".equals(name)) return "ADMIN";
                }
            }
        } catch (Exception e) {
            log.warn("Không decode được JWT: {}", e.getMessage());
        }
        return null;
    }
}
