package com.tourism.iam.controller;

import com.tourism.iam.dto.request.GoogleLoginRequest;
import com.tourism.iam.dto.request.LoginRequest;
import com.tourism.iam.dto.request.LogoutAllRequest;
import com.tourism.iam.dto.request.RefreshTokenRequest;
import com.tourism.iam.dto.request.RegisterRequest;
import com.tourism.iam.dto.response.LoginResponse;
import com.tourism.iam.dto.response.TokenResponse;
import com.tourism.iam.dto.response.UserDetailResponse;
import com.tourism.iam.service.AuthService;
import com.tourism.iam.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    // POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    // POST /api/auth/register
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.ok(Map.of("message",
            "Đăng ký thành công! Vui lòng kiểm tra email để xác thực tài khoản."));
    }

    // GET /api/auth/verify-email?token=xxx
    @GetMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(Map.of("message", "Xác thực email thành công!"));
    }

    // POST /api/auth/resend-verification?email=xxx
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(@RequestParam String email) {
        authService.resendVerificationEmail(email);
        return ResponseEntity.ok(Map.of("message", "Email xác thực đã được gửi lại."));
    }

    // POST /api/auth/refresh-token
    @PostMapping("/refresh-token")
    public ResponseEntity<TokenResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    // POST /api/auth/logout
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestBody Map<String, String> body) {
        authService.logout(body.get("refreshToken"));
        return ResponseEntity.ok(Map.of("message", "Đăng xuất thành công"));
    }

    // POST /api/auth/logout-all
    @PostMapping("/logout-all")
    public ResponseEntity<Map<String, String>> logoutAll(@Valid @RequestBody LogoutAllRequest request) {
        authService.logoutAll(request.getUserId());
        return ResponseEntity.ok(Map.of("message", "Đã đăng xuất khỏi tất cả thiết bị"));
    }

    @PostMapping("/google-login")
    public ResponseEntity<LoginResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
       return ResponseEntity.ok(authService.googleLogin(request));
    }

    // GET /api/auth/profile?userId=xxx
    @GetMapping("/profile")
    public ResponseEntity<UserDetailResponse> getProfile(@RequestParam Integer userId) {
        return ResponseEntity.ok(userService.getUserById(userId));
    }
}