package com.tourism.iam.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.tourism.iam.dto.request.UserUpdateRequest;
import com.tourism.iam.dto.response.UserDetailResponse;
import com.tourism.iam.entity.User;
import com.tourism.iam.repository.UserRepository;
import com.tourism.iam.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final Cloudinary     cloudinary;

    @Override
    @Transactional(readOnly = true)
    public UserDetailResponse getUserById(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserDetailResponse updateUser(Integer userId, UserUpdateRequest request, MultipartFile avatarFile)
            throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Validate phone format and uniqueness
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

        // Upload avatar to Cloudinary if provided
        if (avatarFile != null && !avatarFile.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().upload(
                    avatarFile.getBytes(),
                    ObjectUtils.asMap("folder", "tourism_avatars")
            );
            user.setAvatar((String) result.get("secure_url"));
        }

        return toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public void addCoins(Integer userId, java.math.BigDecimal amount) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        java.math.BigDecimal current = user.getCoinBalance() != null ? user.getCoinBalance() : java.math.BigDecimal.ZERO;
        user.setCoinBalance(current.add(amount));
        userRepository.save(user);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private UserDetailResponse toResponse(User user) {
        UserDetailResponse res = new UserDetailResponse();
        res.setUserID(user.getUserID());
        res.setFullName(user.getFullName());
        res.setPhone(user.getPhone());
        res.setDateOfBirth(user.getDateOfBirth());
        res.setEmail(user.getEmail());
        res.setCoinBalance(user.getCoinBalance());
        res.setAvatar(user.getAvatar());
        res.setStatus(user.getStatus());
        res.setRole(user.getRole() != null ? user.getRole().name() : null);
        return res;
    }
}
