package com.tourism.booking.service;

import com.tourism.booking.dto.request.CoinWithdrawalRequest;
import com.tourism.booking.dto.request.ConfirmManualPayoutRequest;
import com.tourism.booking.dto.response.CoinWithdrawalResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface CoinWithdrawalService {
    CoinWithdrawalResponse createWithdrawal(CoinWithdrawalRequest request);

    List<CoinWithdrawalResponse> getUserHistory(Integer userId);

    Page<CoinWithdrawalResponse> searchAdmin(String status, Integer userId, String errorSource, Pageable pageable);

    CoinWithdrawalResponse getById(Long id);

    void retry(Long id);

    /** Admin xác nhận đã chuyển khoản thủ công cho giao dịch MANUAL. */
    CoinWithdrawalResponse confirmManualPayout(Long id, ConfirmManualPayoutRequest request);
}