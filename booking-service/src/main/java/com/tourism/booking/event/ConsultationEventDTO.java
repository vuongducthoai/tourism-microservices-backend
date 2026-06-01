package com.tourism.booking.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Event payload cho yêu cầu tư vấn → publish lên RabbitMQ.
 * Notification-service consume và push WebSocket cho admin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsultationEventDTO implements Serializable {

    private Integer consultationId;
    private String  requestCode;
    private String  fullName;
    private String  phone;
    private String  email;
    private String  tourCode;
    private String  tourName;
    private String  consultationInfo;

    /** Phân biệt event consultation với event booking trong cùng queue. */
    private String eventType;   // CONSULTATION_CREATED

    /** Idempotency = requestCode + epochMs */
    private String idempotencyKey;
}
