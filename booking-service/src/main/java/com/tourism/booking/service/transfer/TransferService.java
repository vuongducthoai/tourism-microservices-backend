package com.tourism.booking.service.transfer;

import com.tourism.booking.entity.CoinWithdrawal;

public interface TransferService {
    TransferResult transfer(CoinWithdrawal withdrawal);
}