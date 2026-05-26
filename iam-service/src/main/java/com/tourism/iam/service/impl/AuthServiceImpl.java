package com.tourism.iam.service.impl;


import com.tourism.iam.feign.NotificationFeignClient;
import com.tourism.iam.dto.request.GoogleLoginRequest;
import com.tourism.iam.dto.request.LoginRequest;
import com.tourism.iam.dto.request.RefreshTokenRequest;
import com.tourism.iam.dto.request.RegisterRequest;
import com.tourism.iam.dto.request.VerificationEmailRequest;
import com.tourism.iam.dto.response.LoginResponse;
import com.tourism.iam.dto.response.TokenResponse;
import com.tourism.iam.entity.Role;
import com.tourism.iam.entity.User;
import com.tourism.iam.repository.UserRepository;
import com.tourism.iam.service.AuthService;
import com.tourism.iam.service.KeycloakAdminService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final KeycloakAdminService keycloakAdminService;
    private final PasswordEncoder passwordEncoder;
    private final RestTemplate restTemplate;
    private final NotificationFeignClient notificationClient;

    @Value("${keycloak.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    // URL get token from Keycloak
    private String getTokenUrl() {
        return keycloakServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    // URL logout from Keycloak
    private String getLogoutUrl() {
        return keycloakServerUrl + "/realms/" + realm + "/protocol/openid-connect/logout";
    }

@   SuppressWarnings("unchecked")
    private Map<String, Object> getTokenFromKeycloak(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("username", email);
        body.add("password", password);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(getTokenUrl(), entity, Map.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            log.error("Keycloak login failed: {}", e.getMessage());
            throw new RuntimeException("Email hoặc mật khẩu không đúng");
        }
    }


    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        // 1. Find User in iam_db
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found with email: " + request.getEmail()));

        // 2. Check if account is locked
        if(Boolean.FALSE.equals(user.getStatus())){
            throw new RuntimeException("Tài khoản của bạn đã bị khóa");
        }

        // 3. Check if email is verified
        if(Boolean.FALSE.equals(user.getIsEmailVerified())){
            throw new RuntimeException("Vui lòng xác thực email trước khi đăng nhập");
        }

        // 3b. Chặn user Google login bằng password thường
        if (com.tourism.iam.entity.AuthProvider.GOOGLE.equals(user.getAuthProvider())) {
            throw new RuntimeException("Tài khoản này đăng ký qua Google. Vui lòng dùng nút \"Đăng nhập với Google\"");
        }

           // 4. Lazy Migration — If the user is not already in Keycloak, create a new one.
        if (Boolean.FALSE.equals(user.getMigratedToKeycloak())) {
            migrateUserToKeycloak(user, request.getPassword());
        }

        // 5. Call keycloak to get token (password grant)
        Map<String, Object> tokenData;
        try {
            tokenData = getTokenFromKeycloak(request.getEmail(), request.getPassword());
        } catch (HttpClientErrorException e) {
            // Keycloak returned 4xx → wrong credentials, do NOT fallback
            log.error("Keycloak rejected credentials for user {}: {}", request.getEmail(), e.getStatusCode());
            throw new RuntimeException("Email hoặc mật khẩu không đúng");
        } catch (Exception e) {
            // Keycloak is unreachable (connection refused, timeout) → verify password locally then fallback
            log.warn("Keycloak unreachable for user {}, verifying password locally: {}", request.getEmail(), e.getMessage());
            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                throw new RuntimeException("Email hoặc mật khẩu không đúng");
            }
            // Password correct but Keycloak down → issue dev token
            tokenData = new HashMap<>();
            tokenData.put("access_token", "dev-token-" + System.currentTimeMillis() + "-" + user.getUserID());
            tokenData.put("refresh_token", "dev-refresh-" + System.currentTimeMillis() + "-" + user.getUserID());
            tokenData.put("expires_in", 3600);
        }

        // 6. Update lastActiveAt
        user.setLastActiveAt(LocalDateTime.now());
        userRepository.save(user);

        // 7. Build response ()
        return LoginResponse.builder()
                .accessToken((String) tokenData.get("access_token"))
                .refreshToken((String) tokenData.get("refresh_token"))
                .tokenType("Bearer")
                .expiredIn(((Number) tokenData.get("expires_in")).longValue())
                .user(LoginResponse.UserInfo.builder()
                        .userId(user.getUserID())
                        .fullName(user.getFullName())
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .role(user.getRole().name())
                        .provinceName(user.getProvinceName())
                        .districtName(user.getDistrictName())
                        .coinBalance(user.getCoinBalance())
                        .authProvider(user.getAuthProvider() != null ? user.getAuthProvider().name() : "LOCAL")
                        .build())
                .build();
    }

    @Transactional
    private void migrateUserToKeycloak(User user, String plainPassword) {
        // Verify your password with the BCrypt hash in iam_db first
       if(!passwordEncoder.matches(plainPassword,  user.getPassword())){
            throw new RuntimeException("Email hoặc mật khẩu không đúng");
        }

        log.info("Migrating user to Keycloak: email={}", user.getEmail());

        try {
            //Create user in Keycloak with password new
            String keycloakId = keycloakAdminService.createUser(user.getEmail(), plainPassword, user.getFullName(), user.getRole().name(), user.getUserID());

            //Save keycloakId and mark as migrated
            user.setKeycloakId(keycloakId);
            user.setMigratedToKeycloak(true);
            userRepository.save(user);

            log.info("Migration complete: email={}, keycloakId={}", user.getEmail(), keycloakId);
        } catch (Exception e) {
            // If Keycloak migration fails, continue anyway (dev mode)
            log.warn("Keycloak migration failed for user {}: {}", user.getEmail(), e.getMessage());
            user.setMigratedToKeycloak(true);
            userRepository.save(user);
        }
    }

    @Override
    @Transactional
    public void register(RegisterRequest request) {
        // 1. Validate password match
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new RuntimeException("Mật khẩu xác nhận không khớp");
        }

        // 2. Check if the email address already exists.
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email đã được sử dụng");
        }

        // 3. Create user in iam_db (status=false until email verified)
        String otpCode = generateOtp();
        User user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .provinceCode(request.getProvinceCode())
                .provinceName(request.getProvinceName())
                .districtCode(request.getDistrictCode())
                .districtName(request.getDistrictName())
                .role(com.tourism.iam.entity.Role.CUSTOMER)
                .authProvider(com.tourism.iam.entity.AuthProvider.LOCAL)
                .status(false)
                .isEmailVerified(false)
                .migratedToKeycloak(false)
                .otpCode(otpCode)
                .otpExpiry(LocalDateTime.now().plusMinutes(5))
                .otpLastSentAt(LocalDateTime.now())
                .build();

        userRepository.save(user);

        // 4. Create user in Keycloak (disabled — wait for email verification)
        try {
            String keycloakId = keycloakAdminService.createUser(
                    user.getEmail(),
                    request.getPassword(),
                    user.getFullName(),
                    "CUSTOMER",
                    user.getUserID()
            );
            user.setKeycloakId(keycloakId);
            user.setMigratedToKeycloak(true);
            userRepository.save(user);
        } catch (Exception e) {
            log.error("Failed to create Keycloak user during register: {}", e.getMessage());
        }

        // 5. Send OTP email via notification-service
        try {
            notificationClient.sendVerificationEmail(
                VerificationEmailRequest.builder()
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .otpCode(user.getOtpCode())
                    .otpExpiryMinutes(5)
                    .build()
            );
            log.info("OTP email sent to: {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send OTP email for user {}: {}", user.getEmail(), e.getMessage());
        }
    }

    /** Sinh OTP 6 chữ số */
    private String generateOtp() {
        return String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
    }

    @Override
    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(token)
                .orElseThrow(() -> new RuntimeException("Token không hợp lệ"));

        if (user.getVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Token đã hết hạn");
        }

        // Enable in iam_db
        user.setIsEmailVerified(true);
        user.setStatus(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiry(null);
        userRepository.save(user);

        // Enable in Keycloak
        if (user.getKeycloakId() != null) {
            keycloakAdminService.enableUser(user.getKeycloakId());
        }

        log.info("Email verified and account activated: email={}", user.getEmail());
    }

    @Override
    @Transactional
    public void verifyOtp(String email, String otp) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Email không tồn tại"));

        if (Boolean.TRUE.equals(user.getIsEmailVerified())) {
            throw new RuntimeException("Tài khoản đã được xác thực");
        }
        if (user.getOtpCode() == null || user.getOtpExpiry() == null) {
            throw new RuntimeException("OTP chưa được gửi. Vui lòng yêu cầu gửi lại");
        }
        if (user.getOtpExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("OTP đã hết hạn. Vui lòng yêu cầu gửi lại");
        }
        if (!user.getOtpCode().equals(otp)) {
            throw new RuntimeException("Mã OTP không đúng");
        }

        // Verify success
        user.setIsEmailVerified(true);
        user.setStatus(true);
        user.setOtpCode(null);
        user.setOtpExpiry(null);
        userRepository.save(user);

        if (user.getKeycloakId() != null) {
            try { keycloakAdminService.enableUser(user.getKeycloakId()); }
            catch (Exception e) { log.warn("Failed to enable Keycloak user: {}", e.getMessage()); }
        }
        log.info("OTP verified for {}", email);
    }

    @Override
    public void resendVerificationEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Email không tồn tại"));

        if (Boolean.TRUE.equals(user.getIsEmailVerified())) {
            throw new RuntimeException("Email đã được xác thực");
        }

        // Cooldown 60s
        if (user.getOtpLastSentAt() != null) {
            long secondsAgo = java.time.Duration.between(user.getOtpLastSentAt(), LocalDateTime.now()).getSeconds();
            if (secondsAgo < 60) {
                throw new RuntimeException("Vui lòng đợi " + (60 - secondsAgo) + " giây trước khi yêu cầu gửi lại");
            }
        }

        // Sinh OTP mới
        user.setOtpCode(generateOtp());
        user.setOtpExpiry(LocalDateTime.now().plusMinutes(5));
        user.setOtpLastSentAt(LocalDateTime.now());
        userRepository.save(user);

        try {
            notificationClient.sendVerificationEmail(
                VerificationEmailRequest.builder()
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .otpCode(user.getOtpCode())
                    .otpExpiryMinutes(5)
                    .build()
            );
            log.info("Resent OTP to: {}", email);
        } catch (Exception e) {
            log.error("Failed to resend OTP: {}", e.getMessage());
            throw new RuntimeException("Không thể gửi OTP. Vui lòng thử lại sau");
        }
    }

    // ── Forgot password: gửi OTP về email ──────────────────────────────────────
    @Override
    @Transactional
    public void forgotPassword(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Email không tồn tại trong hệ thống"));

        // Chặn user Google (không có password để reset)
        if (com.tourism.iam.entity.AuthProvider.GOOGLE.equals(user.getAuthProvider())) {
            throw new RuntimeException("Tài khoản đăng ký qua Google không thể dùng chức năng này. Vui lòng đăng nhập bằng Google");
        }

        // Cooldown 60s
        if (user.getOtpLastSentAt() != null) {
            long secondsAgo = java.time.Duration.between(user.getOtpLastSentAt(), LocalDateTime.now()).getSeconds();
            if (secondsAgo < 60) {
                throw new RuntimeException("Vui lòng đợi " + (60 - secondsAgo) + " giây trước khi yêu cầu gửi lại");
            }
        }

        // Sinh OTP mới (dùng chung field otpCode/otpExpiry với verify email)
        user.setOtpCode(generateOtp());
        user.setOtpExpiry(LocalDateTime.now().plusMinutes(5));
        user.setOtpLastSentAt(LocalDateTime.now());
        userRepository.save(user);

        try {
            notificationClient.sendVerificationEmail(
                VerificationEmailRequest.builder()
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .otpCode(user.getOtpCode())
                    .otpExpiryMinutes(5)
                    .build()
            );
            log.info("Sent password reset OTP to: {}", email);
        } catch (Exception e) {
            log.error("Failed to send reset OTP: {}", e.getMessage());
            throw new RuntimeException("Không thể gửi OTP. Vui lòng thử lại sau");
        }
    }

    // ── Reset password: verify OTP + đặt password mới ──────────────────────────
    @Override
    @Transactional
    public void resetPassword(String email, String otp, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Email không tồn tại"));

        if (com.tourism.iam.entity.AuthProvider.GOOGLE.equals(user.getAuthProvider())) {
            throw new RuntimeException("Tài khoản Google không hỗ trợ đặt lại mật khẩu");
        }

        if (user.getOtpCode() == null || user.getOtpExpiry() == null) {
            throw new RuntimeException("OTP chưa được gửi. Vui lòng yêu cầu lại");
        }
        if (user.getOtpExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("OTP đã hết hạn. Vui lòng yêu cầu mã mới");
        }
        if (!user.getOtpCode().equals(otp)) {
            throw new RuntimeException("Mã OTP không đúng");
        }

        // Validate password mới (basic)
        if (newPassword == null || newPassword.length() < 8) {
            throw new RuntimeException("Mật khẩu mới phải có ít nhất 8 ký tự");
        }

        // Update password trong iam_db
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setOtpCode(null);
        user.setOtpExpiry(null);
        userRepository.save(user);

        // Update password trong Keycloak (nếu đã sync)
        if (Boolean.TRUE.equals(user.getMigratedToKeycloak()) && user.getKeycloakId() != null) {
            try {
                keycloakAdminService.updatePassword(user.getKeycloakId(), newPassword);
                log.info("Password updated in Keycloak for: {}", email);
            } catch (Exception e) {
                log.warn("Failed to update Keycloak password for {}: {}", email, e.getMessage());
                // Không throw — local password đã update, lần sau migrate lại được
            }
        }

        log.info("Password reset successfully for: {}", email);
    }

    /** @deprecated giữ cho backward compat, không dùng */
    private void legacyResend(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Email không tồn tại"));

        if (Boolean.TRUE.equals(user.getIsEmailVerified())) {
            throw new RuntimeException("Email đã được xác thực");
        }

        // Create a new token
        user.setVerificationToken(UUID.randomUUID().toString());
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));
        userRepository.save(user);

        // If the user has already migrated to Keycloak, use Keycloak to send a verification email.
        if (Boolean.TRUE.equals(user.getMigratedToKeycloak()) && user.getKeycloakId() != null) {
            try {
                keycloakAdminService.sendVerificationEmail(user.getKeycloakId());
                log.info("Resent Keycloak verification email: email={}", email);
            } catch (Exception e) {
                log.warn("Failed to send via Keycloak, using notification-service fallback: {}", e.getMessage());
                sendVerificationViaNotificationService(user);
            }
        } else {
            // Send via notification-service
            sendVerificationViaNotificationService(user);
        }
    }

    private void sendVerificationViaNotificationService(User user) {
        try {
            String verificationUrl = "http://localhost:3000/verify-email?token=" + user.getVerificationToken();
            notificationClient.sendVerificationEmail(
                VerificationEmailRequest.builder()
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .verificationToken(user.getVerificationToken())
                    .verificationUrl(verificationUrl)
                    .build()
            );
            log.info("Sent verification email via notification-service: email={}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send verification email for user {}: {}. Will allow retry.", user.getEmail(), e.getMessage());
            // Don't throw exception - allow the token to be generated for retry
            // User can retry the resend later
        }
    }

    @Override
    public TokenResponse refreshToken(RefreshTokenRequest request) {
        //Call keycloak to refresh token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", request.getRefreshToken());

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(getTokenUrl(), entity, Map.class);
            Map<String, Object> data = response.getBody();

            if (data == null) {
                throw new RuntimeException("Refresh token không hợp lệ hoặc đã hết hạn");
            }

            return TokenResponse.builder()
                    .accessToken((String) data.get("access_token"))
                    .refreshToken((String) data.get("refresh_token"))
                    .tokenType("Bearer")
                    .expiredIn(((Number) data.get("expires_in")).longValue())
                    .build();
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Refresh token không hợp lệ hoặc đã hết hạn");
        }

    }

    @Override
    public void logout(String refreshToken) {
        //Call Keycloak to revoke session
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForEntity(getLogoutUrl(), entity, Void.class);
            log.info("Logout successful");
        } catch (Exception e) {
            log.warn("Keycloak logout failed: {}. Continuing with local logout.", e.getMessage());
        }
    }

    @Override
    public void logoutAll(Integer userId) {
        //Find keycloackId from iam_db and then log out all sessions
        User user = userRepository.findById(userId)
        .orElseThrow(() -> new RuntimeException("User không tồn tại"));

        if (user.getKeycloakId() != null) {
            keycloakAdminService.logoutUser(user.getKeycloakId());
        }
    }

    @Override
    @Transactional
    public LoginResponse googleLogin(GoogleLoginRequest request) {
        // 1. Đổi authorzation code lấy token từ Keycloak
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");   
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("code", request.getCode());
        body.add("redirect_uri", request.getRedirectUri());
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

        Map<String, Object> tokenData;
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(getTokenUrl(), entity, Map.class);
            tokenData = response.getBody();
        } catch (HttpClientErrorException e) {
            log.error("Google login failed: {}", e.getMessage());
            throw new RuntimeException("Google login thất bại");
        }

        // 2. Decode JWT để lấy thông tin user (email, name, sub)
        String accessToken = (String) tokenData.get("access_token");
        Map<String, Object> claims = decodeJwtClaims(accessToken);
        String email = (String) claims.get("email");
        String fullName = (String) claims.get("name");
        String googleSub = (String) claims.get("sub"); // ID gốc từ Keycloak/Google

        // 3. Tìm hoặc tạo user trong iam_db
        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User newUser = User.builder()
                    .email(email)
                    .fullName(fullName != null ? fullName : email)
                    .role(Role.CUSTOMER)
                    .authProvider(com.tourism.iam.entity.AuthProvider.GOOGLE)
                    .providerId(googleSub)
                    .password("")
                    .status(true)
                    .isEmailVerified(true)
                    .migratedToKeycloak(true)
                    .build();
            return userRepository.save(newUser);
        });

        // Backfill cho user cũ chưa có authProvider/providerId
        if (user.getAuthProvider() == null) {
            user.setAuthProvider(com.tourism.iam.entity.AuthProvider.GOOGLE);
        }
        if (user.getProviderId() == null && googleSub != null) {
            user.setProviderId(googleSub);
        }

        if(user.getKeycloakId() == null){
           String keycloadId = keycloakAdminService.findUserIdByEmail(email);
           user.setKeycloakId(keycloadId);
           user.setMigratedToKeycloak(true);
        }

        user.setLastActiveAt(LocalDateTime.now());
        userRepository.save(user);

        // 5. Trả về LoginResponse
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken((String) tokenData.get("refresh_token"))
                .tokenType("Bearer")
                .expiredIn(((Number) tokenData.get("expires_in")).longValue())
                .user(LoginResponse.UserInfo.builder()
                        .userId(user.getUserID())
                        .fullName(user.getFullName())
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .role(user.getRole().name())
                        .provinceName(user.getProvinceName())
                        .districtName(user.getDistrictName())
                        .coinBalance(user.getCoinBalance())
                        .authProvider(user.getAuthProvider() != null ? user.getAuthProvider().name() : "LOCAL")
                        .build())
                .build(); 
    }


   private Map<String, Object> decodeJwtClaims(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(payload, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Không thể decode JWT claims");
        }
    }
}
