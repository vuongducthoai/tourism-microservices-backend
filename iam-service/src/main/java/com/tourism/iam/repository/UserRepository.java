package com.tourism.iam.repository;

import com.tourism.iam.entity.User;
import com.tourism.iam.repository.custom.UserRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer>, UserRepositoryCustom {

    Optional<User> findByEmail(String email);

    boolean existsByEmailAndUserIDNot(String email, Integer userId);

    boolean existsByPhoneAndUserIDNot(String phone, Integer userId);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastActiveAt = :lastActiveAt WHERE u.email = :email")
    void updateLastActiveAt(@Param("email") String email, @Param("lastActiveAt") LocalDateTime lastActiveAt);
}
