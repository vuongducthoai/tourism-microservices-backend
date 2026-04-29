package com.tourism.iam.service;

import com.tourism.iam.dto.request.UserUpdateRequest;
import com.tourism.iam.dto.response.UserDetailResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface UserService {

    UserDetailResponse getUserById(Integer userId);

    UserDetailResponse updateUser(Integer userId, UserUpdateRequest request, MultipartFile avatarFile) throws IOException;

    /**
     * Add coins to user's coin balance (called by booking-service via Feign after coin-refund cancellation).
     * @param userId target user
     * @param amount coin amount to add (already converted: VND / 1000)
     */
    void addCoins(Integer userId, java.math.BigDecimal amount);
}
