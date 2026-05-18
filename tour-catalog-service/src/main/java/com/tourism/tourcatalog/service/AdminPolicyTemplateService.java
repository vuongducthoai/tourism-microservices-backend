package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.admin.PolicyTemplateRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminPolicyTemplateResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AdminPolicyTemplateService {
    AdminPolicyTemplateResponse createPolicy(PolicyTemplateRequest request);
    AdminPolicyTemplateResponse updatePolicy(Integer id, PolicyTemplateRequest request);
    void deletePolicy(Integer id);
    AdminPolicyTemplateResponse getPolicyById(Integer id);
    Page<AdminPolicyTemplateResponse> getAllPolicies(Pageable pageable);
    Page<AdminPolicyTemplateResponse> searchPolicies(String keyword, Pageable pageable);
    List<AdminPolicyTemplateResponse> getPoliciesByContactId(Integer contactId);
}
