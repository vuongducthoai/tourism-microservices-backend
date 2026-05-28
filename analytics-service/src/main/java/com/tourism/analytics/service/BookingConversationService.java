/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.tourism.analytics.dto.ChatMessageRequest
 *  com.tourism.analytics.dto.ChatMessageResponse
 *  com.tourism.analytics.dto.ChatMessageResponse$QuickAction
 *  com.tourism.analytics.dto.ChatMessageResponse$TourSuggestion
 *  com.tourism.analytics.dto.VectorDocumentDTO
 *  com.tourism.analytics.dto.chatbot.BookingConfirmData
 *  com.tourism.analytics.dto.chatbot.BookingConfirmData$PassengerSummary
 *  com.tourism.analytics.dto.chatbot.ChatbotBookingDetailResponse
 *  com.tourism.analytics.dto.chatbot.ChatbotCreateBookingRequest
 *  com.tourism.analytics.dto.chatbot.ChatbotCreateBookingRequest$PassengerRequest
 *  com.tourism.analytics.dto.chatbot.ChatbotCreateBookingResponse
 *  com.tourism.analytics.dto.chatbot.ConversationState
 *  com.tourism.analytics.dto.chatbot.ConversationState$DepartureMeta
 *  com.tourism.analytics.dto.chatbot.ConversationState$PassengerData
 *  com.tourism.analytics.dto.chatbot.ConversationState$Stage
 *  com.tourism.analytics.dto.chatbot.ConversationState$TourGroupDisplay
 *  com.tourism.analytics.dto.chatbot.PaymentUrlResponse
 *  com.tourism.analytics.dto.chatbot.PayosCreateRequest
 *  com.tourism.analytics.dto.feign.ChatbotDepartureInfoResponse
 *  com.tourism.analytics.feign.ChatbotBookingFeignClient
 *  com.tourism.analytics.feign.ChatbotPaymentFeignClient
 *  com.tourism.analytics.feign.TourCatalogFeignClient
 *  com.tourism.analytics.service.LocationResolverService
 *  com.tourism.analytics.service.LocationResolverService$ResolvedLocation
 *  com.tourism.analytics.service.LocationResolverService$Role
 *  com.tourism.analytics.service.RedisSessionService
 *  com.tourism.analytics.service.VectorService
 *  lombok.Generated
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.ChatMessageRequest;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.VectorDocumentDTO;
import com.tourism.analytics.dto.chatbot.BookingConfirmData;
import com.tourism.analytics.dto.chatbot.ChatbotBookingDetailResponse;
import com.tourism.analytics.dto.chatbot.ChatbotCreateBookingRequest;
import com.tourism.analytics.dto.chatbot.ChatbotCreateBookingResponse;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.PaymentUrlResponse;
import com.tourism.analytics.dto.chatbot.PayosCreateRequest;
import com.tourism.analytics.dto.feign.ChatbotDepartureInfoResponse;
import com.tourism.analytics.feign.ChatbotBookingFeignClient;
import com.tourism.analytics.feign.ChatbotPaymentFeignClient;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import com.tourism.analytics.service.LocationResolverService;
import com.tourism.analytics.service.RedisSessionService;
import com.tourism.analytics.service.VectorService;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BookingConversationService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BookingConversationService.class);
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter RAW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    @Value(value="${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;
    private final RedisSessionService sessionService;
    private final VectorService vectorService;
    private final LocationResolverService locationResolver;
    private final TourCatalogFeignClient tourCatalogClient;
    private final ChatbotBookingFeignClient bookingClient;
    private final ChatbotPaymentFeignClient paymentClient;
    private final Gson gson = new Gson();

    public ChatMessageResponse handle(ChatMessageRequest request, ConversationState state) {
        String msg = request.getMessage().trim();
        if (msg.matches("(?i)BK[A-Za-z0-9]{8,}")) {
            log.info("\ud83d\udd0d Global BK lookup at stage {}: {}", (Object)state.getStage(), (Object)msg);
            return this.performLookup(msg.trim(), request.getSessionId(), state);
        }
        if (this.isCancel(msg) && state.getStage() != ConversationState.Stage.IDLE && state.getStage() != ConversationState.Stage.COLLECTING_NOTE_COUPON) {
            state.setStage(ConversationState.Stage.IDLE);
            state.setPreviousStage(null);
            state.setPassengers(new ArrayList());
            this.sessionService.save(request.getSessionId(), state);
            return this.text("\u0110\u00e3 h\u1ee7y. B\u1ea1n c\u1ea7n t\u01b0 v\u1ea5n hay \u0111\u1eb7t tour g\u00ec kh\u00e1c kh\u00f4ng? \ud83d\ude0a", request.getSessionId(), "IDLE");
        }
        return switch (state.getStage()) {
            case IDLE -> this.handleIdle(msg, request.getSessionId(), state, request.getUserId());
            case COLLECTING_SEARCH_INFO -> this.handleSearchInfo(msg, request.getSessionId(), state);
            case SHOWING_SEARCH_RESULTS -> this.handleTourSelection(msg, request.getSessionId(), state);
            case SELECTING_DEPARTURE -> this.handleDepartureSelection(msg, request.getSessionId(), state);
            case COLLECTING_PASSENGERS -> this.handlePassengerInfo(msg, request.getSessionId(), state);
            case COLLECTING_CONTACT_NAME_PHONE -> this.handleContactNamePhone(msg, request.getSessionId(), state);
            case COLLECTING_CONTACT_EMAIL -> this.handleContactEmail(msg, request.getSessionId(), state, request.getUserId());
            case COLLECTING_NOTE_COUPON -> this.handleNoteCoupon(msg, request.getSessionId(), state);
            case CONFIRMING_BOOKING -> this.handleConfirm(msg, request.getSessionId(), state, request.getUserId());
            case BOOKING_SUCCESS -> this.handleAfterSuccess(msg, request.getSessionId(), state);
            case COLLECTING_LOOKUP_CODE -> {
                ChatMessageResponse lookupResp = this.handleLookup(msg, request.getSessionId(), state);
                if (lookupResp != null) {
                    yield lookupResp;
                }
                yield this.text("Vui l\u00f2ng nh\u1eadp m\u00e3 \u0111\u1eb7t tour \u0111\u00fang \u0111\u1ecbnh d\u1ea1ng BKxxxxxxxx (v\u00ed d\u1ee5: BK3f7a9c12).", request.getSessionId(), "COLLECTING_LOOKUP_CODE");
            }
            default -> {
                log.warn("Unhandled stage: {} \u2014 resetting to IDLE", (Object)state.getStage());
                state.setStage(ConversationState.Stage.IDLE);
                this.sessionService.save(request.getSessionId(), state);
                yield this.text("Xin l\u1ed7i, c\u00f3 l\u1ed7i x\u1ea3y ra. B\u1ea1n c\u1ea7n \u0111\u1eb7t tour hay t\u01b0 v\u1ea5n g\u00ec kh\u00f4ng?", request.getSessionId(), "IDLE");
            }
        };
    }

    private ChatMessageResponse handleIdle(String msg, String sessionId, ConversationState state, Integer userId) {
        if (this.isBookingIntent(msg)) {
            state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
            this.parseAndFillSearchParamsV3(msg, state);
            ChatMessageResponse clarify = this.askForMissingSearchInfoIfNeededV3(sessionId, state);
            if (clarify != null) {
                return clarify;
            }
            if (this.hasEnoughSearchParams(state)) {
                return this.doSearch(sessionId, state);
            }
            this.sessionService.save(sessionId, state);
            return this.text("Tuy\u1ec7t! Cho t\u00f4i bi\u1ebft th\u00eam \u0111\u1ec3 t\u00ecm tour ph\u00f9 h\u1ee3p nh\u1ea5t cho b\u1ea1n \ud83d\uddfa\ufe0f\n\n1. **\u0110i\u1ec3m \u0111\u1ebfn** b\u1ea1n mu\u1ed1n \u0111\u1ebfn? (v\u00ed d\u1ee5: \u0110\u00e0 N\u1eb5ng, Ph\u00fa Qu\u1ed1c, H\u1ed9i An...)\n2. **Th\u1eddi gian** d\u1ef1 ki\u1ebfn? (v\u00ed d\u1ee5: th\u00e1ng 6, tu\u1ea7n sau, 20/07...)\n3. **S\u1ed1 ng\u01b0\u1eddi**: m\u1ea5y ng\u01b0\u1eddi l\u1edbn? C\u00f3 tr\u1ebb em/em b\u00e9 kh\u00f4ng?\n", sessionId, "COLLECTING_SEARCH_INFO");
        }
        if (this.isLookupIntent(msg)) {
            String code = this.extractBookingCode(msg);
            if (code != null) {
                return this.performLookup(code, sessionId, state);
            }
            state.setStage(ConversationState.Stage.COLLECTING_LOOKUP_CODE);
            this.sessionService.save(sessionId, state);
            return this.text("Vui l\u00f2ng cho t\u00f4i bi\u1ebft **m\u00e3 \u0111\u1eb7t tour** c\u1ee7a b\u1ea1n (v\u00ed d\u1ee5: BK3f7a9c12):", sessionId, "COLLECTING_LOOKUP_CODE");
        }
        return null;
    }

    private ChatMessageResponse handleSearchInfo(String msg, String sessionId, ConversationState state) {
        this.parseAndFillSearchParamsV3(msg, state);
        ChatMessageResponse clarify = this.askForMissingSearchInfoIfNeededV3(sessionId, state);
        if (clarify != null) {
            return clarify;
        }
        if (!this.hasEnoughSearchParams(state)) {
            this.sessionService.save(sessionId, state);
            return this.text("B\u1ea1n mu\u1ed1n \u0111\u1ebfn **\u0111\u00e2u** v\u00e0 \u0111i v\u00e0o **kho\u1ea3ng th\u1eddi gian** n\u00e0o? M\u1ea5y **ng\u01b0\u1eddi l\u1edbn**? \ud83d\ude42", sessionId, "COLLECTING_SEARCH_INFO");
        }
        return this.doSearch(sessionId, state);
    }

    private ChatMessageResponse askForMissingSearchInfoIfNeededV3(String sessionId, ConversationState state) {
        return null;
    }

    private boolean destinationHasAnyTour(String destination) {
        try {
            String normalizedDest = this.normalizeLocation(destination);
            return this.vectorService.searchSimilar("tour " + destination, 50).stream().filter(d -> "TOUR_DEPARTURE".equals(d.getType())).anyMatch(d -> {
                try {
                    Map m = (Map)this.gson.fromJson(d.getMetadata(), Map.class);
                    String endLoc = this.normalizeLocation(String.valueOf(m.getOrDefault("endLocationName", "")));
                    String tourName = this.normalizeLocation(String.valueOf(m.getOrDefault("tourName", "")));
                    return endLoc.contains(normalizedDest) || tourName.contains(normalizedDest);
                }
                catch (Exception e) {
                    return false;
                }
            });
        }
        catch (Exception e) {
            log.warn("Destination precheck failed: {}", (Object)e.getMessage());
            return true;
        }
    }

    private ChatMessageResponse doSearch(String sessionId, ConversationState state) {
        String query = this.buildSearchQuery(state);
        List<VectorDocumentDTO> docs = this.vectorService.searchSimilar(query, 50);
        String destFilter = state.getSearchDestination();
        String startFilter = state.getSearchStartLocation();
        List<VectorDocumentDTO> departureDocs = docs.stream().filter(d -> "TOUR_DEPARTURE".equals(d.getType())).filter(d -> {
            if (destFilter == null || destFilter.isEmpty()) {
                return true;
            }
            try {
                Map m = (Map)this.gson.fromJson(d.getMetadata(), Map.class);
                String normalizedDest = this.normalizeLocation(destFilter);
                String endLoc = this.normalizeLocation(String.valueOf(m.getOrDefault("endLocationName", "")));
                String tourName = this.normalizeLocation(String.valueOf(m.getOrDefault("tourName", "")));
                return endLoc.contains(normalizedDest) || tourName.contains(normalizedDest);
            }
            catch (Exception e) {
                return true;
            }
        }).filter(d -> {
            if (startFilter == null || startFilter.isEmpty()) {
                return true;
            }
            try {
                Map m = (Map)this.gson.fromJson(d.getMetadata(), Map.class);
                String normStart = this.normalizeLocation(startFilter);
                String startLoc = this.normalizeLocation(String.valueOf(m.getOrDefault("startLocationName", "")));
                return startLoc.contains(normStart);
            }
            catch (Exception e) {
                return true;
            }
        }).collect(Collectors.toList());
        if (departureDocs.isEmpty()) {
            String alternativeStarts = this.buildAlternativeStartHint(destFilter, startFilter);
            this.clearResultContext(state);
            state.setStage(ConversationState.Stage.IDLE);
            state.setSearchDestination(null);
            state.setSearchStartLocation(null);
            this.sessionService.save(sessionId, state);
            Object destMsg = destFilter != null ? " \u0111\u1ebfn **" + destFilter + "**" : "";
            return this.text("M\u00ecnh ch\u01b0a t\u00ecm \u0111\u01b0\u1ee3c tour n\u00e0o" + (String)destMsg + " ph\u00f9 h\u1ee3p \u1edf th\u1eddi \u0111i\u1ec3m n\u00e0y \ud83d\ude15\n\n" + alternativeStarts + "B\u1ea1n th\u1eed:\n\u2022 \u0110\u1ed5i \u0111i\u1ec3m \u0111\u1ebfn (v\u00ed d\u1ee5: **\u0110\u00e0 N\u1eb5ng**, **Nha Trang**, **Ph\u00fa Qu\u1ed1c**)\n\u2022 Thay \u0111\u1ed5i th\u1eddi gian\n\u2022 Ho\u1eb7c m\u00f4 t\u1ea3 l\u1ea1i tour b\u1ea1n mu\u1ed1n", sessionId, "IDLE");
        }
        LinkedHashMap<Integer, List<VectorDocumentDTO>> grouped = new LinkedHashMap<Integer, List<VectorDocumentDTO>>();
        for (VectorDocumentDTO doc : departureDocs) {
            try {
                Map meta = (Map)this.gson.fromJson(doc.getMetadata(), Map.class);
                int tourId = ((Number)meta.get("tourId")).intValue();
                grouped.computeIfAbsent(tourId, k -> new ArrayList<>()).add(doc);
            }
            catch (Exception meta) {
                // empty catch block
            }
        }
        ArrayList<ConversationState.TourGroupDisplay> tourGroups = new ArrayList<ConversationState.TourGroupDisplay>();
        ArrayList<ConversationState.DepartureMeta> allDepartures = new ArrayList<ConversationState.DepartureMeta>();
        int tourCount = 0;
        for (Map.Entry entry : grouped.entrySet()) {
            if (tourCount >= 3) break;
            List tourDocs = (List)entry.getValue();
            try {
                Map meta = (Map)this.gson.fromJson(((VectorDocumentDTO)tourDocs.get(0)).getMetadata(), Map.class);
                ArrayList<ConversationState.DepartureMeta> deps = new ArrayList<ConversationState.DepartureMeta>();
                int i = 0;
                while (i < Math.min(3, tourDocs.size())) {
                    Map dm = (Map)this.gson.fromJson(((VectorDocumentDTO)tourDocs.get(i)).getMetadata(), Map.class);
                    ConversationState.DepartureMeta dep = ConversationState.DepartureMeta.builder().departureId(Integer.valueOf(((Number)dm.get("departureID")).intValue())).departureDate((String)dm.get("departureDate")).availableSlots(Integer.valueOf(dm.get("availableSlots") != null ? ((Number)dm.get("availableSlots")).intValue() : 0)).salePrice(Long.valueOf(dm.get("salePrice") != null ? ((Number)dm.get("salePrice")).longValue() : 0L)).build();
                    deps.add(dep);
                    allDepartures.add(dep);
                    ++i;
                }
                ConversationState.TourGroupDisplay group = ConversationState.TourGroupDisplay.builder().tourId((Integer)entry.getKey()).tourCode((String)meta.get("tourCode")).tourName((String)meta.get("tourName")).imageUrl((String)meta.get("imageUrl")).duration((String)meta.get("duration")).startLocationName((String)meta.get("startLocationName")).adultSalePrice(Long.valueOf(meta.get("salePrice") != null ? ((Number)meta.get("salePrice")).longValue() : 0L)).departures(deps).build();
                tourGroups.add(group);
                ++tourCount;
            }
            catch (Exception e) {
                log.warn("Error parsing tour group: {}", (Object)e.getMessage());
            }
        }
        state.setLastSearchResults(tourGroups);
        state.setLastDepartures(allDepartures);
        if (!tourGroups.isEmpty()) {
            state.setLastMentionedTourId(((ConversationState.TourGroupDisplay)tourGroups.get(0)).getTourId());
            if (!((ConversationState.TourGroupDisplay)tourGroups.get(0)).getDepartures().isEmpty()) {
                state.setLastMentionedDepartureId(((ConversationState.DepartureMeta)((ConversationState.TourGroupDisplay)tourGroups.get(0)).getDepartures().get(0)).getDepartureId());
            }
        }
        state.setStage(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
        this.sessionService.save(sessionId, state);
        StringBuilder sb = new StringBuilder("T\u00f4i t\u00ecm \u0111\u01b0\u1ee3c **" + tourGroups.size() + " tour** ph\u00f9 h\u1ee3p cho b\u1ea1n:\n\n");
        int i = 0;
        while (i < tourGroups.size()) {
            ConversationState.TourGroupDisplay g2 = (ConversationState.TourGroupDisplay)tourGroups.get(i);
            sb.append("**[Tour ").append(i + 1).append("]** \ud83c\udfd6\ufe0f ").append(g2.getTourName()).append("\n");
            sb.append("  \u2708\ufe0f ").append(g2.getStartLocationName() != null ? g2.getStartLocationName() : "").append(" | ").append(g2.getDuration()).append("\n");
            sb.append("  \ud83d\udcb0 T\u1eeb ").append(String.format("%,.0f", g2.getAdultSalePrice() != null ? g2.getAdultSalePrice().doubleValue() : 0.0)).append("\u0111/ng\u01b0\u1eddi l\u1edbn\n");
            sb.append("  \ud83d\udcc5 Ng\u00e0y KH: ");
            g2.getDepartures().forEach(d -> {
                StringBuilder stringBuilder2 = sb.append("[").append(this.formatDate(d.getDepartureDate())).append("] ");
            });
            sb.append("\n\n");
            ++i;
        }
        sb.append("B\u1ea1n th\u00edch **tour n\u00e0o**? (nh\u1eadp 1, 2 ho\u1eb7c 3) \ud83d\ude0a");
        List<ChatMessageResponse.TourSuggestion> suggestions = tourGroups.stream().map(g -> ChatMessageResponse.TourSuggestion.builder().tourId(g.getTourId()).tourCode(g.getTourCode()).tourName(g.getTourName()).imageUrl(g.getImageUrl()).duration(g.getDuration()).minPrice(Double.valueOf(g.getAdultSalePrice() != null ? g.getAdultSalePrice().doubleValue() : 0.0)).detailUrl("/tour/" + g.getTourCode()).build()).collect(Collectors.toList());
        return ChatMessageResponse.builder().reply(sb.toString()).sessionId(sessionId).timestamp(LocalDateTime.now()).messageType("TOUR_SUGGESTIONS").conversationStage("SHOWING_SEARCH_RESULTS").tourSuggestions(suggestions).quickActions(List.of(ChatMessageResponse.QuickAction.builder().label("\ud83d\udd04 T\u00ecm l\u1ea1i").action("RESET_SEARCH").build(), ChatMessageResponse.QuickAction.builder().label("\u274c H\u1ee7y").action("CANCEL").build())).build();
    }

    private String buildAlternativeStartHint(String destFilter, String startFilter) {
        LinkedHashSet<String> starts;
        block4: {
            if (destFilter == null || destFilter.isBlank() || startFilter == null || startFilter.isBlank()) {
                return "";
            }
            try {
                String normalizedDest = this.normalizeLocation(destFilter);
                String normalizedStart = this.normalizeLocation(startFilter);
                starts = new LinkedHashSet<>();
                this.vectorService.searchSimilar("tour du l\u1ecbch " + destFilter, 50).stream().filter(d -> "TOUR_DEPARTURE".equals(d.getType())).forEach(d -> {
                    try {
                        Map m = (Map)this.gson.fromJson(d.getMetadata(), Map.class);
                        String endLoc = this.normalizeLocation(String.valueOf(m.getOrDefault("endLocationName", "")));
                        String tourName = this.normalizeLocation(String.valueOf(m.getOrDefault("tourName", "")));
                        String startName = String.valueOf(m.getOrDefault("startLocationName", "")).trim();
                        String startNorm = this.normalizeLocation(startName);
                        if ((endLoc.contains(normalizedDest) || tourName.contains(normalizedDest)) && !startName.isBlank() && !startNorm.contains(normalizedStart)) {
                            starts.add(startName);
                        }
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                });
                if (!starts.isEmpty()) break block4;
                return "";
            }
            catch (Exception e) {
                return "";
            }
        }
        String joined = starts.stream().limit(4L).collect(Collectors.joining(", "));
        return "Hi\u1ec7n ch\u01b0a c\u00f3 tour kh\u1edfi h\u00e0nh t\u1eeb **" + startFilter + "** \u0111\u1ebfn **" + destFilter + "**, nh\u01b0ng h\u1ec7 th\u1ed1ng c\u00f3 tour \u0111\u1ebfn \u0111i\u1ec3m n\u00e0y kh\u1edfi h\u00e0nh t\u1eeb: **" + joined + "**.\n\n";
    }

    private void clearResultContext(ConversationState state) {
        state.setLastSearchResults(new ArrayList());
        state.setLastDepartures(new ArrayList());
        state.setLastMentionedTourId(null);
        state.setLastMentionedDepartureId(null);
        state.setSelectedTourId(null);
        state.setSelectedTourCode(null);
        state.setSelectedTourName(null);
        state.setSelectedTourImage(null);
        state.setSelectedDuration(null);
        state.setSelectedDepartureId(null);
        state.setDepartureDateDisplay(null);
        state.setDepartureDateRaw(null);
    }

    private ChatMessageResponse handleTourSelection(String msg, String sessionId, ConversationState state) {
        List groups = state.getLastSearchResults();
        if (groups == null || groups.isEmpty()) {
            state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
            this.sessionService.save(sessionId, state);
            return this.text("H\u1ebft phi\u00ean t\u00ecm ki\u1ebfm r\u1ed3i, b\u1ea1n h\u00e3y m\u00f4 t\u1ea3 l\u1ea1i tour mu\u1ed1n \u0111\u1eb7t nh\u00e9! \ud83d\ude0a", sessionId, "COLLECTING_SEARCH_INFO");
        }
        int idx = this.parseTourIndex(msg, groups);
        String normalizedMsg = this.normalizeLocation(msg);
        if (idx < 0 && normalizedMsg.matches(".*(dat\\s*tour|dat\\s*chuyen|book\\s*tour|mua\\s*tour|dat\\s*(tour\\s*)?(nay|do|tren)).*")) {
            if (state.getLastMentionedTourId() != null) {
                int i = 0;
                while (i < groups.size()) {
                    if (Objects.equals(state.getLastMentionedTourId(), ((ConversationState.TourGroupDisplay)groups.get(i)).getTourId())) {
                        idx = i;
                        break;
                    }
                    ++i;
                }
            }
            if (idx < 0 && groups.size() == 1) {
                idx = 0;
            }
            if (idx < 0) {
                return this.text("B\u1ea1n mu\u1ed1n \u0111\u1eb7t **tour n\u00e0o** trong danh s\u00e1ch hi\u1ec7n t\u1ea1i? Nh\u1eadp **1**, **2** ho\u1eb7c **3** nh\u00e9.", sessionId, "SHOWING_SEARCH_RESULTS");
            }
        }
        boolean explicitNewSearch = this.normalizeLocation(msg).matches(".*(tim\\s*lai|tim\\s*tour\\s*khac|tour\\s*khac|doi\\s*sang|doi\\s*diem|di\\s*bien|di\\s*nui).*");
        if (idx < 0) {
            String lower = msg.toLowerCase();
            if (!explicitNewSearch) {
                return null;
            }
            if (lower.length() > 3 && (lower.contains("tour") || lower.contains("\u0111i \u0111\u1ebfn") || lower.contains("\u0111i du l\u1ecbch"))) {
                state.setSearchDestination(null);
                state.setSearchStartLocation(null);
                state.setStage(ConversationState.Stage.COLLECTING_SEARCH_INFO);
                this.parseAndFillSearchParamsV3(msg, state);
                if (this.hasEnoughSearchParams(state)) {
                    return this.doSearch(sessionId, state);
                }
                this.sessionService.save(sessionId, state);
                return this.text("B\u1ea1n mu\u1ed1n t\u00ecm tour \u0111\u1ebfn \u0111\u00e2u? \ud83d\uddfa\ufe0f", sessionId, "COLLECTING_SEARCH_INFO");
            }
            return null;
        }
        ConversationState.TourGroupDisplay selected = (ConversationState.TourGroupDisplay)groups.get(idx);
        state.setSelectedTourId(selected.getTourId());
        state.setSelectedTourCode(selected.getTourCode());
        state.setSelectedTourName(selected.getTourName());
        state.setSelectedTourImage(selected.getImageUrl());
        state.setSelectedDuration(selected.getDuration());
        if (selected.getStartLocationName() != null) {
            state.setDepartureCity(selected.getStartLocationName());
        }
        state.setLastMentionedTourId(selected.getTourId());
        state.setStage(ConversationState.Stage.SELECTING_DEPARTURE);
        this.sessionService.save(sessionId, state);
        StringBuilder sb = new StringBuilder("B\u1ea1n \u0111\u00e3 ch\u1ecdn: **" + selected.getTourName() + "**\n\n");
        sb.append("\ud83d\udcc5 Ch\u1ecdn **ng\u00e0y kh\u1edfi h\u00e0nh**:\n");
        for (ConversationState.DepartureMeta dep : selected.getDepartures()) {
            sb.append("  \u2022 **[").append(this.formatDate(dep.getDepartureDate())).append("]**");
            if (dep.getAvailableSlots() != null) {
                sb.append(" \u2014 c\u00f2n ").append(dep.getAvailableSlots()).append(" ch\u1ed7");
            }
            sb.append("\n");
        }
        sb.append("\nNh\u1eadp **s\u1ed1 th\u1ee9 t\u1ef1** (1, 2, 3) ho\u1eb7c ng\u00e0y c\u1ee5 th\u1ec3 (v\u00ed d\u1ee5: 18/06):");
        return this.text(sb.toString(), sessionId, "SELECTING_DEPARTURE");
    }

    private ChatMessageResponse handleDepartureSelection(String msg, String sessionId, ConversationState state) {
        int depIdx;
        ConversationState.TourGroupDisplay selectedTour;
        ConversationState.TourGroupDisplay tourGroupDisplay = selectedTour = state.getLastSearchResults() == null ? null : (ConversationState.TourGroupDisplay)state.getLastSearchResults().stream().filter(g -> Objects.equals(g.getTourId(), state.getSelectedTourId())).findFirst().orElse(null);
        if (selectedTour == null) {
            state.setStage(ConversationState.Stage.IDLE);
            state.setPreviousStage(null);
            this.sessionService.save(sessionId, state);
            return this.text("\u0110\u00e3 h\u1ebft phi\u00ean, vui l\u00f2ng \u0111\u1eb7t l\u1ea1i t\u1eeb \u0111\u1ea7u nh\u00e9! \ud83d\ude0a", sessionId, "IDLE");
        }
        ConversationState.DepartureMeta matched = null;
        String trimmedMsg = msg.trim();
        if (trimmedMsg.matches("^[1-3]$") && (depIdx = Integer.parseInt(trimmedMsg) - 1) < selectedTour.getDepartures().size()) {
            matched = (ConversationState.DepartureMeta)selectedTour.getDepartures().get(depIdx);
        }
        if (matched == null) {
            for (ConversationState.DepartureMeta dep : selectedTour.getDepartures()) {
                if (!this.dateMatches(msg, dep.getDepartureDate())) continue;
                matched = dep;
                break;
            }
        }
        if (matched == null && this.normalizeLocation(msg).matches(".*(gan\\s*nhat|som\\s*nhat|ngay\\s*dau|dau\\s*tien).*") && selectedTour.getDepartures() != null && !selectedTour.getDepartures().isEmpty()) {
            matched = (ConversationState.DepartureMeta)selectedTour.getDepartures().get(0);
        }
        if (matched == null) {
            boolean looksLikeDate;
            String norm = this.normalizeLocation(msg);
            if (norm.matches(".*\\d+\\s*(nguoi|khach|adult|tre\\s*em|em\\s*be).*")) {
                return this.text("M\u00ecnh \u0111\u00e3 ghi nh\u1eadn \u00fd b\u1ea1n \u0111i **" + msg.trim() + "**, nh\u01b0ng tr\u01b0\u1edbc ti\u00ean m\u00ecnh c\u1ea7n b\u1ea1n ch\u1ecdn **ng\u00e0y kh\u1edfi h\u00e0nh** cho tour n\u00e0y. Sau khi ch\u1ecdn ng\u00e0y, m\u00ecnh s\u1ebd h\u1ecfi l\u1ea1i s\u1ed1 l\u01b0\u1ee3ng ng\u01b0\u1eddi l\u1edbn/tr\u1ebb em \u0111\u1ec3 t\u00ednh gi\u00e1 ch\u00ednh x\u00e1c.", sessionId, "SELECTING_DEPARTURE");
            }
            boolean bl = looksLikeDate = msg.matches(".*\\d{1,2}[/\\-.]\\d{1,2}.*") || msg.toLowerCase().matches(".*ng\u00e0y.*\\d+.*");
            if (looksLikeDate) {
                StringBuilder sb2 = new StringBuilder("\u2753 M\u00ecnh kh\u00f4ng t\u00ecm th\u1ea5y ng\u00e0y \u0111\u00f3 trong danh s\u00e1ch.\n\nC\u00e1c ng\u00e0y kh\u1edfi h\u00e0nh c\u00f3 s\u1eb5n:\n");
                for (ConversationState.DepartureMeta dep : selectedTour.getDepartures()) {
                    sb2.append("  \u2022 **").append(this.formatDate(dep.getDepartureDate())).append("**");
                    if (dep.getAvailableSlots() != null) {
                        sb2.append(" \u2014 c\u00f2n ").append(dep.getAvailableSlots()).append(" ch\u1ed7");
                    }
                    sb2.append("\n");
                }
                sb2.append("\nNh\u1eadp **s\u1ed1 th\u1ee9 t\u1ef1** (1, 2, 3) ho\u1eb7c ng\u00e0y (v\u00ed d\u1ee5: 18/06):");
                return this.text(sb2.toString(), sessionId, "SELECTING_DEPARTURE");
            }
            return null;
        }
        int needed = state.getSearchAdults() + state.getSearchChildren() + state.getSearchToddlers();
        if (matched.getAvailableSlots() != null && matched.getAvailableSlots() < needed) {
            return this.text("Ng\u00e0y **" + this.formatDate(matched.getDepartureDate()) + "** ch\u1ec9 c\u00f2n " + String.valueOf(matched.getAvailableSlots()) + " ch\u1ed7, kh\u00f4ng \u0111\u1ee7 cho " + needed + " ng\u01b0\u1eddi. B\u1ea1n ch\u1ecdn ng\u00e0y kh\u00e1c nh\u00e9!", sessionId, "SELECTING_DEPARTURE");
        }
        state.setSelectedDepartureId(matched.getDepartureId());
        state.setDepartureDateDisplay(this.formatDate(matched.getDepartureDate()));
        state.setDepartureDateRaw(matched.getDepartureDate());
        try {
            ChatbotDepartureInfoResponse pricing = this.tourCatalogClient.getDepartureOrderInfo(matched.getDepartureId());
            state.setAdultPrice(Long.valueOf(pricing.getAdultPrice() != null ? pricing.getAdultPrice().longValue() : matched.getSalePrice().longValue()));
            state.setChildPrice(Long.valueOf(pricing.getChildPrice() != null ? pricing.getChildPrice().longValue() : 0L));
            state.setToddlerPrice(Long.valueOf(pricing.getToddlerPrice() != null ? pricing.getToddlerPrice().longValue() : 0L));
            state.setInfantPrice(Long.valueOf(pricing.getInfantPrice() != null ? pricing.getInfantPrice().longValue() : 0L));
            state.setSingleRoomSurcharge(Long.valueOf(pricing.getSingleRoomSurcharge() != null ? pricing.getSingleRoomSurcharge().longValue() : 0L));
            state.setAvailableSlots(pricing.getAvailableSlots());
        }
        catch (Exception e) {
            log.warn("\u26a0\ufe0f Kh\u00f4ng l\u1ea5y \u0111\u01b0\u1ee3c pricing cho departure {}: {}", (Object)matched.getDepartureId(), (Object)e.getMessage());
            state.setAdultPrice(matched.getSalePrice());
        }
        state.setStage(ConversationState.Stage.COLLECTING_PASSENGERS);
        if (!state.isSearchAdultsProvided()) {
            state.setPassengers(new ArrayList());
            state.setCurrentPassengerIndex(0);
            this.sessionService.save(sessionId, state);
            return this.text(this.buildPassengerCompositionPrompt(state), sessionId, "COLLECTING_PASSENGERS");
        }
        this.initPassengerSlotsFromCounts(state);
        this.sessionService.save(sessionId, state);
        ConversationState.PassengerData first = (ConversationState.PassengerData)state.getPassengers().get(0);
        String typeVi = this.typeToVietnamese(first.getType());
        return this.text("\u0110\u00e3 ch\u1ecdn ng\u00e0y **%s** \u2705\n\nB\u00e2y gi\u1edd m\u00ecnh c\u1ea7n th\u00f4ng tin h\u00e0nh kh\u00e1ch.\n\n**H\u00e0nh kh\u00e1ch 1 (%s):**\nCho t\u00f4i bi\u1ebft h\u1ecd t\u00ean \u0111\u1ea7y \u0111\u1ee7 v\u00e0 gi\u1edbi t\u00ednh (v\u00ed d\u1ee5: *Nguy\u1ec5n V\u0103n A, Nam*)\n".formatted(state.getDepartureDateDisplay(), typeVi), sessionId, "COLLECTING_PASSENGERS");
    }

    private ChatMessageResponse handlePassengerInfo(String msg, String sessionId, ConversationState state) {
        List passengers = state.getPassengers();
        if (passengers == null || passengers.isEmpty()) {
            if (!this.parsePassengerComposition(msg, state)) {
                return this.text(this.buildPassengerCompositionPrompt(state), sessionId, "COLLECTING_PASSENGERS");
            }
            if (state.getSearchInfants() > state.getSearchAdults()) {
                return this.text("S\u1ed1 em b\u00e9 kh\u00f4ng \u0111\u01b0\u1ee3c nhi\u1ec1u h\u01a1n s\u1ed1 ng\u01b0\u1eddi l\u1edbn \u0111i k\u00e8m. B\u1ea1n g\u1eedi l\u1ea1i s\u1ed1 l\u01b0\u1ee3ng gi\u00fap m\u00ecnh nh\u00e9.", sessionId, "COLLECTING_PASSENGERS");
            }
            int needed = state.getSearchAdults() + state.getSearchChildren() + state.getSearchToddlers();
            if (state.getAvailableSlots() != null && needed > state.getAvailableSlots()) {
                return this.text("Ng\u00e0y n\u00e0y ch\u1ec9 c\u00f2n **" + String.valueOf(state.getAvailableSlots()) + " ch\u1ed7**. B\u1ea1n gi\u1ea3m s\u1ed1 kh\u00e1ch ho\u1eb7c ch\u1ecdn ng\u00e0y kh\u00e1c nh\u00e9.", sessionId, "COLLECTING_PASSENGERS");
            }
            this.initPassengerSlotsFromCounts(state);
            this.sessionService.save(sessionId, state);
            ConversationState.PassengerData first = (ConversationState.PassengerData)state.getPassengers().get(0);
            return this.text("\u0110\u00e3 ghi nh\u1eadn **" + this.passengerCountSummary(state) + "**.\n\n**H\u00e0nh kh\u00e1ch 1 (" + this.typeToVietnamese(first.getType()) + "):**\nVui l\u00f2ng nh\u1eadp **h\u1ecd t\u00ean \u0111\u1ea7y \u0111\u1ee7, gi\u1edbi t\u00ednh** (v\u00ed d\u1ee5: *Nguy\u1ec5n V\u0103n A, Nam*)", sessionId, "COLLECTING_PASSENGERS");
        }
        int idx = state.getCurrentPassengerIndex();
        if (idx >= passengers.size()) {
            return this.moveToContact(sessionId, state);
        }
        ConversationState.PassengerData current = (ConversationState.PassengerData)passengers.get(idx);
        if (current.getFullName() == null || current.getFullName().isBlank()) {
            String[] parts = msg.split(",");
            String name = parts[0].trim();
            if (name.length() < 2) {
                return this.text("B\u1ea1n nh\u1eadp gi\u00fap m\u00ecnh h\u1ecd t\u00ean \u0111\u1ea7y \u0111\u1ee7 v\u00e0 gi\u1edbi t\u00ednh c\u1ee7a h\u00e0nh kh\u00e1ch nh\u00e9. V\u00ed d\u1ee5: **Nguy\u1ec5n V\u0103n A, Nam**", sessionId, "COLLECTING_PASSENGERS");
            }
            String gender = parts.length > 1 ? this.parseGender(parts[1].trim()) : "MALE";
            current.setFullName(name);
            current.setGender(gender);
            if ("ADULT".equals(current.getType()) && this.normalizeLocation(msg).matches(".*(phong\\s*don|single\\s*room|o\\s*1\\s*minh).*")) {
                current.setSingleRoom(true);
            }
            String inlineDob = null;
            int i = 1;
            while (i < parts.length) {
                String candidate = this.parseDateOfBirth(parts[i].trim());
                if (candidate != null) {
                    inlineDob = candidate;
                    break;
                }
                ++i;
            }
            if (inlineDob == null) {
                inlineDob = this.parseDateOfBirth(msg);
            }
            if (inlineDob != null) {
                current.setDateOfBirth(inlineDob);
                passengers.set(idx, current);
                state.setPassengers(passengers);
                state.setCurrentPassengerIndex(idx + 1);
                if (idx + 1 < passengers.size()) {
                    ConversationState.PassengerData next = (ConversationState.PassengerData)passengers.get(idx + 1);
                    String typeVi = this.typeToVietnamese(next.getType());
                    this.sessionService.save(sessionId, state);
                    return this.text("\u0110\u00e3 ghi nh\u1eadn **" + name + "**.\n\n**H\u00e0nh kh\u00e1ch " + (idx + 2) + " (" + typeVi + "):**\nH\u1ecd t\u00ean \u0111\u1ea7y \u0111\u1ee7, gi\u1edbi t\u00ednh v\u00e0 ng\u00e0y sinh. V\u00ed d\u1ee5: **Tr\u1ea7n Th\u1ecb B, N\u1eef, 1992-05-03**", sessionId, "COLLECTING_PASSENGERS");
                }
                return this.moveToContact(sessionId, state);
            }
            passengers.set(idx, current);
            state.setPassengers(passengers);
            this.sessionService.save(sessionId, state);
            return this.text("\u0110\u00e3 ghi nh\u1eadn **" + name + "**.\n\nB\u1ea1n cho m\u00ecnh bi\u1ebft **ng\u00e0y sinh** c\u1ee7a h\u00e0nh kh\u00e1ch n\u00e0y nh\u00e9. V\u00ed d\u1ee5: **15/08/1995**", sessionId, "COLLECTING_PASSENGERS");
        }
        if (current.getDateOfBirth() == null || current.getDateOfBirth().isBlank()) {
            String dob = this.parseDateOfBirth(msg);
            if (dob == null) {
                return this.text("Ng\u00e0y sinh ch\u01b0a \u0111\u00fang \u0111\u1ecbnh d\u1ea1ng. B\u1ea1n nh\u1eadp theo d\u1ea1ng **DD/MM/YYYY** ho\u1eb7c **YYYY-MM-DD** nh\u00e9.", sessionId, "COLLECTING_PASSENGERS");
            }
            current.setDateOfBirth(dob);
            passengers.set(idx, current);
            state.setPassengers(passengers);
            state.setCurrentPassengerIndex(idx + 1);
            if (idx + 1 < passengers.size()) {
                ConversationState.PassengerData next = (ConversationState.PassengerData)passengers.get(idx + 1);
                String typeVi = this.typeToVietnamese(next.getType());
                this.sessionService.save(sessionId, state);
                return this.text("\u0110\u00e3 ghi nh\u1eadn ng\u00e0y sinh.\n\n**H\u00e0nh kh\u00e1ch " + (idx + 2) + " (" + typeVi + "):**\nH\u1ecd t\u00ean \u0111\u1ea7y \u0111\u1ee7 v\u00e0 gi\u1edbi t\u00ednh. V\u00ed d\u1ee5: **Tr\u1ea7n Th\u1ecb B, N\u1eef**", sessionId, "COLLECTING_PASSENGERS");
            }
            return this.moveToContact(sessionId, state);
        }
        state.setCurrentPassengerIndex(idx + 1);
        this.sessionService.save(sessionId, state);
        return this.handlePassengerInfo(msg, sessionId, state);
    }

    private ChatMessageResponse moveToContact(String sessionId, ConversationState state) {
        state.setStage(ConversationState.Stage.COLLECTING_CONTACT_NAME_PHONE);
        this.sessionService.save(sessionId, state);
        return this.text("\u2705 \u0110\u00e3 ghi nh\u1eadn th\u00f4ng tin h\u00e0nh kh\u00e1ch!\n\nB\u00e2y gi\u1edd cho t\u00f4i bi\u1ebft **th\u00f4ng tin ng\u01b0\u1eddi li\u00ean h\u1ec7**:\nH\u1ecd t\u00ean \u0111\u1ea7y \u0111\u1ee7 v\u00e0 s\u1ed1 \u0111i\u1ec7n tho\u1ea1i (v\u00ed d\u1ee5: *Nguy\u1ec5n Th\u1ecb B, 0901234567*)\n", sessionId, "COLLECTING_CONTACT_NAME_PHONE");
    }

    private ChatMessageResponse handleContactNamePhone(String msg, String sessionId, ConversationState state) {
        String[] parts = msg.split(",", 2);
        if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return this.text("Vui l\u00f2ng nh\u1eadp h\u1ecd t\u00ean v\u00e0 s\u1ed1 \u0111i\u1ec7n tho\u1ea1i c\u00e1ch nhau b\u1eb1ng d\u1ea5u ph\u1ea9y.\nV\u00ed d\u1ee5: *Nguy\u1ec5n Th\u1ecb B, 0901234567*", sessionId, "COLLECTING_CONTACT_NAME_PHONE");
        }
        String name = parts[0].trim();
        String phone = parts[1].trim().replaceAll("[\\s\\-]", "");
        if (!phone.matches("^0\\d{9,10}$")) {
            return this.text("\u274c S\u1ed1 \u0111i\u1ec7n tho\u1ea1i **kh\u00f4ng h\u1ee3p l\u1ec7**. Vui l\u00f2ng nh\u1eadp s\u1ed1 \u0111i\u1ec7n tho\u1ea1i 10-11 ch\u1eef s\u1ed1 b\u1eaft \u0111\u1ea7u b\u1eb1ng 0.\nV\u00ed d\u1ee5: *" + name + ", 0901234567*", sessionId, "COLLECTING_CONTACT_NAME_PHONE");
        }
        state.setContactName(name);
        state.setContactPhone(phone);
        state.setStage(ConversationState.Stage.COLLECTING_CONTACT_EMAIL);
        this.sessionService.save(sessionId, state);
        return this.text("\u2705 C\u1ea3m \u01a1n **" + state.getContactName() + "**!\n\n\ud83d\udce7 \u0110\u1ecba ch\u1ec9 **email** \u0111\u1ec3 nh\u1eadn x\u00e1c nh\u1eadn \u0111\u1eb7t tour:", sessionId, "COLLECTING_CONTACT_EMAIL");
    }

    private ChatMessageResponse handleContactEmail(String msg, String sessionId, ConversationState state, Integer userId) {
        String email = msg.trim();
        if (!this.isValidEmail(email)) {
            return this.text("Email kh\u00f4ng h\u1ee3p l\u1ec7. Vui l\u00f2ng nh\u1eadp l\u1ea1i (v\u00ed d\u1ee5: *name@gmail.com*):", sessionId, "COLLECTING_CONTACT_EMAIL");
        }
        state.setContactEmail(email);
        state.setStage(ConversationState.Stage.COLLECTING_NOTE_COUPON);
        this.sessionService.save(sessionId, state);
        return this.text("\u2705 \u0110\u00e3 ghi nh\u1eadn email.\n\nB\u1ea1n c\u00f3 **\u0111\u1ecba ch\u1ec9 li\u00ean h\u1ec7, ghi ch\u00fa, m\u00e3 gi\u1ea3m gi\u00e1 ho\u1eb7c mu\u1ed1n d\u00f9ng \u0111i\u1ec3m** kh\u00f4ng?\n- N\u1ebfu c\u00f3, g\u1eedi v\u00ed d\u1ee5: **123 L\u00ea L\u1ee3i, ghi ch\u00fa \u0103n chay, m\u00e3 WELCOME100K**\n- N\u1ebfu kh\u00f4ng, nh\u1eadp **b\u1ecf qua**.\n", sessionId, "COLLECTING_NOTE_COUPON");
    }

    private ChatMessageResponse handleNoteCoupon(String msg, String sessionId, ConversationState state) {
        String normalized = this.normalizeLocation(msg);
        if (!normalized.matches(".*(bo\\s*qua|khong|khong\\s*co|skip|tiep\\s*tuc).*")) {
            String cleaned;
            ArrayList<String> coupons = new ArrayList<String>(state.getCouponCodes() != null ? state.getCouponCodes() : new ArrayList());
            Matcher couponMatcher = Pattern.compile("(?i)\\b([A-Z][A-Z0-9]{3,19})\\b").matcher(msg);
            while (couponMatcher.find()) {
                String code = couponMatcher.group(1).toUpperCase(Locale.ROOT);
                if (coupons.contains(code)) continue;
                coupons.add(code);
            }
            state.setCouponCodes(coupons);
            Matcher pointsMatcher = Pattern.compile("(\\d+)\\s*(diem|point|xu)").matcher(normalized);
            if (pointsMatcher.find()) {
                state.setPointsUsed(Integer.valueOf(Integer.parseInt(pointsMatcher.group(1))));
            }
            if (!(cleaned = msg.replaceAll("(?i)\\b(m\u00e3|ma|coupon|voucher)\\s+[A-Z0-9]{4,20}\\b", " ").replaceAll("(?i)\\b[A-Z][A-Z0-9]{3,19}\\b", " ").replaceAll("(?i)\\d+\\s*(diem|point|xu)", " ").replaceAll("\\s+", " ").trim()).isBlank()) {
                if (normalized.contains("ghi chu") || normalized.contains("note")) {
                    state.setCustomerNote(cleaned);
                } else {
                    state.setContactAddress(cleaned);
                }
            }
        }
        state.setStage(ConversationState.Stage.CONFIRMING_BOOKING);
        this.sessionService.save(sessionId, state);
        return this.buildConfirmCard(sessionId, state);
    }

    private ChatMessageResponse buildConfirmCard(String sessionId, ConversationState state) {
        long singleRoomTotal = state.getPassengers() == null ? 0L : state.getPassengers().stream().filter(ConversationState.PassengerData::isSingleRoom).count() * this.nvl(state.getSingleRoomSurcharge());
        long total = (long)state.getSearchAdults() * this.nvl(state.getAdultPrice()) + (long)state.getSearchChildren() * this.nvl(state.getChildPrice()) + (long)state.getSearchToddlers() * this.nvl(state.getToddlerPrice()) + (long)state.getSearchInfants() * this.nvl(state.getInfantPrice()) + singleRoomTotal;
        List pSummaries = state.getPassengers().stream().map(p -> BookingConfirmData.PassengerSummary.builder().type(p.getType()).fullName(p.getFullName()).gender(p.getGender()).dateOfBirth(p.getDateOfBirth()).build()).collect(Collectors.toList());
        BookingConfirmData confirmData = BookingConfirmData.builder().tourName(state.getSelectedTourName()).tourCode(state.getSelectedTourCode()).tourImage(state.getSelectedTourImage()).duration(state.getSelectedDuration()).departureDate(state.getDepartureDateDisplay()).departureCity(state.getDepartureCity()).passengers(pSummaries).contactName(state.getContactName()).contactPhone(state.getContactPhone()).contactEmail(state.getContactEmail()).adultCount(state.getSearchAdults()).childCount(state.getSearchChildren()).toddlerCount(state.getSearchToddlers()).infantCount(state.getSearchInfants()).adultPrice(this.nvl(state.getAdultPrice())).childPrice(this.nvl(state.getChildPrice())).toddlerPrice(this.nvl(state.getToddlerPrice())).infantPrice(this.nvl(state.getInfantPrice())).singleRoomSurcharge(this.nvl(state.getSingleRoomSurcharge())).estimatedTotal(total).build();
        StringBuilder sb = new StringBuilder("**XAC NHAN DAT TOUR**\n\n");
        sb.append("**").append(state.getSelectedTourName()).append("**\n");
        sb.append("Khoi hanh: **").append(state.getDepartureDateDisplay()).append("** | ").append(state.getSelectedDuration()).append("\n\n");
        sb.append("**Hanh khach:**\n");
        if (state.getSearchAdults() > 0) {
            sb.append("- Nguoi lon x ").append(state.getSearchAdults()).append(": ").append(this.fmt(state.getAdultPrice())).append("d/nguoi\n");
        }
        if (state.getSearchChildren() > 0) {
            sb.append("- Tre em x ").append(state.getSearchChildren()).append(": ").append(this.fmt(state.getChildPrice())).append("d/nguoi\n");
        }
        if (state.getSearchToddlers() > 0) {
            sb.append("- Tre nho x ").append(state.getSearchToddlers()).append(": ").append(this.fmt(state.getToddlerPrice())).append("d/nguoi\n");
        }
        if (state.getSearchInfants() > 0) {
            sb.append("- Em be x ").append(state.getSearchInfants()).append(": ").append(this.fmt(state.getInfantPrice())).append("d/nguoi\n");
        }
        if (singleRoomTotal > 0L) {
            sb.append("- Phong don: ").append(this.fmt(singleRoomTotal)).append("d\n");
        }
        sb.append("\n**Lien he:** ").append(state.getContactName()).append(" | ").append(state.getContactPhone()).append(" | ").append(state.getContactEmail()).append("\n");
        if (state.getContactAddress() != null && !state.getContactAddress().isBlank()) {
            sb.append("**Dia chi:** ").append(state.getContactAddress()).append("\n");
        }
        if (state.getCustomerNote() != null && !state.getCustomerNote().isBlank()) {
            sb.append("**Ghi chu:** ").append(state.getCustomerNote()).append("\n");
        }
        if (state.getCouponCodes() != null && !state.getCouponCodes().isEmpty()) {
            sb.append("**Ma giam gia:** ").append(String.join((CharSequence)", ", state.getCouponCodes())).append("\n");
        }
        if (state.getPointsUsed() != null && state.getPointsUsed() > 0) {
            sb.append("**Diem dung:** ").append(state.getPointsUsed()).append("\n");
        }
        sb.append("\n**TONG DU TINH: ~").append(this.fmt(total)).append("d**\n");
        sb.append("Gia chinh xac se duoc booking-service tinh lai khi xac nhan.\n");
        sb.append("Han thanh toan: **24 gio** ke tu khi dat.\n\n");
        sb.append("Ban muon **xac nhan dat tour** khong? Go **xac nhan** hoac **huy**.");
        return ChatMessageResponse.builder().reply(sb.toString()).sessionId(sessionId).timestamp(LocalDateTime.now()).messageType("BOOKING_CONFIRM").conversationStage("CONFIRMING_BOOKING").bookingConfirmData(confirmData).quickActions(List.of(ChatMessageResponse.QuickAction.builder().label("Xac nhan dat tour").action("CONFIRM_BOOKING").build(), ChatMessageResponse.QuickAction.builder().label("Huy").action("CANCEL").build())).build();
    }

    private ChatMessageResponse handleConfirm(String msg, String sessionId, ConversationState state, Integer userId) {
        if (!this.isConfirm(msg)) {
            return this.text("B\u1ea1n mu\u1ed1n **x\u00e1c nh\u1eadn** \u0111\u1eb7t tour hay **h\u1ee7y**? (G\u00f5 *X\u00e1c nh\u1eadn* ho\u1eb7c *H\u1ee7y*)", sessionId, "CONFIRMING_BOOKING");
        }
        List passengerReqs = state.getPassengers().stream().map(p -> ChatbotCreateBookingRequest.PassengerRequest.builder().fullName(p.getFullName() != null ? p.getFullName() : "H\u00e0nh kh\u00e1ch").gender(p.getGender() != null ? p.getGender() : "MALE").dateOfBirth(p.getDateOfBirth()).type(p.getType()).singleRoom(p.isSingleRoom()).build()).collect(Collectors.toList());
        ChatbotCreateBookingRequest bookingReq = ChatbotCreateBookingRequest.builder().departureId(state.getSelectedDepartureId()).userId(userId).contactFullName(state.getContactName()).contactPhone(state.getContactPhone()).contactEmail(state.getContactEmail()).contactAddress(state.getContactAddress() != null && !state.getContactAddress().isBlank() ? state.getContactAddress() : "\u0110\u1eb7t qua chatbot").customerNote(state.getCustomerNote() != null ? state.getCustomerNote() : "").passengers(passengerReqs).couponCode((List)(state.getCouponCodes() != null ? new ArrayList(state.getCouponCodes()) : new ArrayList())).pointsUsed(Integer.valueOf(state.getPointsUsed() != null ? state.getPointsUsed() : 0)).build();
        try {
            ChatbotCreateBookingResponse bookingResp = this.bookingClient.createBooking(bookingReq);
            state.setBookingCode(bookingResp.getBookingCode());
            state.setBookingId(bookingResp.getBookingId());
            state.setTotalPrice(bookingResp.getTotalPrice());
            String payUrl = null;
            try {
                PayosCreateRequest payReq = PayosCreateRequest.builder().bookingCode(bookingResp.getBookingCode()).amount(BigDecimal.valueOf(bookingResp.getTotalPrice())).description("Thanh toan tour " + bookingResp.getBookingCode()).returnUrl(this.frontendUrl + "/payment-waiting?bookingCode=" + bookingResp.getBookingCode()).cancelUrl(this.frontendUrl + "/payment-failed?cancelled=true&bookingCode=" + bookingResp.getBookingCode()).build();
                PaymentUrlResponse payResp = this.paymentClient.createPayosPayment(payReq);
                payUrl = payResp.getCheckoutUrl();
                if (payResp.getTransactionId() != null) {
                    state.setPaymentWaitingLink(this.frontendUrl + "/payment-waiting?orderCode=" + payResp.getTransactionId() + "&bookingCode=" + bookingResp.getBookingCode());
                }
            }
            catch (Exception pe) {
                log.warn("\u26a0\ufe0f Kh\u00f4ng t\u1ea1o \u0111\u01b0\u1ee3c payment link: {}", (Object)pe.getMessage());
            }
            state.setPaymentUrl(payUrl);
            state.setStage(ConversationState.Stage.BOOKING_SUCCESS);
            this.sessionService.save(sessionId, state);
            StringBuilder sb = new StringBuilder("\u2705 **\u0110\u1eb7t tour th\u00e0nh c\u00f4ng!**\n\n");
            sb.append("\ud83c\udfab M\u00e3 \u0111\u1eb7t tour: **").append(bookingResp.getBookingCode()).append("**\n");
            sb.append("\ud83d\udcb0 T\u1ed5ng ti\u1ec1n: **").append(this.fmt(bookingResp.getTotalPrice())).append("\u0111**\n");
            sb.append("\u23f0 H\u1ea1n thanh to\u00e1n: **24 gi\u1edd** k\u1ec3 t\u1eeb b\u00e2y gi\u1edd\n\n");
            if (payUrl != null) {
                sb.append("\ud83d\udc49 **[Thanh to\u00e1n ngay qua PayOS](").append(payUrl).append(")**\n\n");
            }
            if (state.getPaymentWaitingLink() != null) {
                sb.append("\ud83d\udcca **[Theo d\u00f5i tr\u1ea1ng th\u00e1i thanh to\u00e1n](").append(state.getPaymentWaitingLink()).append(")**\n\n");
            }
            sb.append("\ud83d\udce9 X\u00e1c nh\u1eadn g\u1eedi v\u1ec1: **").append(state.getContactEmail()).append("**\n\n");
            sb.append("\u0110\u1ec3 ki\u1ec3m tra \u0111\u01a1n h\u00e0ng, g\u00f5: *tra c\u1ee9u ").append(bookingResp.getBookingCode()).append("*");
            return ChatMessageResponse.builder().reply(sb.toString()).sessionId(sessionId).timestamp(LocalDateTime.now()).messageType("BOOKING_SUCCESS").conversationStage("BOOKING_SUCCESS").bookingCode(bookingResp.getBookingCode()).paymentUrl(payUrl).paymentWaitingLink(state.getPaymentWaitingLink()).quickActions(List.of(ChatMessageResponse.QuickAction.builder().label("\ud83d\udd0d Xem \u0111\u01a1n h\u00e0ng").action("LOOKUP_" + bookingResp.getBookingCode()).build(), ChatMessageResponse.QuickAction.builder().label("\ud83c\udfd6\ufe0f \u0110\u1eb7t tour kh\u00e1c").action("NEW_BOOKING").build())).build();
        }
        catch (Exception e) {
            log.error("\u274c T\u1ea1o booking th\u1ea5t b\u1ea1i: {}", (Object)e.getMessage(), (Object)e);
            this.sessionService.save(sessionId, state);
            return this.text("\u274c H\u1ec7 th\u1ed1ng \u0111ang g\u1eb7p s\u1ef1 c\u1ed1 khi t\u1ea1o \u0111\u1eb7t tour. Vui l\u00f2ng th\u1eed l\u1ea1i sau ho\u1eb7c li\u00ean h\u1ec7 **1900-xxxx**.\n\nL\u1ed7i: " + e.getMessage(), sessionId, "CONFIRMING_BOOKING");
        }
    }

    private ChatMessageResponse handleAfterSuccess(String msg, String sessionId, ConversationState state) {
        state.setStage(ConversationState.Stage.IDLE);
        this.sessionService.save(sessionId, state);
        return null;
    }

    private ChatMessageResponse handleLookup(String msg, String sessionId, ConversationState state) {
        String code = this.extractBookingCode(msg);
        if (code == null) {
            return null;
        }
        return this.performLookup(code, sessionId, state);
    }

    private ChatMessageResponse performLookup(String code, String sessionId, ConversationState state) {
        try {
            ChatbotBookingDetailResponse detail = this.bookingClient.getBookingDetail(code);
            ConversationState.Stage resumeStage = state.getPreviousStage();
            if (resumeStage != null) {
                state.setStage(resumeStage);
                state.setPreviousStage(null);
            } else {
                state.setStage(ConversationState.Stage.IDLE);
            }
            this.sessionService.save(sessionId, state);
            String statusVi = this.statusToVietnamese(detail.getStatus());
            StringBuilder sb = new StringBuilder("\ud83d\udccb **CHI TI\u1ebeT \u0110\u01a0N H\u00c0NG**\n\n");
            sb.append("\ud83c\udfab M\u00e3: **").append(detail.getBookingCode()).append("**\n");
            sb.append("\ud83c\udfd6\ufe0f **").append(detail.getTourName()).append("**\n");
            sb.append("\ud83d\udccc Tr\u1ea1ng th\u00e1i: **").append(statusVi).append("**\n\n");
            sb.append("\ud83d\udcb0 T\u1ed5ng ti\u1ec1n: **").append(this.fmt(detail.getOriginalPrice())).append("\u0111**\n");
            sb.append("\u2705 \u0110\u00e3 TT: ").append(this.fmt(detail.getPaidAmount())).append("\u0111\n");
            sb.append("\ud83d\udd34 C\u00f2n l\u1ea1i: **").append(this.fmt(detail.getRemainingAmount())).append("\u0111**\n");
            if (detail.getPaymentDeadline() != null) {
                sb.append("\u23f0 H\u1ea1n TT: **").append(detail.getPaymentDeadline(), 0, Math.min(16, detail.getPaymentDeadline().length())).append("**\n");
            }
            if (detail.getPassengers() != null && !detail.getPassengers().isEmpty()) {
                sb.append("\n**\ud83d\udc65 H\u00e0nh kh\u00e1ch:**\n");
                detail.getPassengers().forEach(p -> {
                    StringBuilder stringBuilder2 = sb.append("  \u2022 ").append(p.getFullName()).append(" (").append(this.typeToVietnamese(p.getType())).append(")\n");
                });
            }
            return ChatMessageResponse.builder().reply(sb.toString()).sessionId(sessionId).timestamp(LocalDateTime.now()).messageType("ORDER_DETAIL").conversationStage(state.getStage().name()).orderDetail(detail).quickActions(resumeStage != null ? List.of(ChatMessageResponse.QuickAction.builder().label("Ti\u1ebfp t\u1ee5c \u0111\u1eb7t tour").action("RESUME_BOOKING").build(), ChatMessageResponse.QuickAction.builder().label("H\u1ee7y").action("CANCEL").build()) : new ArrayList()).build();
        }
        catch (Exception e) {
            state.setStage(ConversationState.Stage.IDLE);
            this.sessionService.save(sessionId, state);
            return this.text("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng **" + code + "**. B\u1ea1n ki\u1ec3m tra l\u1ea1i m\u00e3 \u0111\u1eb7t tour nh\u00e9 (\u0111\u1ecbnh d\u1ea1ng: BKxxxxxxxx).", sessionId, "IDLE");
        }
    }

    public boolean isBookingIntent(String msg) {
        String lower = msg.toLowerCase();
        return lower.matches(".*(\u0111\u1eb7t\\s*tour|dat\\s*tour|book\\s*tour|mua\\s*tour|mu\u1ed1n\\s*\u0111i|muon\\s*di|t\u00f4i\\s*(c\u1ea7n|mu\u1ed1n)\\s*\u0111\u1eb7t|toi\\s*(can|muon)\\s*dat|dat\\s*cho|\u0111\u1eb7t\\s*ch\u1ed7|t\u00ecm\\s*tour\\s*(\u0111\u1ec3|de)\\s*\u0111\u1eb7t|tim\\s*tour|muon\\s*dat|mu\u1ed1n\\s*\u0111\u1eb7t).*");
    }

    public boolean isLookupIntent(String msg) {
        String lower = this.normalizeLocation(msg);
        return lower.matches(".*(tra\\s*cuu|kiem\\s*tra|xem\\s*don|tinh\\s*trang|don\\s*hang|booking\\s*cua|ma\\s*dat|don\\s*cua\\s*toi|lich\\s*su\\s*dat).*") || this.extractBookingCode(msg) != null;
    }

    public boolean isCancel(String msg) {
        String lower = msg.toLowerCase().trim();
        if (lower.equals("huy") || lower.equals("thoi") || lower.equals("thoat") || lower.equals("cancel") || lower.equals("exit")) {
            return true;
        }
        return lower.matches(".*(^|\\s)(thoat|khong\\s*dat|exit|cancel|thoi\\s*di|huy\\s*di|thoi\\s+khong)(\\s|$|[.!?]).*");
    }

    private boolean isConfirm(String msg) {
        String lower = msg.toLowerCase();
        return lower.matches(".*(x\u00e1c\\s*nh\u1eadn|xac\\s*nhan|confirm|\u0111\u1ed3ng\\s*\u00fd|dong\\s*y|ok|yes|\u0111\u1eb7t\\s*ngay|dat\\s*ngay|ch\u1eafc\\s*ch\u1eafn|chac\\s*chan).*");
    }

    private void parseAndFillSearchParamsV3(String msg, ConversationState state) {
        boolean hasStartContext;
        String afterDen;
        Optional destOpt;
        String[] denParts;
        String lower = msg.toLowerCase(Locale.ROOT);
        String normMsg = this.locationResolver.normalizeText(msg);
        Matcher adultMatcher = Pattern.compile("(\\d+)\\s*(ng\u01b0\u1eddi\\s*l\u1edbn|nguoi\\s*lon|adult|ng\u01b0\u1eddi|nguoi|kh\u00e1ch|khach|pass)").matcher(lower);
        if (adultMatcher.find()) {
            state.setSearchAdults(Integer.parseInt(adultMatcher.group(1)));
            state.setSearchAdultsProvided(true);
        } else if (normMsg.matches("^\\d{1,2}$") && state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO && state.getSearchDestination() != null) {
            state.setSearchAdults(Integer.parseInt(normMsg));
            state.setSearchAdultsProvided(true);
        }
        Matcher childMatcher = Pattern.compile("(\\d+)\\s*(tr\u1ebb\\s*em|tre\\s*em|child)").matcher(lower);
        if (childMatcher.find()) {
            state.setSearchChildren(Integer.parseInt(childMatcher.group(1)));
            state.setSearchChildrenProvided(true);
        } else if (normMsg.matches(".*(khong\\s*co\\s*tre|khong\\s*tre|ko\\s*tre).*")) {
            state.setSearchChildren(0);
            state.setSearchToddlers(0);
            state.setSearchInfants(0);
            state.setSearchChildrenProvided(true);
        }
        Matcher toddlerMatcher = Pattern.compile("(\\d+)\\s*(tr\u1ebb\\s*nh\u1ecf|tre\\s*nho|toddler|em\\s*b\u00e9|em\\s*be)").matcher(lower);
        if (toddlerMatcher.find()) {
            state.setSearchToddlers(Integer.parseInt(toddlerMatcher.group(1)));
            state.setSearchChildrenProvided(true);
        }
        if ((normMsg.contains(" den ") || normMsg.startsWith("den ")) && (denParts = normMsg.split("\\bden\\b", 2)).length == 2 && (destOpt = this.locationResolver.resolve(afterDen = denParts[1].trim(), LocationResolverService.Role.ANY)).isPresent()) {
            state.setSearchDestination(((LocationResolverService.ResolvedLocation)destOpt.get()).name());
            String destName = ((LocationResolverService.ResolvedLocation)destOpt.get()).name();
            String beforeDen = denParts[0].trim();
            this.locationResolver.resolve(beforeDen, LocationResolverService.Role.ANY).ifPresent(loc -> {
                String d;
                String cand = this.locationResolver.normalizeText(loc.name());
                if (!cand.equals(d = this.locationResolver.normalizeText(destName))) {
                    state.setSearchStartLocation(loc.name());
                    state.setSearchStartLocationProvided(true);
                }
            });
        }
        if (hasStartContext = normMsg.matches(".*(khoi\\s*hanh|xuat\\s*phat|di\\s*tu|toi\\s*o|minh\\s*o|o\\s+.+\\s+thi|tu\\s+.+).*")) {
            this.locationResolver.resolve(msg, LocationResolverService.Role.START).ifPresent(location -> {
                state.setSearchStartLocation(location.name());
                state.setSearchStartLocationProvided(true);
            });
        } else if (state.getSearchDestination() != null && state.getSearchStartLocation() == null) {
            this.locationResolver.resolve(msg, LocationResolverService.Role.START).ifPresent(location -> {
                String dest;
                String candidate = this.locationResolver.normalizeText(location.name());
                if (!candidate.equals(dest = this.locationResolver.normalizeText(state.getSearchDestination()))) {
                    state.setSearchStartLocation(location.name());
                    state.setSearchStartLocationProvided(true);
                }
            });
        } else if (state.getSearchDestination() == null) {
            this.locationResolver.resolve(msg, LocationResolverService.Role.DESTINATION).ifPresent(location -> state.setSearchDestination(location.name()));
        }
        Matcher monthMatcher = Pattern.compile("(?:thang|th\u00e1ng)\\s*(\\d{1,2})").matcher(lower);
        if (monthMatcher.find()) {
            int month = Integer.parseInt(monthMatcher.group(1));
            if (month >= 1 && month <= 12) {
                state.setSearchDateRange(String.format("2027-%02d", month));
                state.setSearchDateRangeProvided(true);
            }
        } else if (normMsg.contains("tuan sau")) {
            state.setSearchDateRange("next-week");
            state.setSearchDateRangeProvided(true);
        } else if (normMsg.matches(".*(gan\\s*nhat|som\\s*nhat|luc\\s*nao\\s*cung\\s*duoc|khi\\s*nao\\s*cung\\s*duoc).*")) {
            state.setSearchDateRange("soonest");
            state.setSearchDateRangeProvided(true);
        }
    }

    private boolean hasEnoughSearchParams(ConversationState state) {
        return state.getSearchDestination() != null && !state.getSearchDestination().isEmpty() || state.getSearchStartLocation() != null && !state.getSearchStartLocation().isEmpty();
    }

    private String buildSearchQuery(ConversationState state) {
        StringBuilder q = new StringBuilder("tour");
        if (state.getSearchDestination() != null) {
            q.append(" ").append(state.getSearchDestination());
        }
        if (state.getSearchStartLocation() != null) {
            q.append(" kh\u1edfi h\u00e0nh t\u1eeb ").append(state.getSearchStartLocation());
        }
        if (state.getSearchDateRange() != null) {
            q.append(" ").append(state.getSearchDateRange());
        }
        q.append(" ").append(state.getSearchAdults()).append(" ng\u01b0\u1eddi l\u1edbn");
        return q.toString();
    }

    private int parseTourIndex(String msg, List<ConversationState.TourGroupDisplay> groups) {
        String lower = msg.trim().toLowerCase();
        if (lower.matches(".*\\b1\\b.*") || lower.contains("tour 1") || lower.contains("\u0111\u1ea7u ti\u00ean") || lower.equals("1")) {
            return 0;
        }
        if (lower.matches(".*\\b2\\b.*") || lower.contains("tour 2") || lower.equals("2")) {
            return 1;
        }
        if (lower.matches(".*\\b3\\b.*") || lower.contains("tour 3") || lower.equals("3")) {
            return 2;
        }
        int i = 0;
        while (i < groups.size()) {
            String name = groups.get(i).getTourName().toLowerCase();
            if (lower.contains(name.substring(0, Math.min(10, name.length())))) {
                return i;
            }
            ++i;
        }
        return -1;
    }

    private boolean dateMatches(String input, String rawDate) {
        String clean = input.replaceAll("[^\\d/\\-]", "").trim();
        if (clean.isEmpty()) {
            return false;
        }
        try {
            String[] rawParts = rawDate.split("-");
            String day = rawParts[2];
            String month = rawParts[1];
            String year = rawParts[0];
            if (clean.equals(day + "/" + month) || clean.equals(day + "/" + month.replaceFirst("^0", "")) || clean.equals(day + "-" + month) || clean.equals(day + "/" + month + "/" + year)) {
                return true;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return false;
    }

    private String extractBookingCode(String msg) {
        Matcher m = Pattern.compile("(BK[A-Za-z0-9]{8})").matcher(msg);
        return m.find() ? m.group(1) : null;
    }

    private String parseDateOfBirth(String msg) {
        String clean = msg == null ? "" : msg.trim();
        try {
            if (clean.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate.parse(clean);
                return clean;
            }
            Matcher m = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").matcher(clean);
            if (m.find()) {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = Integer.parseInt(m.group(3));
                LocalDate dob = LocalDate.of(year, month, day);
                return dob.format(RAW_FMT);
            }
        }
        catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String parseGender(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("n\u1eef") || lower.contains("nu") || lower.contains("female") || lower.contains("f")) {
            return "FEMALE";
        }
        if (lower.contains("kh\u00e1c") || lower.contains("khac") || lower.contains("other")) {
            return "OTHER";
        }
        return "MALE";
    }

    private String buildPassengerCompositionPrompt(ConversationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u0110\u00e3 ch\u1ecdn ng\u00e0y **").append(state.getDepartureDateDisplay()).append("** \u2705\n\n");
        sb.append("B\u00e2y gi\u1edd m\u00ecnh c\u1ea7n s\u1ed1 l\u01b0\u1ee3ng h\u00e0nh kh\u00e1ch \u0111\u1ec3 t\u00ednh gi\u00e1 v\u00e0 thu \u0111\u1ee7 th\u00f4ng tin.\n");
        sb.append("B\u1ea1n \u0111i **bao nhi\u00eau ng\u01b0\u1eddi l\u1edbn**? C\u00f3 **tr\u1ebb em/em b\u00e9** kh\u00f4ng?\n\n");
        sb.append("V\u00ed d\u1ee5: **2 ng\u01b0\u1eddi l\u1edbn**, ho\u1eb7c **2 ng\u01b0\u1eddi l\u1edbn, 1 tr\u1ebb em**.");
        if (state.getChildPrice() != null && state.getChildPrice() > 0L) {
            sb.append("\n\nGi\u00e1 tham kh\u1ea3o: ng\u01b0\u1eddi l\u1edbn ").append(this.fmt(state.getAdultPrice())).append("\u0111, tr\u1ebb em ").append(this.fmt(state.getChildPrice())).append("\u0111.");
        }
        return sb.toString();
    }

    private boolean parsePassengerComposition(String msg, ConversationState state) {
        String norm = this.normalizeLocation(msg);
        Matcher adultMatcher = Pattern.compile("(\\d+)\\s*(nguoi\\s*lon|nguoi|khach|adult)").matcher(norm);
        Matcher childMatcher = Pattern.compile("(\\d+)\\s*(tre\\s*em|child)").matcher(norm);
        Matcher toddlerMatcher = Pattern.compile("(\\d+)\\s*(tre\\s*nho|toddler)").matcher(norm);
        Matcher infantMatcher = Pattern.compile("(\\d+)\\s*(em\\s*be|infant|be\\s*duoi\\s*2|duoi\\s*2\\s*tuoi)").matcher(norm);
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
        if (infantMatcher.find()) {
            state.setSearchInfants(Integer.parseInt(infantMatcher.group(1)));
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
        ArrayList<ConversationState.PassengerData> passengers = new ArrayList<ConversationState.PassengerData>();
        this.addPassengerSlots(passengers, "ADULT", state.getSearchAdults());
        this.addPassengerSlots(passengers, "CHILD", state.getSearchChildren());
        this.addPassengerSlots(passengers, "TODDLER", state.getSearchToddlers());
        this.addPassengerSlots(passengers, "INFANT", state.getSearchInfants());
        state.setPassengers(passengers);
        state.setCurrentPassengerIndex(0);
    }

    private String passengerCountSummary(ConversationState state) {
        ArrayList<String> parts = new ArrayList<String>();
        if (state.getSearchAdults() > 0) {
            parts.add(state.getSearchAdults() + " ng\u01b0\u1eddi l\u1edbn");
        }
        if (state.getSearchChildren() > 0) {
            parts.add(state.getSearchChildren() + " tr\u1ebb em");
        }
        if (state.getSearchToddlers() > 0) {
            parts.add(state.getSearchToddlers() + " tr\u1ebb nh\u1ecf");
        }
        if (state.getSearchInfants() > 0) {
            parts.add(state.getSearchInfants() + " em b\u00e9");
        }
        return String.join((CharSequence)", ", parts);
    }

    private void addPassengerSlots(List<ConversationState.PassengerData> list, String type, int count) {
        int i = 1;
        while (i <= count) {
            list.add(ConversationState.PassengerData.builder().type(type).index(i).singleRoom(false).build());
            ++i;
        }
    }

    private String typeToVietnamese(String type) {
        if (type == null) {
            return "H\u00e0nh kh\u00e1ch";
        }
        return switch (type) {
            case "ADULT" -> "Ng\u01b0\u1eddi l\u1edbn";
            case "CHILD" -> "Tr\u1ebb em";
            case "TODDLER" -> "Tr\u1ebb nh\u1ecf";
            case "INFANT" -> "Em b\u00e9";
            default -> type;
        };
    }

    private String statusToVietnamese(String status) {
        if (status == null) {
            return "Kh\u00f4ng x\u00e1c \u0111\u1ecbnh";
        }
        return switch (status) {
            case "PENDING_PAYMENT" -> "\u23f3 Ch\u1edd thanh to\u00e1n";
            case "OVERDUE_PAYMENT" -> "\u274c Qu\u00e1 h\u1ea1n thanh to\u00e1n";
            case "PENDING_CONFIRMATION" -> "\ud83d\udd0d \u0110\u00e3 thanh to\u00e1n, ch\u1edd x\u00e1c nh\u1eadn";
            case "PAID" -> "\u2705 \u0110\u00e3 thanh to\u00e1n";
            case "CANCELLED" -> "\u274c \u0110\u00e3 h\u1ee7y";
            case "PENDING_REVIEW" -> "\u2b50 Ch\u1edd \u0111\u00e1nh gi\u00e1";
            case "REVIEWED" -> "\u2705 \u0110\u00e3 \u0111\u00e1nh gi\u00e1";
            case "PENDING_REFUND" -> "\ud83d\udcb8 Ch\u1edd ho\u00e0n ti\u1ec1n";
            default -> status;
        };
    }

    private String formatDate(String rawDate) {
        try {
            return LocalDate.parse(rawDate, RAW_FMT).format(DISPLAY_FMT);
        }
        catch (Exception e) {
            return rawDate;
        }
    }

    private boolean isValidEmail(String email) {
        return email != null && email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private String normalizeLocation(String loc) {
        if (loc == null) {
            return "";
        }
        return Normalizer.normalize(loc.replace('\u0111', 'd').replace('\u0110', 'D'), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9 ]", "");
    }

    private long nvl(Long v) {
        return v != null ? v : 0L;
    }

    private String fmt(Long v) {
        if (v == null) {
            return "0";
        }
        return String.format("%,.0f", v.doubleValue());
    }

    private String fmt(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        return String.format("%,.0f", v.doubleValue());
    }

    private ChatMessageResponse text(String reply, String sessionId, String stage) {
        return ChatMessageResponse.builder().reply(reply).sessionId(sessionId).timestamp(LocalDateTime.now()).messageType("TEXT").conversationStage(stage).build();
    }

    public String extractBookingCodePublic(String msg) {
        return this.extractBookingCode(msg);
    }

    public ChatMessageResponse performLookupPublic(String code, String sessionId, ConversationState state) {
        return this.performLookup(code, sessionId, state);
    }

    public ChatMessageResponse performPaymentHelpPublic(String code, String sessionId, ConversationState state) {
        try {
            ChatbotBookingDetailResponse detail = this.bookingClient.getBookingDetail(code);
            state.setStage(ConversationState.Stage.IDLE);
            state.setPreviousStage(null);
            this.sessionService.save(sessionId, state);
            BigDecimal remaining = detail.getRemainingAmount() != null ? detail.getRemainingAmount() : BigDecimal.ZERO;
            StringBuilder sb = new StringBuilder();
            sb.append("\ud83d\udccb **THANH TO\u00c1N \u0110\u01a0N H\u00c0NG**\n\n");
            sb.append("\ud83c\udfab M\u00e3: **").append(detail.getBookingCode()).append("**\n");
            sb.append("\ud83c\udfd6\ufe0f **").append(detail.getTourName()).append("**\n");
            sb.append("\ud83d\udccc Tr\u1ea1ng th\u00e1i: **").append(this.statusToVietnamese(detail.getStatus())).append("**\n");
            sb.append("\ud83d\udd34 C\u00f2n l\u1ea1i: **").append(this.fmt(remaining)).append("\u0111**\n");
            if (detail.getPaymentDeadline() != null) {
                sb.append("\u23f0 H\u1ea1n TT: **").append(detail.getPaymentDeadline(), 0, Math.min(16, detail.getPaymentDeadline().length())).append("**\n");
            }
            String payUrl = null;
            String waitingLink = null;
            if (remaining.compareTo(BigDecimal.ZERO) <= 0 || this.isPaidStatus(detail.getStatus())) {
                sb.append("\n\u2705 \u0110\u01a1n n\u00e0y \u0111\u00e3 \u0111\u01b0\u1ee3c thanh to\u00e1n \u0111\u1ee7, b\u1ea1n kh\u00f4ng c\u1ea7n t\u1ea1o link thanh to\u00e1n m\u1edbi.");
            } else {
                try {
                    PayosCreateRequest payReq = PayosCreateRequest.builder().bookingCode(detail.getBookingCode()).amount(remaining).description("Thanh toan " + detail.getBookingCode()).returnUrl(this.frontendUrl + "/payment-waiting?bookingCode=" + detail.getBookingCode()).cancelUrl(this.frontendUrl + "/payment-failed?cancelled=true&bookingCode=" + detail.getBookingCode()).build();
                    PaymentUrlResponse payResp = this.paymentClient.createPayosPayment(payReq);
                    payUrl = payResp.getCheckoutUrl();
                    if (payResp.getTransactionId() != null) {
                        waitingLink = this.frontendUrl + "/payment-waiting?orderCode=" + payResp.getTransactionId() + "&bookingCode=" + detail.getBookingCode();
                    }
                    if (payUrl != null) {
                        sb.append("\n\ud83d\udc49 **[Thanh to\u00e1n ngay qua PayOS](").append(payUrl).append(")**\n");
                    }
                    if (waitingLink != null) {
                        sb.append("\ud83d\udcca **[Theo d\u00f5i tr\u1ea1ng th\u00e1i thanh to\u00e1n](").append(waitingLink).append(")**\n");
                    }
                }
                catch (Exception pe) {
                    log.warn("Kh\u00f4ng t\u1ea1o \u0111\u01b0\u1ee3c link thanh to\u00e1n cho {}: {}", (Object)detail.getBookingCode(), (Object)pe.getMessage());
                    sb.append("\n\u26a0\ufe0f Hi\u1ec7n ch\u01b0a t\u1ea1o \u0111\u01b0\u1ee3c link thanh to\u00e1n t\u1ef1 \u0111\u1ed9ng. B\u1ea1n th\u1eed l\u1ea1i sau ho\u1eb7c li\u00ean h\u1ec7 hotline **0339263066** \u0111\u1ec3 \u0111\u01b0\u1ee3c h\u1ed7 tr\u1ee3.");
                }
            }
            ArrayList<ChatMessageResponse.QuickAction> actions = new ArrayList<ChatMessageResponse.QuickAction>();
            if (payUrl != null) {
                actions.add(ChatMessageResponse.QuickAction.builder().label("Thanh to\u00e1n ngay").action("PAY_NOW").url(payUrl).build());
            }
            if (waitingLink != null) {
                actions.add(ChatMessageResponse.QuickAction.builder().label("Theo d\u00f5i thanh to\u00e1n").action("PAYMENT_STATUS").url(waitingLink).build());
            }
            return ChatMessageResponse.builder().reply(sb.toString()).sessionId(sessionId).timestamp(LocalDateTime.now()).messageType("ORDER_DETAIL").conversationStage(state.getStage().name()).orderDetail(detail).paymentUrl(payUrl).paymentWaitingLink(waitingLink).quickActions(actions).build();
        }
        catch (Exception e) {
            state.setStage(ConversationState.Stage.IDLE);
            this.sessionService.save(sessionId, state);
            return this.text("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng **" + code + "**. B\u1ea1n ki\u1ec3m tra l\u1ea1i m\u00e3 \u0111\u1eb7t tour nh\u00e9.", sessionId, "IDLE");
        }
    }

    public ChatMessageResponse performCancelHelpPublic(String code, String sessionId, ConversationState state) {
        try {
            ChatbotBookingDetailResponse detail = this.bookingClient.getBookingDetail(code);
            state.setStage(ConversationState.Stage.IDLE);
            state.setPreviousStage(null);
            this.sessionService.save(sessionId, state);
            BigDecimal paid = detail.getPaidAmount() != null ? detail.getPaidAmount() : BigDecimal.ZERO;
            BigDecimal remaining = detail.getRemainingAmount() != null ? detail.getRemainingAmount() : BigDecimal.ZERO;
            StringBuilder sb = new StringBuilder();
            sb.append("\ud83e\uddfe **H\u1ed6 TR\u1ee2 H\u1ee6Y TOUR / BOOKING**\n\n");
            sb.append("\ud83c\udfab M\u00e3: **").append(detail.getBookingCode()).append("**\n");
            sb.append("\ud83c\udfd6\ufe0f **").append(detail.getTourName()).append("**\n");
            sb.append("\ud83d\udccc Tr\u1ea1ng th\u00e1i: **").append(this.statusToVietnamese(detail.getStatus())).append("**\n");
            sb.append("\u2705 \u0110\u00e3 TT: ").append(this.fmt(paid)).append("\u0111\n");
            sb.append("\ud83d\udd34 C\u00f2n l\u1ea1i: **").append(this.fmt(remaining)).append("\u0111**\n\n");
            if (paid.compareTo(BigDecimal.ZERO) <= 0) {
                sb.append("\u0110\u01a1n n\u00e0y hi\u1ec7n **ch\u01b0a thanh to\u00e1n**. B\u1ea1n c\u00f3 th\u1ec3 kh\u00f4ng thanh to\u00e1n \u0111\u1ec3 \u0111\u01a1n t\u1ef1 qu\u00e1 h\u1ea1n, ho\u1eb7c li\u00ean h\u1ec7 admin \u0111\u1ec3 \u0111\u01b0\u1ee3c h\u1ee7y ngay tr\u00ean h\u1ec7 th\u1ed1ng.\n\n");
            } else {
                sb.append("\u0110\u01a1n n\u00e0y \u0111\u00e3 c\u00f3 thanh to\u00e1n n\u00ean c\u1ea7n admin ki\u1ec3m tra \u0111i\u1ec1u ki\u1ec7n h\u1ee7y/ho\u00e0n ti\u1ec1n tr\u01b0\u1edbc khi x\u1eed l\u00fd.\n\n");
            }
            sb.append("Vui l\u00f2ng li\u00ean h\u1ec7:\n");
            sb.append("- Hotline: **0339263066**\n");
            sb.append("- Email: **admin@futuretravel.vn**\n\n");
            sb.append("L\u01b0u \u00fd: n\u00fat **H\u1ee7y** trong chatbot ch\u1ec9 h\u1ee7y lu\u1ed3ng chat, kh\u00f4ng t\u1ef1 h\u1ee7y booking th\u1eadt n\u1ebfu ch\u01b0a c\u00f3 b\u01b0\u1edbc x\u00e1c nh\u1eadn nghi\u1ec7p v\u1ee5.");
            return ChatMessageResponse.builder().reply(sb.toString()).sessionId(sessionId).timestamp(LocalDateTime.now()).messageType("ORDER_DETAIL").conversationStage(state.getStage().name()).orderDetail(detail).quickActions(List.of(ChatMessageResponse.QuickAction.builder().label("G\u1ecdi hotline").action("CALL_SUPPORT").url("tel:0339263066").build(), ChatMessageResponse.QuickAction.builder().label("G\u1eedi email admin").action("EMAIL_SUPPORT").url("mailto:admin@futuretravel.vn").build())).build();
        }
        catch (Exception e) {
            state.setStage(ConversationState.Stage.IDLE);
            this.sessionService.save(sessionId, state);
            return this.text("Kh\u00f4ng t\u00ecm th\u1ea5y \u0111\u01a1n h\u00e0ng **" + code + "**. B\u1ea1n ki\u1ec3m tra l\u1ea1i m\u00e3 \u0111\u1eb7t tour nh\u00e9.", sessionId, "IDLE");
        }
    }

    private boolean isPaidStatus(String status) {
        if (status == null) {
            return false;
        }
        String s = status.trim().toUpperCase();
        return s.equals("PAID") || s.equals("COMPLETED") || s.equals("SUCCESS");
    }

    @Generated
    public BookingConversationService(RedisSessionService sessionService, VectorService vectorService, LocationResolverService locationResolver, TourCatalogFeignClient tourCatalogClient, ChatbotBookingFeignClient bookingClient, ChatbotPaymentFeignClient paymentClient) {
        this.sessionService = sessionService;
        this.vectorService = vectorService;
        this.locationResolver = locationResolver;
        this.tourCatalogClient = tourCatalogClient;
        this.bookingClient = bookingClient;
        this.paymentClient = paymentClient;
    }
}
