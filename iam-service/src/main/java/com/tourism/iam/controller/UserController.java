package com.tourism.iam.controller;

import com.tourism.iam.dto.request.UserUpdateRequest;
import com.tourism.iam.dto.response.UserDetailResponse;
import com.tourism.iam.service.UserService;
import lombok.RequiredArgsConstructor;
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
     * Response: UserDetailResponse {userID, fullName, phone, dateOfBirth, email, coinBalance, avatar, status, role}
     */
    @GetMapping("/{userID}")
    public ResponseEntity<UserDetailResponse> getUserById(@PathVariable Integer userID) {
        return ResponseEntity.ok(userService.getUserById(userID));
    }

    /**
     * PUT /api/users/{userID}
     * Update user profile. Accepts multipart/form-data.
     * Fields: fullName (text), phone (text), dateOfBirth (text, yyyy-MM-dd), avatar (file, optional)
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
     * PATCH /api/users/{userID}/coins?amount=X
     * Add coins to user's balance. Called internally by booking-service via Feign after coin-refund cancellation.
     */
    @PatchMapping("/{userID}/coins")
    public ResponseEntity<Void> addCoins(
            @PathVariable Integer userID,
            @RequestParam BigDecimal amount
    ) {
        userService.addCoins(userID, amount);
        return ResponseEntity.ok().build();
    }
}
