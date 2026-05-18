package com.tourism.forum.repository;

import com.tourism.forum.entity.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Integer> {

    Optional<Tag> findByName(String name);

    @Query("""
        SELECT t FROM Tag t
        LEFT JOIN t.postTags pt
        WHERE (t.isDeleted IS NULL OR t.isDeleted = false)
        GROUP BY t
        ORDER BY COUNT(pt) DESC
        """)
    List<Tag> findPopularTags(Pageable pageable);
}
