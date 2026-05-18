package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.admin.BranchContactRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminBranchContactResponse;
import com.tourism.tourcatalog.entity.BranchContact;
import com.tourism.tourcatalog.exception.DuplicateResourceException;
import com.tourism.tourcatalog.exception.ResourceInUseException;
import com.tourism.tourcatalog.exception.ResourceNotFoundException;
import com.tourism.tourcatalog.repository.BranchContactRepository;
import com.tourism.tourcatalog.repository.PolicyTemplateRepository;
import com.tourism.tourcatalog.service.BranchContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BranchContactServiceImpl implements BranchContactService {

    private final BranchContactRepository branchContactRepository;
    private final PolicyTemplateRepository policyTemplateRepository;

    @Override
    @Transactional
    public AdminBranchContactResponse createBranch(BranchContactRequest request) {
        if (branchContactRepository.existsByBranchName(request.getBranchName())) {
            throw new DuplicateResourceException("Chi nhánh '" + request.getBranchName() + "' đã tồn tại");
        }

        Boolean isHeadOffice = request.getIsHeadOffice() != null && request.getIsHeadOffice();
        if (isHeadOffice && branchContactRepository.existsByIsHeadOfficeTrue()) {
            throw new DuplicateResourceException("Đã có trụ sở chính. Chỉ được phép có một trụ sở chính");
        }

        BranchContact branch = new BranchContact();
        branch.setBranchName(request.getBranchName());
        branch.setPhone(request.getPhone());
        branch.setEmail(request.getEmail());
        branch.setAddress(request.getAddress());
        branch.setIsHeadOffice(isHeadOffice);

        return mapToResponse(branchContactRepository.save(branch));
    }

    @Override
    @Transactional
    public AdminBranchContactResponse updateBranch(Integer id, BranchContactRequest request) {
        BranchContact branch = findById(id);

        if (!branch.getBranchName().equals(request.getBranchName()) &&
                branchContactRepository.existsByBranchNameAndContactIDNot(request.getBranchName(), id)) {
            throw new DuplicateResourceException("Chi nhánh '" + request.getBranchName() + "' đã tồn tại");
        }

        Boolean isHeadOffice = request.getIsHeadOffice() != null && request.getIsHeadOffice();
        if (isHeadOffice && branchContactRepository.existsByIsHeadOfficeTrueAndContactIDNot(id)) {
            throw new DuplicateResourceException("Đã có trụ sở chính. Chỉ được phép có một trụ sở chính");
        }

        branch.setBranchName(request.getBranchName());
        branch.setPhone(request.getPhone());
        branch.setEmail(request.getEmail());
        branch.setAddress(request.getAddress());
        branch.setIsHeadOffice(isHeadOffice);

        return mapToResponse(branchContactRepository.save(branch));
    }

    @Override
    @Transactional
    public void deleteBranch(Integer id) {
        BranchContact branch = findById(id);

        long policyCount = policyTemplateRepository.countByContact_ContactID(id);
        if (policyCount > 0) {
            throw new ResourceInUseException(
                    String.format("Không thể xóa chi nhánh '%s'. Đang có %d policy template sử dụng chi nhánh này",
                            branch.getBranchName(), policyCount));
        }

        branchContactRepository.delete(branch);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminBranchContactResponse getBranchById(Integer id) {
        return mapToResponse(findById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminBranchContactResponse> getAllBranches(Pageable pageable) {
        return branchContactRepository.findAll(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminBranchContactResponse> searchBranches(String keyword, Pageable pageable) {
        return branchContactRepository.searchBranches(keyword, pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminBranchContactResponse> getAllBranchesSimple() {
        return branchContactRepository.findAllOrderByIsHeadOfficeDesc()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private BranchContact findById(Integer id) {
        return branchContactRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy chi nhánh với ID: " + id));
    }

    private AdminBranchContactResponse mapToResponse(BranchContact b) {
        long policyCount = policyTemplateRepository.countByContact_ContactID(b.getContactID());
        return AdminBranchContactResponse.builder()
                .contactID(b.getContactID())
                .branchName(b.getBranchName())
                .phone(b.getPhone())
                .email(b.getEmail())
                .address(b.getAddress())
                .isHeadOffice(b.getIsHeadOffice())
                .policyCount((int) policyCount)
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }
}
