package com.tourism.payment.util;

import java.util.Map;

public final class BankCodeUtil {

    private BankCodeUtil() {}

    // Napas/VietQR bank codes → Vietnamese bank names
    private static final Map<String, String> BANK_NAMES = Map.ofEntries(
        Map.entry("970436", "Vietcombank"),
        Map.entry("970415", "Vietinbank"),
        Map.entry("970418", "BIDV"),
        Map.entry("970405", "Agribank"),
        Map.entry("970432", "VPBank"),
        Map.entry("970422", "MB Bank"),
        Map.entry("970407", "Techcombank"),
        Map.entry("970416", "ACB"),
        Map.entry("970423", "TPBank"),
        Map.entry("970403", "Sacombank"),
        Map.entry("970431", "Eximbank"),
        Map.entry("970441", "VIB"),
        Map.entry("970412", "OCB"),
        Map.entry("970426", "MSB"),
        Map.entry("970440", "SeABank"),
        Map.entry("970448", "OCB"),
        Map.entry("970454", "Bac A Bank"),
        Map.entry("970443", "SHB"),
        Map.entry("970419", "NCB"),
        Map.entry("970414", "Oceanbank"),
        Map.entry("970452", "KienLong Bank"),
        Map.entry("970406", "Dong A Bank"),
        Map.entry("970408", "GPBank"),
        Map.entry("970433", "VietBank"),
        Map.entry("970424", "Shinhan Bank"),
        Map.entry("970458", "TPBANK"),
        Map.entry("970437", "HDBank"),
        Map.entry("970457", "Woori Bank"),
        Map.entry("970428", "SCB"),
        Map.entry("970400", "Saigonbank"),
        Map.entry("970409", "BaoViet Bank"),
        Map.entry("970421", "VRB"),
        Map.entry("970462", "BVBank"),
        Map.entry("970449", "LPBank"),
        Map.entry("970446", "Co-op Bank"),
        Map.entry("970455", "IBK"),
        Map.entry("970444", "CBBank"),
        Map.entry("422589", "HSBC"),
        Map.entry("533948", "Standard Chartered"),
        Map.entry("458761", "Citibank"),
        Map.entry("796500", "DBS Bank")
    );

    public static String getBankName(String bankCode) {
        if (bankCode == null || bankCode.isBlank()) return null;
        return BANK_NAMES.getOrDefault(bankCode.trim(), null);
    }
}
