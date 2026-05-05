package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.VectorDocumentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ChatbotService — chuỗi RAG (Retrieval-Augmented Generation):
 *
 *   1. Nhận message từ user
 *   2. Embed câu hỏi → Pinecone search topK documents
 *   3. Build context từ retrieved documents
 *   4. Build prompt (Vietnamese) → gọi Gemini để sinh câu trả lời
 *   5. Build TourSuggestion từ metadata của retrieved documents
 *   6. Trả về ChatMessageResponse
 *
 * Không truy cập DB trực tiếp — toàn bộ thông tin từ Pinecone metadata.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.generation.model}")
    private String generationModel;

    private final VectorService vectorService;
    private final RestTemplate  restTemplate;
    private final Gson          gson = new Gson();

    private static final String GEMINI_GENERATE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    // Regex detect câu hỏi về giảm giá / ưu đãi
    private static final String DISCOUNT_PATTERN =
            ".*(giảm\\s*(giá|sâu)|ưu\\s*đãi|khuyến\\s*mãi|coupon|mã\\s*giảm|rẻ\\s*nhất|tiết\\s*kiệm).*";

    // ─────────────────────────────────────────────
    // MAIN HANDLER
    // ─────────────────────────────────────────────

    /**
     * Xử lý tin nhắn của user, trả về câu trả lời + tour gợi ý.
     */
    public ChatMessageResponse handleUserMessage(ChatMessageRequest request) {
        log.info("💬 Chatbot received: {}", request.getMessage());

        String userMessage = request.getMessage();
        String sessionId   = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();

        boolean isDiscountQuery = userMessage.toLowerCase().matches(DISCOUNT_PATTERN);
        int topK = isDiscountQuery ? 50 : 10;

        // 1. Retrieve from Pinecone
        List<VectorDocumentDTO> docs = vectorService.searchSimilar(userMessage, topK);
        log.debug("🔍 Retrieved {} documents from Pinecone (topK={})", docs.size(), topK);

        // 2. Build context
        String context = buildEnhancedContext(docs, userMessage);

        // 3. Build prompt & call Gemini
        String prompt = buildEnhancedPrompt(userMessage, context);
        String reply  = callGeminiAPI(prompt);

        // 4. Build tour suggestions
        List<ChatMessageResponse.TourSuggestion> suggestions = buildTourSuggestions(docs);

        // 5. Build quick actions
        List<ChatMessageResponse.QuickAction> quickActions = buildQuickActions(request);

        return ChatMessageResponse.builder()
                .reply(reply)
                .tourSuggestions(suggestions)
                .quickActions(quickActions)
                .sessionId(sessionId)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ─────────────────────────────────────────────
    // CONTEXT BUILDER
    // ─────────────────────────────────────────────

    /**
     * Xây dựng context string từ retrieved documents.
     * Format giống monolith: emit [Tên tour:...] và [Địa điểm: ..., LocationID: X] annotations
     * để Gemini đọc đúng giá, tour code, location ID.
     */
    String buildEnhancedContext(List<VectorDocumentDTO> docs, String userMessage) {
        if (docs == null || docs.isEmpty()) {
            return "Không tìm thấy thông tin liên quan trong hệ thống.";
        }

        boolean isDiscountQuery = userMessage.toLowerCase().matches(DISCOUNT_PATTERN);
        List<VectorDocumentDTO> displayDocs = docs;

        if (isDiscountQuery) {
            // Lọc TOUR_DEPARTURE có bất kỳ loại giảm giá (coupon hoặc regular)
            List<VectorDocumentDTO> discountDocs = docs.stream()
                    .filter(d -> "TOUR_DEPARTURE".equals(d.getType()))
                    .filter(d -> {
                        if (d.getMetadata() == null) return false;
                        try {
                            Map<?,?> m = gson.fromJson(d.getMetadata(), Map.class);
                            double couponDisc = m.containsKey("couponDiscount") ? ((Number) m.get("couponDiscount")).doubleValue() : 0;
                            double sale = m.containsKey("salePrice") ? ((Number) m.get("salePrice")).doubleValue() : 0;
                            double orig = m.containsKey("originalPrice") ? ((Number) m.get("originalPrice")).doubleValue() : 0;
                            return couponDisc > 0 || (orig > 0 && sale > 0 && sale < orig);
                        } catch (Exception e) { return false; }
                    })
                    .sorted((d1, d2) -> Double.compare(extractDiscountAmount(d2), extractDiscountAmount(d1)))
                    .collect(Collectors.toList());

            if (!discountDocs.isEmpty()) {
                log.info("✅ Tìm thấy {} tour có giảm giá", discountDocs.size());
                displayDocs = discountDocs;
            } else {
                log.warn("⚠️ Không tìm thấy tour nào có giảm giá");
            }
        }

        StringBuilder context = new StringBuilder("Dữ liệu từ hệ thống");

        // Emit QUAN TRỌNG message for discount queries
        if (isDiscountQuery && displayDocs != docs) {
            long couponCount = displayDocs.stream().filter(d -> {
                try {
                    Map<?,?> m = gson.fromJson(d.getMetadata(), Map.class);
                    return m.containsKey("couponDiscount") && ((Number) m.get("couponDiscount")).doubleValue() > 0;
                } catch (Exception e) { return false; }
            }).count();

            if (couponCount > 0) {
                context.append(" - QUAN TRỌNG: Có CHÍNH XÁC ").append(couponCount)
                        .append(" tour có mã giảm giá coupon (sắp xếp theo mức giảm từ cao đến thấp)");
            } else {
                context.append(" - QUAN TRỌNG: Có CHÍNH XÁC ").append(displayDocs.size())
                        .append(" tour đang có giảm giá (sắp xếp theo mức giảm từ cao đến thấp)");
            }
        }

        context.append(":\n\n");

        for (int i = 0; i < displayDocs.size(); i++) {
            if (context.length() > 3500) break;
            VectorDocumentDTO doc = displayDocs.get(i);
            context.append(i + 1).append(". ").append(doc.getContent()).append("\n");

            try {
                Map<String, Object> meta = gson.fromJson(doc.getMetadata(), Map.class);
                if (meta == null) { context.append("\n"); continue; }

                if ("TOUR_DEPARTURE".equals(doc.getType())) {
                    double salePrice = toDouble(meta.get("salePrice"));
                    double origPrice = toDouble(meta.get("originalPrice"));
                    String tourName  = getString(meta, "tourName");
                    String tourCode  = getString(meta, "tourCode");
                    String deptDate  = getString(meta, "departureDate");

                    context.append("   [Tên tour: ").append(tourName)
                            .append(", Mã tour: ").append(tourCode)
                            .append(", Ngày: ").append(deptDate)
                            .append(", Giá ADULT: ").append(String.format("%,.0f", salePrice)).append(" VND");

                    double couponDisc = toDouble(meta.get("couponDiscount"));
                    if (couponDisc > 0) {
                        // Có mã giảm giá coupon
                        context.append(", Giá gốc: ").append(String.format("%,.0f", origPrice)).append(" VND")
                                .append(", 🎁 MÃ GIẢM GIÁ ĐẶC BIỆT: ").append(String.format("%,.0f", couponDisc)).append(" VND");
                        String couponCode = getString(meta, "couponCode");
                        if (!couponCode.isEmpty()) {
                            context.append(" (Mã: ").append(couponCode).append(")");
                        }
                        log.info("📊 Tour {} có coupon giảm giá: {} VND", tourName, String.format("%,.0f", couponDisc));
                    } else if (origPrice > 0 && salePrice > 0 && salePrice < origPrice) {
                        // Giảm giá thông thường (không coupon)
                        double normalDisc = origPrice - salePrice;
                        context.append(", Giá gốc: ").append(String.format("%,.0f", origPrice)).append(" VND")
                                .append(", 🎁 MÃ GIẢM GIÁ ĐẶC BIỆT: ").append(String.format("%,.0f", normalDisc)).append(" VND");
                    }

                    context.append("]\n");

                } else if ("LOCATION".equals(doc.getType())) {
                    Object locationId = meta.get("locationID");
                    String locationName = getString(meta, "name");
                    if (locationId != null) {
                        context.append("   [Địa điểm: ").append(locationName)
                                .append(", LocationID: ").append(((Number) locationId).intValue())
                                .append("]\n");
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing metadata for doc {}: {}", doc.getId(), e.getMessage());
            }
            context.append("\n");
        }

        return context.toString();
    }

    /** Tính tổng giá trị giảm giá của 1 TOUR_DEPARTURE doc */
    private double extractDiscountAmount(VectorDocumentDTO doc) {
        try {
            Map<?,?> m = gson.fromJson(doc.getMetadata(), Map.class);
            double couponDisc = m.containsKey("couponDiscount") ? ((Number) m.get("couponDiscount")).doubleValue() : 0;
            double sale = m.containsKey("salePrice") ? ((Number) m.get("salePrice")).doubleValue() : 0;
            double orig = m.containsKey("originalPrice") ? ((Number) m.get("originalPrice")).doubleValue() : 0;
            return couponDisc + Math.max(0, orig - sale);
        } catch (Exception e) { return 0; }
    }

    // ─────────────────────────────────────────────
    // PROMPT BUILDER
    // ─────────────────────────────────────────────

    /**
     * Xây dựng prompt hoàn chỉnh bằng tiếng Việt để gửi cho Gemini.
     */
    String buildEnhancedPrompt(String userMessage, String context) {
        return String.format("""
                Bạn là Trợ lý Du lịch AI chuyên nghiệp, hiện đại và thân thiện của hệ thống Tourism.
            
            🔹 NHIỆM VỤ PHÂN TÍCH DỮ LIỆU:
            1. **Giá:** Luôn dùng "Giá ADULT" (người lớn) làm chuẩn.
            
            2. **⚠️ QUY TẮC BẮT BUỘC KHI NGƯỜI DÙNG HỎI VỀ GIẢM GIÁ/COUPON:**
               
               🚨 TUYỆT ĐỐI PHẢI TUÂN THỦ:
               - Nếu Context có dòng "QUAN TRỌNG: Có CHÍNH XÁC X tour có mã giảm giá coupon"
               - BẠN PHẢI GIỚI THIỆU **TẤT CẢ X TOUR ĐÓ**, KHÔNG ĐƯỢC BỎ QUA BẤT KỲ TOUR NÀO!
               - KHÔNG ĐƯỢC chỉ giới thiệu 1 hoặc một vài tour, PHẢI GIỚI THIỆU HET!
               
               📋 CÁCH NHẬN DIỆN TOUR CÓ COUPON:
               - Trong Context, tìm dòng có " MÃ GIẢM GIÁ ĐẶC BIỆT: X VND"
               - Tour KHÔNG có dòng này thì BỎ QUA, không được đề cập
               
               📝 FORMAT BẮT BUỘC:
               - Câu mở đầu: "Hiện tại có [SỐ LƯỢNG CHÍNH XÁC] tour đang có ưu đãi giảm giá đặc biệt:"
               - Liệt kê TỪNG TOUR theo thứ tự từ cao đến thấp
               - Mỗi tour PHẢI có đầy đủ: Tên, Thời lượng, Ngày, Giá gốc, Mã giảm giá, Link
               
               ✅ VÍ DỤ ĐÚNG (khi có 2 tour):
               ```
               Hiện tại có 2 tour đang có ưu đãi giảm giá đặc biệt:

                **Tour Phú Quốc 3N2Đ**
               3 Ngày 2 Đêm |  20/12/2025
               💰 Giá: 8,000,000 VND |  Mã giảm giá: 1,000,000 VND
               **[Xem chi tiết](/tour/TOUR-PQ-01)**

                **Tour Hà Giang 3N2Đ**
               3 Ngày 2 Đêm |  20/02/2026
               💰 Giá: 6,100,000 VND |  Mã giảm giá: 100,000 VND
               **[Xem chi tiết](/tour/TOUR-HG-04)**

               Bạn có thể xem thêm các tour khác trên hệ thống.
               ```
               
               ❌ SAI LẦM CẦN TRÁNH:
               - ❌ Chỉ giới thiệu 1 tour khi Context có 2 tour
               - ❌ Viết "có tour này" thay vì "có 2 tour"
               - ❌ Bỏ qua tour có mức giảm thấp hơn
            
            3. **Đánh giá:** Chỉ đề xuất tour có Rating >= 4.0 sao nếu khách hỏi về chất lượng.
            4. **Thời gian:** Ưu tiên các ngày khởi hành gần nhất so với hiện tại. Tất cả tour đề xuất đều có ngày khởi hành trong tương lai.
            
            🔹 QUY TẮC LINK (TUYỆT ĐỐI TUÂN THỦ):
            
            **A. Link Tour (Có Mã tour trong Context):**
            - Format: **[Xem chi tiết](/tour/TOUR-CODE)**
            - VÍ DỤ: Nếu context có "Mã tour: TOUR-HG-04" → Viết: **[Xem chi tiết](/tour/TOUR-HG-04)**
            - ❌ KHÔNG viết: /tour/TOUR-HG-04 (thiếu Markdown)
       
            
            **B. Link Địa điểm mà liên quan đến điểm khởi hành , điểm bắt đầu (Có LocationID trong Context):**
            - Format: **[Khám phá ngay](/tours?startLocationID=LOCATION_ID)**
            - VÍ DỤ: Nếu context có "LocationID: 5" → Viết: **[Khám phá ngay](/tours?startLocationID=5)**
            - ✅ LẤY LocationID TỪ CONTEXT: Trong dấu [...] sẽ có "LocationID: X"
            - ❌ KHÔNG tự bịa số, phải dùng số từ context
          
            **C. Link Địa điểm mà liên quan đến điểm đến , nơi muốn đến (Có LocationID trong Context):**
            - Format: **[Khám phá ngay](/tours?endLocationID=LOCATION_ID)**
            - VÍ DỤ: Nếu context có "LocationID: 5" → Viết: **[Khám phá ngay](/tours?endLocationID=5)**
            - ✅ LẤY LocationID TỪ CONTEXT: Trong dấu [...] sẽ có "LocationID: X"
            - ❌ KHÔNG tự bịa số, phải dùng số từ context
            
            **D. Nếu KHÔNG có Mã tour hoặc LocationID:**
            - Không chèn link, chỉ gợi ý tìm kiếm: "Bạn có thể tìm thêm các tour khác trên hệ thống."
            
            🔹 FORMAT VĂN BẢN (STYLE HIỆN ĐẠI & GỌN GÀNG):
            - **Không xuống dòng kép** giữa các thông tin của cùng một tour.
            - Khoảng cách giữa các đoạn không lớn.
            - **In đậm** tên Tour và các thông tin quan trọng.
            - **KHI CÓ NHIỀU TOUR**: Giới thiệu lần lượt từng tour, mỗi tour trên một đoạn riêng biệt.
            - Cấu trúc mong muốn cho mỗi tour:
               
               **[Tên Tour]**
               [Thời lượng] | [Ngày khởi hành]
               💰 Giá: [Giá gốc] VND |  Mã giảm giá: [Số tiền giảm] VND
               **[Xem chi tiết](/tour/TOUR-CODE)**
               
               (Xuống dòng trống trước khi giới thiệu tour tiếp theo)
            
            - Giọng văn: Thân thiện, nhiệt tình, súc tích.
            - MỞ ĐẦU: "Hiện tại có [số lượng] tour đang có ưu đãi giảm giá đặc biệt:"
            - KẾT THÚC: "Bạn có thể xem thêm các tour khác trên hệ thống."
            
            === DỮ LIỆU HỆ THỐNG (CONTEXT) ===
            %s
            
            === CÂU HỎI KHÁCH HÀNG ===
            "%s"
            
            === TRẢ LỜI CỦA BẠN (Markdown) ===
            """, context, userMessage);
    }

    // ─────────────────────────────────────────────
    // GEMINI API
    // ─────────────────────────────────────────────

    /**
     * Gọi Gemini generateContent API để sinh câu trả lời.
     * Temperature thấp (0.2) để giảm hallucination.
     */
    String callGeminiAPI(String prompt) {
        try {
            String url = GEMINI_GENERATE_URL + generationModel + ":generateContent?key=" + geminiApiKey;

            Map<String, Object> genConfig = new HashMap<>();
            genConfig.put("temperature",     0.2);
            genConfig.put("maxOutputTokens", 1000);

            Map<String, Object> body = new HashMap<>();
            body.put("contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
            ));
            body.put("generationConfig", genConfig);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, req, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<Map<String, Object>> candidates =
                        (List<Map<String, Object>>) response.getBody().get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    Map<String, Object> content =
                            (Map<String, Object>) candidates.get(0).get("content");
                    List<Map<String, Object>> parts =
                            (List<Map<String, Object>>) content.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error calling Gemini API: {}", e.getMessage(), e);
        }
        return "Xin lỗi, tôi đang gặp sự cố kỹ thuật. Vui lòng thử lại sau.";
    }

    // ─────────────────────────────────────────────
    // TOUR SUGGESTIONS
    // ─────────────────────────────────────────────

    /**
     * Xây dựng danh sách TourSuggestion từ metadata trong Pinecone results.
     * Deduplicate theo tourId, chọn departure có giá thấp nhất.
     */
    private List<ChatMessageResponse.TourSuggestion> buildTourSuggestions(List<VectorDocumentDTO> docs) {
        if (docs == null || docs.isEmpty()) return new ArrayList<>();

        Map<Integer, ChatMessageResponse.TourSuggestion> seen = new LinkedHashMap<>();

        for (VectorDocumentDTO doc : docs) {
            if (doc.getMetadata() == null) continue;
            if (seen.size() >= 6) break;

            try {
                Map<String, Object> meta = gson.fromJson(doc.getMetadata(), Map.class);
                if (meta == null) continue;

                Integer tourId   = toInt(meta.get("tourId"));
                String  tourCode = getString(meta, "tourCode");
                String  tourName = getString(meta, "tourName");
                if (tourId == null || tourCode.isEmpty()) continue;

                double salePrice = toDouble(meta.get("salePrice"), meta.get("minPrice"));

                if (seen.containsKey(tourId)) {
                    // Update min price
                    ChatMessageResponse.TourSuggestion existing = seen.get(tourId);
                    if (salePrice > 0 && (existing.getMinPrice() == null || salePrice < existing.getMinPrice())) {
                        existing.setMinPrice(salePrice);
                    }
                } else {
                    seen.put(tourId, ChatMessageResponse.TourSuggestion.builder()
                            .tourId(tourId)
                            .tourCode(tourCode)
                            .tourName(tourName)
                            .imageUrl(getString(meta, "imageUrl"))
                            .minPrice(salePrice > 0 ? salePrice : null)
                            .duration(getString(meta, "duration"))
                            .detailUrl("/tour/" + tourCode)
                            .relevanceScore(doc.getScore() != null ? doc.getScore().doubleValue() : 0.0)
                            .build());
                }
            } catch (Exception e) {
                log.debug("Failed to parse metadata for suggestion: {}", e.getMessage());
            }
        }

        return new ArrayList<>(seen.values());
    }

    // ─────────────────────────────────────────────
    // QUICK ACTIONS
    // ─────────────────────────────────────────────

    private List<ChatMessageResponse.QuickAction> buildQuickActions(ChatMessageRequest request) {
        List<ChatMessageResponse.QuickAction> actions = new ArrayList<>();
        String message = request.getMessage().toLowerCase();

        if (message.contains("giảm giá") || message.contains("khuyến mãi") || message.contains("ưu đãi") || message.contains("coupon")) {
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("💰 Tours giảm giá sốc").action("VIEW_DEALS").url("/tour?filter=discount").build());
        }
        if (message.contains("yêu thích") || message.contains("đánh giá cao") || message.contains("tốt nhất")) {
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("⭐ Tours được yêu thích").action("VIEW_FAVORITES").url("/tour?sort=rating").build());
        }
        if (message.contains("gần nhất") || message.contains("sắp khởi hành") || message.contains("sắp đi")) {
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("📅 Khởi hành gần nhất").action("VIEW_UPCOMING").url("/tour?sort=date").build());
        }

        // Default actions nếu không có context cụ thể
        if (actions.isEmpty()) {
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("Xem tour giảm giá").action("navigate").url("/tours?sort=discount").build());
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("Tour miền Bắc").action("navigate").url("/tours?region=NORTH").build());
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("Tour miền Nam").action("navigate").url("/tours?region=SOUTH").build());
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("Xem tất cả tour").action("navigate").url("/tours").build());
        }
        return actions;
    }

    // ─────────────────────────────────────────────
    // UTILS
    // ─────────────────────────────────────────────

    private Integer toInt(Object val) {
        if (val == null) return null;
        try { return ((Number) val).intValue(); } catch (Exception e) { return null; }
    }

    private double toDouble(Object... vals) {
        for (Object v : vals) {
            if (v != null) {
                try {
                    double d = ((Number) v).doubleValue();
                    if (d > 0) return d;
                } catch (Exception ignored) {}
            }
        }
        return 0.0;
    }

    private String getString(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v != null ? v.toString() : "";
    }
}
