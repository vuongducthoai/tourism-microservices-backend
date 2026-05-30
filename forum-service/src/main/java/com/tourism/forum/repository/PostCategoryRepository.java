package com.tourism.forum.repository;

import com.tourism.forum.entity.PostCategory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PostCategoryRepository extends JpaRepository<PostCategory, Integer> {

    @Query("""
        SELECT c FROM PostCategory c
        WHERE (c.isDeleted IS NULL OR c.isDeleted = false)
        ORDER BY c.displayOrder ASC NULLS LAST
        """)
    List<PostCategory> findAllActive();

    @Query("""
        SELECT c FROM PostCategory c
        LEFT JOIN c.posts p
        WHERE (c.isDeleted IS NULL OR c.isDeleted = false)
        GROUP BY c
        ORDER BY COUNT(p) DESC
        """)
    List<PostCategory> findPopularCategories(Pageable pageable);

    @Query("""
        SELECT c.name, COUNT(p)
        FROM PostCategory c
        LEFT JOIN c.posts p
        WHERE (c.isDeleted IS NULL OR c.isDeleted = false)
        GROUP BY c.categoryID, c.name
        ORDER BY COUNT(p) DESC
        """)
    List<Object[]> findTopCategoriesWithCount(Pageable pageable);
}
