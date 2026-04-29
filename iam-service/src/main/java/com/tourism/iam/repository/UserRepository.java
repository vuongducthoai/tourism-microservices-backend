package com.tourism.iam.repository;

import com.tourism.iam.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByEmail(String email);

    boolean existsByEmailAndUserIDNot(String email, Integer userId);

    boolean existsByPhoneAndUserIDNot(String phone, Integer userId);
}
