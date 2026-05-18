package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "post_categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostCategory extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "categoryid")
    private Integer categoryID;

    @Column(name = "category_name", nullable = false)
    private String name;

    private String slug;
    private String description;
    private String iconUrl;
    private String icon;
    private String color;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "post_count")
    private Integer postCount = 0;

    @OneToMany(mappedBy = "category")
    private List<ForumPost> posts;
}
