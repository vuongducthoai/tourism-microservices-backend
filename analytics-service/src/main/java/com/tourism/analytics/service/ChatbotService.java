package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.VectorDocumentDTO;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.IntentResult;
import com.tourism.analytics.dto.chatbot.IntentResult.RetrievalTask;
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
 * ChatbotService — chuỗi RAG (Retrieval-Augmented Generation) + Stateful Booking Flow:
 *
 *   - Nếu user đang trong booking flow (Redis session có stage != IDLE) → ủy quyền BookingConversationService
 *   - Nếu phát hiện booking/lookup intent → ủy quyền BookingConversationService
 *   - Ngược lại → RAG Pinecone + Gemini như cũ
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.generation.model}")
    private String generationModel;

    private final VectorService              vectorService;
    private final RestTemplate               restTemplate;
    private final RedisSessionService        sessionService;
    private final BookingConversationService bookingService;
    private final IntentRouter               intentRouter;
    private final Gson                       gson = new Gson();

    private static final String GEMINI_GENERATE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final String DISCOUNT_PATTERN =
            ".*(giảm\\s*(giá|sâu)|giam\\s*(gia|sau)|ưu\\s*đãi|uu\\s*dai|khuyến\\s*mãi|khuyen\\s*mai"
            + "|rẻ\\s*nhất|re\\s*nhat|tiết\\s*kiệm|tiet\\s*kiem|sale|giá\\s*tốt|gia\\s*tot|giá\\s*rẻ|gia\\s*re"
            + "|ty\\s*le\\s*giam|tỷ\\s*lệ\\s*giảm).*";

    private static final String COUPON_PATTERN =
            ".*(coupon|mã\\s*giảm|ma\\s*giam|voucher|mã\\s*khuyến|ma\\s*khuyen|mã\\s*ưu|ma\\s*uu"
            + "|promo\\s*code|discount\\s*code).*";

    private enum RagMode {
        GENERAL_POLICY,
        TOUR_CONTEXT,
        TOUR_SEARCH,
        DISCOUNT
    }

    // ─────────────────────────────────────────────
    // MAIN HANDLER
    // ─────────────────────────────────────────────

    public ChatMessageResponse handleUserMessage(ChatMessageRequest request) {
        log.info("💬 Chatbot received: {}", request.getMessage());

        String userMessage = request.getMessage();
        String sessionId   = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        ChatMessageRequest finalRequest = ChatMessageRequest.builder()
                .message(userMessage).sessionId(sessionId).userId(request.getUserId()).build();

        // 1. Load session state from Redis
        ConversationState state = sessionService.getOrCreate(sessionId);

        // 2. Record user turn in recentTurns
        addTurn(state, "user", userMessage);

        ChatMessageResponse resp = null;

        // 3. IntentRouter ALWAYS runs first — classifies intent regardless of stage
        IntentResult intent = intentRouter.route(userMessage, state);
        if (state.getStage() == ConversationState.Stage.COLLECTING_LOOKUP_CODE
                && intent.getIntent() != IntentResult.Intent.BOOKING_LOOKUP_PAYMENT
                && intent.getIntent() != IntentResult.Intent.CANCEL
                && bookingService.extractBookingCodePublic(userMessage) == null) {
            log.info("♻️ Reset stage COLLECTING_LOOKUP_CODE -> IDLE for sessionId={} vì user không còn ở luồng tra cứu mã BK", sessionId);
            state.setStage(ConversationState.Stage.IDLE);
            state.setPreviousStage(null);
            sessionService.save(sessionId, state);
            intent = intentRouter.route(userMessage, state);
        }
        log.info("🎯 Intent: {} (source={}, confidence={})", intent.getIntent(), intent.getRawSource(), intent.getConfidence());

        if (isTourContextReference(userMessage) && !hasTourContext(state)
                && intent.getIntent() != IntentResult.Intent.CANCEL
                && intent.getIntent() != IntentResult.Intent.BOOKING_LOOKUP_PAYMENT
                && intent.getIntent() != IntentResult.Intent.BOOKING_CANCEL_HELP) {
            resp = buildResponse("Mình chưa có danh sách tour nào để xem. Bạn muốn tìm tour đi đâu?",
                    sessionId, state, new ArrayList<>());
        }

        // 4a. Deterministic handlers (no Gemini needed for known intents)
        if (resp == null) {
            resp = handleDeterministic(intent, userMessage, sessionId, state, finalRequest);
        }

        // 4b. Booking flow handler (state machine)
        if (resp == null) {
            resp = handleBookingFlow(intent, finalRequest, state);
        }

        // 5. Fall through to RAG if no booking service handled it
        if (resp == null) {
            if (state.getStage() == ConversationState.Stage.COLLECTING_LOOKUP_CODE) {
                log.info("♻️ Chuẩn bị rơi qua RAG nên reset stage COLLECTING_LOOKUP_CODE -> IDLE cho sessionId={} để tránh giữ context tra cứu sai", sessionId);
                state.setStage(ConversationState.Stage.IDLE);
                state.setPreviousStage(null);
                sessionService.save(sessionId, state);
            }
            resp = handleWithRAG(userMessage, finalRequest, sessionId, state, chooseRagMode(intent, userMessage));
        }

        // 6. Record assistant turn in recentTurns
        if (resp != null && resp.getReply() != null) {
            addTurn(state, "assistant", resp.getReply());
            sessionService.save(sessionId, state);
        }

        return resp;
    }

    /** Add a turn to recentTurns, keeping max 6 turns (3 exchanges) */
    private void addTurn(ConversationState state, String role, String content) {
        if (state.getRecentTurns() == null) {
            state.setRecentTurns(new ArrayList<>());
        }
        state.getRecentTurns().add(ConversationState.ChatTurn.builder()
                .role(role)
                .content(content != null && content.length() > 300 ? content.substring(0, 300) + "…" : content)
                .timestamp(System.currentTimeMillis())
                .build());
        // Keep max 6 turns (3 user + 3 assistant)
        while (state.getRecentTurns().size() > 6) {
            state.getRecentTurns().remove(0);
        }
    }

    private RagMode chooseRagMode(IntentResult intent, String userMessage) {
        if (intent != null && intent.getIntent() == IntentResult.Intent.TOUR_RETRIEVAL) {
            if (intent.getRetrievalTask() == RetrievalTask.DISCOUNT || intent.getRetrievalTask() == RetrievalTask.COUPON) {
                return RagMode.DISCOUNT;
            }
            if (intent.getRetrievalTask() == RetrievalTask.SEARCH) {
                return RagMode.TOUR_SEARCH;
            }
            return RagMode.TOUR_CONTEXT;
        }
        String normalized = normalizeText(userMessage);
        if (normalized.matches(".*(giam\\s*gia|khuyen\\s*mai|uu\\s*dai|coupon|voucher).*")) {
            return RagMode.DISCOUNT;
        }
        return RagMode.GENERAL_POLICY;
    }

    private List<VectorDocumentDTO> filterDocsForRagMode(List<VectorDocumentDTO> docs, RagMode mode) {
        if (docs == null) return new ArrayList<>();
        if (mode == RagMode.DISCOUNT || mode == RagMode.TOUR_SEARCH) {
            return docs;
        }
        Set<String> allowed = mode == RagMode.TOUR_CONTEXT
                ? Set.of("TOUR_SUMMARY", "TOUR_ITINERARY_DAY", "TOUR_POLICY", "FAQ", "REVIEW", "TOUR_DEPARTURE")
                : Set.of("FAQ", "TOUR_POLICY", "POLICY", "LOCATION", "REVIEW");
        return docs.stream()
                .filter(d -> d.getType() != null && allowed.contains(d.getType()))
                .limit(10)
                .collect(Collectors.toList());
    }

    private ChatMessageResponse handleWithRAG(String userMessage, ChatMessageRequest request, String sessionId, ConversationState state, RagMode mode) {
        boolean isDiscountQuery = userMessage.toLowerCase().matches(DISCOUNT_PATTERN);
        boolean isCouponQuery   = userMessage.toLowerCase().matches(COUPON_PATTERN);
        int topK = (isDiscountQuery || isCouponQuery || mode == RagMode.DISCOUNT) ? 50 : 10;

        List<VectorDocumentDTO> docs = filterDocsForRagMode(vectorService.searchSimilar(userMessage, topK), mode);
        log.debug("🔍 Retrieved {} documents from Pinecone (topK={})", docs.size(), topK);

        // Build context (with recentTurns if available)
        String context = buildEnhancedContext(docs, userMessage);
        String contextWindow = mode == RagMode.GENERAL_POLICY ? "" : buildContextWindow(state);

        // Inject booking context block when session is active
        String bookingBlock = buildBookingContextBlock(state);
        if (!bookingBlock.isEmpty()) {
            context = bookingBlock + "\n\n" + context;
        }

        // Build prompt & call Gemini
        String prompt = buildEnhancedPromptWithHistory(userMessage, context, contextWindow, mode);
        String reply  = callGeminiAPI(prompt);
        reply = sanitizeGeminiReply(reply);

        boolean showTourCards = (mode == RagMode.DISCOUNT || mode == RagMode.TOUR_SEARCH)
                && !isSupportStyleQuery(userMessage)
                && (isDiscountQuery || isCouponQuery || isTourLikeQuery(userMessage));
        List<ChatMessageResponse.TourSuggestion> suggestions = showTourCards
                ? buildTourSuggestions(docs)
                : new ArrayList<>();
        List<ChatMessageResponse.QuickAction> quickActions = buildQuickActions(request);

        // Add resume/cancel actions when session is in progress
        if (hasBookingDraft(state)) {
            quickActions.add(ChatMessageResponse.QuickAction.builder()
                    .label("▶ Tiếp tục đặt tour").action("RESUME_BOOKING").build());
            quickActions.add(ChatMessageResponse.QuickAction.builder()
                    .label("✖ Hủy").action("CANCEL").build());
        }

        return ChatMessageResponse.builder()
                .reply(reply)
                .tourSuggestions(suggestions)
                .quickActions(quickActions)
                .sessionId(sessionId)
                .timestamp(java.time.LocalDateTime.now())
                .messageType("TEXT")
                .conversationStage(state.getStage().name())
                .build();
    }

    /**
     * handleDeterministic — xử lý intent không cần Gemini (ASK_SLOT, ASK_PRICE, ASK_DEPARTURE_DATE, BOOKING_LOOKUP).
     * Trả về null nếu không xử lý được.
     */
    private ChatMessageResponse handleDeterministic(IntentResult intent, String userMessage,
                                                     String sessionId, ConversationState state,
                                                     ChatMessageRequest request) {
        switch (intent.getIntent()) {
            case GREETING -> {
                // B3: reset booking state so user can start fresh without leftover context
                boolean hadActiveSession = state.getStage() != ConversationState.Stage.IDLE;
                state.setStage(ConversationState.Stage.IDLE);
                state.setSearchDestination(null);
                state.setSearchStartLocation(null);
                state.setSearchStartLocationProvided(false);
                state.setSearchDateRange(null);
                state.setSearchDateRangeProvided(false);
                state.setPreviousStage(null);
                state.setLastSearchResults(null);
                state.setLastDepartures(null);
                state.setLastMentionedTourId(null);
                state.setPassengers(new java.util.ArrayList<>());
                sessionService.save(sessionId, state);
                String greetReply = hadActiveSession
                        ? "Chào lại bạn! Mình đã reset phiên cũ. Bạn cần hỗ trợ gì nào?"
                        : "Chào bạn, mình có thể hỗ trợ tìm tour, xem booking, kiểm tra thanh toán hoặc tư vấn lịch trình.\nBạn đang cần hỗ trợ phần nào?";
                return buildResponse(greetReply, sessionId, state, List.of(
                        ChatMessageResponse.QuickAction.builder().label("Tìm tour").action("RESET_SEARCH").build(),
                        ChatMessageResponse.QuickAction.builder().label("Xem booking").action("LOOKUP").build(),
                        ChatMessageResponse.QuickAction.builder().label("Tour giảm giá").action("VIEW_DEALS").url("/tours?sort=discount").build()
                ));
            }
            case CANCEL -> {
                clearBookingDraft(state);
                state.setStage(ConversationState.Stage.IDLE);
                state.setPreviousStage(null);
                sessionService.save(sessionId, state);
                return buildResponse("Đã hủy luồng hiện tại. Bạn cần tìm tour hay tra cứu booking nào khác không?", sessionId, state, new ArrayList<>());
            }
            case RESUME_BOOKING -> {
                return buildResumeResponse(sessionId, state);
            }
            case BOOKING_LOOKUP_PAYMENT -> {
                String code = intent.getBookingCode() != null ? intent.getBookingCode()
                        : bookingService.extractBookingCodePublic(userMessage);
                if (code != null) {
                    if (intent.getRawSource() != null && intent.getRawSource().contains("payment")) {
                        return bookingService.performPaymentHelpPublic(code, sessionId, state);
                    }
                    if (state.getStage() != ConversationState.Stage.IDLE
                            && state.getStage() != ConversationState.Stage.COLLECTING_LOOKUP_CODE) {
                        state.setPreviousStage(state.getStage());
                    }
                    return bookingService.performLookupPublic(code, sessionId, state);
                }
                if (state.getStage() != ConversationState.Stage.IDLE
                        && state.getStage() != ConversationState.Stage.COLLECTING_LOOKUP_CODE) {
                    state.setPreviousStage(state.getStage());
                }
                state.setStage(ConversationState.Stage.COLLECTING_LOOKUP_CODE);
                sessionService.save(sessionId, state);
                return buildResponse("Bạn gửi mình mã booking dạng **BK...** để mình kiểm tra đơn hoặc hỗ trợ thanh toán nhé.",
                        sessionId, state, List.of(
                        ChatMessageResponse.QuickAction.builder().label("Nhập mã booking").action("LOOKUP").build()
                ));
            }
            case BOOKING_CANCEL_HELP -> {
                String code = intent.getBookingCode() != null ? intent.getBookingCode()
                        : bookingService.extractBookingCodePublic(userMessage);
                if (code != null) {
                    return bookingService.performCancelHelpPublic(code, sessionId, state);
                }
                return buildResponse("Bạn gửi mình mã booking dạng **BK...** để mình hướng dẫn hủy/hoàn tour chính xác nhé.",
                        sessionId, state, new ArrayList<>());
            }
            case TOUR_RETRIEVAL -> {
                return handleTourRetrieval(intent, userMessage, sessionId, state);
            }
            case GENERAL_RAG -> {
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private ChatMessageResponse handleTourRetrieval(IntentResult intent, String userMessage,
                                                    String sessionId, ConversationState state) {
        RetrievalTask task = intent.getRetrievalTask() != null ? intent.getRetrievalTask() : RetrievalTask.SEARCH;
        switch (task) {
            case SEARCH -> {
                return null; // booking/search flow will call Pinecone + strict metadata filter.
            }
            case DISCOUNT, COUPON -> {
                return buildDiscountAnswer(userMessage, sessionId, state, task == RetrievalTask.COUPON);
            }
            case DETAIL, ITINERARY, POLICY -> {
                return buildTourDetailAnswer(intent, sessionId, state);
            }
            case SLOT -> {
                return buildContextAnswer(intent, sessionId, state, "slot");
            }
            case PRICE, CHILD_PRICE -> {
                return buildContextAnswer(intent, sessionId, state, "price");
            }
            case DEPARTURE_DATE -> {
                return buildContextAnswer(intent, sessionId, state, "date");
            }
            default -> {
                return null;
            }
        }
    }

    private ChatMessageResponse buildContextAnswer(IntentResult intent, String sessionId,
                                                   ConversationState state, String kind) {
        List<ConversationState.TourGroupDisplay> results = state.getLastSearchResults();
        if (results == null || results.isEmpty()) {
            return buildResponse("Mình chưa có tour cụ thể trong phiên này. Bạn gửi tên tour hoặc điểm đến trước nhé.",
                    sessionId, state, new ArrayList<>());
        }

        List<ConversationState.TourGroupDisplay> targets = resolveTargetTours(intent, state);
        if (targets.isEmpty()) {
            targets = results.size() == 1 ? List.of(results.get(0)) : results;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            ConversationState.TourGroupDisplay t = targets.get(i);
            int displayIndex = results.indexOf(t) >= 0 ? results.indexOf(t) + 1 : i + 1;
            sb.append("**Tour ").append(displayIndex).append(" - ").append(t.getTourName()).append("**\n");
            if ("price".equals(kind)) {
                if (t.getAdultSalePrice() != null) {
                    sb.append("- Người lớn: **")
                            .append(String.format("%,.0f", t.getAdultSalePrice().doubleValue()))
                            .append(" VND/người**\n");
                }
            } else if ("slot".equals(kind)) {
                for (ConversationState.DepartureMeta dep : t.getDepartures()) {
                    sb.append("- ").append(formatDate(dep.getDepartureDate()));
                    if (dep.getAvailableSlots() != null) sb.append(": còn **").append(dep.getAvailableSlots()).append(" chỗ**");
                    sb.append("\n");
                }
            } else if ("date".equals(kind)) {
                for (ConversationState.DepartureMeta dep : t.getDepartures()) {
                    sb.append("- **").append(formatDate(dep.getDepartureDate())).append("**");
                    if (dep.getAvailableSlots() != null) sb.append(" - còn ").append(dep.getAvailableSlots()).append(" chỗ");
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
        return buildResponse(sb.toString().trim(), sessionId, state, new ArrayList<>());
    }

    /**
     * handleBookingFlow — delegates to BookingConversationService for booking/search intents.
     */
    private ChatMessageResponse handleBookingFlow(IntentResult intent, ChatMessageRequest request, ConversationState state) {
        ConversationState.Stage stage = state.getStage();
        ChatMessageRequest flowRequest = request;
        switch (intent.getIntent()) {
            case TOUR_RETRIEVAL -> {
                if (intent.getRetrievalTask() != RetrievalTask.SEARCH) {
                    return null;
                }
                boolean activeBookingStage = state.getStage() != ConversationState.Stage.IDLE
                        && state.getStage() != ConversationState.Stage.COLLECTING_SEARCH_INFO
                        && state.getStage() != ConversationState.Stage.SHOWING_SEARCH_RESULTS;
                if (activeBookingStage) {
                    if (state.getPreviousStage() == null) {
                        state.setPreviousStage(state.getStage());
                    }
                    sessionService.save(request.getSessionId(), state);
                    String tourName = state.getSelectedTourName() != null ? state.getSelectedTourName() : "tour dang dat";
                    return buildResponse("Ban dang dat **" + tourName + "** va chua tao booking.\n\n"
                            + "Neu muon tim tour moi, hay go **huy** de huy luong hien tai roi tim lai.\n"
                            + "Neu muon tiep tuc luong dang do, go **tiep tuc dat tour**.",
                            request.getSessionId(), state, List.of(
                                    ChatMessageResponse.QuickAction.builder().label("Tiep tuc dat tour").action("RESUME_BOOKING").build(),
                                    ChatMessageResponse.QuickAction.builder().label("Huy luong dat tour").action("CANCEL").build()
                            ));
                }
                // Pre-fill params from intent
                if (intent.getRawSource() != null && intent.getRawSource().contains("change")) {
                    state.setSearchDestination(null);
                    state.setSearchStartLocation(null);
                    state.setSearchDateRange(null);
                }
                // Guard: nếu intent không cung cấp destination mới VÀ đang có kết quả cũ từ search trước
                // → soft-reset search context để tránh dùng destination cũ từ Redis
                if (intent.getDestination() == null && intent.getStartLocation() == null
                        && (state.getStage() == ConversationState.Stage.SHOWING_SEARCH_RESULTS
                            || state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO)
                        && state.getSearchDestination() != null) {
                    log.info("🔄 Soft-reset search context sessionId={} oldDest={} — intent có destination=null nhưng stage={} có dữ liệu cũ",
                            request.getSessionId(), state.getSearchDestination(), state.getStage());
                    bookingService.softReset(state, "new-search-no-entity");
                }
                if (intent.getDestination() != null) {
                    boolean collectingWithDestination = state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO
                            && state.getSearchDestination() != null
                            && state.getSearchStartLocation() == null;
                    if (collectingWithDestination) {
                        state.setSearchStartLocation(intent.getDestination());
                        state.setSearchStartLocationProvided(true);
                        flowRequest = requestWithMessage(request, "khởi hành " + intent.getDestination());
                    } else {
                        state.setSearchDestination(intent.getDestination());
                    }
                }
                if (intent.getStartLocation() != null) {
                    state.setSearchStartLocation(intent.getStartLocation());
                    state.setSearchStartLocationProvided(true);
                    flowRequest = requestWithMessage(request, "khởi hành " + intent.getStartLocation());
                }
                if (intent.getTravelMonth() != null) {
                    state.setSearchDateRange(intent.getTravelMonth());
                    state.setSearchDateRangeProvided(true);
                }
                if (intent.getAdultCount() != null && intent.getAdultCount() > 0) {
                    state.setSearchAdults(intent.getAdultCount());
                    state.setSearchAdultsProvided(true);
                }
                if (state.getStage() == ConversationState.Stage.IDLE
                        || state.getStage() == ConversationState.Stage.SHOWING_SEARCH_RESULTS
                        || state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO
                        || activeBookingStage) {
                    state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
                }
                return bookingService.handle(flowRequest, state);
            }
            case TRANSACTION_FLOW -> {
                if (intent.getDestination() != null) {
                    boolean collectingWithDestination = state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO
                            && state.getSearchDestination() != null
                            && state.getSearchStartLocation() == null;
                    if (collectingWithDestination) {
                        state.setSearchStartLocation(intent.getDestination());
                        state.setSearchStartLocationProvided(true);
                        flowRequest = requestWithMessage(request, "khởi hành " + intent.getDestination());
                    } else {
                        state.setSearchDestination(intent.getDestination());
                    }
                }
                if (intent.getStartLocation() != null) {
                    state.setSearchStartLocation(intent.getStartLocation());
                    state.setSearchStartLocationProvided(true);
                    flowRequest = requestWithMessage(request, "khởi hành " + intent.getStartLocation());
                }
                if (intent.getTravelMonth() != null) {
                    state.setSearchDateRange(intent.getTravelMonth());
                    state.setSearchDateRangeProvided(true);
                }
                if (intent.getAdultCount() != null && intent.getAdultCount() > 0) {
                    state.setSearchAdults(intent.getAdultCount());
                    state.setSearchAdultsProvided(true);
                }
                if (state.getStage() == ConversationState.Stage.IDLE) {
                    state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
                }
                return bookingService.handle(flowRequest, state);
            }
            default -> {
                // B5: only delegate to booking service for non-off-topic messages when in active stage
                // UNKNOWN intent means off-topic → go to RAG, do NOT corrupt booking state
                if (stage != ConversationState.Stage.IDLE
                        && intent.getIntent() != IntentResult.Intent.UNKNOWN) {
                    ChatMessageResponse resp = bookingService.handle(request, state);
                    return resp; // may be null (bookingService returns null for off-topic)
                }
                return null;
            }
        }
    }

    private ChatMessageRequest requestWithMessage(ChatMessageRequest request, String message) {
        return ChatMessageRequest.builder()
                .message(message)
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .build();
    }

    private ChatMessageResponse buildResumeResponse(String sessionId, ConversationState state) {
        if (state.getStage() == ConversationState.Stage.COLLECTING_LOOKUP_CODE
                && state.getPreviousStage() != null) {
            state.setStage(state.getPreviousStage());
            state.setPreviousStage(null);
            sessionService.save(sessionId, state);
        }
        ConversationState.Stage stage = state.getStage();
        if (stage == ConversationState.Stage.COLLECTING_SEARCH_INFO) {
            StringBuilder sb = new StringBuilder("Mình đang giữ yêu cầu tìm tour");
            if (state.getSearchDestination() != null) sb.append(" đến **").append(state.getSearchDestination()).append("**");
            if (state.getSearchStartLocation() != null) sb.append(" khởi hành từ **").append(state.getSearchStartLocation()).append("**");
            sb.append(".\n\nBạn bổ sung tiếp thông tin còn thiếu giúp mình: điểm khởi hành, thời gian dự kiến và số người.");
            return buildResponse(sb.toString(), sessionId, state, new ArrayList<>());
        }
        if (stage == ConversationState.Stage.SHOWING_SEARCH_RESULTS && state.getLastSearchResults() != null && !state.getLastSearchResults().isEmpty()) {
            StringBuilder sb = new StringBuilder("Mình nhắc lại các tour đang hiển thị:\n\n");
            appendSearchResultsSummary(sb, state.getLastSearchResults());
            sb.append("Bạn muốn chọn tour nào? Nhập **1**, **2** hoặc **3**.");
            return buildResponse(sb.toString(), sessionId, state, new ArrayList<>());
        }
        if (stage == ConversationState.Stage.SELECTING_DEPARTURE) {
            ConversationState.TourGroupDisplay selected = findSelectedTour(state);
            if (selected != null) {
                StringBuilder sb = new StringBuilder("Bạn đang chọn ngày cho **")
                        .append(selected.getTourName()).append("**.\n\nCác ngày khởi hành còn chỗ:\n");
                for (ConversationState.DepartureMeta dep : selected.getDepartures()) {
                    sb.append("- **").append(formatDate(dep.getDepartureDate())).append("**");
                    if (dep.getAvailableSlots() != null) sb.append(" - còn ").append(dep.getAvailableSlots()).append(" chỗ");
                    sb.append("\n");
                }
                sb.append("\nBạn muốn đi ngày nào?");
                return buildResponse(sb.toString(), sessionId, state, new ArrayList<>());
            }
        }
        if (stage == ConversationState.Stage.COLLECTING_PASSENGERS) {
            return buildResponse("Bạn đang nhập thông tin hành khách. Vui lòng gửi **họ tên, giới tính** cho hành khách hiện tại.", sessionId, state, new ArrayList<>());
        }
        if (stage == ConversationState.Stage.COLLECTING_CONTACT_NAME_PHONE) {
            return buildResponse("Bạn đang nhập thông tin liên hệ. Vui lòng gửi theo dạng: **Họ tên, số điện thoại**.", sessionId, state, new ArrayList<>());
        }
        if (stage == ConversationState.Stage.COLLECTING_CONTACT_EMAIL) {
            return buildResponse("Bạn đang nhập email nhận xác nhận đặt tour. Vui lòng gửi địa chỉ email.", sessionId, state, new ArrayList<>());
        }
        if (stage == ConversationState.Stage.COLLECTING_NOTE_COUPON) {
            return buildResponse("Bạn đang ở bước ghi chú/mã giảm giá. Nếu không có, gõ **bỏ qua** để sang xác nhận booking.", sessionId, state, new ArrayList<>());
        }
        if (stage == ConversationState.Stage.CONFIRMING_BOOKING) {
            return buildResponse("Bạn đang ở bước xác nhận đặt tour. Gõ **Xác nhận** để đặt tour hoặc **Hủy** để dừng.", sessionId, state, new ArrayList<>());
        }
        return buildResponse("Hiện chưa có luồng đặt tour đang chờ. Bạn muốn tìm tour đi đâu?", sessionId, state, new ArrayList<>());
    }

    private ChatMessageResponse buildTourDetailAnswer(IntentResult intent, String sessionId, ConversationState state) {
        List<ConversationState.TourGroupDisplay> results = state.getLastSearchResults();
        if (results == null || results.isEmpty()) {
            if (intent.getResolvedTourIdx() != null || intent.getResolvedTourId() != null) {
                return buildResponse("Mình chưa có danh sách tour nào để xem. Bạn muốn tìm tour đi đâu?", sessionId, state, new ArrayList<>());
            }
            List<ConversationState.TourGroupDisplay> found = findTourGroupsFromVectors(intent.getQueryText(), 3);
            if (!found.isEmpty()) {
                state.setLastSearchResults(found);
                state.setLastMentionedTourId(found.get(0).getTourId());
                results = found;
            } else {
                return buildResponse("Mình chưa xác định được tour bạn muốn xem chi tiết. Bạn gửi tên tour, mã tour hoặc điểm đến nhé.", sessionId, state, new ArrayList<>());
            }
        }

        List<ConversationState.TourGroupDisplay> targets = resolveTargetTours(intent, state);
        if (targets.isEmpty() && intent.getQueryText() != null) {
            targets = findTourGroupsFromVectors(intent.getQueryText(), 3);
            if (!targets.isEmpty()) {
                state.setLastSearchResults(targets);
                state.setLastMentionedTourId(targets.get(0).getTourId());
            }
        }
        if (targets.isEmpty()) {
            return buildResponse("Mình chưa tìm thấy tour khớp với thông tin bạn vừa gửi. Bạn có thể gửi lại tên tour hoặc chọn tour 1/2/3 trong danh sách hiện tại.", sessionId, state, new ArrayList<>());
        }
        if (targets.size() > 1 && intent.getQueryText() != null && !intent.getQueryText().isBlank()) {
            targets = List.of(targets.get(0));
        }
        if (targets.size() > 1) {
            return buildResponse("Bạn muốn xem chi tiết **tour 1**, **tour 2** hay **tour 3**?", sessionId, state, new ArrayList<>());
        }

        ConversationState.TourGroupDisplay t = targets.get(0);
        state.setLastMentionedTourId(t.getTourId());
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(t.getTourName()).append("**\n");
        if (t.getDuration() != null) sb.append("- Thời lượng: ").append(t.getDuration()).append("\n");
        if (t.getStartLocationName() != null) sb.append("- Khởi hành: ").append(t.getStartLocationName()).append("\n");
        if (t.getAdultSalePrice() != null) sb.append("- Giá từ: **").append(String.format("%,.0f", t.getAdultSalePrice().doubleValue())).append(" VND/người lớn**\n");
        if (t.getTourCode() != null) sb.append("- Link chi tiết: **[Xem tour](/tour/").append(t.getTourCode()).append(")**\n");
        if (t.getDepartures() != null && !t.getDepartures().isEmpty()) {
            sb.append("\nNgày khởi hành đang có:\n");
            for (ConversationState.DepartureMeta dep : t.getDepartures()) {
                sb.append("- **").append(formatDate(dep.getDepartureDate())).append("**");
                if (dep.getAvailableSlots() != null) sb.append(" - còn ").append(dep.getAvailableSlots()).append(" chỗ");
                sb.append("\n");
            }
        }
        sb.append("\nNếu muốn đặt tour này, bạn nhập **1** hoặc tên tour nhé.");
        return buildResponse(sb.toString(), sessionId, state, new ArrayList<>());
    }

    private List<ConversationState.TourGroupDisplay> resolveTargetTours(IntentResult intent, ConversationState state) {
        List<ConversationState.TourGroupDisplay> results = state.getLastSearchResults();
        if (results == null || results.isEmpty()) return new ArrayList<>();

        Integer idx = intent.getResolvedTourIdx();
        if (idx != null && idx >= 0 && idx < results.size()) return List.of(results.get(idx));
        if (intent.getResolvedTourId() != null) {
            return results.stream()
                    .filter(t -> Objects.equals(t.getTourId(), intent.getResolvedTourId()))
                    .collect(Collectors.toList());
        }

        String query = normalizeText(intent.getQueryText());
        if (!query.isBlank()) {
            List<ConversationState.TourGroupDisplay> byName = results.stream()
                    .filter(t -> normalizeText(t.getTourName()).contains(query) || query.contains(normalizeText(t.getTourName())))
                    .collect(Collectors.toList());
            if (!byName.isEmpty()) return byName;
            // User typed a concrete tour/location name that does not match current context.
            // Do not silently fall back to the previous tour; caller will search data again.
            if (query.length() >= 5) return new ArrayList<>();
        }
        if (state.getLastMentionedTourId() != null) {
            List<ConversationState.TourGroupDisplay> mentioned = results.stream()
                    .filter(t -> Objects.equals(t.getTourId(), state.getLastMentionedTourId()))
                    .collect(Collectors.toList());
            if (!mentioned.isEmpty()) return mentioned;
        }
        if (results.size() == 1) return List.of(results.get(0));
        return results;
    }

    private ChatMessageResponse buildDiscountAnswer(String userMessage, String sessionId, ConversationState state, boolean couponOnly) {
        List<VectorDocumentDTO> docs = vectorService.searchSimilar(userMessage, 50);
        List<VectorDocumentDTO> filtered = docs.stream()
                .filter(d -> "TOUR_DEPARTURE".equals(d.getType()))
                .filter(d -> {
                    Map<String, Object> meta = parseMeta(d);
                    if (meta.isEmpty()) return false;
                    double sale = toDouble(meta.get("salePrice"));
                    double original = toDouble(meta.get("originalPrice"));
                    double coupon = toDouble(meta.get("couponDiscount"));
                    return couponOnly ? coupon > 0 : (original > sale && sale > 0) || coupon > 0;
                })
                .sorted((a, b) -> Double.compare(discountValue(b, couponOnly), discountValue(a, couponOnly)))
                .limit(12)
                .collect(Collectors.toList());

        List<ConversationState.TourGroupDisplay> groups = buildTourGroupsFromDocs(filtered, 5);
        if (groups.isEmpty()) {
            return buildResponse(couponOnly
                    ? "Hiện mình chưa thấy mã coupon phù hợp trong dữ liệu đang mở bán."
                    : "Hiện mình chưa thấy tour giảm giá phù hợp trong dữ liệu đang mở bán.",
                    sessionId, state, new ArrayList<>());
        }

        StringBuilder sb = new StringBuilder(couponOnly
                ? "Mình thấy các tour có coupon nổi bật:\n\n"
                : "Mình thấy một số tour đang có ưu đãi nổi bật:\n\n");
        for (int i = 0; i < groups.size(); i++) {
            ConversationState.TourGroupDisplay g = groups.get(i);
            sb.append("**").append(i + 1).append(". ").append(g.getTourName()).append("**\n");
            if (g.getDuration() != null) sb.append("- Thời lượng: ").append(g.getDuration()).append("\n");
            if (g.getStartLocationName() != null) sb.append("- Khởi hành: ").append(g.getStartLocationName()).append("\n");
            if (g.getAdultSalePrice() != null) sb.append("- Giá từ: **").append(String.format("%,.0f", g.getAdultSalePrice().doubleValue())).append("đ/người lớn**\n");
            if (g.getTourCode() != null) sb.append("- Link: **[Xem tour](/tour/").append(g.getTourCode()).append(")**\n");
            sb.append("\n");
        }
        if (hasBookingDraft(state)) {
            sb.append("Bạn vẫn đang ở bước **").append(state.getStage().name()).append("**. Bấm **Tiếp tục đặt tour** để quay lại luồng đang làm nhé.");
        }
        return buildResponse(sb.toString(), sessionId, state, List.of(
                ChatMessageResponse.QuickAction.builder().label("Xem thêm ưu đãi").action("VIEW_DEALS").url("/tours?sort=discount").build()
        ));
    }

    private List<ConversationState.TourGroupDisplay> findTourGroupsFromVectors(String query, int maxTours) {
        if (query == null || query.isBlank()) return new ArrayList<>();
        List<VectorDocumentDTO> docs = vectorService.searchSimilar(query, 30).stream()
                .filter(d -> "TOUR_DEPARTURE".equals(d.getType()) || "TOUR_SUMMARY".equals(d.getType()))
                .collect(Collectors.toList());
        return buildTourGroupsFromDocs(docs, maxTours);
    }

    private List<ConversationState.TourGroupDisplay> buildTourGroupsFromDocs(List<VectorDocumentDTO> docs, int maxTours) {
        Map<Integer, ConversationState.TourGroupDisplay> groups = new LinkedHashMap<>();
        for (VectorDocumentDTO doc : docs) {
            Map<String, Object> meta = parseMeta(doc);
            Integer tourId = toInt(meta.get("tourId"));
            if (tourId == null || groups.size() >= maxTours && !groups.containsKey(tourId)) continue;

            ConversationState.TourGroupDisplay group = groups.computeIfAbsent(tourId, id ->
                    ConversationState.TourGroupDisplay.builder()
                            .tourId(id)
                            .tourCode(getString(meta, "tourCode"))
                            .tourName(getString(meta, "tourName"))
                            .imageUrl(getString(meta, "imageUrl"))
                            .duration(getString(meta, "duration"))
                            .startLocationName(getString(meta, "startLocationName"))
                            .adultSalePrice((long) toDouble(meta.get("salePrice"), meta.get("minPrice")))
                            .departures(new ArrayList<>())
                            .build());

            if ("TOUR_DEPARTURE".equals(doc.getType())) {
                Integer depId = toInt(meta.get("departureID"));
                String depDate = getString(meta, "departureDate");
                boolean exists = group.getDepartures().stream().anyMatch(d -> Objects.equals(d.getDepartureId(), depId));
                if (!exists && depId != null) {
                    group.getDepartures().add(ConversationState.DepartureMeta.builder()
                            .departureId(depId)
                            .departureDate(depDate)
                            .availableSlots(toInt(meta.get("availableSlots")))
                            .salePrice((long) toDouble(meta.get("salePrice")))
                            .build());
                }
            }
        }
        return new ArrayList<>(groups.values());
    }

    private Map<String, Object> parseMeta(VectorDocumentDTO doc) {
        try {
            if (doc == null || doc.getMetadata() == null || doc.getMetadata().isBlank()) return Map.of();
            Map<String, Object> meta = gson.fromJson(doc.getMetadata(), Map.class);
            return meta == null ? Map.of() : meta;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private double discountValue(VectorDocumentDTO doc, boolean couponOnly) {
        Map<String, Object> meta = parseMeta(doc);
        double coupon = toDouble(meta.get("couponDiscount"));
        if (couponOnly) return coupon;
        double sale = toDouble(meta.get("salePrice"));
        double original = toDouble(meta.get("originalPrice"));
        return Math.max(0, original - sale) + coupon;
    }

    private ConversationState.TourGroupDisplay findSelectedTour(ConversationState state) {
        if (state.getLastSearchResults() == null || state.getSelectedTourId() == null) return null;
        return state.getLastSearchResults().stream()
                .filter(t -> Objects.equals(t.getTourId(), state.getSelectedTourId()))
                .findFirst()
                .orElse(null);
    }

    private void appendSearchResultsSummary(StringBuilder sb, List<ConversationState.TourGroupDisplay> results) {
        for (int i = 0; i < results.size(); i++) {
            ConversationState.TourGroupDisplay t = results.get(i);
            sb.append("**Tour ").append(i + 1).append(" - ").append(t.getTourName()).append("**");
            if (t.getAdultSalePrice() != null) {
                sb.append(" - ").append(String.format("%,.0f", t.getAdultSalePrice().doubleValue())).append(" VND");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    /** Build booking context block to inject into RAG prompt when session is active */
    private String buildBookingContextBlock(ConversationState state) {
        if (state.getStage() == ConversationState.Stage.IDLE) return "";
        if (state.getLastSearchResults() == null || state.getLastSearchResults().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("=== PHIÊN ĐẶT TOUR ĐANG HOẠT ĐỘNG ===\n");
        sb.append("Trạng thái: ").append(state.getStage().name()).append("\n");
        if (state.getSelectedTourName() != null) {
            sb.append("Tour đang chọn: ").append(state.getSelectedTourName()).append("\n");
        }
        if (state.getDepartureDateDisplay() != null) {
            sb.append("Ngày khởi hành: ").append(state.getDepartureDateDisplay()).append("\n");
        }
        if (!state.getLastSearchResults().isEmpty()) {
            sb.append("Kết quả tìm kiếm hiện tại:\n");
            for (int i = 0; i < state.getLastSearchResults().size(); i++) {
                ConversationState.TourGroupDisplay t = state.getLastSearchResults().get(i);
                sb.append("  Tour ").append(i + 1).append(": ").append(t.getTourName())
                  .append(" — ").append(String.format("%,.0f", t.getAdultSalePrice().doubleValue()))
                  .append(" VND | Slot: ");
                if (!t.getDepartures().isEmpty()) {
                    t.getDepartures().forEach(d ->
                        sb.append(formatDate(d.getDepartureDate())).append("(").append(d.getAvailableSlots()).append(" chỗ) "));
                }
                sb.append("\n");
            }
        }
        sb.append("======================================\n");
        return sb.toString();
    }

    private boolean hasBookingDraft(ConversationState state) {
        if (state == null) return false;
        if (state.getSelectedTourId() != null || state.getSelectedDepartureId() != null) return true;
        if (state.getPassengers() != null && !state.getPassengers().isEmpty()) return true;
        if (state.getContactName() != null || state.getContactPhone() != null || state.getContactEmail() != null) return true;
        return switch (state.getStage()) {
            case SELECTING_DEPARTURE, COLLECTING_PASSENGERS, COLLECTING_CONTACT_NAME_PHONE,
                 COLLECTING_CONTACT_EMAIL, COLLECTING_NOTE_COUPON, CONFIRMING_BOOKING,
                 BOOKING_SUCCESS -> true;
            default -> false;
        };
    }

    private boolean hasTourContext(ConversationState state) {
        if (state == null) return false;
        if (state.getSelectedTourId() != null || state.getLastMentionedTourId() != null) return true;
        return state.getLastSearchResults() != null && !state.getLastSearchResults().isEmpty();
    }

    private boolean isTourContextReference(String message) {
        String m = normalizeText(message);
        return m.matches(".*\\b(tour|chuyen|cai)\\s*(nay|do|kia|tren)\\b.*")
                || m.matches(".*\\btour\\s*[123]\\b.*")
                || m.matches(".*\\b(dat|chon|xem|lay)\\s*(tour|chuyen|cai)?\\s*(do|nay|kia|tren)\\b.*");
    }

    private void clearBookingDraft(ConversationState state) {
        state.setSelectedTourId(null);
        state.setSelectedTourCode(null);
        state.setSelectedTourName(null);
        state.setSelectedTourImage(null);
        state.setSelectedDuration(null);
        state.setDepartureCity(null);
        state.setSelectedDepartureId(null);
        state.setDepartureDateDisplay(null);
        state.setDepartureDateRaw(null);
        state.setAdultPrice(null);
        state.setChildPrice(null);
        state.setToddlerPrice(null);
        state.setInfantPrice(null);
        state.setSingleRoomSurcharge(null);
        state.setAvailableSlots(null);
        state.setPassengers(new ArrayList<>());
        state.setCurrentPassengerIndex(0);
        state.setContactName(null);
        state.setContactPhone(null);
        state.setContactEmail(null);
        state.setContactAddress(null);
        state.setCustomerNote(null);
        state.setCouponCodes(new ArrayList<>());
        state.setPointsUsed(null);
        state.setBookingCode(null);
        state.setBookingId(null);
        state.setTotalPrice(null);
        state.setPaymentUrl(null);
        state.setPaymentWaitingLink(null);
        state.setPaymentDeadline(null);
        state.setLookupCode(null);
        state.setLastMentionedDepartureId(null);
    }

    private ChatMessageResponse buildResponse(String reply, String sessionId, ConversationState state,
                                               List<ChatMessageResponse.QuickAction> extraActions) {
        List<ChatMessageResponse.QuickAction> actions = new ArrayList<>(extraActions);
        if (hasBookingDraft(state)) {
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("▶ Tiếp tục đặt tour").action("RESUME_BOOKING").build());
            actions.add(ChatMessageResponse.QuickAction.builder()
                    .label("✖ Hủy").action("CANCEL").build());
        }
        return ChatMessageResponse.builder()
                .reply(reply).sessionId(sessionId)
                .timestamp(java.time.LocalDateTime.now())
                .messageType("TEXT")
                .conversationStage(state.getStage().name())
                .quickActions(actions)
                .build();
    }

    private String formatDate(String raw) {
        if (raw == null) return "";
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(raw);
            return d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) { return raw; }
    }

    /** Build conversation history string from recentTurns for Gemini context */
    private String buildContextWindow(ConversationState state) {
        if (state.getRecentTurns() == null || state.getRecentTurns().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        // Exclude last "user" turn (that's the current question)
        List<ConversationState.ChatTurn> turns = state.getRecentTurns();
        int limit = Math.max(0, turns.size() - 1);
        for (int i = 0; i < limit; i++) {
            ConversationState.ChatTurn t = turns.get(i);
            sb.append("user".equals(t.getRole()) ? "Khách: " : "Bot: ")
              .append(t.getContent()).append("\n");
        }
        return sb.toString().trim();
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
                            .append(", M� tour: ").append(tourCode)
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
            
            **A. Link Tour (C� M� tour trong Context):**
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

    /**
     * buildEnhancedPromptWithHistory — giống buildEnhancedPrompt nhưng thêm lịch sử hội thoại.
     */
    String buildEnhancedPromptWithHistory(String userMessage, String context, String history, RagMode mode) {
        String historySection = (history != null && !history.isBlank())
                ? "\n=== LỊCH SỬ HỘI THOẠI GẦN NHẤT ===\n" + history + "\n"
                : "";
        String modeRules = switch (mode) {
            case GENERAL_POLICY -> "\n=== CHE DO GENERAL_POLICY ===\nChi tra loi dung cau hoi chinh sach/tu van chung. Khong gioi thieu tour, coupon, khuyen mai neu khach khong hoi uu dai. Neu thieu du lieu, noi ro chua co thong tin chi tiet trong he thong.\n";
            case TOUR_CONTEXT -> "\n=== CHE DO TOUR_CONTEXT ===\nChi dung tour dang duoc resolve trong context. Khong loi tour khac de goi y thay the. Neu thieu chinh sach/bao gom/khong bao gom, noi ro chua co du lieu chi tiet.\n";
            case TOUR_SEARCH -> "\n=== CHE DO TOUR_SEARCH ===\nChi tra loi cac tour khop dieu kien tim kiem trong context. Khong them tour khong khop de du so luong.\n";
            case DISCOUNT -> "\n=== CHE DO DISCOUNT ===\nChi liet ke uu dai/coupon co trong context.\n";
        };
        return (modeRules + "\n" + buildEnhancedPrompt(userMessage, context)).replace(
                "=== DỮ LIỆU HỆ THỐNG (CONTEXT) ===",
                historySection + "=== DỮ LIỆU HỆ THỐNG (CONTEXT) ==="
        );
    }

    // ─────────────────────────────────────────────
    // GEMINI API
    // ─────────────────────────────────────────────

    /**
     * Gọi Gemini generateContent API để sinh câu trả lời.
     * Temperature thấp (0.2) để giảm hallucination.
     */
    private String sanitizeGeminiReply(String reply) {
        if (reply == null || reply.isBlank()) return reply;
        StringBuilder cleaned = new StringBuilder();
        for (String line : reply.split("\\R")) {
            String normalized = normalizeText(line);
            boolean leakedInstruction = normalized.contains("format van ban")
                    || normalized.contains("du lieu he thong")
                    || normalized.contains("cau hoi khach hang")
                    || normalized.contains("tra loi cua ban")
                    || normalized.contains("khoang cach giua")
                    || normalized.contains("khong tu bia")
                    || normalized.contains("lay locationid")
                    || normalized.contains("khi co nhieu tour");
            if (!leakedInstruction) cleaned.append(line).append("\n");
        }
        String result = cleaned.toString().trim();
        return result.isBlank()
                ? "Mình đã nhận câu hỏi của bạn. Bạn nói rõ thêm một chút để mình tư vấn đúng hơn nhé."
                : result;
    }
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

        return actions;
    }

    private boolean isSupportStyleQuery(String message) {
        String m = normalizeText(message);
        return m.matches(".*(booking|don\\s*hang|ma\\s*booking|ma\\s*dat|thanh\\s*toan|loi|sao\\s*khong|khong\\s*xem|xem\\s*booking).*");
    }

    private boolean isTourLikeQuery(String message) {
        String m = normalizeText(message);
        return m.matches(".*(tour|du\\s*lich|chuyen\\s*di|di\\s*bien|di\\s*nui|nghi\\s*duong).*");
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return java.text.Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
