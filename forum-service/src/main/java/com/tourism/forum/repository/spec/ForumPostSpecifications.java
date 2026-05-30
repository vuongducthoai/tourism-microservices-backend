package com.tourism.forum.repository.spec;

import com.tourism.forum.dto.request.AdminPostFilterRequest;
import com.tourism.forum.entity.ForumPost;
import com.tourism.forum.entity.PostType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public final class ForumPostSpecifications {

    private ForumPostSpecifications() {
    }

    public static Specification<ForumPost> withFilter(AdminPostFilterRequest f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Admin sees everything except soft-deleted records.
            predicates.add(cb.isFalse(root.get("isDeleted")));

            if (f.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), f.getStatus()));
            }
            if (f.getCategoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("categoryID"), f.getCategoryId()));
            }
            if (f.getPostType() != null && !f.getPostType().isBlank()) {
                try {
                    predicates.add(cb.equal(root.get("postType"),
                            PostType.valueOf(f.getPostType().trim().toUpperCase())));
                } catch (IllegalArgumentException ignored) {
                    // Unknown postType -> ignore the filter rather than error out.
                }
            }
            if (f.getUserId() != null) {
                predicates.add(cb.equal(root.get("userId"), f.getUserId()));
            }
            if (f.getModerationLabel() != null && !f.getModerationLabel().isBlank()) {
                predicates.add(cb.equal(root.get("moderationLabel"), f.getModerationLabel()));
            }
            if (f.getSearch() != null && !f.getSearch().isBlank()) {
                String like = "%" + f.getSearch().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("summary")), like),
                        cb.like(cb.lower(root.get("content")), like)
                ));
            }
            if (f.getDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("createdAt"), f.getDateFrom().atStartOfDay()));
            }
            if (f.getDateTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(
                        root.get("createdAt"), f.getDateTo().atTime(LocalTime.MAX)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
