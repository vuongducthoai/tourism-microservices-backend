package com.tourism.forum.repository;

import com.tourism.forum.entity.PostTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostTagRepository extends JpaRepository<PostTag, Integer> {

    List<PostTag> findByPostPostID(Integer postId);

    void deleteByPostPostID(Integer postId);
}
