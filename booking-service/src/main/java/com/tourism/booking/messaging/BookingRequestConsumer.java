package com.tourism.booking.messaging;

import com.tourism.booking.dto.request.CreateBookingRequest;
import com.tourism.booking.dto.response.BookingRequestResult;
import com.tourism.booking.dto.response.CreateBookingResponse;
import com.tourism.booking.service.AsyncBookingService;
import com.tourism.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Worker xử lý yêu cầu đặt tour từ Kafka.
 * Cùng một departureId (key) vào cùng partition → xử lý TUẦN TỰ, dàn tải theo thời gian.
 * Tận dụng lại logic createBooking (đã có atomic update, Redis gate, Saga, idempotency).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingRequestConsumer {

    private final BookingService bookingService;
    private final AsyncBookingService asyncBookingService;

    @KafkaListener(topics = AsyncBookingService.TOPIC, groupId = "booking-workers")
    public void onBookingRequest(CreateBookingRequest request) {
        String requestId = request.getIdempotencyKey();
        try {
            CreateBookingResponse res = bookingService.createBooking(request);
            asyncBookingService.store(new BookingRequestResult(
                    requestId, "SUCCESS", res.getBookingCode(), res.getBookingId(), null));
            log.info("Xu ly booking async THANH CONG (requestId={}, bookingCode={})",
                    requestId, res.getBookingCode());
        } catch (Exception e) {
            asyncBookingService.store(new BookingRequestResult(
                    requestId, "FAILED", null, null, e.getMessage()));
            log.warn("Xu ly booking async THAT BAI (requestId={}): {}", requestId, e.getMessage());
        }
    }
}
