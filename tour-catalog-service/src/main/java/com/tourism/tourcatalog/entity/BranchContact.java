package com.tourism.tourcatalog.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "branch_contacts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BranchContact extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer contactID;

    @NotBlank
    private String branchName;

    @NotBlank
    private String phone;

    @NotBlank @Email
    private String email;

    @NotBlank
    private String address;

    @Column(name = "is_head_office")
    private Boolean isHeadOffice = false;

    @OneToMany(mappedBy = "contact", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<PolicyTemplate> policies;
}
