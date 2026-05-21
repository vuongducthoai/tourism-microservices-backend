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

    // Regex detect câu hỏi về giảm giá theo giá (originalPrice > salePrice)
    // Khớp cả có dấu lẫn không dấu (giam gia / giảm giá)
    private static final String DISCOUNT_PATTERN =
            ".*(giảm\\s*(giá|sâu)|giam\\s*(gia|sau)|ưu\\s*đãi|uu\\s*dai|khuyến\\s*mãi|khuyen\\s*mai"
            + "|rẻ\\s*nhất|re\\s*nhat|tiết\\s*kiệm|tiet\\s*kiem|sale|giá\\s*tốt|gia\\s*tot|giá\\s*rẻ|gia\\s*re"
            + "|ty\\s*le\\s*giam|tỷ\\s*lệ\\s*giảm).*";

    // Regex detect câu hỏi về coupon / mã giảm giá cụ thể (có dấu và không dấu)
    private static final String COUPON_PATTERN =
            ".*(coupon|mã\\s*giảm|ma\\s*giam|voucher|mã\\s*khuyến|ma\\s*khuyen|mã\\s*ưu|ma\\s*uu"
            + "|promo\\s*code|discount\\s*code).*";

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
        boolean isCouponQuery   = userMessage.toLowerCase().matches(COUPON_PATTERN);
        int topK = (isDiscountQuery || isCouponQuery) ? 50 : 10;

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

        String lowerMsg = userMessage.toLowerCase();
        boolean isDiscountQuery = lowerMsg.matches(DISCOUNT_PATTERN);
        boolean isCouponQuery   = lowerMsg.matches(COUPON_PATTERN);
        List<VectorDocumentDTO> displayDocs = docs;

        if (isCouponQuery) {
            // Hỏi về coupon → lọc tour có couponDiscount > 0, sort theo couponDiscount DESC
            List<VectorDocumentDTO> couponDocs = docs.stream()
                    .filter(d -> "TOUR_DEPARTURE".equals(d.getType()))
                    .filter(d -> {
                        if (d.getMetadata() == null) return false;
                        try {
                            Map<?,?> m = gson.fromJson(d.getMetadata(), Map.class);
                            double cd = m.containsKey("couponDiscount") ? ((Number) m.get("couponDiscount")).doubleValue() : 0;
                            return cd > 0;
                        } catch (Exception e) { return false; }
                    })
                    .sorted((d1, d2) -> {
                        try {
                            Object c1 = gson.fromJson(d1.getMetadata(), Map.class).get("couponDiscount");
                            Object c2 = gson.fromJson(d2.getMetadata(), Map.class).get("couponDiscount");
                            double v1 = c1 != null ? ((Number) c1).doubleValue() : 0;
                            double v2 = c2 != null ? ((Number) c2).doubleValue() : 0;
                            return Double.compare(v2, v1);
                        } catch (Exception e) { return 0; }
                    })
                    .collect(Collectors.toList());

            if (!couponDocs.isEmpty()) {
                log.info("✅ Tìm thấy {} tour có mã coupon", couponDocs.size());
                displayDocs = couponDocs;
            } else {
                log.warn("⚠️ Không tìm thấy tour nào có mã coupon");
            }
        } else if (isDiscountQuery) {
            // Hỏi về giảm giá sâu → lọc TOUR_DEPARTURE có originalPrice > salePrice, sort theo (orig - sale) DESC
            List<VectorDocumentDTO> discountDocs = docs.stream()
                    .filter(d -> "TOUR_DEPARTURE".equals(d.getType()))
                    .filter(d -> {
                        if (d.getMetadata() == null) return false;
                        try {
                            Map<?,?> m = gson.fromJson(d.getMetadata(), Map.class);
                            double sale = m.containsKey("salePrice") ? ((Number) m.get("salePrice")).doubleValue() : 0;
                            double orig = m.containsKey("originalPrice") ? ((Number) m.get("originalPrice")).doubleValue() : 0;
                            return orig > sale && sale > 0;
                        } catch (Exception e) { return false; }
                    })
                    .sorted((d1, d2) -> Double.compare(extractDiscountAmount(d2), extractDiscountAmount(d1)))
                    .collect(Collectors.toList());

            if (!discountDocs.isEmpty()) {
                log.info("✅ Tìm thấy {} tour giảm giá sâu (theo originalPrice - salePrice)", discountDocs.size());
                displayDocs = discountDocs;
            } else {
                log.warn("⚠️ Không tìm thấy tour nào có originalPrice > salePrice");
            }
        }

        StringBuilder context = new StringBuilder();

        // Build tour entries first, then prepend header with accurate count
        StringBuilder tourEntries = new StringBuilder();
        int addedCount = 0;

        for (int i = 0; i < displayDocs.size(); i++) {
            if (tourEntries.length() > 4000) break;
            VectorDocumentDTO doc = displayDocs.get(i);
            tourEntries.append(i + 1).append(". ").append(doc.getContent()).append("\n");

            try {
                Map<String, Object> meta = gson.fromJson(doc.getMetadata(), Map.class);
                if (meta == null) { context.append("\n"); continue; }

                if ("TOUR_DEPARTURE".equals(doc.getType())) {
                    double salePrice = toDouble(meta.get("salePrice"));
                    double origPrice = toDouble(meta.get("originalPrice"));
                    String tourName  = getString(meta, "tourName");
                    String tourCode  = getString(meta, "tourCode");
                    String deptDate  = getString(meta, "departureDate");

                    tourEntries.append("   [Tên tour: ").append(tourName)
                            .append(", Mã tour: ").append(tourCode)
                            .append(", Ngày: ").append(deptDate)
                            .append(", Giá ADULT: ").append(String.format("%,.0f", salePrice)).append(" VND");

                    // Hiển thị mức giảm giá thực tế (originalPrice - salePrice)
                    if (origPrice > 0 && salePrice > 0 && origPrice > salePrice) {
                        double priceDisc = origPrice - salePrice;
                        double pct = (priceDisc / origPrice) * 100;
                        tourEntries.append(", Giá gốc: ").append(String.format("%,.0f", origPrice)).append(" VND")
                                .append(", GIẢM GIÁ: ").append(String.format("%,.0f", priceDisc))
                                .append(" VND (").append(String.format("%.0f", pct)).append("%)");
                        log.debug("📊 Tour {} giảm giá: {} VND ({}%)", tourName,
                                String.format("%,.0f", priceDisc), String.format("%.0f", pct));
                    }
                    // Hiển thị coupon nếu có (độc lập, bổ sung thêm)
                    double couponDisc = toDouble(meta.get("couponDiscount"));
                    if (couponDisc > 0) {
                        String couponCode    = getString(meta, "couponCode");
                        String couponEndDate = getString(meta, "couponEndDate");
                        tourEntries.append(", 🎁 MÃ COUPON: ").append(couponCode.isEmpty() ? "N/A" : couponCode)
                                .append(" giảm thêm ").append(String.format("%,.0f", couponDisc)).append(" VND");
                        if (!couponEndDate.isEmpty()) {
                            tourEntries.append(" (HSD: ").append(couponEndDate).append(")");
                        }
                    }

                    tourEntries.append("]\n");
                    addedCount++;

                } else if ("LOCATION".equals(doc.getType())) {
                    Object locationId = meta.get("locationID");
                    String locationName = getString(meta, "name");
                    if (locationId != null) {
                        tourEntries.append("   [Địa điểm: ").append(locationName)
                                .append(", LocationID: ").append(((Number) locationId).intValue())
                                .append("]\n");
                    }
                    addedCount++;
                } else if ("COUPON".equals(doc.getType())) {
                    String couponCode    = getString(meta, "couponCode");
                    Object discountAmt   = meta.get("discountAmount");
                    String couponType    = getString(meta, "couponType");
                    String endDate       = getString(meta, "endDate");
                    Object remaining     = meta.get("usageLimit");
                    if (!couponCode.isEmpty()) {
                        tourEntries.append("   [🎁 COUPON: ").append(couponCode);
                        if (discountAmt != null)
                            tourEntries.append(", Giảm: ").append(String.format("%,.0f", ((Number) discountAmt).doubleValue())).append(" VND");
                        if ("GLOBAL".equals(couponType))
                            tourEntries.append(", Áp dụng: tất cả tour");
                        else
                            tourEntries.append(", Áp dụng: lịch khởi hành cụ thể");
                        if (!endDate.isEmpty())
                            tourEntries.append(", HSD: ").append(endDate.length() > 10 ? endDate.substring(0, 10) : endDate);
                        tourEntries.append("]\n");
                        addedCount++;
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing metadata for doc {}: {}", doc.getId(), e.getMessage());
            }
            tourEntries.append("\n");
        }

        // Build final context with accurate count in header
        context.append("Dữ liệu từ hệ thống");
        if (isCouponQuery && displayDocs != docs) {
            context.append(" - QUAN TRỌNG: Có CHÍNH XÁC ").append(addedCount)
                    .append(" tour đang có mã giảm giá coupon (sắp xếp theo mức coupon từ cao đến thấp)");
        } else if (isDiscountQuery && displayDocs != docs) {
            context.append(" - QUAN TRỌNG: Có CHÍNH XÁC ").append(addedCount)
                    .append(" tour đang được giảm giá (sắp xếp theo mức giảm sâu từ cao đến thấp)");
        }
        context.append(":\n\n").append(tourEntries);

        return context.toString();
    }

    /** Tính mức giảm giá theo originalPrice - salePrice (không tính coupon) */
    private double extractDiscountAmount(VectorDocumentDTO doc) {
        try {
            Map<?,?> m = gson.fromJson(doc.getMetadata(), Map.class);
            double sale = m.containsKey("salePrice") ? ((Number) m.get("salePrice")).doubleValue() : 0;
            double orig = m.containsKey("originalPrice") ? ((Number) m.get("originalPrice")).doubleValue() : 0;
            return Math.max(0, orig - sale);
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
            
            2. **⚠️ QUY TẮC PHÂN BIỆT GIẢM GIÁ VÀ COUPON:**
               
               📌 HAI KHÁI NIỆM KHÁC NHAU:
               - **GIẢM GIÁ** = "GIẢM GIÁ: X VND (Y%%)" trong context → chênh lệch giá gốc trừ giá bán
               - **COUPON** = "🎁 MÃ COUPON: CODE giảm thêm X VND" → mã giảm giá bổ sung, dùng khi đặt
               
               🚨 KHI HỎI VỀ GIẢM GIÁ SÂU NHẤT / TOUR RẺ NHẤT:
               - Sắp xếp tour theo cột "GIẢM GIÁ: X VND" từ cao đến thấp
               - Hiển thị rõ: Giá bán, Giá gốc, Số tiền tiết kiệm, Phần trăm giảm
               - BẮT BUỘC liệt kê đủ tất cả tour trong context
               
               🚨 KHI HỎI VỀ COUPON / MÃ GIẢM GIÁ:
               - Chỉ giới thiệu tour có dòng "🎁 MÃ COUPON:" trong context
               - Hiển thị rõ mã coupon code, số tiền giảm thêm, hạn sử dụng (HSD) nếu có
               - KHÔNG được bỏ qua bất kỳ tour nào có coupon
               
               🚨 TUYỆT ĐỐI PHẢI TUÂN THỦ:
               - Nếu context có "QUAN TRỌNG: Có CHÍNH XÁC X tour..." → LIỆT KÊ ĐỦ X TOUR
               - KHÔNG TỰ BỊA thêm tour không có trong context
               - KHÔNG BỎ QUA tour có mức giảm thấp hơn
               
               📝 FORMAT CHO MỖI TOUR:
               **[Tên Tour]**
               [Thời lượng] | [Ngày khởi hành]
               💰 Giá bán: X VND | Giá gốc: Y VND | Tiết kiệm: Z VND (W%%)
               🎁 Coupon: [MÃ] giảm thêm [số tiền] VND (HSD: ...) ← chỉ hiện nếu có coupon
               **[Xem chi tiết](/tour/TOUR-CODE)**
               
               ✅ VÍ DỤ (tour vừa có giảm giá vừa có coupon):
               **Tour Phú Quốc 3N2Đ**
               3 Ngày 2 Đêm | 20/12/2025
               💰 Giá bán: 7,000,000 VND | Giá gốc: 8,000,000 VND | Tiết kiệm: 1,000,000 VND (13%%)
               🎁 Coupon: SUMMER2027 giảm thêm 300,000 VND (HSD: 2027-12-31)
               **[Xem chi tiết](/tour/TOUR-PQ-01)**
            
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
        Map<String, Object> genConfig = new HashMap<>();
        genConfig.put("temperature",     0.2);
        genConfig.put("maxOutputTokens", 3000);

        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", prompt)))
        ));
        body.put("generationConfig", genConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        // Try primary model, then fallback, with 1 retry on 503
        String[] models = { generationModel, "gemini-flash-lite-latest", "gemini-2.0-flash-lite" };
        for (String model : models) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    String url = GEMINI_GENERATE_URL + model + ":generateContent?key=" + geminiApiKey;
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
                                log.info("✅ Chatbot Gemini OK model={} parts={} attempt={}", model, parts.size(), attempt);
                                StringBuilder sb = new StringBuilder();
                                for (Map<String, Object> part : parts) {
                                    Object t = part.get("text");
                                    if (t != null) sb.append(t.toString());
                                }
                                return sb.toString();
                            }
                        }
                    }
                } catch (org.springframework.web.client.HttpServerErrorException e) {
                    log.warn("⚠️ Gemini model={} attempt={} server error: {} — retrying…", model, attempt, e.getStatusCode());
                    if (attempt < 2) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                } catch (Exception e) {
                    log.error("❌ Error calling Gemini API model={}: {}", model, e.getMessage());
                    break;
                }
            }
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
