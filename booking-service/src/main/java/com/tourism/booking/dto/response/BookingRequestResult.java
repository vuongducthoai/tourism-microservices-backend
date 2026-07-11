package com.tourism.booking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kết quả xử lý bất đồng bộ của một yêu cầu đặt tour qua hàng đợi Kafka.
 * FE gửi yêu cầu → nhận requestId → poll trạng thái tới khi SUCCESS/FAILED.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequestResult {
    private String requestId;
    private String status;       // PROCESSING | SUCCESS | FAILED
    private String bookingCode;  // khi SUCCESS
    private Integer bookingId;   // khi SUCCESS
    private String message;      // khi FAILED
}
