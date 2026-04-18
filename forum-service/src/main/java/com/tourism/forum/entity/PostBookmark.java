package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "post_bookmarks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostBookmark extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer bookmarkID;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private ForumPost post;
}
