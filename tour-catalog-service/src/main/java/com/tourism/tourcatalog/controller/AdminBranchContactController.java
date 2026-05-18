package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.admin.BranchContactRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminBranchContactResponse;
import com.tourism.tourcatalog.service.BranchContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/api/admin/branches")
@RequiredArgsConstructor
@Tag(name = "Admin - Branch Contacts", description = "Quản lý chi nhánh")
public class AdminBranchContactController {

    private final BranchContactService branchContactService;

    @Operation(summary = "Danh sách chi nhánh (phân trang)")
    @GetMapping
    public ResponseEntity<Page<AdminBranchContactResponse>> getAllBranches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "contactID") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(branchContactService.getAllBranches(pageable));
    }

    @Operation(summary = "Tìm kiếm chi nhánh theo keyword")
    @GetMapping("/search")
    public ResponseEntity<Page<AdminBranchContactResponse>> searchBranches(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(branchContactService.searchBranches(keyword, pageable));
    }

    @Operation(summary = "Danh sách chi nhánh đơn giản (không phân trang)")
    @GetMapping("/simple")
    public ResponseEntity<List<AdminBranchContactResponse>> getAllBranchesSimple() {
        return ResponseEntity.ok(branchContactService.getAllBranchesSimple());
    }

    @Operation(summary = "Chi tiết chi nhánh theo ID")
    @GetMapping("/{contactID}")
    public ResponseEntity<AdminBranchContactResponse> getBranchById(@PathVariable Integer contactID) {
        return ResponseEntity.ok(branchContactService.getBranchById(contactID));
    }

    @Operation(summary = "Tạo chi nhánh mới")
    @PostMapping
    public ResponseEntity<AdminBranchContactResponse> createBranch(
            @Valid @RequestBody BranchContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(branchContactService.createBranch(request));
    }

    @Operation(summary = "Cập nhật chi nhánh")
    @PutMapping("/{contactID}")
    public ResponseEntity<AdminBranchContactResponse> updateBranch(
            @PathVariable Integer contactID,
            @Valid @RequestBody BranchContactRequest request) {
        return ResponseEntity.ok(branchContactService.updateBranch(contactID, request));
    }

    @Operation(summary = "Xóa chi nhánh")
    @DeleteMapping("/{contactID}")
    public ResponseEntity<Void> deleteBranch(@PathVariable Integer contactID) {
        branchContactService.deleteBranch(contactID);
        return ResponseEntity.noContent().build();
    }
}
