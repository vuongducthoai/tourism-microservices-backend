package com.tourism.booking.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationUpdateStatusRequest {

    @NotBlank
    private String status;       // PENDING | IN_PROGRESS | RESOLVED | CLOSED

    private String adminNotes;   // ghi chú sau khi xử lý
}
