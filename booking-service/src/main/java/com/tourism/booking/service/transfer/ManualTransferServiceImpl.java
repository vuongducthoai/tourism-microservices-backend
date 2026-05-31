package com.tourism.booking.service.transfer;

import com.tourism.booking.entity.CoinWithdrawal;
import org.springframework.stereotype.Service;

@Service("manualTransferService")
public class ManualTransferServiceImpl implements TransferService {

    @Override
    public TransferResult transfer(CoinWithdrawal withdrawal) {
        return TransferResult.manual(
                "Chuyen sang che do xu ly thu cong cho lenh " + withdrawal.getReferenceCode());
    }
}