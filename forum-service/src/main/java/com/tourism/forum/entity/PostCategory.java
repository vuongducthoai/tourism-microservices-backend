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
    private Integer categoryID;

    @Column(nullable = false)
    private String name;

    private String slug;
    private String description;
    private String iconUrl;

    @Column(name = "display_order")
    private Integer displayOrder;

    @OneToMany(mappedBy = "category")
    private List<ForumPost> posts;
}
