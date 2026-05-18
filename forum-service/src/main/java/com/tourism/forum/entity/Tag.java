package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "tags")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tag extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tagid")
    private Integer tagID;

    @Column(name = "tag_name", nullable = false, unique = true)
    private String name;

    private String slug;
    private String color;
    private String description;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "usage_count")
    private Integer usageCount = 0;

    @OneToMany(mappedBy = "tag")
    private List<PostTag> postTags;
}
