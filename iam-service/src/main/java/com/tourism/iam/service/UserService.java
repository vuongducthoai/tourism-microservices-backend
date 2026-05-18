package com.tourism.iam.service;

import com.tourism.iam.dto.request.UserSearchRequest;
import com.tourism.iam.dto.request.UserStatusUpdateRequest;
import com.tourism.iam.dto.request.UserUpdateRequest;
import com.tourism.iam.dto.response.UserAdminResponse;
import com.tourism.iam.dto.response.UserDetailResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface UserService {

    UserDetailResponse getUserById(Integer userId);

    UserDetailResponse updateUser(Integer userId, UserUpdateRequest request, MultipartFile avatarFile) throws IOException;

    /**
     * Add coins to user's coin balance (called by booking-service via Feign after coin-refund cancellation).
     */
    void addCoins(Integer userId, java.math.BigDecimal amount);

    /**
     * Deduct coins from user's coin balance (called by booking-service via Feign when points are redeemed).
     */
    void deductCoins(Integer userId, java.math.BigDecimal amount);

    /**
     * Admin search users by fullName / phone / email.
     * Returns only CUSTOMER role, sorted Online → Away → Offline.
     */
    Page<UserAdminResponse> searchUsers(UserSearchRequest searchDTO, Pageable pageable);

    /**
     * Admin lock / unlock user account.
     * Sends email notification + pushes WebSocket to /topic/admin/users.
     */
    UserAdminResponse updateUserStatus(UserStatusUpdateRequest requestDTO);
}
