package com.tourism.booking.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationResponse {
    private Integer consultationId;
    private String requestCode;

    private String fullName;
    private String phone;
    private String email;
    private Integer userId;

    private Integer tourId;
    private String tourCode;
    private String tourName;
    private String consultationInfo;

    private String status;

    private Integer adminId;
    private String adminEmail;
    private String adminNotes;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime resolvedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
