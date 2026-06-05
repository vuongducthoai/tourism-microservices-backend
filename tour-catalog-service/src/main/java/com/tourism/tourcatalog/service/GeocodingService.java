package com.tourism.tourcatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Reverse geocoding (toạ độ → tên địa điểm) qua Nominatim (OpenStreetMap).
 * Chạy ở backend (Docker có dns 8.8.8.8) nên ổn định kể cả khi client dùng mạng có DNS yếu.
 */
@Slf4j
@Service
public class GeocodingService {

    private static final String NOMINATIM_BASE = "https://nominatim.openstreetmap.org/reverse";
    private final WebClient webClient;

    public GeocodingService() {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.webClient = WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(jdk))
                // Nominatim yêu cầu User-Agent định danh, nếu không sẽ bị từ chối.
                .defaultHeader("User-Agent", "TourismMicroservices/1.0 (admin tour route editor)")
                .build();
    }

    /** Trả về tên địa điểm gợi ý từ lat/lng, hoặc null nếu không có/lỗi. */
    public String reverse(double lat, double lng) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(NOMINATIM_BASE)
                    .queryParam("format", "jsonv2")
                    .queryParam("lat", lat)
                    .queryParam("lon", lng)
                    .queryParam("zoom", 16)
                    .queryParam("accept-language", "vi")
                    .toUriString();

            JsonNode root = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(12))
                    .block();

            if (root == null) return null;
            JsonNode a = root.path("address");
            // Ưu tiên tên địa danh cụ thể → khu vực → tên hiển thị đầu tiên.
            String[] keys = {"tourism", "attraction", "leisure", "natural", "building",
                    "suburb", "village", "town", "city_district", "neighbourhood"};
            for (String k : keys) {
                String v = a.path(k).asText(null);
                if (v != null && !v.isBlank()) return v;
            }
            String display = root.path("display_name").asText(null);
            if (display != null && !display.isBlank()) {
                return display.split(",")[0].trim();
            }
            return null;
        } catch (Exception e) {
            log.warn("Reverse geocode failed for {},{}: {}", lat, lng, e.getMessage());
            return null;
        }
    }
}
