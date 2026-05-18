package com.tourism.tourcatalog.util;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class VietnamAirportUtils {

    @Data
    @AllArgsConstructor
    public static class AirportInfo {
        private String code;
        private String name;
        private String cityName;
    }

    private static final Map<String, AirportInfo> AIRPORT_MAP = new HashMap<>();

    static {
        // Miền Bắc
        AIRPORT_MAP.put("hà nội", new AirportInfo("HAN", "Sân bay Quốc tế Nội Bài", "Hà Nội"));
        AIRPORT_MAP.put("hanoi", new AirportInfo("HAN", "Sân bay Quốc tế Nội Bài", "Hà Nội"));
        AIRPORT_MAP.put("noi bai", new AirportInfo("HAN", "Sân bay Quốc tế Nội Bài", "Hà Nội"));

        AIRPORT_MAP.put("hải phòng", new AirportInfo("HPH", "Sân bay Quốc tế Cát Bi", "Hải Phòng"));
        AIRPORT_MAP.put("hai phong", new AirportInfo("HPH", "Sân bay Quốc tế Cát Bi", "Hải Phòng"));
        AIRPORT_MAP.put("cat bi", new AirportInfo("HPH", "Sân bay Quốc tế Cát Bi", "Hải Phòng"));

        AIRPORT_MAP.put("điện biên", new AirportInfo("DIN", "Sân bay Điện Biên Phủ", "Điện Biên"));
        AIRPORT_MAP.put("dien bien", new AirportInfo("DIN", "Sân bay Điện Biên Phủ", "Điện Biên"));

        AIRPORT_MAP.put("vân đồn", new AirportInfo("VDO", "Sân bay Quốc tế Vân Đồn", "Quảng Ninh"));
        AIRPORT_MAP.put("van don", new AirportInfo("VDO", "Sân bay Quốc tế Vân Đồn", "Quảng Ninh"));
        AIRPORT_MAP.put("quảng ninh", new AirportInfo("VDO", "Sân bay Quốc tế Vân Đồn", "Quảng Ninh"));
        AIRPORT_MAP.put("quang ninh", new AirportInfo("VDO", "Sân bay Quốc tế Vân Đồn", "Quảng Ninh"));

        // Miền Trung
        AIRPORT_MAP.put("thanh hóa", new AirportInfo("THD", "Sân bay Thọ Xuân", "Thanh Hóa"));
        AIRPORT_MAP.put("thanh hoa", new AirportInfo("THD", "Sân bay Thọ Xuân", "Thanh Hóa"));
        AIRPORT_MAP.put("tho xuan", new AirportInfo("THD", "Sân bay Thọ Xuân", "Thanh Hóa"));

        AIRPORT_MAP.put("vinh", new AirportInfo("VII", "Sân bay Quốc tế Vinh", "Nghệ An"));
        AIRPORT_MAP.put("nghệ an", new AirportInfo("VII", "Sân bay Quốc tế Vinh", "Nghệ An"));
        AIRPORT_MAP.put("nghe an", new AirportInfo("VII", "Sân bay Quốc tế Vinh", "Nghệ An"));

        AIRPORT_MAP.put("đồng hới", new AirportInfo("VDH", "Sân bay Đồng Hới", "Quảng Bình"));
        AIRPORT_MAP.put("dong hoi", new AirportInfo("VDH", "Sân bay Đồng Hới", "Quảng Bình"));
        AIRPORT_MAP.put("quảng bình", new AirportInfo("VDH", "Sân bay Đồng Hới", "Quảng Bình"));
        AIRPORT_MAP.put("quang binh", new AirportInfo("VDH", "Sân bay Đồng Hới", "Quảng Bình"));

        AIRPORT_MAP.put("huế", new AirportInfo("HUI", "Sân bay Quốc tế Phú Bài", "Thừa Thiên Huế"));
        AIRPORT_MAP.put("hue", new AirportInfo("HUI", "Sân bay Quốc tế Phú Bài", "Thừa Thiên Huế"));
        AIRPORT_MAP.put("phu bai", new AirportInfo("HUI", "Sân bay Quốc tế Phú Bài", "Thừa Thiên Huế"));
        AIRPORT_MAP.put("thừa thiên huế", new AirportInfo("HUI", "Sân bay Quốc tế Phú Bài", "Thừa Thiên Huế"));

        AIRPORT_MAP.put("đà nẵng", new AirportInfo("DAD", "Sân bay Quốc tế Đà Nẵng", "Đà Nẵng"));
        AIRPORT_MAP.put("da nang", new AirportInfo("DAD", "Sân bay Quốc tế Đà Nẵng", "Đà Nẵng"));
        AIRPORT_MAP.put("danang", new AirportInfo("DAD", "Sân bay Quốc tế Đà Nẵng", "Đà Nẵng"));

        AIRPORT_MAP.put("chu lai", new AirportInfo("VCL", "Sân bay Chu Lai", "Quảng Nam"));
        AIRPORT_MAP.put("quảng nam", new AirportInfo("VCL", "Sân bay Chu Lai", "Quảng Nam"));
        AIRPORT_MAP.put("quang nam", new AirportInfo("VCL", "Sân bay Chu Lai", "Quảng Nam"));

        AIRPORT_MAP.put("pleiku", new AirportInfo("PXU", "Sân bay Pleiku", "Gia Lai"));
        AIRPORT_MAP.put("gia lai", new AirportInfo("PXU", "Sân bay Pleiku", "Gia Lai"));

        AIRPORT_MAP.put("buôn ma thuột", new AirportInfo("BMV", "Sân bay Buôn Ma Thuột", "Đắk Lắk"));
        AIRPORT_MAP.put("buon ma thuot", new AirportInfo("BMV", "Sân bay Buôn Ma Thuột", "Đắk Lắk"));
        AIRPORT_MAP.put("dak lak", new AirportInfo("BMV", "Sân bay Buôn Ma Thuột", "Đắk Lắk"));
        AIRPORT_MAP.put("đắk lắk", new AirportInfo("BMV", "Sân bay Buôn Ma Thuột", "Đắk Lắk"));

        AIRPORT_MAP.put("tuy hòa", new AirportInfo("TBB", "Sân bay Tuy Hòa", "Phú Yên"));
        AIRPORT_MAP.put("tuy hoa", new AirportInfo("TBB", "Sân bay Tuy Hòa", "Phú Yên"));
        AIRPORT_MAP.put("phú yên", new AirportInfo("TBB", "Sân bay Tuy Hòa", "Phú Yên"));
        AIRPORT_MAP.put("phu yen", new AirportInfo("TBB", "Sân bay Tuy Hòa", "Phú Yên"));

        // Miền Nam
        AIRPORT_MAP.put("nha trang", new AirportInfo("CXR", "Sân bay Quốc tế Cam Ranh", "Khánh Hòa"));
        AIRPORT_MAP.put("cam ranh", new AirportInfo("CXR", "Sân bay Quốc tế Cam Ranh", "Khánh Hòa"));
        AIRPORT_MAP.put("khánh hòa", new AirportInfo("CXR", "Sân bay Quốc tế Cam Ranh", "Khánh Hòa"));
        AIRPORT_MAP.put("khanh hoa", new AirportInfo("CXR", "Sân bay Quốc tế Cam Ranh", "Khánh Hòa"));

        AIRPORT_MAP.put("đà lạt", new AirportInfo("DLI", "Sân bay Liên Khương", "Lâm Đồng"));
        AIRPORT_MAP.put("da lat", new AirportInfo("DLI", "Sân bay Liên Khương", "Lâm Đồng"));
        AIRPORT_MAP.put("dalat", new AirportInfo("DLI", "Sân bay Liên Khương", "Lâm Đồng"));
        AIRPORT_MAP.put("lâm đồng", new AirportInfo("DLI", "Sân bay Liên Khương", "Lâm Đồng"));
        AIRPORT_MAP.put("lam dong", new AirportInfo("DLI", "Sân bay Liên Khương", "Lâm Đồng"));

        AIRPORT_MAP.put("hồ chí minh", new AirportInfo("SGN", "Sân bay Quốc tế Tân Sơn Nhất", "TP. Hồ Chí Minh"));
        AIRPORT_MAP.put("ho chi minh", new AirportInfo("SGN", "Sân bay Quốc tế Tân Sơn Nhất", "TP. Hồ Chí Minh"));
        AIRPORT_MAP.put("sài gòn", new AirportInfo("SGN", "Sân bay Quốc tế Tân Sơn Nhất", "TP. Hồ Chí Minh"));
        AIRPORT_MAP.put("saigon", new AirportInfo("SGN", "Sân bay Quốc tế Tân Sơn Nhất", "TP. Hồ Chí Minh"));
        AIRPORT_MAP.put("tan son nhat", new AirportInfo("SGN", "Sân bay Quốc tế Tân Sơn Nhất", "TP. Hồ Chí Minh"));

        AIRPORT_MAP.put("vũng tàu", new AirportInfo("VTG", "Sân bay Vũng Tàu", "Bà Rịa - Vũng Tàu"));
        AIRPORT_MAP.put("vung tau", new AirportInfo("VTG", "Sân bay Vũng Tàu", "Bà Rịa - Vũng Tàu"));

        AIRPORT_MAP.put("côn đảo", new AirportInfo("VCS", "Sân bay Côn Đảo", "Bà Rịa - Vũng Tàu"));
        AIRPORT_MAP.put("con dao", new AirportInfo("VCS", "Sân bay Côn Đảo", "Bà Rịa - Vũng Tàu"));

        AIRPORT_MAP.put("cần thơ", new AirportInfo("VCA", "Sân bay Quốc tế Cần Thơ", "Cần Thơ"));
        AIRPORT_MAP.put("can tho", new AirportInfo("VCA", "Sân bay Quốc tế Cần Thơ", "Cần Thơ"));

        AIRPORT_MAP.put("phú quốc", new AirportInfo("PQC", "Sân bay Quốc tế Phú Quốc", "Kiên Giang"));
        AIRPORT_MAP.put("phu quoc", new AirportInfo("PQC", "Sân bay Quốc tế Phú Quốc", "Kiên Giang"));

        AIRPORT_MAP.put("rạch giá", new AirportInfo("VKG", "Sân bay Rạch Giá", "Kiên Giang"));
        AIRPORT_MAP.put("rach gia", new AirportInfo("VKG", "Sân bay Rạch Giá", "Kiên Giang"));
        AIRPORT_MAP.put("kiên giang", new AirportInfo("VKG", "Sân bay Rạch Giá", "Kiên Giang"));
        AIRPORT_MAP.put("kien giang", new AirportInfo("VKG", "Sân bay Rạch Giá", "Kiên Giang"));

        AIRPORT_MAP.put("cà mau", new AirportInfo("CAH", "Sân bay Cà Mau", "Cà Mau"));
        AIRPORT_MAP.put("ca mau", new AirportInfo("CAH", "Sân bay Cà Mau", "Cà Mau"));
    }

    public static Optional<AirportInfo> getAirportInfo(String locationName) {
        if (locationName == null || locationName.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalizeLocationName(locationName);
        return Optional.ofNullable(AIRPORT_MAP.get(normalized));
    }

    private static String normalizeLocationName(String name) {
        return name.toLowerCase()
                .trim()
                .replaceAll("\\s+", " ")
                .replace("tp.", "")
                .replace("thành phố", "")
                .replace("tỉnh", "")
                .trim();
    }

    public static Map<String, AirportInfo> getAllAirports() {
        return new HashMap<>(AIRPORT_MAP);
    }

    public static boolean isValidAirportCode(String code) {
        if (code == null) return false;
        return AIRPORT_MAP.values().stream()
                .anyMatch(info -> info.getCode().equalsIgnoreCase(code));
    }
}
