package com.tourism.booking.controller;

import com.tourism.booking.entity.GreenFundContribution;
import com.tourism.booking.entity.GreenFundLedger;
import com.tourism.booking.entity.TreePlantingBatch;
import com.tourism.booking.repository.GreenFundContributionRepository;
import com.tourism.booking.repository.GreenFundLedgerRepository;
import com.tourism.booking.repository.TreePlantingBatchRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Admin Quỹ Xanh (PLAN_GREEN_FUND_TRONG_CAY GĐ4):
 * nhập đợt trồng cây thật (minh bạch) + audit toàn bộ đóng góp & ledger.
 * Theo pattern admin hiện có của booking-service (/api/admin/**, gateway xác thực).
 */
@RestController
@RequestMapping("/api/admin/green-fund")
@RequiredArgsConstructor
public class AdminGreenFundController {

    private final TreePlantingBatchRepository batchRepository;
    private final GreenFundContributionRepository contributionRepository;
    private final GreenFundLedgerRepository ledgerRepository;

    // ── Đợt trồng cây thật ──

    @GetMapping("/batches")
    public ResponseEntity<?> getBatches() {
        return ResponseEntity.ok(Map.of("success", true,
                "data", batchRepository.findAllByOrderByPlantedDateDesc()));
    }

    @PostMapping("/batches")
    public ResponseEntity<?> createBatch(@RequestBody BatchRequest request) {
        validate(request);
        TreePlantingBatch batch = batchRepository.save(TreePlantingBatch.builder()
                .location(request.getLocation())
                .plantedDate(request.getPlantedDate())
                .treeCount(request.getTreeCount())
                .imageUrl(request.getImageUrl())
                .note(request.getNote())
                .build());
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã thêm đợt trồng cây", "data", batch));
    }

    @PutMapping("/batches/{id}")
    public ResponseEntity<?> updateBatch(@PathVariable Long id, @RequestBody BatchRequest request) {
        validate(request);
        TreePlantingBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đợt trồng: " + id));
        batch.setLocation(request.getLocation());
        batch.setPlantedDate(request.getPlantedDate());
        batch.setTreeCount(request.getTreeCount());
        batch.setImageUrl(request.getImageUrl());
        batch.setNote(request.getNote());
        batchRepository.save(batch);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã cập nhật đợt trồng cây", "data", batch));
    }

    @DeleteMapping("/batches/{id}")
    public ResponseEntity<?> deleteBatch(@PathVariable Long id) {
        batchRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa đợt trồng cây"));
    }

    // ── Audit ──

    /** Toàn bộ đóng góp, lọc theo nguồn hoặc user, phân trang — đối soát quỹ. */
    @GetMapping("/contributions")
    public ResponseEntity<?> getContributions(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<GreenFundContribution> p;
        if (userId != null) {
            p = contributionRepository.findByUserIdOrderByCreatedAtDesc(userId, pr);
        } else if (source != null && !source.isBlank()) {
            GreenFundContribution.Source s;
            try {
                s = GreenFundContribution.Source.valueOf(source.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Nguồn không hợp lệ: " + source);
            }
            p = contributionRepository.findBySourceOrderByCreatedAtDesc(s, pr);
        } else {
            p = contributionRepository.findAllByOrderByCreatedAtDesc(pr);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "items", p.getContent(),
                "page", p.getNumber(),
                "totalPages", p.getTotalPages(),
                "totalElements", p.getTotalElements()
        )));
    }

    /** Ledger thô — đối soát: totalFundRaised phải = SUM(contributions.amountVnd). */
    @GetMapping("/ledger")
    public ResponseEntity<?> getLedger() {
        return ResponseEntity.ok(Map.of("success", true,
                "data", ledgerRepository.findById(GreenFundLedger.SINGLETON_ID).orElse(null)));
    }

    private void validate(BatchRequest request) {
        if (request.getLocation() == null || request.getLocation().isBlank()) {
            throw new RuntimeException("Thiếu địa điểm trồng cây");
        }
        if (request.getTreeCount() == null || request.getTreeCount() <= 0) {
            throw new RuntimeException("Số cây phải > 0");
        }
    }

    @Data
    public static class BatchRequest {
        private String location;
        private LocalDate plantedDate;
        private Integer treeCount;
        private String imageUrl;
        private String note;
    }
}
