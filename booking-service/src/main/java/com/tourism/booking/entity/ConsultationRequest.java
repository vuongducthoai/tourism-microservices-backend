package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Yêu cầu tư vấn tour gửi từ user/guest qua modal trên trang chi tiết tour.
 * Admin nhận thông báo real-time, gọi điện, đánh dấu trạng thái xử lý.
 */
@Entity
@Table(name = "consultation_requests", indexes = {
        @Index(name = "idx_consultation_status",  columnList = "status"),
        @Index(name = "idx_consultation_created", columnList = "created_at"),
        @Index(name = "idx_consultation_phone",   columnList = "phone")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ConsultationRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consultation_id")
    private Integer consultationId;

    @Column(name = "request_code", unique = true, nullable = false, length = 20)
    private String requestCode;

    // Người gửi
    @Column(name = "full_name", nullable = false)         
    private String fullName;

    @Column(nullable = false, length = 20)                
    private String phone;

    @Column(nullable = false)                             
    private String email;

    @Column(name = "user_id")                             
    private Integer userId;

    // Nội dung tư vấn
    @Column(name = "tour_id")                             
    private Integer tourId;

    @Column(name = "tour_code", length = 50)             
    private String tourCode;

    @Column(name = "tour_name")                           
    private String tourName;

    @Column(name = "consultation_info", columnDefinition = "TEXT")
    private String consultationInfo;

    // Trạng thái xử lý
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConsultationStatus status = ConsultationStatus.PENDING;

    @Column(name = "admin_id")        
    private Integer adminId;

    @Column(name = "admin_email")     
    private String adminEmail;

    @Column(name = "admin_notes", columnDefinition = "TEXT")
    private String adminNotes;

    @Column(name = "resolved_at")     private LocalDateTime resolvedAt;

    public enum ConsultationStatus { PENDING, IN_PROGRESS, RESOLVED, CLOSED }
}
