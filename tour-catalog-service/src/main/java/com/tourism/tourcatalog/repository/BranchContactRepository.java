package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.BranchContact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BranchContactRepository extends JpaRepository<BranchContact, Integer> {

    boolean existsByBranchName(String branchName);

    @Query("SELECT COUNT(b) > 0 FROM BranchContact b WHERE b.branchName = :branchName AND b.contactID != :excludeId")
    boolean existsByBranchNameAndContactIDNot(@Param("branchName") String branchName, @Param("excludeId") Integer excludeId);

    boolean existsByIsHeadOfficeTrue();

    @Query("SELECT COUNT(b) > 0 FROM BranchContact b WHERE b.isHeadOffice = true AND b.contactID != :excludeId")
    boolean existsByIsHeadOfficeTrueAndContactIDNot(@Param("excludeId") Integer excludeId);

    @Query("SELECT b FROM BranchContact b WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            " LOWER(b.branchName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            " LOWER(b.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            " LOWER(b.phone) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<BranchContact> searchBranches(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT b FROM BranchContact b ORDER BY b.isHeadOffice DESC")
    List<BranchContact> findAllOrderByIsHeadOfficeDesc();
}
