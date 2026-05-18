package com.tourism.booking.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueueHealthResponse {
    private String queue;       // queue name (for logging)
    private int    ready;       // messages waiting to be sent
    private int    unacked;     // messages being processed
    private int    consumers;   // active consumers
    private int    dlqReady;    // messages in dead-letter queue
    private String status;      // HEALTHY | BACKLOG | CONSUMER_DOWN | DLQ_ATTENTION | BROKER_DOWN
    private String message;     // business-friendly Vietnamese description for admin UI
    private String checkedAt;   // timestamp — TZ set via Docker env var
}
