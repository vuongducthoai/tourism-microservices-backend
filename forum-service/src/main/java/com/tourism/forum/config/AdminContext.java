package com.tourism.forum.config;

/**
 * Lưu thông tin admin hiện tại theo từng request (ThreadLocal).
 * Set trong AdminAuthInterceptor, đọc trong service layer để ghi audit log.
 */
public final class AdminContext {

    public record AdminIdentity(Integer userId, String email, String role) {}

    private static final ThreadLocal<AdminIdentity> CTX = new ThreadLocal<>();

    private AdminContext() {}

    public static void set(Integer userId, String email, String role) {
        CTX.set(new AdminIdentity(userId, email, role));
    }

    public static AdminIdentity get() {
        return CTX.get();
    }

    public static Integer currentUserId() {
        AdminIdentity id = CTX.get();
        return id != null ? id.userId() : null;
    }

    public static String currentEmail() {
        AdminIdentity id = CTX.get();
        return id != null ? id.email() : null;
    }

    public static String currentRole() {
        AdminIdentity id = CTX.get();
        return id != null ? id.role() : null;
    }

    public static boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(currentRole());
    }

    public static void clear() {
        CTX.remove();
    }
}
