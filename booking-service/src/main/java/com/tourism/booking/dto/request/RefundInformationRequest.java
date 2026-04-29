package com.tourism.booking.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RefundInformationRequest {
    private String accountName;
    private String accountNumber;
    private String bank;
}
