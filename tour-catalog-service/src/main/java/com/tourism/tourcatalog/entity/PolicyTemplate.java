package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "policy_templates")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PolicyTemplate extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer policyTemplateID;

    @NotBlank @Size(max = 255)
    @Column(name = "template_name", unique = true, nullable = false)
    private String templateName;

    @Column(columnDefinition = "TEXT")
    private String tourPriceIncludes;

    @Column(columnDefinition = "TEXT")
    private String tourPriceExcludes;

    @Column(columnDefinition = "TEXT")
    private String childPricingNotes;

    @Column(columnDefinition = "TEXT")
    private String paymentConditions;

    @Column(columnDefinition = "TEXT")
    private String registrationConditions;

    @Column(columnDefinition = "TEXT")
    private String regularDayCancellationRules;

    @Column(columnDefinition = "TEXT")
    private String holidayCancellationRules;

    @Column(columnDefinition = "TEXT")
    private String forceMajeureRules;

    @Column(columnDefinition = "TEXT")
    private String packingList;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private BranchContact contact;

    @OneToMany(mappedBy = "policyTemplate")
    private List<TourDeparture> tourDepartures;

    private Boolean status = true;
}
