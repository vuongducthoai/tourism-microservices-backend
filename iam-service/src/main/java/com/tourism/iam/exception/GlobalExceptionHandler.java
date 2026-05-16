package com.tourism.iam.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        log.error("RuntimeException: {}", ex.getMessage());

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = ex.getMessage();

        if (message != null) {
            // BAD_REQUEST (400) - validation errors
            if (message.contains("Email đã được sử dụng") ||
                message.contains("Mật khẩu xác nhận không khớp") ||
                message.contains("Vui lòng xác thực email") ||
                message.contains("Token không hợp lệ") ||
                message.contains("Token đã hết hạn")) {
                status = HttpStatus.BAD_REQUEST;
            }
            // UNAUTHORIZED (401) - authentication errors
            else if (message.contains("Email hoặc mật khẩu không đúng") ||
                     message.contains("Sai mật khẩu") ||
                     message.contains("Account is locked") ||
                     message.contains("invalid_grant")) {
                status = HttpStatus.UNAUTHORIZED;
            }
            // NOT_FOUND (404) - resource not found
            else if (message.contains("User không tồn tại") ||
                     message.contains("User not found") ||
                     message.contains("Email không tồn tại")) {
                status = HttpStatus.NOT_FOUND;
            }
            // BAD_REQUEST for other token/validation issues
            else if (message.contains("Token") || message.contains("hết hạn")) {
                status = HttpStatus.BAD_REQUEST;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);

        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex) {
        log.error("Unexpected exception: ", ex);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", "Internal Server Error");
        body.put("message", "An unexpected error occurred");

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
