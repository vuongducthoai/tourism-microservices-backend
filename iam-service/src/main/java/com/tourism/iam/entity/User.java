package com.tourism.iam.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer userID;

    @NotBlank @Size(max = 100)
    private String fullName;

    @Pattern(regexp = "^\\d{10,11}$")
    private String phone;

    @NotBlank @Email
    @Column(unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    private Role role;

    @NotBlank
    private String password;

    private String avatar;
    private Boolean status = true;

    @Past
    private LocalDate dateOfBirth;

    @Column(name = "coin_balance")
    @Min(0)
    private BigDecimal coinBalance = BigDecimal.ZERO;

    @Column(name = "province_code", length = 10)
    private String provinceCode;

    @Column(name = "province_name", length = 100)
    private String provinceName;

    @Column(name = "district_code", length = 10)
    private String districtCode;

    @Column(name = "district_name", length = 100)
    private String districtName;

    @Column(name = "is_email_verified", nullable = false)
    private Boolean isEmailVerified = false;

    @Column(name = "verification_token")
    private String verificationToken;

    @Column(name = "verification_token_expiry")
    private LocalDateTime verificationTokenExpiry;

    private LocalDateTime lastActiveAt;
}
