package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.admin.PolicyTemplateRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminPolicyTemplateResponse;
import com.tourism.tourcatalog.entity.BranchContact;
import com.tourism.tourcatalog.entity.PolicyTemplate;
import com.tourism.tourcatalog.exception.DuplicateResourceException;
import com.tourism.tourcatalog.exception.ResourceNotFoundException;
import com.tourism.tourcatalog.repository.BranchContactRepository;
import com.tourism.tourcatalog.repository.PolicyTemplateRepository;
import com.tourism.tourcatalog.service.AdminPolicyTemplateService;
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
public class AdminPolicyTemplateServiceImpl implements AdminPolicyTemplateService {

    private final PolicyTemplateRepository policyTemplateRepository;
    private final BranchContactRepository branchContactRepository;

    @Override
    @Transactional
    public AdminPolicyTemplateResponse createPolicy(PolicyTemplateRequest request) {
        if (policyTemplateRepository.existsByTemplateName(request.getTemplateName())) {
            throw new DuplicateResourceException("Template '" + request.getTemplateName() + "' đã tồn tại");
        }

        BranchContact contact = findContactById(request.getContactId());

        PolicyTemplate policy = new PolicyTemplate();
        mapRequestToEntity(request, policy);
        policy.setContact(contact);
        policy.setStatus(true);

        return mapToResponse(policyTemplateRepository.save(policy));
    }

    @Override
    @Transactional
    public AdminPolicyTemplateResponse updatePolicy(Integer id, PolicyTemplateRequest request) {
        PolicyTemplate policy = findById(id);

        if (!policy.getTemplateName().equals(request.getTemplateName()) &&
                policyTemplateRepository.existsByTemplateNameAndPolicyTemplateIDNot(request.getTemplateName(), id)) {
            throw new DuplicateResourceException("Template '" + request.getTemplateName() + "' đã tồn tại");
        }

        BranchContact contact = findContactById(request.getContactId());
        mapRequestToEntity(request, policy);
        policy.setContact(contact);

        return mapToResponse(policyTemplateRepository.save(policy));
    }

    @Override
    @Transactional
    public void deletePolicy(Integer id) {
        PolicyTemplate policy = findById(id);
        policyTemplateRepository.delete(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPolicyTemplateResponse getPolicyById(Integer id) {
        return mapToResponse(findById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminPolicyTemplateResponse> getAllPolicies(Pageable pageable) {
        return policyTemplateRepository.findAll(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminPolicyTemplateResponse> searchPolicies(String keyword, Pageable pageable) {
        return policyTemplateRepository.searchPolicies(keyword, pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminPolicyTemplateResponse> getPoliciesByContactId(Integer contactId) {
        return policyTemplateRepository.findByContact_ContactID(contactId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private PolicyTemplate findById(Integer id) {
        return policyTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy policy template với ID: " + id));
    }

    private BranchContact findContactById(Integer contactId) {
        return branchContactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy chi nhánh với ID: " + contactId));
    }

    private void mapRequestToEntity(PolicyTemplateRequest request, PolicyTemplate policy) {
        policy.setTemplateName(request.getTemplateName());
        policy.setTourPriceIncludes(request.getTourPriceIncludes());
        policy.setTourPriceExcludes(request.getTourPriceExcludes());
        policy.setChildPricingNotes(request.getChildPricingNotes());
        policy.setPaymentConditions(request.getPaymentConditions());
        policy.setRegistrationConditions(request.getRegistrationConditions());
        policy.setRegularDayCancellationRules(request.getRegularDayCancellationRules());
        policy.setHolidayCancellationRules(request.getHolidayCancellationRules());
        policy.setForceMajeureRules(request.getForceMajeureRules());
        policy.setPackingList(request.getPackingList());
    }

    private AdminPolicyTemplateResponse mapToResponse(PolicyTemplate p) {
        AdminPolicyTemplateResponse.BranchInfo branchInfo = null;
        if (p.getContact() != null) {
            branchInfo = AdminPolicyTemplateResponse.BranchInfo.builder()
                    .contactID(p.getContact().getContactID())
                    .branchName(p.getContact().getBranchName())
                    .phone(p.getContact().getPhone())
                    .email(p.getContact().getEmail())
                    .build();
        }

        int usageCount = p.getTourDepartures() != null ? p.getTourDepartures().size() : 0;

        return AdminPolicyTemplateResponse.builder()
                .policyTemplateID(p.getPolicyTemplateID())
                .templateName(p.getTemplateName())
                .tourPriceIncludes(p.getTourPriceIncludes())
                .tourPriceExcludes(p.getTourPriceExcludes())
                .childPricingNotes(p.getChildPricingNotes())
                .paymentConditions(p.getPaymentConditions())
                .registrationConditions(p.getRegistrationConditions())
                .regularDayCancellationRules(p.getRegularDayCancellationRules())
                .holidayCancellationRules(p.getHolidayCancellationRules())
                .forceMajeureRules(p.getForceMajeureRules())
                .packingList(p.getPackingList())
                .branchInfo(branchInfo)
                .usageCount(usageCount)
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
