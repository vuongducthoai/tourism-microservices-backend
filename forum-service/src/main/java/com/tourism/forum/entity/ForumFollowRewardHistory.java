package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Lịch sử thưởng follow TRỌN ĐỜI cho mỗi cặp (follower → following).
 * Unfollow rồi follow lại → không thưởng nữa (PLAN_FORUM_COIN_REWARD §3.6).
 */
@Entity
@Table(name = "forum_follow_reward_history",
        uniqueConstraints = @UniqueConstraint(name = "uq_follow_reward_pair",
                columnNames = {"follower_id", "following_id"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForumFollowRewardHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "follower_id", nullable = false)
    private Integer followerId;

    @Column(name = "following_id", nullable = false)
    private Integer followingId;
}
