package com.tourism.booking.service.transfer;

import com.tourism.booking.config.SepayConfig;
import com.tourism.booking.entity.CoinWithdrawal;
import com.tourism.booking.entity.CoinWithdrawalErrorSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service("sepayTransferService")
@RequiredArgsConstructor
public class SepayTransferServiceImpl implements TransferService {

    private final SepayConfig sepayConfig;
    private final RestTemplate restTemplate;

    @Value("${sepay.transfer-path:/transfers}")
    private String transferPath;

    @Override
    public TransferResult transfer(CoinWithdrawal withdrawal) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(sepayConfig.getToken());
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("account_number", withdrawal.getAccountNumber());
            payload.put("account_name", withdrawal.getAccountName());
            payload.put("bank_code", withdrawal.getBank());
            payload.put("amount", withdrawal.getMoneyAmount());
            payload.put("content", "RUTDIEM " + withdrawal.getReferenceCode());
            payload.put("reference_code", withdrawal.getReferenceCode());

            String url = sepayConfig.getApiUrl() + transferPath;
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return TransferResult.retryableFailure(
                        CoinWithdrawalErrorSource.SEPAY,
                        "SePay returned status " + response.getStatusCode().value());
            }

            String transferRef = extractTransferRef(response.getBody(), withdrawal.getReferenceCode());
            return TransferResult.success(transferRef);
        } catch (HttpStatusCodeException ex) {
            int status = ex.getStatusCode().value();
            String body = ex.getResponseBodyAsString();
            log.error("SePay transfer HTTP {} for {}: {}", status, withdrawal.getReferenceCode(), body);

            if (status == 404 || status == 405) {
                return TransferResult.manual(
                        "SePay khong ho tro API chuyen khoan tu dong cho tai khoan/token hien tai (HTTP " + status + ")");
            }

            return TransferResult.retryableFailure(
                    CoinWithdrawalErrorSource.SEPAY,
                    "SePay HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
        } catch (Exception ex) {
            log.error("SePay transfer failed for {}: {}", withdrawal.getReferenceCode(), ex.getMessage());
            return TransferResult.retryableFailure(CoinWithdrawalErrorSource.SEPAY, ex.getMessage());
        }
    }

    private String extractTransferRef(Map<?, ?> body, String fallback) {
        if (body == null) return fallback;
        Object direct = firstNonNull(
                body.get("reference_number"),
                body.get("referenceNumber"),
                body.get("transaction_reference"),
                body.get("transactionReference"),
                body.get("id"));
        if (direct != null) return String.valueOf(direct);

        Object data = body.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object nested = firstNonNull(
                    dataMap.get("reference_number"),
                    dataMap.get("referenceNumber"),
                    dataMap.get("transaction_reference"),
                    dataMap.get("transactionReference"),
                    dataMap.get("id"));
            if (nested != null) return String.valueOf(nested);
        }
        return fallback;
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }
}