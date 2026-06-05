package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Cờ "mốc like đã thưởng" — mỗi mốc chỉ thưởng 1 lần/bài (hoặc comment).
 * Chống like/unlike cày mốc (PLAN_FORUM_COIN_REWARD §3.4).
 */
@Entity
@Table(name = "post_reward_milestones",
        uniqueConstraints = @UniqueConstraint(name = "uq_reward_milestone",
                columnNames = {"target_type", "target_id", "milestone"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostRewardMilestone extends BaseEntity {

    public enum TargetType { POST, COMMENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Integer targetId;

    @Column(name = "milestone", nullable = false)
    private Integer milestone;
}
