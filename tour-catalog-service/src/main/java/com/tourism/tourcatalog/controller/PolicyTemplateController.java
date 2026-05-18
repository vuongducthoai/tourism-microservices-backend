package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.admin.PolicyTemplateRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminPolicyTemplateResponse;
import com.tourism.tourcatalog.repository.PolicyTemplateRepository;
import com.tourism.tourcatalog.service.AdminPolicyTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Admin - Policy Templates", description = "Quản lý policy template")
public class PolicyTemplateController {

    private final PolicyTemplateRepository policyTemplateRepository;
    private final AdminPolicyTemplateService adminPolicyTemplateService;

    // --- Endpoint cũ giữ nguyên để không breaking change với departure dropdown ---
    @Operation(summary = "Danh sách policy template đơn giản (dùng cho dropdown)")
    @GetMapping("/admin/policy-templates/simple")
    public ResponseEntity<Page<PolicyTemplateDTO>> getAllPolicyTemplatesSimple(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1000") int size) {
        return ResponseEntity.ok(
                policyTemplateRepository.findAllByIsDeletedFalse(PageRequest.of(page, size))
                        .map(pt -> new PolicyTemplateDTO(pt.getPolicyTemplateID(), pt.getTemplateName()))
        );
    }

    // --- Admin CRUD endpoints ---

    @Operation(summary = "Danh sách policy template đầy đủ (phân trang)")
    @GetMapping("/admin/policy-templates")
    public ResponseEntity<Page<AdminPolicyTemplateResponse>> getAllPolicies(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "policyTemplateID") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(adminPolicyTemplateService.getAllPolicies(pageable));
    }

    @Operation(summary = "Tìm kiếm policy template theo keyword")
    @GetMapping("/admin/policy-templates/search")
    public ResponseEntity<Page<AdminPolicyTemplateResponse>> searchPolicies(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(adminPolicyTemplateService.searchPolicies(keyword, pageable));
    }

    @Operation(summary = "Danh sách policy theo chi nhánh")
    @GetMapping("/admin/policy-templates/by-contact/{contactId}")
    public ResponseEntity<List<AdminPolicyTemplateResponse>> getPoliciesByContact(
            @PathVariable Integer contactId) {
        return ResponseEntity.ok(adminPolicyTemplateService.getPoliciesByContactId(contactId));
    }

    @Operation(summary = "Chi tiết policy template theo ID")
    @GetMapping("/admin/policy-templates/{policyTemplateID}")
    public ResponseEntity<AdminPolicyTemplateResponse> getPolicyById(
            @PathVariable Integer policyTemplateID) {
        return ResponseEntity.ok(adminPolicyTemplateService.getPolicyById(policyTemplateID));
    }

    @Operation(summary = "Tạo policy template mới")
    @PostMapping("/admin/policy-templates")
    public ResponseEntity<AdminPolicyTemplateResponse> createPolicy(
            @Valid @RequestBody PolicyTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminPolicyTemplateService.createPolicy(request));
    }

    @Operation(summary = "Cập nhật policy template")
    @PutMapping("/admin/policy-templates/{policyTemplateID}")
    public ResponseEntity<AdminPolicyTemplateResponse> updatePolicy(
            @PathVariable Integer policyTemplateID,
            @Valid @RequestBody PolicyTemplateRequest request) {
        return ResponseEntity.ok(adminPolicyTemplateService.updatePolicy(policyTemplateID, request));
    }

    @Operation(summary = "Xóa policy template")
    @DeleteMapping("/admin/policy-templates/{policyTemplateID}")
    public ResponseEntity<Void> deletePolicy(@PathVariable Integer policyTemplateID) {
        adminPolicyTemplateService.deletePolicy(policyTemplateID);
        return ResponseEntity.noContent().build();
    }

    // --- Dropdown DTO (dùng nội bộ) ---
    @Data
    @AllArgsConstructor
    public static class PolicyTemplateDTO {
        private Integer policyTemplateID;
        private String templateName;
    }
}
