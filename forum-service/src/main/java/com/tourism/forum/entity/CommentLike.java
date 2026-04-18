package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "comment_likes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentLike extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer commentLikeID;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private PostComment comment;
}
