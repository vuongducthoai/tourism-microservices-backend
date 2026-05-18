package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.admin.BranchContactRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminBranchContactResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface BranchContactService {
    AdminBranchContactResponse createBranch(BranchContactRequest request);
    AdminBranchContactResponse updateBranch(Integer id, BranchContactRequest request);
    void deleteBranch(Integer id);
    AdminBranchContactResponse getBranchById(Integer id);
    Page<AdminBranchContactResponse> getAllBranches(Pageable pageable);
    Page<AdminBranchContactResponse> searchBranches(String keyword, Pageable pageable);
    List<AdminBranchContactResponse> getAllBranchesSimple();
}
