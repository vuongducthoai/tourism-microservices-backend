package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "post_comments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostComment extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer commentID;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private Integer likeCount = 0;

    // Tham chiếu sang IAM Service bằng ID
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private ForumPost post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private PostComment parentComment;

    @OneToMany(mappedBy = "parentComment", cascade = CascadeType.ALL)
    private List<PostComment> replies;
}
