package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "followers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Follower extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer followerID;

    // Người theo dõi (IAM Service)
    @Column(name = "follower_user_id", nullable = false)
    private Integer followerUserId;

    // Người được theo dõi (IAM Service)
    @Column(name = "following_id", nullable = false)
    private Integer followingUserId;
}
