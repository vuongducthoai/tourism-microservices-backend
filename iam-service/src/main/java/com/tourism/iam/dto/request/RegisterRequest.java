package com.tourism.iam.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 100)
    private String fullName;

    @NotBlank @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "Mật khẩu phải có ít nhất 8 kí tự")
       @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
        message = "Mật khẩu phải có chữ hoa, chữ thường và số"
    )
    private String password;

    @NotBlank
    private String confirmPassword;

    @NotBlank
    private String provinceCode;
    private String provinceName;

    @NotBlank
    private String districtCode;
    private String districtName;
    
}
