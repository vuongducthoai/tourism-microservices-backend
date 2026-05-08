package com.tourism.iam.repository.custom.impl;

import com.tourism.iam.dto.request.UserSearchRequest;
import com.tourism.iam.entity.Role;
import com.tourism.iam.entity.User;
import com.tourism.iam.repository.custom.UserRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Ports monolith UserRepositoryCustomImpl exactly:
 * - Filter by role = CUSTOMER
 * - Filter by fullName / phone / email (LIKE, case-insensitive)
 * - Order by lastActiveAt DESC (in-memory activity sort handled in service)
 */
@Repository
public class UserRepositoryCustomImpl implements UserRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<User> searchUsers(UserSearchRequest searchDTO, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<User> query = cb.createQuery(User.class);
        Root<User> root = query.from(User.class);

        List<Predicate> predicates = buildPredicates(cb, root, searchDTO);
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(cb.desc(root.get("lastActiveAt")));

        TypedQuery<User> typedQuery = entityManager.createQuery(query);
        if (pageable.isPaged()) {
            typedQuery.setFirstResult((int) pageable.getOffset());
            typedQuery.setMaxResults(pageable.getPageSize());
        }
        List<User> users = typedQuery.getResultList();

        // Count query
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<User> countRoot = countQuery.from(User.class);
        List<Predicate> countPredicates = buildPredicates(cb, countRoot, searchDTO);
        countQuery.select(cb.count(countRoot));
        countQuery.where(countPredicates.toArray(new Predicate[0]));
        Long totalElements = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(users, pageable, totalElements);
    }

    private List<Predicate> buildPredicates(CriteriaBuilder cb, Root<User> root, UserSearchRequest searchDTO) {
        List<Predicate> predicates = new ArrayList<>();

        // Only CUSTOMER role (matches monolith)
        predicates.add(cb.equal(root.get("role"), Role.CUSTOMER));

        if (searchDTO.getFullName() != null && !searchDTO.getFullName().trim().isEmpty()) {
            String nameValue = "%" + searchDTO.getFullName().trim().toLowerCase() + "%";
            predicates.add(cb.like(cb.lower(root.get("fullName").as(String.class)), nameValue));
        }

        if (searchDTO.getPhone() != null && !searchDTO.getPhone().trim().isEmpty()) {
            String phoneValue = "%" + searchDTO.getPhone().trim() + "%";
            predicates.add(cb.like(root.get("phone").as(String.class), phoneValue));
        }

        if (searchDTO.getEmail() != null && !searchDTO.getEmail().trim().isEmpty()) {
            String emailValue = "%" + searchDTO.getEmail().trim().toLowerCase() + "%";
            predicates.add(cb.like(cb.lower(root.get("email").as(String.class)), emailValue));
        }

        return predicates;
    }
}
