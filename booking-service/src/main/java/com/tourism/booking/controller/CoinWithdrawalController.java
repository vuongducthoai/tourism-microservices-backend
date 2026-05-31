package com.tourism.booking.controller;

import com.tourism.booking.dto.request.CoinWithdrawalRequest;
import com.tourism.booking.dto.request.ConfirmManualPayoutRequest;
import com.tourism.booking.dto.response.CoinWithdrawalResponse;
import com.tourism.booking.service.CoinWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coin-withdrawals")
@RequiredArgsConstructor
@Tag(name = "Coin Withdrawals", description = "Rut diem tu dong ve tai khoan ngan hang ca nhan")
public class CoinWithdrawalController {

    private final CoinWithdrawalService coinWithdrawalService;

    @Operation(summary = "Tao yeu cau rut diem", description = "Tru diem ngay va dua lenh vao outbox xu ly tu dong")
    @ApiResponse(responseCode = "200", description = "Tao yeu cau rut diem thanh cong")
    @PostMapping
    public ResponseEntity<CoinWithdrawalResponse> create(@Valid @RequestBody CoinWithdrawalRequest request) {
        return ResponseEntity.ok(coinWithdrawalService.createWithdrawal(request));
    }

    @Operation(summary = "Lich su rut diem cua user")
    @GetMapping("/my-history")
    public ResponseEntity<List<CoinWithdrawalResponse>> myHistory(@RequestParam Integer userId) {
        return ResponseEntity.ok(coinWithdrawalService.getUserHistory(userId));
    }

    @Operation(summary = "[Admin] Danh sach rut diem")
    @GetMapping("/admin/search")
    public ResponseEntity<Page<CoinWithdrawalResponse>> searchAdmin(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) String errorSource,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("ASC") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(coinWithdrawalService.searchAdmin(status, userId, errorSource, pageable));
    }

    @Operation(summary = "[Admin] Chi tiet giao dich rut diem")
    @GetMapping("/admin/{id}")
    public ResponseEntity<CoinWithdrawalResponse> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(coinWithdrawalService.getById(id));
    }

    @Operation(summary = "[Admin] Retry giao dich FAILED")
    @PostMapping("/admin/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        coinWithdrawalService.retry(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "[Admin] Xac nhan chuyen khoan thu cong cho giao dich MANUAL",
               description = "Admin quet QR, chuyen khoan xong roi bam xac nhan. He thong danh dau COMPLETED va gui thong bao cho user.")
    @PostMapping("/admin/{id}/confirm-manual")
    public ResponseEntity<CoinWithdrawalResponse> confirmManualPayout(
            @PathVariable Long id,
            @RequestBody(required = false) ConfirmManualPayoutRequest request) {
        if (request == null) request = new ConfirmManualPayoutRequest();
        return ResponseEntity.ok(coinWithdrawalService.confirmManualPayout(id, request));
    }
}