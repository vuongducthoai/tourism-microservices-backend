package com.tourism.forum.repository;

import com.tourism.forum.entity.Follower;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FollowerRepository extends JpaRepository<Follower, Integer> {
    // Lấy tất cả userId của người đang follow user X (giữ nguyên cho luồng notification cũ)
    @Query("SELECT f.followerUserId FROM Follower f WHERE f.followingUserId = :userId")
    List<Integer> findFollowerIdsByFollowingUserId(@Param("userId") Integer userId);

    // Sprint C
    Optional<Follower> findByFollowerUserIdAndFollowingUserId(Integer followerUserId, Integer followingUserId);

    boolean existsByFollowerUserIdAndFollowingUserId(Integer followerUserId, Integer followingUserId);

    @Query("SELECT f.followingUserId FROM Follower f WHERE f.followerUserId = :userId")
    List<Integer> findFollowingIdsByFollowerUserId(@Param("userId") Integer userId);
}
