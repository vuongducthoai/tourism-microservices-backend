package com.tourism.iam.repository;

import com.tourism.iam.entity.Role;
import com.tourism.iam.entity.User;
import com.tourism.iam.repository.custom.UserRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer>, UserRepositoryCustom {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndUserIDNot(String email, Integer userId);

    boolean existsByPhoneAndUserIDNot(String phone, Integer userId);

    Optional<User> findByVerificationToken(String token);
    
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastActiveAt = :lastActiveAt WHERE u.email = :email")
    void updateLastActiveAt(@Param("email") String email, @Param("lastActiveAt") LocalDateTime lastActiveAt);

    // ─── Dashboard stats queries ───

    Long countByRole(Role role);

    Long countByStatusAndRole(Boolean status, Role role);

    Long countByRoleAndCreatedAtBetween(Role role, LocalDateTime start, LocalDateTime end);

    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :role AND u.createdAt < :date AND (u.isDeleted = false OR u.isDeleted IS NULL)")
    Long countByRoleAndCreatedAtBefore(@Param("role") Role role, @Param("date") LocalDateTime date);

    @Query("""
            SELECT FUNCTION('DATE', u.createdAt), COUNT(u)
            FROM User u
            WHERE u.role = :role
              AND u.createdAt >= :start
              AND u.createdAt < :end
              AND (u.isDeleted = false OR u.isDeleted IS NULL)
            GROUP BY FUNCTION('DATE', u.createdAt)
            ORDER BY FUNCTION('DATE', u.createdAt)
            """)
    List<Object[]> getDailyNewUsersCounts(@Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end,
                                          @Param("role") Role role);

    List<User> findTop5ByRoleOrderByCreatedAtDesc(Role role);
}
