package com.tourism.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler quy đổi Quỹ Xanh → số cây (PLAN_GREEN_FUND_TRONG_CAY §3).
 * Mặc định mỗi giờ; chỉnh qua greenfund.convert-interval-ms.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GreenFundConverter {

    private final GreenFundService greenFundService;

    @Scheduled(fixedDelayString = "${greenfund.convert-interval-ms:3600000}", initialDelay = 60_000)
    public void convert() {
        try {
            greenFundService.convertPendingFund();
        } catch (Exception e) {
            log.warn("Green fund conversion failed: {}", e.getMessage());
        }
    }
}
