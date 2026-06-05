package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Thưởng bài viết có độ trễ 24h (PLAN_FORUM_COIN_REWARD §3.3, §4):
 * không cộng coin ngay khi đăng — chờ eligibleAt, nếu bài vẫn PUBLISHED mới thưởng.
 * Chặn kịch bản "đăng bài → nhận coin → xóa bài".
 */
@Entity
@Table(name = "post_reward_schedules",
        uniqueConstraints = @UniqueConstraint(name = "uq_reward_schedule_post", columnNames = "post_id"),
        indexes = @Index(name = "idx_reward_schedule_due", columnList = "status, eligible_at"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostRewardSchedule extends BaseEntity {

    public enum ScheduleStatus { WAITING, REWARDED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Integer postId;

    /** Tác giả bài viết */
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "eligible_at", nullable = false)
    private LocalDateTime eligibleAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ScheduleStatus status = ScheduleStatus.WAITING;
}
