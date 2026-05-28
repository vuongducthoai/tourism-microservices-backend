package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.VectorDocumentDTO;
import com.tourism.analytics.dto.chatbot.*;
import com.tourism.analytics.dto.feign.ChatbotDepartureInfoResponse;
import com.tourism.analytics.feign.ChatbotBookingFeignClient;
import com.tourism.analytics.feign.ChatbotPaymentFeignClient;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BookingConversationService — xử lý stateful booking flow trong chatbot.
 * ChatbotService ủy quyền cho service này khi phát hiện booking/lookup intent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingConversationService {

    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter RAW_FMT     = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    private final RedisSessionService        sessionService;
    private final VectorService              vectorService;
    private final LocationResolverService    locationResolver;
    private final TourCatalogFeignClient     tourCatalogClient;
    private final ChatbotBookingFeignClient  bookingClient;
    private final ChatbotPaymentFeignClient  paymentClient;
    private final Gson                       gson = new Gson();

    // ─────────────────────────────────────────────────────────────────
    // ENTRY POINT
    // ─────────────────────────────────────────────────────────────────

    public ChatMessageResponse handle(ChatMessageRequest request, ConversationState state) {
        String msg = request.getMessage().trim();

        // ── Global BK lookup — hoạt động ở MỌI stage ──
        if (msg.matches("(?i)BK[A-Za-z0-9]{8,}")) {
            log.info("🔍 Global BK lookup at stage {}: {}", state.getStage(), msg);
            return performLookup(msg.trim(), request.getSessionId(), state);
        }

        // Global cancel check in any active booking stage
        if (isCancel(msg) && state.getStage() != ConversationState.Stage.IDLE) {
            state.setStage(ConversationState.Stage.IDLE);
            state.setPassengers(new ArrayList<>());
            sessionService.save(request.getSessionId(), state);
            return text("Đã hủy. Bạn cần tư vấn hay đặt tour gì khác không? 😊", request.getSessionId(), "IDLE");
        }

        return switch (state.getStage()) {
            case IDLE                           -> handleIdle(msg, request.getSessionId(), state, request.getUserId());
            case COLLECTING_SEARCH_INFO         -> handleSearchInfo(msg, request.getSessionId(), state);
            case SHOWING_SEARCH_RESULTS         -> handleTourSelection(msg, request.getSessionId(), state);
            case SELECTING_DEPARTURE            -> handleDepartureSelection(msg, request.getSessionId(), state);
            case COLLECTING_PASSENGERS          -> handlePassengerInfo(msg, request.getSessionId(), state);
            case COLLECTING_CONTACT_NAME_PHONE  -> handleContactNamePhone(msg, request.getSessionId(), state);
            case COLLECTING_CONTACT_EMAIL       -> handleContactEmail(msg, request.getSessionId(), state, request.getUserId());
            case CONFIRMING_BOOKING             -> handleConfirm(msg, request.getSessionId(), state, request.getUserId());
            case BOOKING_SUCCESS                -> handleAfterSuccess(msg, request.getSessionId(), state);
            case COLLECTING_LOOKUP_CODE         -> handleLookup(msg, request.getSessionId(), state);
        };
    }

    // ─────────────────────────────────────────────────────────────────
    // STAGE HANDLERS
    // ─────────────────────────────────────────────────────────────────

    private ChatMessageResponse handleIdle(String msg, String sessionId, ConversationState state, Integer userId) {
        if (isBookingIntent(msg)) {
            state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
            // Try to parse params right from first message
            parseAndFillSearchParamsV2(msg, state);
            ChatMessageResponse clarify = askForMissingSearchInfoIfNeeded(sessionId, state);
            if (clarify != null) {
                return clarify;
            }
            if (hasEnoughSearchParams(state)) {
                return doSearch(sessionId, state);
            }
            // B4: if destination is present, search immediately even without all params
            if (state.getSearchDestination() != null && !state.getSearchDestination().isBlank()) {
                return doSearch(sessionId, state);
            }
            sessionService.save(sessionId, state);
            return text("""
                    Tuyệt! Cho tôi biết thêm để tìm tour phù hợp nhất cho bạn 🗺️
                    
                    1. **Điểm đến** bạn muốn đến? (ví dụ: Đà Nẵng, Phú Quốc, Hội An...)
                    2. **Thời gian** dự kiến? (ví dụ: tháng 6, tuần sau, 20/07...)
                    3. **Số người**: mấy người lớn? Có trẻ em/em bé không?
                    """, sessionId, "COLLECTING_SEARCH_INFO");
        }
        if (isLookupIntent(msg)) {
            // Check if bookingCode is already in message
            String code = extractBookingCode(msg);
            if (code != null) {
                return performLookup(code, sessionId, state);
            }
            state.setStage(ConversationState.Stage.COLLECTING_LOOKUP_CODE);
            sessionService.save(sessionId, state);
            return text("Vui lòng cho tôi biết **mã đặt tour** của bạn (ví dụ: BK3f7a9c12):", sessionId, "COLLECTING_LOOKUP_CODE");
        }
        return null; // caller falls back to RAG
    }

    private ChatMessageResponse handleSearchInfo(String msg, String sessionId, ConversationState state) {
        // B2: track destination before parse to detect changes
        String prevDestination = state.getSearchDestination();

        parseAndFillSearchParamsV2(msg, state);

        // B2: if destination changed, clear cached search results from previous query
        String newDestination = state.getSearchDestination();
        if (prevDestination != null && newDestination != null && !prevDestination.equalsIgnoreCase(newDestination)) {
            log.info("Destination changed: {} → {}. Clearing cached search results.", prevDestination, newDestination);
            state.setLastSearchResults(null);
            state.setLastDepartures(null);
            state.setLastMentionedTourId(null);
        }

        ChatMessageResponse clarify = askForMissingSearchInfoIfNeeded(sessionId, state);
        if (clarify != null) {
            return clarify;
        }
        if (!hasEnoughSearchParams(state)) {
            // B4: if destination is present, search immediately even without full params
            if (state.getSearchDestination() != null && !state.getSearchDestination().isBlank()) {
                return doSearch(sessionId, state);
            }
            sessionService.save(sessionId, state);
            return text("Bạn muốn đến **đâu** và đi vào **khoảng thời gian** nào? Mấy **người lớn**? 🙂", sessionId, "COLLECTING_SEARCH_INFO");
        }
        return doSearch(sessionId, state);
    }

    private ChatMessageResponse askForMissingSearchInfoIfNeeded(String sessionId, ConversationState state) {
        if (state.getSearchDestination() == null || state.getSearchDestination().isBlank()) return null;
        boolean hasStart = state.getSearchStartLocation() != null && !state.getSearchStartLocation().isBlank();
        if (hasStart && state.isSearchDateRangeProvided() && state.isSearchAdultsProvided()) return null;

        if (!destinationHasAnyTour(state.getSearchDestination())) {
            String dest = state.getSearchDestination();
            state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
            state.setSearchDestination(null);
            sessionService.save(sessionId, state);
            return text("Hiá»‡n mÃ¬nh chÆ°a tháº¥y tour **" + dest + "** Ä‘ang má»Ÿ bÃ¡n trong há»‡ thá»‘ng.\n\n"
                    + "Báº¡n muá»‘n Ä‘á»•i sang Ä‘iá»ƒm Ä‘áº¿n khÃ¡c, hay Ä‘á»ƒ mÃ¬nh gá»£i Ã½ tour gáº§n giÃ¡ trá»‹ tÆ°Æ¡ng tá»±?", sessionId, "COLLECTING_SEARCH_INFO");
        }

        StringBuilder sb = new StringBuilder("Dáº¡ tuyá»‡t vá»i, **")
                .append(state.getSearchDestination())
                .append("** lÃ  lá»±a chá»n ráº¥t thÃº vá»‹ áº¡! Äá»ƒ mÃ¬nh tÃ¬m tour phÃ¹ há»£p nháº¥t, báº¡n cho mÃ¬nh biáº¿t thÃªm:\n\n");
        if (!hasStart) sb.append("â€¢ Khá»Ÿi hÃ nh tá»« Ä‘Ã¢u áº¡? (HCM, HÃ  Ná»™i, ÄÃ  Náºµng...)\n");
        if (!state.isSearchDateRangeProvided()) sb.append("â€¢ Dá»± kiáº¿n Ä‘i thÃ¡ng máº¥y hoáº·c khoáº£ng thá»i gian nÃ o?\n");
        if (!state.isSearchAdultsProvided()) sb.append("â€¢ Äi bao nhiÃªu ngÆ°á»i lá»›n? CÃ³ tráº» em/em bÃ© khÃ´ng?\n");
        sessionService.save(sessionId, state);
        return text(sb.toString(), sessionId, "COLLECTING_SEARCH_INFO");
    }

    private boolean destinationHasAnyTour(String destination) {
        try {
            String normalizedDest = normalizeLocation(destination);
            return vectorService.searchSimilar("tour " + destination, 50).stream()
                    .filter(d -> "TOUR_DEPARTURE".equals(d.getType()))
                    .anyMatch(d -> {
                        try {
                            Map<String, Object> m = gson.fromJson(d.getMetadata(), Map.class);
                            String endLoc = normalizeLocation(String.valueOf(m.getOrDefault("endLocationName", "")));
                            String tourName = normalizeLocation(String.valueOf(m.getOrDefault("tourName", "")));
                            return endLoc.contains(normalizedDest) || tourName.contains(normalizedDest);
                        } catch (Exception e) {
                            return false;
                        }
                    });
        } catch (Exception e) {
            log.warn("Destination precheck failed: {}", e.getMessage());
            return true;
        }
    }

    private ChatMessageResponse doSearch(String sessionId, ConversationState state) {
        String query = buildSearchQuery(state);
        // topK=50 để có đủ kết quả sau khi Java filter 2 tầng (destination + startLocation)
        List<VectorDocumentDTO> docs = vectorService.searchSimilar(query, 50);

        // Filter only TOUR_DEPARTURE docs + Java-side destination + startLocation filter
        String destFilter  = state.getSearchDestination();
        String startFilter = state.getSearchStartLocation();
        List<VectorDocumentDTO> departureDocs = docs.stream()
                .filter(d -> "TOUR_DEPARTURE".equals(d.getType()))
                .filter(d -> {
                    if (destFilter == null || destFilter.isEmpty()) return true;
                    try {
                        Map<String, Object> m = gson.fromJson(d.getMetadata(), Map.class);
                        String normalizedDest = normalizeLocation(destFilter);
                        String endLoc   = normalizeLocation(String.valueOf(m.getOrDefault("endLocationName", "")));
                        String tourName = normalizeLocation(String.valueOf(m.getOrDefault("tourName", "")));
                        return endLoc.contains(normalizedDest) || tourName.contains(normalizedDest);
                    } catch (Exception e) { return true; }
                })
                .filter(d -> {
                    if (startFilter == null || startFilter.isEmpty()) return true;
                    try {
                        Map<String, Object> m = gson.fromJson(d.getMetadata(), Map.class);
                        String normStart = normalizeLocation(startFilter);
                        String startLoc  = normalizeLocation(String.valueOf(m.getOrDefault("startLocationName", "")));
                        return startLoc.contains(normStart)
                                || ("hcm".equals(normStart) && (startLoc.contains("ho chi minh") || startLoc.contains("sai gon") || startLoc.contains("tp ho chi minh")))
                                || ("ha noi".equals(normStart) && startLoc.contains("ha noi"))
                                || ("da nang".equals(normStart) && startLoc.contains("da nang"));
                    } catch (Exception e) { return true; }
                })
                .collect(Collectors.toList());

        // Không fallback tour ngẫu nhiên — báo rõ không tìm thấy
        if (departureDocs.isEmpty()) {
            state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
            state.setSearchDestination(null);
            state.setSearchStartLocation(null);
            sessionService.save(sessionId, state);
            String destMsg = destFilter != null ? " đến **" + destFilter + "**" : "";
            return text("Mình chưa tìm được tour nào" + destMsg + " phù hợp ở thời điểm này 😕\n\n"
                      + "Bạn thử:\n"
                      + "• Đổi điểm đến (ví dụ: **Đà Nẵng**, **Nha Trang**, **Phú Quốc**)\n"
                      + "• Thay đổi thời gian\n"
                      + "• Hoặc mô tả lại tour bạn muốn", sessionId, "COLLECTING_SEARCH_INFO");
        }

        // Group by tourId — max 3 tours, max 3 departures each
        Map<Integer, List<VectorDocumentDTO>> grouped = new LinkedHashMap<>();
        for (VectorDocumentDTO doc : departureDocs) {
            try {
                Map<String, Object> meta = gson.fromJson(doc.getMetadata(), Map.class);
                int tourId = ((Number) meta.get("tourId")).intValue();
                grouped.computeIfAbsent(tourId, k -> new ArrayList<>()).add(doc);
            } catch (Exception ignored) {}
        }

        List<ConversationState.TourGroupDisplay> tourGroups = new ArrayList<>();
        List<ConversationState.DepartureMeta>    allDepartures = new ArrayList<>();

        int tourCount = 0;
        for (Map.Entry<Integer, List<VectorDocumentDTO>> entry : grouped.entrySet()) {
            if (tourCount >= 3) break;
            List<VectorDocumentDTO> tourDocs = entry.getValue();
            try {
                Map<String, Object> meta = gson.fromJson(tourDocs.get(0).getMetadata(), Map.class);

                List<ConversationState.DepartureMeta> deps = new ArrayList<>();
                for (int i = 0; i < Math.min(3, tourDocs.size()); i++) {
                    Map<String, Object> dm = gson.fromJson(tourDocs.get(i).getMetadata(), Map.class);
                    ConversationState.DepartureMeta dep = ConversationState.DepartureMeta.builder()
                            .departureId(((Number) dm.get("departureID")).intValue())
                            .departureDate((String) dm.get("departureDate"))
                            .availableSlots(dm.get("availableSlots") != null ? ((Number) dm.get("availableSlots")).intValue() : 0)
                            .salePrice(dm.get("salePrice") != null ? ((Number) dm.get("salePrice")).longValue() : 0L)
                            .build();
                    deps.add(dep);
                    allDepartures.add(dep);
                }

                ConversationState.TourGroupDisplay group = ConversationState.TourGroupDisplay.builder()
                        .tourId(entry.getKey())
                        .tourCode((String) meta.get("tourCode"))
                        .tourName((String) meta.get("tourName"))
                        .imageUrl((String) meta.get("imageUrl"))
                        .duration((String) meta.get("duration"))
                        .startLocationName((String) meta.get("startLocationName"))
                        .adultSalePrice(meta.get("salePrice") != null ? ((Number) meta.get("salePrice")).longValue() : 0L)
                        .departures(deps)
                        .build();
                tourGroups.add(group);
                tourCount++;
            } catch (Exception e) {
                log.warn("Error parsing tour group: {}", e.getMessage());
            }
        }

        state.setLastSearchResults(tourGroups);
        state.setLastDepartures(allDepartures);
        // Cập nhật lastMentionedTourId = tour đầu tiên trong kết quả
        if (!tourGroups.isEmpty()) {
            state.setLastMentionedTourId(tourGroups.get(0).getTourId());
            if (!tourGroups.get(0).getDepartures().isEmpty()) {
                state.setLastMentionedDepartureId(tourGroups.get(0).getDepartures().get(0).getDepartureId());
            }
        }
        state.setStage(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
        sessionService.save(sessionId, state);

        // Build reply
        StringBuilder sb = new StringBuilder("Tôi tìm được **" + tourGroups.size() + " tour** phù hợp cho bạn:\n\n");
        for (int i = 0; i < tourGroups.size(); i++) {
            ConversationState.TourGroupDisplay g = tourGroups.get(i);
            sb.append("**[Tour ").append(i + 1).append("]** 🏖️ ").append(g.getTourName()).append("\n");
            sb.append("  ✈️ ").append(g.getStartLocationName() != null ? g.getStartLocationName() : "").append(" | ").append(g.getDuration()).append("\n");
            sb.append("  💰 Từ ").append(String.format("%,.0f", (double) g.getAdultSalePrice())).append("đ/người lớn\n");
            sb.append("  📅 Ngày KH: ");
            g.getDepartures().forEach(d -> sb.append("[").append(formatDate(d.getDepartureDate())).append("] "));
            sb.append("\n\n");
        }
        sb.append("Bạn thích **tour nào**? (nhập 1, 2 hoặc 3) 😊");

        // Build tourSuggestion list for frontend cards
        List<ChatMessageResponse.TourSuggestion> suggestions = tourGroups.stream().map(g ->
                ChatMessageResponse.TourSuggestion.builder()
                        .tourId(g.getTourId()).tourCode(g.getTourCode()).tourName(g.getTourName())
                        .imageUrl(g.getImageUrl()).duration(g.getDuration())
                        .minPrice(g.getAdultSalePrice() != null ? g.getAdultSalePrice().doubleValue() : 0.0)
                        .detailUrl("/tour/" + g.getTourCode())
                        .build()
        ).collect(Collectors.toList());

        return ChatMessageResponse.builder()
                .reply(sb.toString()).sessionId(sessionId).timestamp(java.time.LocalDateTime.now())
                .messageType("TOUR_SUGGESTIONS").conversationStage("SHOWING_SEARCH_RESULTS")
                .tourSuggestions(suggestions)
                .quickActions(List.of(
                        ChatMessageResponse.QuickAction.builder().label("🔄 Tìm lại").action("RESET_SEARCH").build(),
                        ChatMessageResponse.QuickAction.builder().label("❌ Hủy").action("CANCEL").build()))
                .build();
    }

    private ChatMessageResponse handleTourSelection(String msg, String sessionId, ConversationState state) {
        List<ConversationState.TourGroupDisplay> groups = state.getLastSearchResults();
        if (groups == null || groups.isEmpty()) {
            state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
            sessionService.save(sessionId, state);
            return text("Hết phiên tìm kiếm rồi, bạn hãy mô tả lại tour muốn đặt nhé! 😊", sessionId, "COLLECTING_SEARCH_INFO");
        }

        int idx = parseTourIndex(msg, groups);
        boolean explicitNewSearch = normalizeLocation(msg).matches(".*(tim\\s*lai|tim\\s*tour\\s*khac|tour\\s*khac|doi\\s*sang|doi\\s*diem|di\\s*bien|di\\s*nui).*");
        if (idx < 0) {
            // Off-topic → return null, ChatbotService xử lý bằng IntentRouter/RAG
            // Không trả lời "Nhập 1,2,3" khi user hỏi câu khác (ví dụ: "còn mấy slot?")
            String lower = msg.toLowerCase();
            if (!explicitNewSearch) {
                return null;
            }
            if (lower.length() > 3 && (lower.contains("tour") || lower.contains("đi đến") || lower.contains("đi du lịch"))) {
                // Re-search request: cất destination cũ, parse lại
                state.setSearchDestination(null);
                state.setSearchStartLocation(null);
                state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
                parseAndFillSearchParamsV2(msg, state);
                if (hasEnoughSearchParams(state)) return doSearch(sessionId, state);
                sessionService.save(sessionId, state);
                return text("Bạn muốn tìm tour đến đâu? 🗺️", sessionId, "COLLECTING_SEARCH_INFO");
            }
            // Nhượng cho ChatbotService xử lý (ASK_SLOT, RAG, ...)
            return null;
        }

        ConversationState.TourGroupDisplay selected = groups.get(idx);
        state.setSelectedTourId(selected.getTourId());
        state.setSelectedTourCode(selected.getTourCode());
        state.setSelectedTourName(selected.getTourName());
        state.setSelectedTourImage(selected.getImageUrl());
        state.setSelectedDuration(selected.getDuration());
        // Fix: set departureCity từ startLocationName của tour đã chọn
        if (selected.getStartLocationName() != null) {
            state.setDepartureCity(selected.getStartLocationName());
        }
        // Cập nhật lastMentionedTourId
        state.setLastMentionedTourId(selected.getTourId());
        state.setStage(ConversationState.Stage.SELECTING_DEPARTURE);
        sessionService.save(sessionId, state);

        StringBuilder sb = new StringBuilder("Bạn đã chọn: **" + selected.getTourName() + "**\n\n");
        sb.append("📅 Chọn **ngày khởi hành**:\n");
        for (ConversationState.DepartureMeta dep : selected.getDepartures()) {
            sb.append("  • **[").append(formatDate(dep.getDepartureDate())).append("]**");
            if (dep.getAvailableSlots() != null) sb.append(" — còn ").append(dep.getAvailableSlots()).append(" chỗ");
            sb.append("\n");
        }
        sb.append("\nNhập ngày bạn chọn (ví dụ: 18/06):");

        return text(sb.toString(), sessionId, "SELECTING_DEPARTURE");
    }

    private ChatMessageResponse handleDepartureSelection(String msg, String sessionId, ConversationState state) {
        ConversationState.TourGroupDisplay selectedTour = state.getLastSearchResults() == null ? null
                : state.getLastSearchResults().stream()
                .filter(g -> Objects.equals(g.getTourId(), state.getSelectedTourId()))
                .findFirst().orElse(null);

        if (selectedTour == null) {
            state.setStage(ConversationState.Stage.IDLE);
            sessionService.save(sessionId, state);
            return text("Đã hết phiên, vui lòng đặt lại từ đầu nhé! 😊", sessionId, "IDLE");
        }

        ConversationState.DepartureMeta matched = null;
        for (ConversationState.DepartureMeta dep : selectedTour.getDepartures()) {
            if (dateMatches(msg, dep.getDepartureDate())) {
                matched = dep;
                break;
            }
        }

        if (matched == null) {
            // Nếu trông giống ngày tháng → re-show danh sách; nếu off-topic → trả null cho ChatbotService
            boolean looksLikeDate = msg.matches(".*\\d{1,2}[/\\-.]\\d{1,2}.*") || msg.toLowerCase().matches(".*ngày.*\\d+.*");
            if (looksLikeDate) {
                // Hiển thị lại danh sách ngày
                StringBuilder sb2 = new StringBuilder("❓ Mình không tìm thấy ngày đó trong danh sách.\n\nCác ngày khởi hành có sẵn:\n");
                for (ConversationState.DepartureMeta dep : selectedTour.getDepartures()) {
                    sb2.append("  • **").append(formatDate(dep.getDepartureDate())).append("**");
                    if (dep.getAvailableSlots() != null) sb2.append(" — còn ").append(dep.getAvailableSlots()).append(" chỗ");
                    sb2.append("\n");
                }
                sb2.append("\nNhập ngày bạ muốn chọn (ví dụ: 18/06):");
                return text(sb2.toString(), sessionId, "SELECTING_DEPARTURE");
            }
            // Off-topic → trả null để ChatbotService chuyển sang RAG
            return null;
        }

        // Check availability
        int needed = state.getSearchAdults() + state.getSearchChildren() + state.getSearchToddlers();
        if (matched.getAvailableSlots() != null && matched.getAvailableSlots() < needed) {
            return text("Ngày **" + formatDate(matched.getDepartureDate()) + "** chỉ còn " + matched.getAvailableSlots() + " chỗ, không đủ cho " + needed + " người. Bạn chọn ngày khác nhé!", sessionId, "SELECTING_DEPARTURE");
        }

        state.setSelectedDepartureId(matched.getDepartureId());
        state.setDepartureDateDisplay(formatDate(matched.getDepartureDate()));
        state.setDepartureDateRaw(matched.getDepartureDate());

        // Fetch full pricing
        try {
            ChatbotDepartureInfoResponse pricing = tourCatalogClient.getDepartureOrderInfo(matched.getDepartureId());
            state.setAdultPrice(pricing.getAdultPrice() != null ? pricing.getAdultPrice().longValue() : matched.getSalePrice());
            state.setChildPrice(pricing.getChildPrice() != null ? pricing.getChildPrice().longValue() : 0L);
            state.setToddlerPrice(pricing.getToddlerPrice() != null ? pricing.getToddlerPrice().longValue() : 0L);
            state.setInfantPrice(pricing.getInfantPrice() != null ? pricing.getInfantPrice().longValue() : 0L);
            state.setSingleRoomSurcharge(pricing.getSingleRoomSurcharge() != null ? pricing.getSingleRoomSurcharge().longValue() : 0L);
            state.setAvailableSlots(pricing.getAvailableSlots());
        } catch (Exception e) {
            log.warn("⚠️ Không lấy được pricing cho departure {}: {}", matched.getDepartureId(), e.getMessage());
            state.setAdultPrice(matched.getSalePrice());
        }

        state.setStage(ConversationState.Stage.COLLECTING_PASSENGERS);

        if (!state.isSearchAdultsProvided()) {
            state.setPassengers(new ArrayList<>());
            state.setCurrentPassengerIndex(0);
            sessionService.save(sessionId, state);
            return text(buildPassengerCompositionPrompt(state), sessionId, "COLLECTING_PASSENGERS");
        }

        initPassengerSlotsFromCounts(state);
        sessionService.save(sessionId, state);

        ConversationState.PassengerData first = state.getPassengers().get(0);
        String typeVi = typeToVietnamese(first.getType());
        return text("""
                Đã chọn ngày **%s** ✅
                
                Bây giờ mình cần thông tin hành khách.
                
                **Hành khách 1 (%s):**
                Cho tôi biết họ tên đầy đủ và giới tính (ví dụ: *Nguyễn Văn A, Nam*)
                """.formatted(state.getDepartureDateDisplay(), typeVi), sessionId, "COLLECTING_PASSENGERS");
    }

    private ChatMessageResponse handlePassengerInfo(String msg, String sessionId, ConversationState state) {
        List<ConversationState.PassengerData> passengers = state.getPassengers();
        if (passengers == null || passengers.isEmpty()) {
            if (!parsePassengerComposition(msg, state)) {
                return text(buildPassengerCompositionPrompt(state), sessionId, "COLLECTING_PASSENGERS");
            }
            int needed = state.getSearchAdults() + state.getSearchChildren() + state.getSearchToddlers();
            if (state.getAvailableSlots() != null && needed > state.getAvailableSlots()) {
                return text("NgÃ y nÃ y chá»‰ cÃ²n **" + state.getAvailableSlots() + " chá»—**. Báº¡n giáº£m sá»‘ khÃ¡ch hoáº·c chá»n ngÃ y khÃ¡c nhÃ©.", sessionId, "COLLECTING_PASSENGERS");
            }
            initPassengerSlotsFromCounts(state);
            sessionService.save(sessionId, state);
            ConversationState.PassengerData first = state.getPassengers().get(0);
            return text("ÄÃ£ ghi nháº­n **" + passengerCountSummary(state) + "**.\n\n"
                    + "**HÃ nh khÃ¡ch 1 (" + typeToVietnamese(first.getType()) + "):**\n"
                    + "Vui lÃ²ng nháº­p **há» tÃªn Ä‘áº§y Ä‘á»§, giá»›i tÃ­nh** (vÃ­ dá»¥: *Nguyá»…n VÄƒn A, Nam*)", sessionId, "COLLECTING_PASSENGERS");
        }
        int idx = state.getCurrentPassengerIndex();

        if (idx >= passengers.size()) {
            // All collected — move to contact
            return moveToContact(sessionId, state);
        }

        ConversationState.PassengerData current = passengers.get(idx);
        // Parse "Họ tên, Giới tính"
        String[] parts = msg.split(",", 2);
        String name   = parts[0].trim();
        String gender = parts.length > 1 ? parseGender(parts[1].trim()) : "MALE";

        current.setFullName(name);
        current.setGender(gender);
        current.setDateOfBirth(getPlaceholderDob(current.getType()));
        passengers.set(idx, current);

        state.setPassengers(passengers);
        state.setCurrentPassengerIndex(idx + 1);

        if (idx + 1 < passengers.size()) {
            ConversationState.PassengerData next = passengers.get(idx + 1);
            String typeVi = typeToVietnamese(next.getType());
            sessionService.save(sessionId, state);
            return text("✅ Đã ghi nhận **" + name + "**.\n\n**Hành khách " + (idx + 2) + " (" + typeVi + "):**\nHọ tên đầy đủ và giới tính:", sessionId, "COLLECTING_PASSENGERS");
        }
        return moveToContact(sessionId, state);
    }

    private ChatMessageResponse moveToContact(String sessionId, ConversationState state) {
        state.setStage(ConversationState.Stage.COLLECTING_CONTACT_NAME_PHONE);
        sessionService.save(sessionId, state);
        return text("""
                ✅ Đã ghi nhận thông tin hành khách!
                
                Bây giờ cho tôi biết **thông tin người liên hệ**:
                Họ tên đầy đủ và số điện thoại (ví dụ: *Nguyễn Thị B, 0901234567*)
                """, sessionId, "COLLECTING_CONTACT_NAME_PHONE");
    }

    private ChatMessageResponse handleContactNamePhone(String msg, String sessionId, ConversationState state) {
        String[] parts = msg.split(",", 2);
        if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return text("Vui lòng nhập họ tên và số điện thoại cách nhau bằng dấu phẩy.\nVí dụ: *Nguyễn Thị B, 0901234567*", sessionId, "COLLECTING_CONTACT_NAME_PHONE");
        }
        String name  = parts[0].trim();
        String phone = parts[1].trim().replaceAll("[\\s\\-]", "");
        // Phone validation: phải là 10-11 chữ số, bắt đầu bằng 0
        if (!phone.matches("^0\\d{9,10}$")) {
            return text("❌ Số điện thoại **không hợp lệ**. Vui lòng nhập số điện thoại 10-11 chữ số bắt đầu bằng 0.\nVí dụ: *" + name + ", 0901234567*", sessionId, "COLLECTING_CONTACT_NAME_PHONE");
        }
        state.setContactName(name);
        state.setContactPhone(phone);
        state.setStage(ConversationState.Stage.COLLECTING_CONTACT_EMAIL);
        sessionService.save(sessionId, state);
        return text("✅ Cảm ơn **" + state.getContactName() + "**!\n\n📧 Địa chỉ **email** để nhận xác nhận đặt tour:", sessionId, "COLLECTING_CONTACT_EMAIL");
    }

    private ChatMessageResponse handleContactEmail(String msg, String sessionId, ConversationState state, Integer userId) {
        String email = msg.trim();
        if (!isValidEmail(email)) {
            return text("Email không hợp lệ. Vui lòng nhập lại (ví dụ: *name@gmail.com*):", sessionId, "COLLECTING_CONTACT_EMAIL");
        }
        state.setContactEmail(email);
        state.setStage(ConversationState.Stage.CONFIRMING_BOOKING);
        sessionService.save(sessionId, state);
        return buildConfirmCard(sessionId, state);
    }

    private ChatMessageResponse buildConfirmCard(String sessionId, ConversationState state) {
        // Tính estimated total
        long total = (long) state.getSearchAdults() * nvl(state.getAdultPrice())
                   + (long) state.getSearchChildren() * nvl(state.getChildPrice())
                   + (long) state.getSearchToddlers() * nvl(state.getToddlerPrice())
                   + (long) state.getSearchInfants()  * nvl(state.getInfantPrice());

        List<BookingConfirmData.PassengerSummary> pSummaries = state.getPassengers().stream()
                .map(p -> BookingConfirmData.PassengerSummary.builder()
                        .type(p.getType()).fullName(p.getFullName()).gender(p.getGender()).build())
                .collect(Collectors.toList());

        BookingConfirmData confirmData = BookingConfirmData.builder()
                .tourName(state.getSelectedTourName()).tourCode(state.getSelectedTourCode())
                .tourImage(state.getSelectedTourImage()).duration(state.getSelectedDuration())
                .departureDate(state.getDepartureDateDisplay()).departureCity(state.getDepartureCity())
                .passengers(pSummaries)
                .contactName(state.getContactName()).contactPhone(state.getContactPhone()).contactEmail(state.getContactEmail())
                .adultCount(state.getSearchAdults()).childCount(state.getSearchChildren())
                .toddlerCount(state.getSearchToddlers()).infantCount(state.getSearchInfants())
                .adultPrice(nvl(state.getAdultPrice())).childPrice(nvl(state.getChildPrice()))
                .toddlerPrice(nvl(state.getToddlerPrice())).infantPrice(nvl(state.getInfantPrice()))
                .singleRoomSurcharge(nvl(state.getSingleRoomSurcharge()))
                .estimatedTotal(total)
                .build();

        StringBuilder sb = new StringBuilder("📋 **XÁC NHẬN ĐẶT TOUR**\n\n");
        sb.append("🏖️ **").append(state.getSelectedTourName()).append("**\n");
        sb.append("📅 Khởi hành: **").append(state.getDepartureDateDisplay()).append("** | ⏱️ ").append(state.getSelectedDuration()).append("\n\n");
        sb.append("**👥 Hành khách:**\n");
        if (state.getSearchAdults() > 0)   sb.append("  • Người lớn × ").append(state.getSearchAdults())  .append(": ").append(fmt(state.getAdultPrice())).append("đ/người\n");
        if (state.getSearchChildren() > 0) sb.append("  • Trẻ em × ").append(state.getSearchChildren())  .append(": ").append(fmt(state.getChildPrice())).append("đ/người\n");
        if (state.getSearchToddlers() > 0) sb.append("  • Trẻ nhỏ × ").append(state.getSearchToddlers()) .append(": ").append(fmt(state.getToddlerPrice())).append("đ/người\n");
        if (state.getSearchInfants() > 0)  sb.append("  • Em bé × ").append(state.getSearchInfants())    .append(": ").append(fmt(state.getInfantPrice())).append("đ/người\n");
        sb.append("\n**👤 Liên hệ:** ").append(state.getContactName()).append(" | ").append(state.getContactPhone()).append(" | ").append(state.getContactEmail()).append("\n\n");
        sb.append("💰 **TỔNG DỰ TÍNH: ~").append(fmt(total)).append("đ**\n");
        sb.append("*(Giá chính xác sẽ được hệ thống xác nhận)*\n\n");
        sb.append("⚠️ Hạn thanh toán: **24 giờ** kể từ khi đặt\n\n");
        sb.append("Bạn có muốn **xác nhận đặt tour** không? (Gõ **Xác nhận** / **Hủy**)");

        return ChatMessageResponse.builder()
                .reply(sb.toString()).sessionId(sessionId).timestamp(java.time.LocalDateTime.now())
                .messageType("BOOKING_CONFIRM").conversationStage("CONFIRMING_BOOKING")
                .bookingConfirmData(confirmData)
                .quickActions(List.of(
                        ChatMessageResponse.QuickAction.builder().label("✅ Xác nhận đặt tour").action("CONFIRM_BOOKING").build(),
                        ChatMessageResponse.QuickAction.builder().label("❌ Hủy").action("CANCEL").build()))
                .build();
    }

    private ChatMessageResponse handleConfirm(String msg, String sessionId, ConversationState state, Integer userId) {
        if (!isConfirm(msg)) {
            return text("Bạn muốn **xác nhận** đặt tour hay **hủy**? (Gõ *Xác nhận* hoặc *Hủy*)", sessionId, "CONFIRMING_BOOKING");
        }

        // Build CreateBookingRequest
        List<ChatbotCreateBookingRequest.PassengerRequest> passengerReqs = state.getPassengers().stream()
                .map(p -> ChatbotCreateBookingRequest.PassengerRequest.builder()
                        .fullName(p.getFullName() != null ? p.getFullName() : "Hành khách")
                        .gender(p.getGender() != null ? p.getGender() : "MALE")
                        .dateOfBirth(getPlaceholderDob(p.getType()))
                        .type(p.getType())
                        .singleRoom(p.isSingleRoom())
                        .build())
                .collect(Collectors.toList());

        ChatbotCreateBookingRequest bookingReq = ChatbotCreateBookingRequest.builder()
                .departureId(state.getSelectedDepartureId())
                .userId(userId)
                .contactFullName(state.getContactName())
                .contactPhone(state.getContactPhone())
                .contactEmail(state.getContactEmail())
                .contactAddress("Đặt qua chatbot")
                .customerNote("")
                .passengers(passengerReqs)
                .couponCode(new ArrayList<>())
                .pointsUsed(0)
                .build();

        try {
            ChatbotCreateBookingResponse bookingResp = bookingClient.createBooking(bookingReq);
            state.setBookingCode(bookingResp.getBookingCode());
            state.setBookingId(bookingResp.getBookingId());
            state.setTotalPrice(bookingResp.getTotalPrice());

            // Create PayOS payment link
            String payUrl = null;
            try {
                PayosCreateRequest payReq = PayosCreateRequest.builder()
                        .bookingCode(bookingResp.getBookingCode())
                        .amount(BigDecimal.valueOf(bookingResp.getTotalPrice()))
                        .description("Thanh toan tour " + bookingResp.getBookingCode())
                        // returnUrl: chỉ bookingCode — PayOS sẽ append orderCode khi redirect
                        .returnUrl(frontendUrl + "/payment-waiting?bookingCode=" + bookingResp.getBookingCode())
                        // cancelUrl: /payment-cancel không tồn tại → dùng /payment-failed
                        .cancelUrl(frontendUrl + "/payment-failed?cancelled=true&bookingCode=" + bookingResp.getBookingCode())
                        .build();
                PaymentUrlResponse payResp = paymentClient.createPayosPayment(payReq);
                payUrl = payResp.getCheckoutUrl();
                // Build paymentWaitingLink với orderCode (transactionId) — sau khi PayOS đã trả về
                if (payResp.getTransactionId() != null) {
                    state.setPaymentWaitingLink(frontendUrl + "/payment-waiting?orderCode=" + payResp.getTransactionId()
                            + "&bookingCode=" + bookingResp.getBookingCode());
                }
            } catch (Exception pe) {
                log.warn("⚠️ Không tạo được payment link: {}", pe.getMessage());
            }

            state.setPaymentUrl(payUrl);
            state.setStage(ConversationState.Stage.BOOKING_SUCCESS);
            sessionService.save(sessionId, state);

            StringBuilder sb = new StringBuilder("✅ **Đặt tour thành công!**\n\n");
            sb.append("🎫 Mã đặt tour: **").append(bookingResp.getBookingCode()).append("**\n");
            sb.append("💰 Tổng tiền: **").append(fmt(bookingResp.getTotalPrice())).append("đ**\n");
            sb.append("⏰ Hạn thanh toán: **24 giờ** kể từ bây giờ\n\n");
            if (payUrl != null) {
                sb.append("👉 **[Thanh toán ngay qua PayOS](").append(payUrl).append(")**\n\n");
            }
            // Hiển thị payment-waiting link (có orderCode) nếu có
            if (state.getPaymentWaitingLink() != null) {
                sb.append("📊 **[Theo dõi trạng thái thanh toán](").append(state.getPaymentWaitingLink()).append(")**\n\n");
            }
            sb.append("📩 Xác nhận gửi về: **").append(state.getContactEmail()).append("**\n\n");
            sb.append("Để kiểm tra đơn hàng, gõ: *tra cứu ").append(bookingResp.getBookingCode()).append("*");

            return ChatMessageResponse.builder()
                    .reply(sb.toString()).sessionId(sessionId).timestamp(java.time.LocalDateTime.now())
                    .messageType("BOOKING_SUCCESS").conversationStage("BOOKING_SUCCESS")
                    .bookingCode(bookingResp.getBookingCode())
                    .paymentUrl(payUrl)
                    .paymentWaitingLink(state.getPaymentWaitingLink())
                    .quickActions(List.of(
                            ChatMessageResponse.QuickAction.builder().label("🔍 Xem đơn hàng").action("LOOKUP_" + bookingResp.getBookingCode()).build(),
                            ChatMessageResponse.QuickAction.builder().label("🏖️ Đặt tour khác").action("NEW_BOOKING").build()))
                    .build();

        } catch (Exception e) {
            log.error("❌ Tạo booking thất bại: {}", e.getMessage(), e);
            sessionService.save(sessionId, state);
            return text("❌ Hệ thống đang gặp sự cố khi tạo đặt tour. Vui lòng thử lại sau hoặc liên hệ **1900-xxxx**.\n\nLỗi: " + e.getMessage(), sessionId, "CONFIRMING_BOOKING");
        }
    }

    private ChatMessageResponse handleAfterSuccess(String msg, String sessionId, ConversationState state) {
        // Reset to IDLE for next conversation
        state.setStage(ConversationState.Stage.IDLE);
        sessionService.save(sessionId, state);
        return null; // fall back to RAG for general questions
    }

    private ChatMessageResponse handleLookup(String msg, String sessionId, ConversationState state) {
        String code = extractBookingCode(msg);
        if (code == null) return null; // Not a booking code — let RAG handle the message
        return performLookup(code, sessionId, state);
    }

    private ChatMessageResponse performLookup(String code, String sessionId, ConversationState state) {
        try {
            ChatbotBookingDetailResponse detail = bookingClient.getBookingDetail(code);
            state.setStage(ConversationState.Stage.IDLE);
            sessionService.save(sessionId, state);

            String statusVi = statusToVietnamese(detail.getStatus());
            StringBuilder sb = new StringBuilder("📋 **CHI TIẾT ĐƠN HÀNG**\n\n");
            sb.append("🎫 Mã: **").append(detail.getBookingCode()).append("**\n");
            sb.append("🏖️ **").append(detail.getTourName()).append("**\n");
            sb.append("📌 Trạng thái: **").append(statusVi).append("**\n\n");
            sb.append("💰 Tổng tiền: **").append(fmt(detail.getOriginalPrice())).append("đ**\n");
            sb.append("✅ Đã TT: ").append(fmt(detail.getPaidAmount())).append("đ\n");
            sb.append("🔴 Còn lại: **").append(fmt(detail.getRemainingAmount())).append("đ**\n");
            if (detail.getPaymentDeadline() != null) {
                sb.append("⏰ Hạn TT: **").append(detail.getPaymentDeadline(), 0, Math.min(16, detail.getPaymentDeadline().length())).append("**\n");
            }
            if (detail.getPassengers() != null && !detail.getPassengers().isEmpty()) {
                sb.append("\n**👥 Hành khách:**\n");
                detail.getPassengers().forEach(p ->
                        sb.append("  • ").append(p.getFullName()).append(" (").append(typeToVietnamese(p.getType())).append(")\n"));
            }

            return ChatMessageResponse.builder()
                    .reply(sb.toString()).sessionId(sessionId).timestamp(java.time.LocalDateTime.now())
                    .messageType("ORDER_DETAIL").conversationStage("IDLE")
                    .orderDetail(detail).build();

        } catch (Exception e) {
            state.setStage(ConversationState.Stage.IDLE);
            sessionService.save(sessionId, state);
            return text("Không tìm thấy đơn hàng **" + code + "**. Bạn kiểm tra lại mã đặt tour nhé (định dạng: BKxxxxxxxx).", sessionId, "IDLE");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // INTENT DETECTION
    // ─────────────────────────────────────────────────────────────────

    public boolean isBookingIntent(String msg) {
        String lower = msg.toLowerCase();
        return lower.matches(".*(đặt\\s*tour|dat\\s*tour|book\\s*tour|mua\\s*tour|muốn\\s*đi|muon\\s*di|tôi\\s*(cần|muốn)\\s*đặt|toi\\s*(can|muon)\\s*dat|dat\\s*cho|đặt\\s*chỗ|tìm\\s*tour\\s*(để|de)\\s*đặt|tim\\s*tour|muon\\s*dat|muốn\\s*đặt).*");
    }

    public boolean isLookupIntent(String msg) {
        String lower = normalizeLocation(msg);
        return lower.matches(".*(tra\\s*cuu|kiem\\s*tra|xem\\s*don|tinh\\s*trang|don\\s*hang|booking\\s*cua|ma\\s*dat|don\\s*cua\\s*toi|lich\\s*su\\s*dat).*")
                || extractBookingCode(msg) != null;
    }


    public boolean isCancel(String msg) {
        String lower = msg.toLowerCase().trim();
        // Single-word cancel
        if (lower.equals("hủy") || lower.equals("huy") || lower.equals("thôi") || lower.equals("thoi")
                || lower.equals("thoát") || lower.equals("thoat") || lower.equals("cancel") || lower.equals("exit")) {
            return true;
        }
        // Multi-word explicit cancel phrases
        return lower.matches(".*(hủy\\s*đặt|huy\\s*dat|hủy\\s*tour|huy\\s*tour|bỏ\\s*qua|bo\\s*qua|không\\s*đặt|khong\\s*dat|thôi\\s*đi|thoi\\s*di|hủy\\s*đi|huy\\s*di|thoát\\s*ra|thoat\\s*ra).*");
    }

    private boolean isConfirm(String msg) {
        String lower = msg.toLowerCase();
        return lower.matches(".*(xác\\s*nhận|xac\\s*nhan|confirm|đồng\\s*ý|dong\\s*y|ok|yes|đặt\\s*ngay|dat\\s*ngay|chắc\\s*chắn|chac\\s*chan).*");
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    private void parseAndFillSearchParams(String msg, ConversationState state) {
        String lower = msg.toLowerCase();
        // Extract adults/children keywords
        Pattern adultP = Pattern.compile("(\\d+)\\s*(người\\s*lớn|nguoi\\s*lon|adult|người|nguoi|khách|khach|người|pass)");
        Matcher am = adultP.matcher(lower);
        if (am.find()) state.setSearchAdults(Integer.parseInt(am.group(1)));

        Pattern childP = Pattern.compile("(\\d+)\\s*(trẻ\\s*em|tre\\s*em|child|em\\s*nhỏ|em\\s*nho)");
        Matcher cm = childP.matcher(lower);
        if (cm.find()) state.setSearchChildren(Integer.parseInt(cm.group(1)));

        Pattern toddlerP = Pattern.compile("(\\d+)\\s*(trẻ\\s*nhỏ|tre\\s*nho|toddler|em\\s*bé|em\\s*be)");
        Matcher tm = toddlerP.matcher(lower);
        if (tm.find()) state.setSearchToddlers(Integer.parseInt(tm.group(1)));

        // Extract destination keywords (simple: look for known destinations)
        String[] dests = {"đà nẵng","da nang","phú quốc","phu quoc","hội an","hoi an","nha trang","hà nội","ha noi",
                "sài gòn","sai gon","hồ chí minh","hcm","huế","hue","đà lạt","da lat","quy nhon","quy nhơn",
                "hạ long","ha long","cần thơ","can tho","côn đảo","con dao","sa pa","sapa"};
        for (String d : dests) {
            if (lower.contains(d)) { state.setSearchDestination(d); break; }
        }

        // Extract start location (khởi hành từ đâu)
        String normMsg = normalizeLocation(msg);
        if (normMsg.matches(".*(khoi\\s*hanh\\s*(tu|o)\\s*ha\\s*noi|di\\s*tu\\s*ha\\s*noi|xuat\\s*phat.*ha\\s*noi).*")) {
            state.setSearchStartLocation("h\u00e0 n\u1ed9i");
        } else if (normMsg.matches(".*(khoi\\s*hanh.*hcm|khoi\\s*hanh.*ho\\s*chi\\s*minh|xuat\\s*phat.*sai\\s*gon|di\\s*tu\\s*hcm|tu\\s*hcm|tu\\s*sai\\s*gon).*")) {
            state.setSearchStartLocation("hcm");
        } else if (normMsg.matches(".*(khoi\\s*hanh.*da\\s*nang|tu\\s*da\\s*nang).*")) {
            state.setSearchStartLocation("\u0111\u00e0 n\u1eb5ng");
        }

        // Normalized override: keep start location separate from destination.
        boolean hasStartContext = normMsg.matches(".*(khoi\\s*hanh|xuat\\s*phat|di\\s*tu|toi\\s*o|minh\\s*o|tu\\s*hcm|tu\\s*sai\\s*gon).*");
        if (hasStartContext) {
            String destNorm = normalizeLocation(state.getSearchDestination());
            if (destNorm.equals("hcm") || destNorm.equals("sai gon") || destNorm.equals("ho chi minh")
                    || destNorm.equals("ha noi") || destNorm.equals("da nang")) {
                state.setSearchDestination(null);
            }
        } else {
            String[][] aliases = {
                    {"nha trang", "nha trang", "nhatrang"},
                    {"sa pa", "sa pa", "sapa"},
                    {"ha long", "ha long", "halong"},
                    {"da nang", "da nang", "danang"},
                    {"phu quoc", "phu quoc"},
                    {"hoi an", "hoi an"},
                    {"da lat", "da lat", "dalat"},
                    {"hue", "hue"},
                    {"quy nhon", "quy nhon"},
                    {"can tho", "can tho"},
                    {"con dao", "con dao"},
                    {"vung tau", "vung tau"}
            };
            for (String[] group : aliases) {
                for (int i = 1; i < group.length; i++) {
                    if (normMsg.contains(group[i])) {
                        state.setSearchDestination(group[0]);
                        break;
                    }
                }
                if (group[0].equals(state.getSearchDestination())) break;
            }
        }

        // Extract date range
        if (lower.contains("tháng 6") || lower.contains("thang 6") || lower.contains("/06")) state.setSearchDateRange("2026-06");
        else if (lower.contains("tháng 7") || lower.contains("thang 7") || lower.contains("/07")) state.setSearchDateRange("2026-07");
        else if (lower.contains("tháng 8") || lower.contains("thang 8") || lower.contains("/08")) state.setSearchDateRange("2026-08");
        else if (lower.contains("tuần sau") || lower.contains("tuan sau")) state.setSearchDateRange("next-week");
    }

    private void parseAndFillSearchParamsV2(String msg, ConversationState state) {
        String lower = msg.toLowerCase();
        String normMsg = normalizeLocation(msg);

        Matcher adultMatcher = Pattern.compile("(\\d+)\\s*(ngÆ°á»i\\s*lá»›n|nguoi\\s*lon|adult|ngÆ°á»i|nguoi|khÃ¡ch|khach|pass)").matcher(lower);
        if (adultMatcher.find()) {
            state.setSearchAdults(Integer.parseInt(adultMatcher.group(1)));
            state.setSearchAdultsProvided(true);
        } else if (normMsg.matches("^\\d{1,2}$")
                && state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO
                && state.getSearchDestination() != null) {
            state.setSearchAdults(Integer.parseInt(normMsg));
            state.setSearchAdultsProvided(true);
        }

        Matcher childMatcher = Pattern.compile("(\\d+)\\s*(tráº»\\s*em|tre\\s*em|child|em\\s*nhá»|em\\s*nho)").matcher(lower);
        if (childMatcher.find()) {
            state.setSearchChildren(Integer.parseInt(childMatcher.group(1)));
            state.setSearchChildrenProvided(true);
        } else if (normMsg.matches(".*(khong\\s*co\\s*tre|khong\\s*tre|ko\\s*tre).*")) {
            state.setSearchChildren(0);
            state.setSearchToddlers(0);
            state.setSearchInfants(0);
            state.setSearchChildrenProvided(true);
        }

        Matcher toddlerMatcher = Pattern.compile("(\\d+)\\s*(tráº»\\s*nhá»|tre\\s*nho|toddler|em\\s*bÃ©|em\\s*be)").matcher(lower);
        if (toddlerMatcher.find()) {
            state.setSearchToddlers(Integer.parseInt(toddlerMatcher.group(1)));
            state.setSearchChildrenProvided(true);
        }

        String[][] aliases = {
                {"nha trang", "nha trang", "nhatrang"},
                {"sa pa", "sa pa", "sapa"},
                {"ha long", "ha long", "halong"},
                {"da nang", "da nang", "danang"},
                {"phu quoc", "phu quoc"},
                {"hoi an", "hoi an"},
                {"da lat", "da lat", "dalat"},
                {"hue", "hue"},
                {"quy nhon", "quy nhon"},
                {"can tho", "can tho"},
                {"con dao", "con dao"},
                {"vung tau", "vung tau"},
                {"hcm", "hcm", "sai gon", "ho chi minh", "tp hcm"},
                {"ha noi", "ha noi", "hanoi"}
        };

        boolean hasStartContext = normMsg.matches(".*(khoi\\s*hanh|xuat\\s*phat|di\\s*tu|toi\\s*o|minh\\s*o|tu\\s*hcm|tu\\s*sai\\s*gon).*");
        if (hasStartContext) {
            String start = extractKnownLocation(normMsg, aliases);
            if (start != null) {
                state.setSearchStartLocation(start);
                state.setSearchStartLocationProvided(true);
            }
            String destNorm = normalizeLocation(state.getSearchDestination());
            if (destNorm.equals("hcm") || destNorm.equals("sai gon") || destNorm.equals("ho chi minh")
                    || destNorm.equals("ha noi") || destNorm.equals("da nang")) {
                state.setSearchDestination(null);
            }
        } else if (state.getSearchDestination() != null && state.getSearchStartLocation() == null
                && normMsg.matches("^(hcm|sai\\s*gon|ho\\s*chi\\s*minh|tp\\s*hcm|ha\\s*noi|hanoi|da\\s*nang|danang)$")) {
            String start = extractKnownLocation(normMsg, aliases);
            if (start != null) {
                state.setSearchStartLocation(start);
                state.setSearchStartLocationProvided(true);
            }
        } else {
            String dest = extractKnownLocation(normMsg, aliases);
            if (dest != null) state.setSearchDestination(dest);
        }

        Matcher monthMatcher = Pattern.compile("(?:thang|thÃ¡ng)\\s*(\\d{1,2})").matcher(lower);
        if (monthMatcher.find()) {
            int month = Integer.parseInt(monthMatcher.group(1));
            if (month >= 1 && month <= 12) {
                state.setSearchDateRange(String.format("2027-%02d", month));
                state.setSearchDateRangeProvided(true);
            }
        } else if (lower.contains("tuáº§n sau") || lower.contains("tuan sau")) {
            state.setSearchDateRange("next-week");
            state.setSearchDateRangeProvided(true);
        } else if (normMsg.matches(".*(gan\\s*nhat|som\\s*nhat|luc\\s*nao\\s*cung\\s*duoc|khi\\s*nao\\s*cung\\s*duoc).*")) {
            state.setSearchDateRange("soonest");
            state.setSearchDateRangeProvided(true);
        }
    }

    private String extractKnownLocation(String normMsg, String[][] aliases) {
        for (String[] group : aliases) {
            for (int i = 1; i < group.length; i++) {
                if (normMsg.contains(group[i])) return group[0];
            }
        }
        return null;
    }

    private boolean hasEnoughSearchParams(ConversationState state) {
        return (state.getSearchDestination() != null && !state.getSearchDestination().isEmpty())
                || (state.getSearchStartLocation() != null && !state.getSearchStartLocation().isEmpty());
    }

    private String buildSearchQuery(ConversationState state) {
        StringBuilder q = new StringBuilder("tour");
        if (state.getSearchDestination() != null) q.append(" ").append(state.getSearchDestination());
        if (state.getSearchStartLocation() != null) q.append(" kh\u1edfi h\u00e0nh t\u1eeb ").append(state.getSearchStartLocation());
        if (state.getSearchDateRange() != null) q.append(" ").append(state.getSearchDateRange());
        q.append(" ").append(state.getSearchAdults()).append(" người lớn");
        return q.toString();
    }

    private int parseTourIndex(String msg, List<ConversationState.TourGroupDisplay> groups) {
        String lower = msg.trim().toLowerCase();
        if (lower.matches(".*\\b1\\b.*") || lower.contains("tour 1") || lower.contains("đầu tiên") || lower.equals("1")) return 0;
        if (lower.matches(".*\\b2\\b.*") || lower.contains("tour 2") || lower.equals("2")) return 1;
        if (lower.matches(".*\\b3\\b.*") || lower.contains("tour 3") || lower.equals("3")) return 2;
        // Fuzzy name match
        for (int i = 0; i < groups.size(); i++) {
            String name = groups.get(i).getTourName().toLowerCase();
            if (lower.contains(name.substring(0, Math.min(10, name.length())))) return i;
        }
        return -1;
    }

    private boolean dateMatches(String input, String rawDate) {
        // rawDate = "2026-06-18", input can be "18/06", "18/6/2026", "18-06", etc.
        String clean = input.replaceAll("[^\\d/\\-]", "").trim();
        if (clean.isEmpty()) return false;
        try {
            String[] rawParts = rawDate.split("-"); // [2026, 06, 18]
            String day   = rawParts[2];
            String month = rawParts[1];
            String year  = rawParts[0];
            // Try dd/MM pattern
            if (clean.equals(day + "/" + month) || clean.equals(day + "/" + month.replaceFirst("^0", ""))
                    || clean.equals(day + "-" + month) || clean.equals(day + "/" + month + "/" + year)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private String extractBookingCode(String msg) {
        Matcher m = Pattern.compile("(BK[A-Za-z0-9]{8})").matcher(msg);
        return m.find() ? m.group(1) : null;
    }

    private String parseGender(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("nữ") || lower.contains("nu") || lower.contains("female") || lower.contains("f")) return "FEMALE";
        if (lower.contains("khác") || lower.contains("khac") || lower.contains("other")) return "OTHER";
        return "MALE";
    }

    private String getPlaceholderDob(String type) {
        return switch (type) {
            case "CHILD"   -> "2015-06-01";
            case "TODDLER" -> "2022-06-01";
            case "INFANT"  -> "2025-01-01";
            default        -> "1990-01-01";
        };
    }

    private String buildPassengerCompositionPrompt(ConversationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("ÄÃ£ chá»n ngÃ y **").append(state.getDepartureDateDisplay()).append("** âœ…\n\n");
        sb.append("BÃ¢y giá» mÃ¬nh cáº§n sá»‘ lÆ°á»£ng hÃ nh khÃ¡ch Ä‘á»ƒ tÃ­nh giÃ¡ vÃ  thu Ä‘á»§ thÃ´ng tin.\n");
        sb.append("Báº¡n Ä‘i **bao nhiÃªu ngÆ°á»i lá»›n**? CÃ³ **tráº» em/em bÃ©** khÃ´ng?\n\n");
        sb.append("VÃ­ dá»¥: **2 ngÆ°á»i lá»›n**, hoáº·c **2 ngÆ°á»i lá»›n, 1 tráº» em**.");
        if (state.getChildPrice() != null && state.getChildPrice() > 0) {
            sb.append("\n\nGiÃ¡ tham kháº£o: ngÆ°á»i lá»›n ")
                    .append(fmt(state.getAdultPrice())).append("Ä‘, tráº» em ")
                    .append(fmt(state.getChildPrice())).append("Ä‘.");
        }
        return sb.toString();
    }

    private boolean parsePassengerComposition(String msg, ConversationState state) {
        String norm = normalizeLocation(msg);
        Matcher adultMatcher = Pattern.compile("(\\d+)\\s*(nguoi\\s*lon|nguoi|khach|adult)").matcher(norm);
        Matcher childMatcher = Pattern.compile("(\\d+)\\s*(tre\\s*em|child)").matcher(norm);
        Matcher toddlerMatcher = Pattern.compile("(\\d+)\\s*(tre\\s*nho|em\\s*be|toddler)").matcher(norm);

        boolean found = false;
        if (adultMatcher.find()) {
            state.setSearchAdults(Integer.parseInt(adultMatcher.group(1)));
            state.setSearchAdultsProvided(true);
            found = true;
        } else if (norm.matches("^\\d{1,2}$")) {
            state.setSearchAdults(Integer.parseInt(norm));
            state.setSearchAdultsProvided(true);
            found = true;
        }
        if (childMatcher.find()) {
            state.setSearchChildren(Integer.parseInt(childMatcher.group(1)));
            state.setSearchChildrenProvided(true);
            found = true;
        }
        if (toddlerMatcher.find()) {
            state.setSearchToddlers(Integer.parseInt(toddlerMatcher.group(1)));
            state.setSearchChildrenProvided(true);
            found = true;
        }
        if (norm.matches(".*(khong\\s*co\\s*tre|khong\\s*tre|ko\\s*tre).*")) {
            state.setSearchChildren(0);
            state.setSearchToddlers(0);
            state.setSearchInfants(0);
            state.setSearchChildrenProvided(true);
        }
        return found && state.getSearchAdults() > 0;
    }

    private void initPassengerSlotsFromCounts(ConversationState state) {
        List<ConversationState.PassengerData> passengers = new ArrayList<>();
        addPassengerSlots(passengers, "ADULT",   state.getSearchAdults());
        addPassengerSlots(passengers, "CHILD",   state.getSearchChildren());
        addPassengerSlots(passengers, "TODDLER", state.getSearchToddlers());
        addPassengerSlots(passengers, "INFANT",  state.getSearchInfants());
        state.setPassengers(passengers);
        state.setCurrentPassengerIndex(0);
    }

    private String passengerCountSummary(ConversationState state) {
        List<String> parts = new ArrayList<>();
        if (state.getSearchAdults() > 0) parts.add(state.getSearchAdults() + " ngÆ°á»i lá»›n");
        if (state.getSearchChildren() > 0) parts.add(state.getSearchChildren() + " tráº» em");
        if (state.getSearchToddlers() > 0) parts.add(state.getSearchToddlers() + " tráº» nhá»");
        if (state.getSearchInfants() > 0) parts.add(state.getSearchInfants() + " em bÃ©");
        return String.join(", ", parts);
    }

    private void addPassengerSlots(List<ConversationState.PassengerData> list, String type, int count) {
        for (int i = 1; i <= count; i++) {
            list.add(ConversationState.PassengerData.builder()
                    .type(type).index(i).singleRoom(false).build());
        }
    }

    private String typeToVietnamese(String type) {
        if (type == null) return "Hành khách";
        return switch (type) {
            case "ADULT"   -> "Người lớn";
            case "CHILD"   -> "Trẻ em";
            case "TODDLER" -> "Trẻ nhỏ";
            case "INFANT"  -> "Em bé";
            default        -> type;
        };
    }

    private String statusToVietnamese(String status) {
        if (status == null) return "Không xác định";
        return switch (status) {
            case "PENDING_PAYMENT"      -> "⏳ Chờ thanh toán";
            case "OVERDUE_PAYMENT"      -> "❌ Quá hạn thanh toán";
            case "PENDING_CONFIRMATION" -> "🔍 Đã thanh toán, chờ xác nhận";
            case "PAID"                 -> "✅ Đã thanh toán";
            case "CANCELLED"            -> "❌ Đã hủy";
            case "PENDING_REVIEW"       -> "⭐ Chờ đánh giá";
            case "REVIEWED"             -> "✅ Đã đánh giá";
            case "PENDING_REFUND"       -> "💸 Chờ hoàn tiền";
            default                     -> status;
        };
    }

    private String formatDate(String rawDate) {
        try {
            return LocalDate.parse(rawDate, RAW_FMT).format(DISPLAY_FMT);
        } catch (Exception e) {
            return rawDate;
        }
    }

    private boolean isValidEmail(String email) {
        return email != null && email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    /** Chuẩn hóa tên địa điểm để so sánh: bỏ dấu, lowercase */
    private String normalizeLocation(String loc) {
        if (loc == null) return "";
        return java.text.Normalizer.normalize(loc, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9 ]", "");
    }

    private long nvl(Long v) { return v != null ? v : 0L; }

    private String fmt(Long v) {
        if (v == null) return "0";
        return String.format("%,.0f", v.doubleValue());
    }

    private String fmt(java.math.BigDecimal v) {
        if (v == null) return "0";
        return String.format("%,.0f", v.doubleValue());
    }

    private ChatMessageResponse text(String reply, String sessionId, String stage) {
        return ChatMessageResponse.builder()
                .reply(reply).sessionId(sessionId).timestamp(java.time.LocalDateTime.now())
                .messageType("TEXT").conversationStage(stage)
                .build();
    }

    /** Public wrappers — cho ChatbotService gọi khi xử lý BOOKING_LOOKUP intent */
    public String extractBookingCodePublic(String msg) { return extractBookingCode(msg); }
    public ChatMessageResponse performLookupPublic(String code, String sessionId, ConversationState state) {
        return performLookup(code, sessionId, state);
    }
}
