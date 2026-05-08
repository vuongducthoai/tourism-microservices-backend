package com.tourism.iam.converter;

import com.tourism.iam.dto.request.UserStatusEventDTO;
import com.tourism.iam.dto.response.UserAdminResponse;
import com.tourism.iam.dto.response.UserDetailResponse;
import com.tourism.iam.entity.User;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

/**
 * Centralised Entity ↔ DTO conversions for User.
 *
 * Fields with the same name and type are mapped automatically by ModelMapper
 * (STRICT strategy). Fields that differ (e.g. role: enum → String) are mapped
 * manually after the auto-mapping.
 */
@Component
@RequiredArgsConstructor
public class UserConverter {

    private final ModelMapper modelMapper;

    /**
     * User → UserDetailResponse
     * Automatic: userID, fullName, phone, dateOfBirth, email, coinBalance, avatar, status.
     * Manual   : role (Role enum → String).
     */
    public UserDetailResponse toDetailResponse(User user) {
        UserDetailResponse dto = modelMapper.map(user, UserDetailResponse.class);
        dto.setRole(user.getRole() != null ? user.getRole().name() : null);
        return dto;
    }

    /**
     * User → UserAdminResponse
     * Automatic: userID, fullName, phone, dateOfBirth, email, coinBalance, avatar, status, lastActiveAt.
     * Manual   : role (Role enum → String).
     * Note     : activityStatus must be set by the caller after computing it.
     */
    public UserAdminResponse toAdminResponse(User user) {
        UserAdminResponse dto = modelMapper.map(user, UserAdminResponse.class);
        dto.setRole(user.getRole() != null ? user.getRole().name() : null);
        return dto;
    }

    /**
     * UserAdminResponse → UserStatusEventDTO (payload sent to notification-service).
     * Automatic: userID, fullName, phone, email, avatar, coinBalance, dateOfBirth, status,
     *            role, lastActiveAt, activityStatus.
     * Manual   : reason (not part of UserAdminResponse — supplied by caller).
     */
    public UserStatusEventDTO toStatusEventDTO(UserAdminResponse source, String reason) {
        UserStatusEventDTO dto = modelMapper.map(source, UserStatusEventDTO.class);
        dto.setReason(reason);
        return dto;
    }
}
