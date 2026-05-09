package com.tourism.iam.controller;

import com.tourism.iam.dto.request.UserSearchRequest;
import com.tourism.iam.dto.request.UserStatusUpdateRequest;
import com.tourism.iam.dto.request.UserUpdateRequest;
import com.tourism.iam.dto.response.UserAdminResponse;
import com.tourism.iam.dto.response.UserDetailResponse;
import com.tourism.iam.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Users", description = "Quản lý người dùng — profile, ảnh đại diện, coin và admin user management")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Lấy thông tin người dùng theo ID", description = "Trả về đầy đủ profile: tên, email, phone, ngày sinh, avatar, số dư coin, role")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thành công"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy user")
    })
    @GetMapping("/{userID}")
    public ResponseEntity<UserDetailResponse> getUserById(@PathVariable Integer userID) {
        return ResponseEntity.ok(userService.getUserById(userID));
    }

    @Operation(summary = "Cập nhật profile người dùng", description = "Chấp nhận multipart/form-data. Có thể cập nhật: fullName, phone, dateOfBirth, avatar (file ảnh)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cập nhật thành công"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy user")
    })
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

    @Operation(summary = "[Internal] Cộng coin cho người dùng", description = "Endpoint nội bộ — booking-service gọi qua Feign sau khi booking hoàn thành")
    @ApiResponse(responseCode = "200", description = "Cộng coin thành công")
    @PostMapping("/{userID}/coins")
    public ResponseEntity<Void> addCoins(
            @PathVariable Integer userID,
            @RequestParam BigDecimal amount
    ) {
        userService.addCoins(userID, amount);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Admin] Tìm kiếm người dùng", description = "Tìm kiếm CUSTOMER theo fullName / phone / email với phân trang. Kết quả sắp xếp: Online → Away → Offline")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Danh sách user phân trang")
    })
    @PostMapping("/admin/search")
    public ResponseEntity<Page<UserAdminResponse>> searchUsers(
            @RequestBody UserSearchRequest searchDTO,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(userService.searchUsers(searchDTO, pageable));
    }

    @Operation(summary = "[Admin] Khoá / mở khoá tài khoản", description = "Thay đổi trạng thái tài khoản. Side effects: gửi email thông báo + push WebSocket đến /topic/admin/users")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cập nhật thành công"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy user")
    })
    @PostMapping("/admin/update-status")
    public ResponseEntity<UserAdminResponse> updateUserStatus(
            @RequestBody UserStatusUpdateRequest requestDTO
    ) {
        return ResponseEntity.ok(userService.updateUserStatus(requestDTO));
    }
}
