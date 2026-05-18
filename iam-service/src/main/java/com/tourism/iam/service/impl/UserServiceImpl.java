package com.tourism.iam.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.tourism.iam.converter.UserConverter;
import com.tourism.iam.dto.request.UserSearchRequest;
import com.tourism.iam.dto.request.UserStatusUpdateRequest;
import com.tourism.iam.dto.request.UserUpdateRequest;
import com.tourism.iam.dto.response.UserAdminResponse;
import com.tourism.iam.dto.response.UserDetailResponse;
import com.tourism.iam.entity.User;
import com.tourism.iam.feign.NotificationFeignClient;
import com.tourism.iam.repository.UserRepository;
import com.tourism.iam.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository          userRepository;
    private final Cloudinary              cloudinary;
    private final UserConverter           userConverter;
    private final NotificationFeignClient notificationFeignClient;

    @Override
    @Transactional(readOnly = true)
    public UserDetailResponse getUserById(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        return userConverter.toDetailResponse(user);
    }

    @Override
    @Transactional
    public UserDetailResponse updateUser(Integer userId, UserUpdateRequest request, MultipartFile avatarFile)
            throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            String phone = request.getPhone().trim();
            if (!phone.matches("^0\\d{9,10}$")) {
                throw new IllegalArgumentException("Phone must be 10-11 digits starting with 0");
            }
            if (userRepository.existsByPhoneAndUserIDNot(phone, userId)) {
                throw new IllegalArgumentException("Phone number already in use");
            }
            user.setPhone(phone);
        }

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }

        if (request.getDateOfBirth() != null && !request.getDateOfBirth().isBlank()) {
            user.setDateOfBirth(LocalDate.parse(request.getDateOfBirth(), DateTimeFormatter.ISO_LOCAL_DATE));
        }

        if (avatarFile != null && !avatarFile.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().upload(
                    avatarFile.getBytes(),
                    ObjectUtils.asMap("folder", "tourism_avatars")
            );
            user.setAvatar((String) result.get("secure_url"));
        }

        return userConverter.toDetailResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public void addCoins(Integer userId, java.math.BigDecimal amount) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        java.math.BigDecimal current = user.getCoinBalance() != null
                ? user.getCoinBalance()
                : java.math.BigDecimal.ZERO;
        user.setCoinBalance(current.add(amount));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void deductCoins(Integer userId, java.math.BigDecimal amount) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        java.math.BigDecimal current = user.getCoinBalance() != null
                ? user.getCoinBalance()
                : java.math.BigDecimal.ZERO;
        if (current.compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient coin balance for user: " + userId);
        }
        user.setCoinBalance(current.subtract(amount));
        userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserAdminResponse> searchUsers(UserSearchRequest searchDTO, Pageable pageable) {
        log.info("Searching users with filters: {}", searchDTO);

        Page<User> allUsersPage = userRepository.searchUsers(searchDTO, Pageable.unpaged());
        log.info("Found {} users", allUsersPage.getTotalElements());

        LocalDateTime now               = LocalDateTime.now();
        LocalDateTime fiveMinutesAgo   = now.minusMinutes(5);
        LocalDateTime thirtyMinutesAgo = now.minusMinutes(30);

        List<UserAdminResponse> allDtoList = allUsersPage.getContent().stream()
                .map(user -> {
                    UserAdminResponse dto = userConverter.toAdminResponse(user);
                    dto.setActivityStatus(computeActivityStatus(user.getLastActiveAt(),
                            fiveMinutesAgo, thirtyMinutesAgo));
                    return dto;
                })
                .collect(Collectors.toList());

        allDtoList.sort(Comparator
                .comparingInt((UserAdminResponse dto) -> getActivityPriority(dto.getActivityStatus()))
                .thenComparing(UserAdminResponse::getLastActiveAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
        );

        int start    = (int) pageable.getOffset();
        int end      = Math.min(start + pageable.getPageSize(), allDtoList.size());
        List<UserAdminResponse> pagedList = allDtoList.subList(start, end);

        log.info("Returning page {} with {} users", pageable.getPageNumber(), pagedList.size());
        return new PageImpl<>(pagedList, pageable, allDtoList.size());
    }

    @Override
    @Transactional
    public UserAdminResponse updateUserStatus(UserStatusUpdateRequest requestDTO) {
        User user = userRepository.findById(requestDTO.getUserID())
                .orElseThrow(() -> new RuntimeException("User not found: " + requestDTO.getUserID()));

        user.setStatus(requestDTO.getStatus());
        User updatedUser = userRepository.save(user);

        LocalDateTime now = LocalDateTime.now();
        UserAdminResponse responseDTO = userConverter.toAdminResponse(updatedUser);
        responseDTO.setActivityStatus(
                computeActivityStatus(updatedUser.getLastActiveAt(),
                        now.minusMinutes(5), now.minusMinutes(30)));

        // Delegate both email sending and WebSocket push to notification-service
        try {
            notificationFeignClient.notifyUserStatusUpdated(
                    userConverter.toStatusEventDTO(responseDTO, requestDTO.getReason()));
            log.info("Forwarded user-status-updated to notification-service for userId={}",
                    requestDTO.getUserID());
        } catch (Exception e) {
            log.error("Failed to notify notification-service for userId={}: {}",
                    requestDTO.getUserID(), e.getMessage());
        }

        return responseDTO;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String computeActivityStatus(LocalDateTime lastActiveAt,
                                          LocalDateTime fiveMinutesAgo,
                                          LocalDateTime thirtyMinutesAgo) {
        if (lastActiveAt == null)                    return "Offline";
        if (lastActiveAt.isAfter(fiveMinutesAgo))   return "Online";
        if (lastActiveAt.isAfter(thirtyMinutesAgo)) return "Away";
        return "Offline";
    }

    private int getActivityPriority(String activityStatus) {
        if (activityStatus == null) return 4;
        return switch (activityStatus) {
            case "Online"  -> 1;
            case "Away"    -> 2;
            case "Offline" -> 3;
            default        -> 4;
        };
    }
}