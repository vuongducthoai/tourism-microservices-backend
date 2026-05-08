package com.tourism.iam.controller;

import com.tourism.iam.dto.request.UserSearchRequest;
import com.tourism.iam.dto.request.UserStatusUpdateRequest;
import com.tourism.iam.dto.request.UserUpdateRequest;
import com.tourism.iam.dto.response.UserAdminResponse;
import com.tourism.iam.dto.response.UserDetailResponse;
import com.tourism.iam.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * GET /api/users/{userID}
     * Returns full profile of a user by ID.
     */
    @GetMapping("/{userID}")
    public ResponseEntity<UserDetailResponse> getUserById(@PathVariable Integer userID) {
        return ResponseEntity.ok(userService.getUserById(userID));
    }

    /**
     * PUT /api/users/{userID}
     * Update user profile. Accepts multipart/form-data.
     */
    @PutMapping(value = "/{userID}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserDetailResponse> updateUser(
            @PathVariable Integer userID,
            @RequestPart(value = "fullName",    required = false) String fullName,
            @RequestPart(value = "phone",       required = false) String phone,
            @RequestPart(value = "dateOfBirth", required = false) String dateOfBirth,
            @RequestPart(value = "avatar",      required = false) MultipartFile avatar
    ) throws IOException {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setFullName(fullName);
        request.setPhone(phone);
        request.setDateOfBirth(dateOfBirth);

        return ResponseEntity.ok(userService.updateUser(userID, request, avatar));
    }

    /**
     * POST /api/users/{userID}/coins?amount=X
     * Add coins to user's balance. Called by booking-service via Feign.
     */
    @PostMapping("/{userID}/coins")
    public ResponseEntity<Void> addCoins(
            @PathVariable Integer userID,
            @RequestParam BigDecimal amount
    ) {
        userService.addCoins(userID, amount);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/users/admin/search?page=0&size=6
     * Search users by fullName / phone / email.
     * Returns CUSTOMER accounts only, sorted Online → Away → Offline.
     * Body: { fullName, phone, email } (all nullable)
     */
    @PostMapping("/admin/search")
    public ResponseEntity<Page<UserAdminResponse>> searchUsers(
            @RequestBody UserSearchRequest searchDTO,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(userService.searchUsers(searchDTO, pageable));
    }

    /**
     * POST /api/users/admin/update-status
     * Lock or unlock a user account.
     * Body: { userID, status (true=active/false=locked), reason }
     * Side effects: email notification + WebSocket push to /topic/admin/users
     */
    @PostMapping("/admin/update-status")
    public ResponseEntity<UserAdminResponse> updateUserStatus(
            @RequestBody UserStatusUpdateRequest requestDTO
    ) {
        return ResponseEntity.ok(userService.updateUserStatus(requestDTO));
    }
}
