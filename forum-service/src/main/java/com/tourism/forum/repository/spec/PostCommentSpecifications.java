package com.tourism.forum.repository.spec;

import com.tourism.forum.dto.request.AdminCommentFilterRequest;
import com.tourism.forum.entity.PostComment;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class PostCommentSpecifications {

    private PostCommentSpecifications() {
    }

    public static Specification<PostComment> withFilter(AdminCommentFilterRequest f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.or(
                    cb.isNull(root.get("isDeleted")),
                    cb.isFalse(root.get("isDeleted"))
            ));

            if (f.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), f.getStatus()));
            }
            if (f.getPostId() != null) {
                predicates.add(cb.equal(root.get("post").get("postID"), f.getPostId()));
            }
            if (f.getUserId() != null) {
                predicates.add(cb.equal(root.get("userId"), f.getUserId()));
            }
            if (f.getModerationLabel() != null && !f.getModerationLabel().isBlank()) {
                predicates.add(cb.equal(root.get("moderationLabel"), f.getModerationLabel()));
            }
            if (f.getSearch() != null && !f.getSearch().isBlank()) {
                String like = "%" + f.getSearch().toLowerCase() + "%";
                predicates.add(cb.like(cb.lower(root.get("content")), like));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
