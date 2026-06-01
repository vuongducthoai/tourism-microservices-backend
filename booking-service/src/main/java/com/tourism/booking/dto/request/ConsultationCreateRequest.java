package com.tourism.booking.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationCreateRequest {

    @NotBlank(message = "Họ tên là bắt buộc")
    @Size(max = 255)
    private String fullName;

    @NotBlank(message = "Số điện thoại là bắt buộc")
    @Pattern(regexp = "^(0|\\+84)\\d{9,10}$", message = "Số điện thoại không hợp lệ")
    private String phone;

    @NotBlank(message = "Email là bắt buộc")
    @Email(message = "Email không hợp lệ")
    private String email;

    private Integer tourId;          // optional — null khi inquiry chung

    @Size(max = 50)
    private String tourCode;         // FE truyền sẵn (snapshot)

    @Size(max = 255)
    private String tourName;         // FE truyền sẵn (snapshot)

    @Size(max = 2000)
    private String consultationInfo; // optional
}
