package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Config thưởng coin forum chỉnh được runtime (PLAN_ADMIN_FORUM_COIN §6).
 * Chỉ lưu các key bị admin GHI ĐÈ — giá trị mặc định vẫn từ application.yml.
 */
@Entity
@Table(name = "forum_reward_configs",
        uniqueConstraints = @UniqueConstraint(name = "uq_reward_config_key", columnNames = "config_key"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForumRewardConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 60)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 100)
    private String configValue;

    @Column(name = "updated_by")
    private Integer updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
