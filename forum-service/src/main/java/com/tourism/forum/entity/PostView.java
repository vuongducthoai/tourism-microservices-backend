package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "post_views")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostView extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer viewID;

    @Column(name = "user_id")
    private Integer userId;

    private String ipAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private ForumPost post;
}
