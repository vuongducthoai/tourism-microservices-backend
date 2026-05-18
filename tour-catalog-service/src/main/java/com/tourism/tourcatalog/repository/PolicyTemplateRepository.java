package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.PolicyTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PolicyTemplateRepository extends JpaRepository<PolicyTemplate, Integer> {

    Page<PolicyTemplate> findAllByIsDeletedFalse(Pageable pageable);

    boolean existsByTemplateName(String templateName);

    @Query("SELECT COUNT(p) > 0 FROM PolicyTemplate p WHERE p.templateName = :templateName AND p.policyTemplateID != :excludeId")
    boolean existsByTemplateNameAndPolicyTemplateIDNot(@Param("templateName") String templateName, @Param("excludeId") Integer excludeId);

    List<PolicyTemplate> findByContact_ContactID(Integer contactId);

    @Query("SELECT p FROM PolicyTemplate p WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            " LOWER(p.templateName) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<PolicyTemplate> searchPolicies(@Param("keyword") String keyword, Pageable pageable);

    long countByContact_ContactID(Integer contactId);

    List<PolicyTemplate> findByStatusTrue();
}
