package com.tourism.forum.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.forum.feign.IamFeignClient;
import com.tourism.forum.feign.dto.UserBriefResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Base64;
import java.util.Set;

/**
 * Guards /api/admin/forum/** — cho phép ADMIN và MODERATOR.
 * Endpoint/Controller có annotation @RequireAdmin sẽ chỉ ADMIN qua được.
 *
 * Role được resolve từ:
 *   1. gateway-forwarded X-User-Role header (luồng JWT Keycloak thật), hoặc
 *   2. bearer token trực tiếp:
 *        - JWT thật  -> đọc realm_access.roles,
 *        - dev-token ("dev-token-{ts}-{userId}") -> tra role qua iam-service.
 */
@Component
@Slf4j
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "MODERATOR");

    private final IamFeignClient iamFeignClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminAuthInterceptor(@Lazy IamFeignClient iamFeignClient) {
        this.iamFeignClient = iamFeignClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String role = normalize(request.getHeader("X-User-Role"));
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            role = normalize(resolveRole(request.getHeader("Authorization")));
        }

        if (role == null || !ALLOWED_ROLES.contains(role)) {
            return deny(response, HttpStatus.FORBIDDEN, "Yêu cầu quyền ADMIN hoặc MODERATOR để truy cập");
        }

        captureAdmin(request, role);

        // ADMIN-only check: method/class có @RequireAdmin?
        if (handler instanceof HandlerMethod hm) {
            boolean needAdmin = hm.getMethodAnnotation(RequireAdmin.class) != null
                    || hm.getBeanType().isAnnotationPresent(RequireAdmin.class);
            if (needAdmin && !"ADMIN".equals(role)) {
                return deny(response, HttpStatus.FORBIDDEN, "Thao tác này chỉ ADMIN được phép");
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AdminContext.clear();
    }

    private boolean deny(HttpServletResponse response, HttpStatus status, String message) throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
        return false;
    }

    private static String normalize(String role) {
        return role == null ? null : role.trim().toUpperCase();
    }

    /** Lấy danh tính admin từ header gateway (hoặc dev-token) lưu vào ThreadLocal. */
    private void captureAdmin(HttpServletRequest request, String role) {
        String email = request.getHeader("X-User-Email");
        Integer userId = null;
        String idHeader = request.getHeader("X-User-Id");
        if (idHeader != null && !idHeader.isBlank()) {
            try { userId = Integer.valueOf(idHeader.trim()); } catch (NumberFormatException ignored) {}
        }
        if (userId == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer dev-token-")) {
                userId = extractDevUserId(auth.substring(7).trim());
            }
        }
        AdminContext.set(userId, email, role);
    }

    private String resolveRole(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();

        if (token.startsWith("dev-token-")) {
            Integer userId = extractDevUserId(token);
            if (userId == null) {
                return null;
            }
            try {
                UserBriefResponse user = iamFeignClient.getUserById(userId);
                return user != null ? user.getRole() : null;
            } catch (Exception e) {
                log.warn("Không lấy được role của user {} từ iam-service: {}", userId, e.getMessage());
                return null;
            }
        }

        return extractJwtRole(token);
    }

    private Integer extractDevUserId(String token) {
        String[] segments = token.substring("dev-token-".length()).split("-");
        if (segments.length == 0) {
            return null;
        }
        try {
            return Integer.valueOf(segments[segments.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractJwtRole(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode roles = objectMapper.readTree(payload).path("realm_access").path("roles");
            if (roles.isArray()) {
                // Ưu tiên ADMIN nếu user có cả 2 role
                String found = null;
                for (JsonNode r : roles) {
                    String name = normalize(r.asText());
                    if ("ADMIN".equals(name)) return "ADMIN";
                    if ("MODERATOR".equals(name)) found = "MODERATOR";
                }
                return found;
            }
        } catch (Exception e) {
            log.warn("Không decode được JWT: {}", e.getMessage());
        }
        return null;
    }
}
