package com.tourism.iam.service;



import com.tourism.iam.dto.request.GoogleLoginRequest;
import com.tourism.iam.dto.request.LoginRequest;
import com.tourism.iam.dto.request.RefreshTokenRequest;
import com.tourism.iam.dto.request.RegisterRequest;
import com.tourism.iam.dto.response.LoginResponse;
import com.tourism.iam.dto.response.TokenResponse;

public interface AuthService {
      LoginResponse login(LoginRequest request);
      void register(RegisterRequest request);
      void verifyEmail(String token);
      void verifyOtp(String email, String otp);
      void resendVerificationEmail(String email);
      void forgotPassword(String email);
      void resetPassword(String email, String otp, String newPassword);
      TokenResponse refreshToken(RefreshTokenRequest request);
      void logout(String refreshToken);
      void logoutAll(Integer userId);
      LoginResponse googleLogin(GoogleLoginRequest request);
}
