package com.tourism.iam.repository.custom;

import com.tourism.iam.dto.request.UserSearchRequest;
import com.tourism.iam.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserRepositoryCustom {
    Page<User> searchUsers(UserSearchRequest searchDTO, Pageable pageable);
}
