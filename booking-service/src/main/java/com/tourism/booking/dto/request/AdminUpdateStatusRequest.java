package com.tourism.booking.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for POST /api/bookings/admin/update-status
 * Mirrors monolith's BookingUpdateStatusRequestDTO.
 *
 * Supported transitions:
 *  PENDING_CONFIRMATION → PAID      (admin confirms payment)
 *  PENDING_PAYMENT      → CANCELLED (no refund — booking not paid yet)
 *  PENDING_CONFIRMATION → CANCELLED (with refund)
 *  PAID                 → CANCELLED (with refund)
 *  PENDING_REFUND       → CANCELLED (admin processed bank refund)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminUpdateStatusRequest {

    /** ID of the booking to update */
    private Integer bookingID;

    /** Target status: "PAID" | "CANCELLED" */
    private String  bookingStatus;

    /** Required when bookingStatus == "CANCELLED" */
    private String  cancelReason;
}
