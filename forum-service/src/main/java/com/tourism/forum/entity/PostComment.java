package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "post_comments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostComment extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "commentid")
    private Integer commentID;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "like_count")
    private Integer likeCount = 0;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status = ContentStatus.PUBLISHED;

    @Column(name = "is_edited")
    private Boolean isEdited = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private ForumPost post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private PostComment parentComment;

    @OneToMany(mappedBy = "parentComment", cascade = CascadeType.ALL)
    private List<PostComment> replies;

    // ── AI Moderation ─────────────────────────────────────────────────────────
    @Column(name = "moderation_score")
    private Double moderationScore;           // 0.0 – 1.0 (0 = sạch, 1 = toxic)

    @Column(name = "moderation_label", length = 50)
    private String moderationLabel;           // "SAFE" | "BORDERLINE" | "TOXIC"

    @Column(name = "moderation_reason", columnDefinition = "TEXT")
    private String moderationReason;          // giải thích ngắn từ AI

    @Column(name = "moderated_at")
    private LocalDateTime moderatedAt;

    // Soft-delete tracking
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Integer deletedBy;

    @Column(name = "delete_reason", columnDefinition = "TEXT")
    private String deleteReason;
}
