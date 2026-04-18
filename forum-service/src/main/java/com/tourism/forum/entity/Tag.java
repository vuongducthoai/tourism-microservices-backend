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
    private Integer tagID;

    @Column(nullable = false, unique = true)
    private String name;

    private String slug;

    @OneToMany(mappedBy = "tag")
    private List<PostTag> postTags;
}
