package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tourism.analytics.dto.ChatMessageResponse;
import com.tourism.analytics.dto.VectorDocumentDTO;
import com.tourism.analytics.dto.chatbot.ConversationState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TourFactAnswerService {

    private static final Pattern TOUR_CODE_PATTERN =
            Pattern.compile("\\b[A-Z0-9]{2,}(?:-[A-Z0-9]{2,})+\\b", Pattern.CASE_INSENSITIVE);
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Set<String> FACT_TYPES = Set.of(
            "TOUR_AMENITY",
            "TOUR_ITINERARY_DAY",
            "TOUR_START_LOCATION",
            "TOUR_DEPARTURE_FULL",
            "TOUR_DEPARTURE"
    );

    private final VectorService vectorService;
    private final Gson gson = new Gson();

    public ChatMessageResponse tryAnswer(String userMessage, String sessionId, ConversationState state) {
        if (!canRun(state)) return null;

        String normalized = normalizeSemanticAliases(normalize(userMessage));
        String exactTourCode = extractTourCode(userMessage);
        boolean itinerarySignal = hasItinerarySignal(normalized);
        boolean foodHotelSignal = hasFoodHotelSignal(normalized);
        boolean startSignal = hasStartLocationSignal(normalized);

        if (!itinerarySignal && !foodHotelSignal && !startSignal && exactTourCode == null) {
            return null;
        }

        List<VectorDocumentDTO> docs = findFactDocs(userMessage, 50);
        if (docs.isEmpty()) return null;

        if (itinerarySignal || (exactTourCode != null && normalized.contains("lich"))) {
            ChatMessageResponse itinerary = answerItinerary(docs, exactTourCode, sessionId, state);
            if (itinerary != null) return itinerary;
        }

        if (startSignal) {
            ChatMessageResponse startAnswer = answerStartLocation(docs, normalized, sessionId, state);
            if (startAnswer != null) return startAnswer;
        }

        if (foodHotelSignal || exactTourCode != null) {
            return answerAmenity(docs, exactTourCode, normalized, sessionId, state);
        }

        return null;
    }

    private boolean canRun(ConversationState state) {
        if (state == null || state.getStage() == null) return true;
        return state.getStage() == ConversationState.Stage.IDLE
                || state.getStage() == ConversationState.Stage.SHOWING_SEARCH_RESULTS
                || state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO;
    }

    private List<VectorDocumentDTO> findFactDocs(String userMessage, int topK) {
        List<VectorDocumentDTO> docs = vectorService.searchSimilar(userMessage, topK);
        if (docs == null) return new ArrayList<>();
        return docs.stream()
                .filter(doc -> doc.getType() != null && FACT_TYPES.contains(doc.getType()))
                .toList();
    }

    private ChatMessageResponse answerItinerary(List<VectorDocumentDTO> docs, String exactTourCode,
                                                String sessionId, ConversationState state) {
        List<VectorDocumentDTO> dayDocs = docs.stream()
                .filter(doc -> "TOUR_ITINERARY_DAY".equals(doc.getType()))
                .filter(doc -> exactTourCode == null || exactTourCode.equalsIgnoreCase(asString(meta(doc).get("tourCode"))))
                .sorted(Comparator.comparingInt(doc -> asInt(meta(doc).get("dayNumber"))))
                .toList();
        if (dayDocs.isEmpty()) return null;

        Map<String, Object> first = meta(dayDocs.get(0));
        String tourName = asString(first.get("tourName"));
        String tourCode = asString(first.get("tourCode"));

        StringBuilder reply = new StringBuilder();
        reply.append("Có lịch trình cho tour ").append(tourName);
        if (!tourCode.isBlank()) reply.append(" (").append(tourCode).append(")");
        reply.append(":\n");

        for (VectorDocumentDTO doc : dayDocs) {
            Map<String, Object> m = meta(doc);
            reply.append("\nNgày ").append(asInt(m.get("dayNumber"))).append(": ")
                    .append(asString(m.get("title")));
            String meals = asString(m.get("meals"));
            if (!meals.isBlank()) reply.append("\nBữa ăn: ").append(meals);
            String content = contentAfter(doc.getContent(), "Chi tiết:");
            if (!content.isBlank()) reply.append("\n").append(shorten(content, 360));
            reply.append("\n");
        }

        return textResponse(reply.toString().trim(), sessionId, state);
    }

    private ChatMessageResponse answerStartLocation(List<VectorDocumentDTO> docs, String normalized,
                                                    String sessionId, ConversationState state) {
        boolean asksHcm = containsAny(normalized, "ho chi minh", "hcm", "sai gon", "tp ho chi minh");
        if (!asksHcm) return null;

        LinkedHashMap<String, VectorDocumentDTO> byTour = new LinkedHashMap<>();
        for (VectorDocumentDTO doc : docs) {
            if (!"TOUR_START_LOCATION".equals(doc.getType()) && !"TOUR_DEPARTURE_FULL".equals(doc.getType())) continue;
            Map<String, Object> m = meta(doc);
            String start = normalize(asString(m.get("startLocationName")));
            if (containsAny(start, "ho chi minh", "hcm", "sai gon")) {
                byTour.putIfAbsent(asString(m.get("tourCode")), doc);
            }
        }
        if (byTour.isEmpty()) return null;

        StringBuilder reply = new StringBuilder("Hiện có các tour khởi hành từ Hồ Chí Minh:");
        List<ChatMessageResponse.TourSuggestion> suggestions = new ArrayList<>();
        int index = 1;
        for (VectorDocumentDTO doc : byTour.values()) {
            Map<String, Object> m = meta(doc);
            reply.append("\n").append(index++).append(". ")
                    .append(asString(m.get("tourName")))
                    .append(" (").append(asString(m.get("tourCode"))).append(")")
                    .append(" - ").append(asString(m.get("duration")))
                    .append(", giá từ ").append(formatPrice(asDouble(m.get("minPrice"))));
            List<String> dates = asStringList(m.get("departureDates"));
            if (!dates.isEmpty()) reply.append(", lịch gần nhất: ").append(String.join(", ", dates.subList(0, Math.min(3, dates.size()))));

            suggestions.add(buildSuggestion(doc));
            if (suggestions.size() >= 5) break;
        }
        rememberTourContext(state, new ArrayList<>(byTour.values()));

        return ChatMessageResponse.builder()
                .reply(reply.toString())
                .tourSuggestions(suggestions)
                .quickActions(new ArrayList<>())
                .sessionId(sessionId)
                .timestamp(LocalDateTime.now())
                .messageType("TOUR_SUGGESTIONS")
                .conversationStage(stageName(state))
                .build();
    }

    private ChatMessageResponse answerAmenity(List<VectorDocumentDTO> docs, String exactTourCode, String normalized,
                                              String sessionId, ConversationState state) {
        LinkedHashMap<String, VectorDocumentDTO> byTour = new LinkedHashMap<>();
        for (VectorDocumentDTO doc : docs) {
            if (!"TOUR_AMENITY".equals(doc.getType()) && !"TOUR_DEPARTURE_FULL".equals(doc.getType())) continue;
            Map<String, Object> m = meta(doc);
            if (exactTourCode != null && !exactTourCode.equalsIgnoreCase(asString(m.get("tourCode")))) continue;
            if (exactTourCode == null && !matchesAmenityNeed(doc, m, normalized)) continue;
            byTour.putIfAbsent(asString(m.get("tourCode")), doc);
        }
        if (byTour.isEmpty()) return null;

        if (byTour.size() == 1) {
            VectorDocumentDTO doc = byTour.values().iterator().next();
            Map<String, Object> m = meta(doc);
            rememberTourContext(state, List.of(doc));
            StringBuilder reply = new StringBuilder();
            reply.append("Có thông tin cho tour ").append(asString(m.get("tourName")));
            String tourCode = asString(m.get("tourCode"));
            if (!tourCode.isBlank()) reply.append(" (").append(tourCode).append(")");
            reply.append(".");
            String meals = asString(m.get("meals"));
            String hotel = asString(m.get("hotel"));
            String attractions = asString(m.get("attractions"));
            if (!meals.isBlank()) reply.append("\nẨm thực/bữa ăn: ").append(meals);
            if (!hotel.isBlank()) reply.append("\nKhách sạn/lưu trú: ").append(hotel);
            if (!attractions.isBlank()) reply.append("\nĐiểm tham quan: ").append(attractions);
            return ChatMessageResponse.builder()
                    .reply(reply.toString())
                    .tourSuggestions(List.of(buildSuggestion(doc)))
                    .quickActions(new ArrayList<>())
                    .sessionId(sessionId)
                    .timestamp(LocalDateTime.now())
                    .messageType("TOUR_SUGGESTIONS")
                    .conversationStage(stageName(state))
                    .build();
        }

        StringBuilder reply = new StringBuilder("Mình tìm thấy các tour khớp nhu cầu ẩm thực/khách sạn:");
        List<ChatMessageResponse.TourSuggestion> suggestions = new ArrayList<>();
        int index = 1;
        for (VectorDocumentDTO doc : byTour.values()) {
            Map<String, Object> m = meta(doc);
            reply.append("\n").append(index++).append(". ")
                    .append(asString(m.get("tourName")))
                    .append(" (").append(asString(m.get("tourCode"))).append(")")
                    .append(" - ").append(asString(m.get("duration")));
            String meals = asString(m.get("meals"));
            if (!meals.isBlank()) reply.append(", ẩm thực: ").append(meals);
            suggestions.add(buildSuggestion(doc));
            if (suggestions.size() >= 5) break;
        }
        rememberTourContext(state, new ArrayList<>(byTour.values()));

        return ChatMessageResponse.builder()
                .reply(reply.toString())
                .tourSuggestions(suggestions)
                .quickActions(new ArrayList<>())
                .sessionId(sessionId)
                .timestamp(LocalDateTime.now())
                .messageType("TOUR_SUGGESTIONS")
                .conversationStage(stageName(state))
                .build();
    }

    private boolean matchesAmenityNeed(VectorDocumentDTO doc, Map<String, Object> meta, String normalized) {
        String haystack = normalizeSemanticAliases(normalize(asString(meta.get("tourName")) + " "
                + asString(meta.get("tourCode")) + " "
                + asString(meta.get("startLocationName")) + " "
                + asString(meta.get("endLocationName")) + " "
                + asString(meta.get("meals")) + " "
                + asString(meta.get("hotel")) + " "
                + asString(meta.get("attractions")) + " "
                + (doc.getContent() != null ? doc.getContent() : "")));
        String mealText = normalizeSemanticAliases(normalize(asString(meta.get("meals"))));
        if (containsAny(normalized, "buffet") && !containsAny(haystack, "buffet")) return false;
        boolean asksPho = hasFoodToken(normalized, "pho");
        boolean asksBun = hasFoodToken(normalized, "bun");
        if ((asksPho || asksBun)
                && !((asksPho && hasFoodToken(mealText, "pho"))
                    || (asksBun && hasFoodToken(mealText, "bun")))) {
            return false;
        }
        if (normalized.contains("hai san") && !haystack.contains("hai san")) return false;
        if (normalized.contains("tuoi") && !haystack.contains("tuoi")) return false;
        if (normalized.contains("moi bua") && !haystack.contains("moi bua")) return false;
        if (containsAny(normalized, "khach san", "hotel", "resort") && !containsAny(haystack, "khach san", "hotel", "resort")) return false;
        for (String place : mentionedPlaces(normalized)) {
            if (!haystack.contains(place)) return false;
        }
        List<String> contentWords = amenityContentWords(normalized);
        if (!contentWords.isEmpty()) {
            long hits = contentWords.stream().filter(haystack::contains).count();
            return hits > 0;
        }
        return true;
    }

    private boolean hasFoodToken(String text, String token) {
        if (text == null || token == null) return false;
        return Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(token) + "([^a-z0-9]|$)")
                .matcher(text)
                .find();
    }

    private List<String> mentionedPlaces(String normalized) {
        String[] places = {
                "ha noi", "sa pa", "phu quoc", "ho chi minh", "hcm", "sai gon",
                "da nang", "hoi an", "hue", "ha long", "nha trang", "vung tau",
                "can tho", "hai phong", "cat ba"
        };
        List<String> result = new ArrayList<>();
        for (String place : places) {
            if (normalized.contains(place)) {
                if ("hcm".equals(place) || "sai gon".equals(place)) {
                    result.add("ho chi minh");
                } else {
                    result.add(place);
                }
            }
        }
        return result.stream().distinct().toList();
    }

    private void rememberTourContext(ConversationState state, List<VectorDocumentDTO> docs) {
        if (state == null || docs == null || docs.isEmpty()) return;

        List<VectorDocumentDTO> enrichedDocs = enrichWithDepartureDocs(docs);
        LinkedHashMap<Integer, ConversationState.TourGroupDisplay> groups = new LinkedHashMap<>();
        List<ConversationState.DepartureMeta> allDepartures = new ArrayList<>();
        for (VectorDocumentDTO doc : enrichedDocs) {
            Map<String, Object> m = meta(doc);
            Integer tourId = asInt(m.get("tourId"));
            if (tourId == null || tourId <= 0) continue;

            ConversationState.TourGroupDisplay group = groups.computeIfAbsent(tourId, id ->
                    ConversationState.TourGroupDisplay.builder()
                            .tourId(id)
                            .tourCode(asString(m.get("tourCode")))
                            .tourName(asString(m.get("tourName")))
                            .imageUrl(asString(m.get("imageUrl")))
                            .duration(asString(m.get("duration")))
                            .startLocationName(asString(m.get("startLocationName")))
                            .adultSalePrice((long) asDouble(firstNonNull(m.get("salePrice"), m.get("minPrice"))))
                            .departures(new ArrayList<>())
                            .build());

            Integer departureId = asInt(m.get("departureID"));
            String departureDate = asString(m.get("departureDate"));
            if (departureId != null && departureId > 0 && !departureDate.isBlank()) {
                boolean exists = group.getDepartures().stream()
                        .anyMatch(dep -> departureId.equals(dep.getDepartureId()));
                if (!exists) {
                    ConversationState.DepartureMeta depMeta = ConversationState.DepartureMeta.builder()
                            .departureId(departureId)
                            .departureDate(departureDate)
                            .availableSlots(asInt(m.get("availableSlots")))
                            .salePrice((long) asDouble(m.get("salePrice")))
                            .build();
                    group.getDepartures().add(depMeta);
                    allDepartures.add(depMeta);
                }
            }
        }

        if (!groups.isEmpty()) {
            state.setLastSearchResults(new ArrayList<>(groups.values()));
            state.setLastDepartures(allDepartures);
            state.setLastMentionedTourId(groups.values().iterator().next().getTourId());
            if (!allDepartures.isEmpty()) {
                state.setLastMentionedDepartureId(allDepartures.get(0).getDepartureId());
            }
            if (state.getStage() == ConversationState.Stage.IDLE
                    || state.getStage() == ConversationState.Stage.COLLECTING_SEARCH_INFO
                    || state.getStage() == ConversationState.Stage.SHOWING_SEARCH_RESULTS) {
                state.setStage(ConversationState.Stage.SHOWING_SEARCH_RESULTS);
            }
        }
    }

    private ChatMessageResponse textResponse(String reply, String sessionId, ConversationState state) {
        return ChatMessageResponse.builder()
                .reply(reply)
                .tourSuggestions(new ArrayList<>())
                .quickActions(new ArrayList<>())
                .sessionId(sessionId)
                .timestamp(LocalDateTime.now())
                .messageType("TEXT")
                .conversationStage(stageName(state))
                .build();
    }

    private ChatMessageResponse.TourSuggestion buildSuggestion(VectorDocumentDTO doc) {
        Map<String, Object> m = meta(doc);
        Integer tourId = asInt(m.get("tourId"));
        String tourCode = asString(m.get("tourCode"));
        return ChatMessageResponse.TourSuggestion.builder()
                .tourId(tourId)
                .tourCode(tourCode)
                .tourName(asString(m.get("tourName")))
                .imageUrl(asString(m.get("imageUrl")))
                .minPrice(asDouble(firstNonNull(m.get("minPrice"), m.get("salePrice"))))
                .duration(asString(m.get("duration")))
                .detailUrl(!tourCode.isBlank() ? "/tour/" + tourCode : null)
                .relevanceScore(doc.getScore() != null ? doc.getScore().doubleValue() : null)
                .build();
    }

    private boolean hasFoodHotelSignal(String text) {
        return containsAny(text,
                "buffet", "an sang", "bua an", "bua", "am thuc", "thuc don", "do an",
                "khach san", "hotel", "resort", "luu tru", "pho", "bun", "mon an", "an gi",
                "muon an", "hai san", "dac san", "an uong")
                || text.matches(".*(^|\\s)an\\s+[^\\s].*");
    }

    private boolean hasItinerarySignal(String text) {
        return containsAny(text, "lich trinh", "chuong trinh", "ngay 1", "ngay 2", "di dau");
    }

    private boolean hasStartLocationSignal(String text) {
        return containsAny(text, "khoi hanh", "xuat phat", "tu ho chi minh", "tu hcm", "tu sai gon");
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private String extractTourCode(String text) {
        if (text == null) return null;
        Matcher matcher = TOUR_CODE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    private Map<String, Object> meta(VectorDocumentDTO doc) {
        if (doc == null || doc.getMetadata() == null || doc.getMetadata().isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = gson.fromJson(doc.getMetadata(), MAP_TYPE);
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String normalize(String text) {
        if (text == null) return "";
        String value = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return value.replaceAll("\\s+", " ").trim();
    }

    private String normalizeSemanticAliases(String text) {
        if (text == null) return "";
        return text.replaceAll("\\b(bufet|buffe|buftet|buffert|buffer)\\b", "buffet");
    }

    private List<String> amenityContentWords(String normalized) {
        if (normalized == null || normalized.isBlank()) return new ArrayList<>();
        Set<String> stopWords = Set.of(
                "toi", "minh", "muon", "thich", "co", "khong", "ko", "k", "tour", "nao", "ma",
                "an", "uong", "mon", "bua", "thuc", "don", "am", "khach", "san",
                "gi", "hay", "voi", "di", "du", "lich", "cho", "hoi", "xem"
        );
        List<String> words = new ArrayList<>();
        for (String raw : normalized.split("\\s+")) {
            String word = raw.replaceAll("[^a-z0-9]", "");
            if (word.length() < 3 || stopWords.contains(word)) continue;
            words.add(word);
        }
        return words.stream().distinct().toList();
    }

    private List<VectorDocumentDTO> enrichWithDepartureDocs(List<VectorDocumentDTO> docs) {
        if (docs == null || docs.isEmpty()) return new ArrayList<>();
        List<VectorDocumentDTO> enriched = new ArrayList<>(docs);
        Set<String> seenIds = new java.util.HashSet<>();
        Set<Integer> tourIds = new java.util.LinkedHashSet<>();
        Set<String> tourCodes = new java.util.LinkedHashSet<>();

        for (VectorDocumentDTO doc : docs) {
            if (doc.getId() != null) seenIds.add(doc.getId());
            Map<String, Object> m = meta(doc);
            Integer tourId = asInt(m.get("tourId"));
            if (tourId != null && tourId > 0) tourIds.add(tourId);
            String tourCode = asString(m.get("tourCode"));
            if (!tourCode.isBlank()) tourCodes.add(tourCode);
        }

        for (String tourCode : tourCodes.stream().limit(5).toList()) {
            List<VectorDocumentDTO> found = vectorService.searchSimilar(
                    tourCode + " lich khoi hanh ngay khoi hanh gia tour departure",
                    30
            );
            if (found == null) continue;
            for (VectorDocumentDTO doc : found) {
                if (!"TOUR_DEPARTURE".equals(doc.getType()) && !"TOUR_DEPARTURE_FULL".equals(doc.getType())) continue;
                Map<String, Object> m = meta(doc);
                Integer tourId = asInt(m.get("tourId"));
                String docTourCode = asString(m.get("tourCode"));
                boolean sameTour = (tourId != null && tourIds.contains(tourId))
                        || (!docTourCode.isBlank() && tourCode.equalsIgnoreCase(docTourCode));
                if (!sameTour) continue;
                String id = doc.getId() != null ? doc.getId() : doc.getType() + "_" + asString(m.get("departureID"));
                if (seenIds.add(id)) enriched.add(doc);
            }
        }
        return enriched;
    }

    private String contentAfter(String content, String marker) {
        if (content == null || marker == null) return "";
        int idx = content.indexOf(marker);
        return idx >= 0 ? content.substring(idx + marker.length()).trim() : content.trim();
    }

    private String shorten(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return text.substring(0, max - 3).trim() + "...";
    }

    private String stageName(ConversationState state) {
        return state != null && state.getStage() != null ? state.getStage().name() : ConversationState.Stage.IDLE.name();
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private int asInt(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value != null ? Double.parseDouble(String.valueOf(value)) : 0.0;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> raw)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (item != null) result.add(String.valueOf(item));
        }
        return result;
    }

    private String formatPrice(double price) {
        if (price <= 0) return "đang cập nhật";
        return String.format("%,.0f VNĐ", price);
    }
}
