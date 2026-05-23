package com.tourism.forum.repository;

import com.tourism.forum.entity.Follower;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
public interface FollowerRepository extends JpaRepository<Follower, Integer> {
    // Lấy tất cả userId của người đang follow user X
    // followingUserId = người được follow (tác giả bài viết)
    @Query("SELECT f.followerUserId FROM Follower f WHERE f.followingUserId = :userId")
    List<Integer> findFollowerIdsByFollowingUserId(@Param("userId") Integer userId);
}