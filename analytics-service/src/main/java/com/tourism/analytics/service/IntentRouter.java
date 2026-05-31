package com.tourism.analytics.service;

import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.IntentResult;
import com.tourism.analytics.dto.chatbot.IntentResult.Intent;
import com.tourism.analytics.dto.chatbot.IntentResult.RetrievalTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central intent router for chatbot messages.
 *
 * Business-critical questions are classified before the booking stage machine.
 * That keeps SELECTING_DEPARTURE from swallowing support/booking/discount/detail
 * questions as invalid dates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentRouter {

    private final ReferenceResolverService referenceResolver;
    private final GeminiIntentService geminiIntentService;
    private final LocationResolverService locationResolver;

    private static final Pattern BK_PATTERN = Pattern.compile("(?i)(BK[A-Za-z0-9]{8,})");

    public IntentResult route(String message, ConversationState state) {
        if (message == null || message.isBlank()) {
            return buildResult(Intent.UNKNOWN, "fast-path", 1.0);
        }

        String msg = message.trim();
        String norm = normalize(msg);
        ConversationState.Stage stage = state.getStage();

        Matcher bkM = BK_PATTERN.matcher(msg);
        String bookingCode = bkM.find() ? bkM.group(1) : null;
        if (bookingCode != null && isBookingCancelHelp(norm)) {
            return IntentResult.builder()
                    .intent(Intent.BOOKING_CANCEL_HELP)
                    .bookingCode(bookingCode)
                    .rawSource("fast-path-cancel-booking")
                    .confidence(1.0)
                    .build();
        }
        if (bookingCode != null && isPaymentHelp(norm)) {
            return IntentResult.builder()
                    .intent(Intent.BOOKING_LOOKUP_PAYMENT)
                    .bookingCode(bookingCode)
                    .rawSource("fast-path-payment-with-code")
                    .confidence(1.0)
                    .build();
        }
        if (bookingCode != null) {
            return IntentResult.builder()
                    .intent(Intent.BOOKING_LOOKUP_PAYMENT)
                    .bookingCode(bookingCode)
                    .rawSource("fast-path")
                    .confidence(1.0)
                    .build();
        }

        if (stage == ConversationState.Stage.COLLECTING_NOTE_COUPON
                && norm.matches(".*(bo\\s*qua|khong|khong\\s*co|skip|tiep\\s*tuc).*")) {
            return buildResult(Intent.TRANSACTION_FLOW, "fast-path-stage-optional", 1.0);
        }
        if (isCancel(norm)) return buildResult(Intent.CANCEL, "fast-path", 1.0);
        if (isResume(norm)) return buildResult(Intent.RESUME_BOOKING, "fast-path", 1.0);
        if (isGreeting(norm)) return buildResult(Intent.GREETING, "fast-path", 0.98);

        if (stage == ConversationState.Stage.CONFIRMING_BOOKING && isConfirm(norm)) {
            return buildResult(Intent.TRANSACTION_FLOW, "fast-path", 1.0);
        }
        if (stage == ConversationState.Stage.SHOWING_SEARCH_RESULTS && msg.matches("^[123]$")) {
            return buildResult(Intent.TRANSACTION_FLOW, "fast-path-stage-selection", 1.0);
        }
        if (stage == ConversationState.Stage.SELECTING_DEPARTURE && msg.matches("^[123]$")) {
            return buildResult(Intent.TRANSACTION_FLOW, "fast-path-stage-selection", 1.0);
        }

        if (isBookingIntent(norm)) {
            IntentResult r = extractSearchEntities(norm);
            r.setIntent(Intent.TRANSACTION_FLOW);
            r.setRawSource("fast-path");
            r.setConfidence(0.9);
            return r;
        }

        if (isRatingOrReviewQuery(norm)) return buildResult(Intent.GENERAL_RAG, "fast-path-rag", 0.95);

        if (referenceResolver.isPronounReference(msg) || referenceResolver.isContextualShortQuestion(msg)) {
            ReferenceResolverService.ResolvedContext ctx = referenceResolver.resolve(msg, state);
            if (!ctx.isAmbiguous()) {
                RetrievalTask task = mapResolvedTask(ctx.resolvedIntent());
                return IntentResult.builder()
                        .intent(Intent.TOUR_RETRIEVAL)
                        .retrievalTask(task != null ? task : RetrievalTask.DETAIL)
                        .resolvedTourId(ctx.tourId())
                        .resolvedDepId(ctx.departureId())
                        .rawSource("reference-resolver")
                        .confidence(0.9)
                        .build();
            }
        }

        if (isAskDiscount(norm)) return buildRetrievalResult(RetrievalTask.DISCOUNT, norm, state, "fast-path", 0.95);
        if (isAskCoupon(norm)) return buildRetrievalResult(RetrievalTask.COUPON, norm, state, "fast-path", 0.95);
        if (isSystemHelp(norm)) return buildResult(Intent.GENERAL_RAG, "fast-path", 0.9);
        if (isAskSlot(norm)) return buildRetrievalResult(RetrievalTask.SLOT, norm, state, "fast-path", 0.9);
        if (isAskPrice(norm)) return buildRetrievalResult(RetrievalTask.PRICE, norm, state, "fast-path", 0.9);
        if (isAskDepartureDate(norm)) return buildRetrievalResult(RetrievalTask.DEPARTURE_DATE, norm, state, "fast-path", 0.9);
        if (isAskPolicy(norm)) return buildRetrievalResult(RetrievalTask.POLICY, norm, state, "fast-path", 0.9);
        if (isAskDetail(norm)) return buildRetrievalResult(RetrievalTask.DETAIL, norm, state, "fast-path", 0.9);
        if (isLookupIntent(norm)) return buildResult(Intent.BOOKING_LOOKUP_PAYMENT, "fast-path", 0.95);
        if (isGeneralPaymentInfoQuery(norm)) return buildResult(Intent.GENERAL_RAG, "fast-path", 0.9);
        if (isPaymentHelp(norm)) return buildResult(Intent.BOOKING_LOOKUP_PAYMENT, "fast-path-payment", 0.9);
        if (isAskItinerary(norm)) return buildRetrievalResult(RetrievalTask.ITINERARY, norm, state, "fast-path", 0.9);

        // B1: guard — câu hỏi đánh giá/review/xếp hạng KHÔNG phải booking intent
        if (isRatingOrReviewQuery(norm)) return buildResult(Intent.GENERAL_RAG, "fast-path-rag", 0.95);
        if (isGeneralAdviceQuery(norm)) return buildResult(Intent.GENERAL_RAG, "fast-path-rag", 0.9);

        if (isStartLocationSearch(norm)) {
            IntentResult r = extractSearchEntities(norm);
            r.setIntent(Intent.TOUR_RETRIEVAL);
            r.setRetrievalTask(RetrievalTask.SEARCH);
            r.setRawSource("fast-path");
            r.setConfidence(0.9);
            return r;
        }

        if (isChangeSearch(norm)) {
            IntentResult r = extractSearchEntities(norm);
            r.setIntent(Intent.TOUR_RETRIEVAL);
            r.setRetrievalTask(RetrievalTask.SEARCH);
            r.setRawSource("fast-path");
            r.setConfidence(0.85);
            return r;
        }

        // B0: stage-aware fast-path for common booking-flow answers → avoid Gemini call
        if (stage == ConversationState.Stage.COLLECTING_SEARCH_INFO) {
            if (state.getSearchDestination() != null
                    && state.getSearchStartLocation() == null
                    && hasSearchLocation(norm)) {
                IntentResult r = extractSearchEntities(norm);
                r.setIntent(Intent.TOUR_RETRIEVAL);
                r.setRetrievalTask(RetrievalTask.SEARCH);
                r.setRawSource("fast-path-stage-slot");
                r.setConfidence(0.9);
                return r;
            }
            if (isMonthInput(norm) && !isTourSearch(norm) && !hasSearchLocation(norm)) {
                return buildRetrievalResult(RetrievalTask.SEARCH, norm, state, "fast-path-stage", 0.88);
            }
            if (isPeopleCountInput(norm) && !isTourSearch(norm) && !hasSearchLocation(norm)) {
                return buildRetrievalResult(RetrievalTask.SEARCH, norm, state, "fast-path-stage", 0.88);
            }
            if (isNewDestinationInput(norm, state)) {
                IntentResult r = extractSearchEntities(norm);
                r.setIntent(Intent.TOUR_RETRIEVAL);
                r.setRetrievalTask(RetrievalTask.SEARCH);
                r.setRawSource("fast-path-stage");
                r.setConfidence(0.88);
                return r;
            }
        }
        if (stage == ConversationState.Stage.SHOWING_SEARCH_RESULTS && isNumericSelection(norm)) {
            return buildResult(Intent.TRANSACTION_FLOW, "fast-path-stage-selection", 0.95);
        }

        if (isTourSearch(norm) || hasSearchLocation(norm)) {
            IntentResult r = extractSearchEntities(norm);
            // Guard: nếu không resolve được destination VÀ không có startLocation → thematic/chung chung → GENERAL_RAG
            if (r.getDestination() == null && r.getStartLocation() == null) {
                log.info("🌊 isTourSearch=true nhưng destination=null startLocation=null → GENERAL_RAG (thematic/vague query)");
                return buildResult(Intent.GENERAL_RAG, "fast-path-thematic", 0.8);
            }
            r.setIntent(Intent.TOUR_RETRIEVAL);
            r.setRetrievalTask(RetrievalTask.SEARCH);
            r.setRawSource("fast-path");
            r.setConfidence(0.85);
            return r;
        }

        // Thematic queries ("đi biển", "đi núi") không có entity địa điểm → tư vấn gợi ý qua RAG
        if (isThematicQuery(norm)) {
            log.info("🏖️ Thematic query detected → GENERAL_RAG (gợi ý địa điểm theo chủ đề)");
            return buildResult(Intent.GENERAL_RAG, "fast-path-thematic", 0.82);
        }

        if (state.getRecentTurns() != null && !state.getRecentTurns().isEmpty()) {
            try {
                IntentResult geminiResult = geminiIntentService.classify(msg, state);
                if (geminiResult != null && geminiResult.getIntent() != Intent.UNKNOWN) {
                    log.info("Gemini classified intent={}", geminiResult.getIntent());
                    return geminiResult;
                }
            } catch (Exception e) {
                log.warn("⚠ Gemini intent classification failed: {}", e.getMessage());
                // B0: Gemini quota/error → stage-aware fallback instead of UNKNOWN
                return fallbackIntentByStage(stage, norm, state);
            }
        }

        // B0: no Gemini (no history) → stage-aware fallback
        return fallbackIntentByStage(stage, norm, state);
    }

    private boolean isAskSlot(String s) {
        return s.matches(".*(con\\s*may\\s*slot|con\\s*cho\\s*khong|het\\s*cho\\s*chua|\\bslot\\b|cho\\s*trong|bao\\s*nhieu\\s*cho|con\\s*cho|may\\s*slot).*");
    }

    private boolean isAskPrice(String s) {
        return s.matches(".*(gia\\s*(tour|chuyen|do|nay|bao|cua)|bao\\s*nhieu\\s*tien|may\\s*tien|gia\\s*bao|chi\\s*phi|tien\\s*tour).*")
                && !s.matches(".*(tre\\s*em|em\\s*be).*");
    }

    private boolean isAskDepartureDate(String s) {
        return s.matches(".*(ngay\\s*khoi\\s*hanh|lich\\s*khoi\\s*hanh|ngay\\s*di|khi\\s*nao\\s*khoi\\s*hanh|ngay\\s*xuat\\s*phat).*");
    }

    private boolean isAskDetail(String s) {
        return s.matches(".*(xem\\s*chi\\s*tiet|chi\\s*tiet\\s*tour|tour\\s*nay\\s*co\\s*gi|tour\\s*do\\s*co\\s*gi|thong\\s*tin\\s*tour).*");
    }

    private boolean isAskItinerary(String s) {
        return s.matches(".*(lich\\s*trinh|chuong\\s*trinh|ngay\\s*1\\s*di|bao\\s*gom\\s*gi|diem\\s*tham\\s*quan|an\\s*gi|o\\s*dau).*");
    }

    private boolean isAskPolicy(String s) {
        return s.matches(".*(chinh\\s*sach|huy\\s*hoan|hoan\\s*tien|dieu\\s*kien\\s*huy|dieu\\s*kien\\s*hoan|bao\\s*hiem|bao\\s*gom|khong\\s*bao\\s*gom).*")
                && s.matches(".*(tour\\s*[123]|tour\\s*(nay|do|tren)|chuyen\\s*(nay|do|tren)|cai\\s*(nay|do|[123])).*");
    }

    private boolean isAskDiscount(String s) {
        return s.matches(".*(giam\\s*gia|uu\\s*dai|khuyen\\s*mai|sale|gia\\s*re|gia\\s*tot|dang\\s*giam|re\\s*nhat).*");
    }

    private boolean isAskCoupon(String s) {
        return s.matches(".*(coupon|ma\\s*giam|voucher|ma\\s*khuyen|promo\\s*code|discount\\s*code).*");
    }

    // B1: guard for rating/review/ranking queries — should NOT trigger booking flow
    private boolean isRatingOrReviewQuery(String s) {
        return s.matches(".*(danh\\s*gia|duoc\\s*danh\\s*gia|xep\\s*hang|noi\\s*tieng|pho\\s*bien|duoc\\s*yeu|tot\\s*nhat"
                + "|uy\\s*tin|review|rating|danh\\s*gia\\s*cao|diem\\s*so|binh\\s*luan|nhan\\s*xet"
                + "|khach\\s*hang\\s*thich|duoc\\s*khen|an\\s*tuong|de\\s*cu|nhat|hay\\s*nhat|khuyen\\s*nao).*");
    }

    // B0: stage-aware fallback when Gemini is unavailable
    private IntentResult fallbackIntentByStage(ConversationState.Stage stage, String norm, ConversationState state) {
        if (stage == ConversationState.Stage.COLLECTING_SEARCH_INFO) {
            if (isSearchInfoAnswer(norm, state)) {
                IntentResult r = extractSearchEntities(norm);
                r.setRawSource("stage-fallback");
                r.setConfidence(0.65);
                return r;
            }
            return buildResult(Intent.UNKNOWN, "stage-fallback-rag", 0.35);
        }
        if (stage == ConversationState.Stage.SHOWING_SEARCH_RESULTS) {
            return buildResult(Intent.UNKNOWN, "stage-fallback", 0.35);
        }
        if (stage == ConversationState.Stage.SELECTING_DEPARTURE) {
            if (norm.matches("^[123]$") || norm.matches(".*\\d{1,2}[/\\-.]\\d{1,2}.*")) {
                return buildResult(Intent.TRANSACTION_FLOW, "stage-fallback-date", 0.75);
            }
            if (isPeopleCountInput(norm)) {
                return buildResult(Intent.TRANSACTION_FLOW, "stage-fallback-passenger-before-date", 0.75);
            }
            return buildResult(Intent.UNKNOWN, "stage-fallback", 0.35);
        }
        if (stage == ConversationState.Stage.COLLECTING_PASSENGERS
                || stage == ConversationState.Stage.COLLECTING_CONTACT_NAME_PHONE
                || stage == ConversationState.Stage.COLLECTING_CONTACT_EMAIL
                || stage == ConversationState.Stage.COLLECTING_NOTE_COUPON
                || stage == ConversationState.Stage.CONFIRMING_BOOKING
                || stage == ConversationState.Stage.BOOKING_SUCCESS) {
            return buildResult(Intent.TRANSACTION_FLOW, "stage-fallback", 0.65);
        }
        if (stage == ConversationState.Stage.COLLECTING_LOOKUP_CODE) {
            return buildResult(Intent.UNKNOWN, "stage-fallback-lookup-exit", 0.35);
        }
        return buildResult(Intent.UNKNOWN, "fast-path", 0.3);
    }

    // B0: is input just a month indicator? e.g. "thang 6", "t7"
    private boolean isMonthInput(String s) {
        return s.matches("^(thang\\s*[1-9][0-2]?|t[1-9]|thang\\s*[1-9]|[0-9]{1,2}/[0-9]{4})$")
                || s.matches(".*(thang\\s*[1-9][0-2]?|quy\\s*[1-4]|dau\\s*nam|cuoi\\s*nam|he\\s*nay|dip\\s*tet|dip\\s*le|le\\s*30/4|le\\s*2/9).*");
    }

    // B0: is input a people count? e.g. "2 nguoi lon", "3 adults"
    private boolean isPeopleCountInput(String s) {
        return s.matches(".*(\\d+\\s*(nguoi\\s*(lon|adult|nguoi|khach)|adults?|people|person|khach|nguoi)).*")
                || s.matches("^(\\d+|mot|hai|ba|bon|nam|sau|bay|tam|chin|muoi)\\s*(nguoi|khach|person|adult).*");
    }

    private boolean isSearchInfoAnswer(String s, ConversationState state) {
        return isMonthInput(s)
                || isPeopleCountInput(s)
                || hasSearchLocation(s)
                || s.matches(".*(gan\\s*nhat|som\\s*nhat|bat\\s*ky|luc\\s*nao|cuoi\\s*tuan|di\\s*tu|khoi\\s*hanh|xuat\\s*phat).*");
    }

    private boolean isGeneralPaymentInfoQuery(String s) {
        return s.matches(".*(phuong\\s*thuc\\s*thanh\\s*toan|hinh\\s*thuc\\s*thanh\\s*toan"
                + "|cach\\s*thanh\\s*toan|thanh\\s*toan\\s*bang\\s*(gi|the|cash|the\\s*tin\\s*dung)"
                + "|chap\\s*nhan\\s*thanh\\s*toan|co\\s*the\\s*thanh\\s*toan\\s*(bang|qua)"
                + "|thanh\\s*toan\\s*nhu\\s*the\\s*nao).*");
    }

    private boolean isGeneralAdviceQuery(String s) {
        boolean advice = s.matches(".*(nen\\s*chuan\\s*bi|can\\s*luu\\s*y|kinh\\s*nghiem|hanh\\s*ly|an\\s*toan|"
                + "gia\\s*dinh|tre\\s*nho|nguoi\\s*gia|di\\s*dai\\s*ngay|mua\\s*mua|thoi\\s*tiet|"
                + "bao\\s*gom|khong\\s*bao\\s*gom|chinh\\s*sach|huy\\s*hoan|hoan\\s*tien"
                + "|bao\\s*hiem|bao\\s*ve|dieu\\s*kien\\s*hoan|dieu\\s*kien\\s*huy).*");
        boolean explicitSearch = s.matches(".*(co\\s*tour|tour\\s*(di|den|khoi\\s*hanh)|toi\\s*muon\\s*di|muon\\s*di).*");
        return advice && !explicitSearch;
    }

    // B0: numeric selection in search results  e.g. "1", "chon 2", "tour so 3"
    private boolean isNumericSelection(String s) {
        return s.matches("^[123]$")
                || s.matches(".*(chon\\s*[123]|so\\s*[123]|tour\\s*[123]|option\\s*[123]|tour\\s*dau|dau\\s*tien|cai\\s*dau).*");
    }

    // B2: detect that user is inputting a NEW destination while COLLECTING_SEARCH_INFO
    private boolean isNewDestinationInput(String norm, ConversationState state) {
        if (state.getSearchDestination() == null) return false;
        String newDest = extractFreeDestination(norm);
        if (newDest == null) newDest = extractLocation(norm, LocationResolverService.Role.DESTINATION);
        if (newDest == null) return false;
        // Has a new location different from current destination
        String normalizedCurrent = normalize(state.getSearchDestination());
        String normalizedNew = normalize(newDest);
        return !normalizedCurrent.equals(normalizedNew);
    }

    private boolean isSystemHelp(String s) {
        return s.matches(".*(co\\s*ho\\s*tro\\s*dat\\s*tour|dat\\s*tour\\s*tren\\s*(web|nay|do)?|ho\\s*tro\\s*dat|dat\\s*online|co\\s*dat\\s*duoc\\s*khong).*");
    }

    private boolean isTourSearch(String s) {
        // NOTE: "di bien" / "di nui" đã được tách sang isThematicQuery() — không route SEARCH nếu không có entity địa điểm cụ thể
        return s.matches(".*(tour\\s*(nao|den|di|o|tai|co|gia)|tim\\s*tour|toi\\s*muon\\s*di|muon\\s*di|"
                + "di\\s*(du\\s*lich|tham\\s*quan)|co\\s*tour\\s*(nao|di|den)|goi\\s*y\\s*tour|"
                + "tim\\s*(tour|chuyen|chuyen\\s*di)|^di\\s+[a-z].*).*")
                || s.matches(".*\\btour\\b.+\\b(den|di)\\b.+")
                || s.matches(".*\\b[a-z][a-z\\s]+\\b(den|di)\\b\\s+[a-z][a-z\\s]+.*")
                || (s.matches(".*\\b(co|hoi|xem|di|den)\\b.*") && hasSearchLocation(s));
    }

    /**
     * Thematic queries: user muốn đi "biển" / "núi" / "miền Tây" nhưng CHƯA chỉ định địa điểm cụ thể.
     * Những query này KHÔNG đủ để route TOUR_RETRIEVAL/SEARCH vì không có destination entity.
     * Chúng sẽ được route sang GENERAL_RAG để bot tư vấn gợi ý địa điểm.
     */
    private boolean isThematicQuery(String s) {
        return s.matches(".*(di\\s*(bien|nui|mien\\s*tay|mien\\s*bac|mien\\s*trung|rung|hoang\\s*sa|son\\s*doong)|"
                + "tour\\s*(bien|nui|rung|bien\\s*dao|hoang\\s*da)|du\\s*lich\\s*(bien|nui|mien)).*");
    }

    private boolean isBookingIntent(String s) {
        return s.matches(".*(toi\\s*)?(muon\\s*)?(dat\\s*tour|book\\s*tour|mua\\s*tour|dat\\s*cho|dat\\s*ngay|dat\\s*cho\\s*toi|dat\\s*tiep).*")
                || s.matches(".*(dat|book)\\s+(tour|chuyen)\\s*(nay|do|tren|so\\s*[123])?.*");
    }

    private boolean isLookupIntent(String s) {
        return s.matches(".*(tra\\s*cuu|kiem\\s*tra\\s*don|xem\\s*don|tinh\\s*trang\\s*don|xem.*booking|tra\\s*booking|ma\\s*booking|\\bbooking\\b|don\\s*hang|don\\s*cua\\s*toi|booking\\s*cua\\s*toi|lich\\s*su\\s*dat).*");
    }

    private boolean isPaymentHelp(String s) {
        return s.matches(".*(thanh\\s*toan|payos|payment|qr\\s*code|chuyen\\s*khoan|da\\s*thanh\\s*toan).*");
    }

    private boolean isBookingCancelHelp(String s) {
        return s.matches(".*(huy|cancel|hoan\\s*tien|huy\\s*don|huy\\s*booking|huy\\s*tour).*")
                && (s.matches(".*\\bbooking\\b.*") || s.matches(".*\\bma\\b.*") || s.matches(".*\\bdon\\b.*") || s.matches(".*\\btour\\b.*"));
    }

    private boolean isChangeSearch(String s) {
        // B2: expanded patterns for destination change intent
        return s.matches(".*(tim\\s*lai|tim\\s*tour\\s*khac|doi\\s*diem|doi\\s*ngay|thay\\s*doi\\s*tim|doi\\s*sang"
                + "|bay\\s*gio\\s*(di|den|muon\\s*di)\\s+[a-z]"
                + "|muon\\s*(di|den)\\s+[a-z]"
                + "|thay\\s*(diem|tour|sang)"
                + "|di\\s+[a-z][a-z\\s]+\\s*(di|thoi|nhe|luon|vay)"
                + "|khac\\s*(di|nhe|di|nao)"
                + "|doi\\s*(sang|qua|thanh)).*");
    }

    private boolean isCancel(String s) {
        // Exact single-word cancel (must be the ENTIRE message)
        if (s.equals("huy") || s.equals("thoi") || s.equals("cancel") || s.equals("thoat")) return true;
        // Multi-word: only explicit cancel compound phrases (bare "huy" removed to avoid matching "huy bo bao hiem" etc.)
        return s.matches(".*(^|\\s)(cancel|khong\\s*can\\s*nua|khong\\s*dat|thoi\\s*di|huy\\s*di|huy\\s*thoi|thoi\\s+khong)(\\s|$|[.!?]).*");
    }

    private boolean isResume(String s) {
        return s.matches(".*(tiep\\s*tuc|resume|quay\\s*lai|dat\\s*tiep).*");
    }

    private boolean isGreeting(String s) {
        // Allow common typo variants: helllo/hellooo, hiii, heyyy
        return s.matches("^(xin\\s*chao|chao|hell+o+|hi+|hey+|alo|alo\\s*ban).*$");
    }

    private boolean isStartLocationSearch(String s) {
        return s.matches(".*(khoi\\s*hanh|xuat\\s*phat|di\\s*tu|toi\\s*o|minh\\s*o|o\\s+.+\\s+thi|tu\\s+.+).*")
                && locationResolver.resolve(s, LocationResolverService.Role.START).isPresent();
    }

    private boolean isConfirm(String s) {
        return s.matches(".*(xac\\s*nhan|confirm|dong\\s*y|ok|yes|dat\\s*ngay).*");
    }

    private IntentResult buildRetrievalResult(RetrievalTask task, String norm, ConversationState state, String source, double confidence) {
        Integer tourIdx = null;
        if (norm.contains("tour 1") || norm.contains("cai 1") || norm.contains("cai dau") || norm.contains("so 1")) {
            tourIdx = 0;
        } else if (norm.contains("tour 2") || norm.contains("cai 2") || norm.contains("so 2")) {
            tourIdx = 1;
        } else if (norm.contains("tour 3") || norm.contains("cai 3") || norm.contains("so 3")) {
            tourIdx = 2;
        } else if (norm.matches(".*(tour\\s*do|tour\\s*nay|cai\\s*(nay|do)|no|tour\\s*tren).*")
                && state.getLastMentionedTourId() != null
                && state.getLastSearchResults() != null) {
            for (int i = 0; i < state.getLastSearchResults().size(); i++) {
                if (state.getLastMentionedTourId().equals(state.getLastSearchResults().get(i).getTourId())) {
                    tourIdx = i;
                    break;
                }
            }
        }

        return IntentResult.builder()
                .intent(Intent.TOUR_RETRIEVAL)
                .retrievalTask(task)
                .resolvedTourIdx(tourIdx)
                .queryText(extractQueryText(norm))
                .rawSource(source)
                .confidence(confidence)
                .build();
    }

    private IntentResult extractSearchEntities(String norm) {
        IntentResult r = IntentResult.builder()
                .intent(Intent.TOUR_RETRIEVAL)
                .retrievalTask(RetrievalTask.SEARCH)
                .build();

        if (norm.matches(".*(khoi\\s*hanh|xuat\\s*phat|di\\s*tu|toi\\s*o|minh\\s*o|o\\s+.+\\s+thi|tu\\s+.+).*")) {
            String start = extractLocation(norm, LocationResolverService.Role.START);
            if (start != null) r.setStartLocation(start);
        }

        // "X đến Y" pattern: Y is always the destination (highest priority)
        if (norm.contains(" den ") || norm.startsWith("den ")) {
            String[] denParts = norm.split("\\bden\\b", 2);
            if (denParts.length == 2) {
                String afterDen = stripSearchModifiers(denParts[1].trim());
                String destFromDen = extractLocation(afterDen, LocationResolverService.Role.DESTINATION);
                if (destFromDen == null) destFromDen = extractLocation(afterDen, LocationResolverService.Role.ANY);
                if (destFromDen == null) destFromDen = extractFreeDestination("di " + afterDen);
                if (destFromDen != null) {
                    r.setDestination(destFromDen);
                    String beforeDen = denParts[0].trim();
                    String startFromBefore = extractLocation(beforeDen, LocationResolverService.Role.ANY);
                    if (startFromBefore != null) r.setStartLocation(startFromBefore);
                }
            }
        }
        if (r.getDestination() == null && norm.contains(" di ")) {
            String[] diParts = norm.split("\\bdi\\b", 2);
            if (diParts.length == 2) {
                String beforeDi = stripSearchModifiers(diParts[0].trim());
                String afterDi = stripSearchModifiers(diParts[1].trim());
                String startFromBefore = extractLocation(beforeDi, LocationResolverService.Role.ANY);
                String destFromAfter = extractLocation(afterDi, LocationResolverService.Role.DESTINATION);
                if (destFromAfter == null) destFromAfter = extractLocation(afterDi, LocationResolverService.Role.ANY);
                if (destFromAfter == null) destFromAfter = extractFreeDestination("di " + afterDi);
                if (startFromBefore != null && destFromAfter != null) {
                    r.setStartLocation(startFromBefore);
                    r.setDestination(destFromAfter);
                }
            }
        }
        if (r.getDestination() == null && !isStartLocationSearch(norm)) {
            String dest = extractLocation(norm, LocationResolverService.Role.DESTINATION);
            if (dest == null) dest = extractFreeDestination(norm);
            if (dest != null) r.setDestination(dest);
            if (norm.contains("di bien")) r.setDestination(null);
        }

        for (int m = 1; m <= 12; m++) {
            if (norm.contains("thang " + m) || norm.contains("/" + String.format("%02d", m))) {
                r.setTravelMonth("thang " + m);
                break;
            }
        }

        Matcher am = Pattern.compile("(\\d+)\\s*(nguoi\\s*lon|adult|nguoi|khach)").matcher(norm);
        if (am.find()) r.setAdultCount(Integer.parseInt(am.group(1)));
        r.setQueryText(extractQueryText(norm));
        return r;
    }

    private boolean hasSearchLocation(String norm) {
        return locationResolver.resolve(norm, LocationResolverService.Role.DESTINATION).isPresent()
                || locationResolver.resolve(norm, LocationResolverService.Role.START).isPresent();
    }

    private String extractQueryText(String norm) {
        if (norm == null || norm.isBlank()) return null;
        String q = norm
                .replaceAll("\\b(xem|cho|toi|minh|ban|giup|nhe|di|co|khong|ko|la|ve|thong tin|chi tiet|tour|chuyen|nay|do|tren|so|gia|bao nhieu|con may|slot|cho|ngay khoi hanh|lich trinh)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return q.isBlank() ? null : q;
    }

    private String extractFreeDestination(String norm) {
        if (norm == null || norm.isBlank()) return null;
        Matcher m = Pattern.compile(".*(?:tour\\s*)?(?:di|den|ve)\\s+(.+?)(?:\\s+(?:khong|ko|a|nhe|vay|thi\\s*sao))?$").matcher(norm);
        if (!m.matches()) return null;
        String dest = m.group(1)
                .replaceAll("\\b(du\\s*lich|tham\\s*quan|tour|chuyen|cho\\s*toi|giup\\s*toi)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (dest.isBlank() || dest.length() < 3 || dest.length() > 40) return null;
        // Guard: loại bỏ thematic keywords và token quá ngắn tránh false match
        if (dest.matches(".*\\b(bien|nui|gia\\s*re|uu\\s*dai|khuyen\\s*mai)\\b.*")) return null;
        // Single token phải ≥ 3 chars để tránh "mi" khớp "Hồ Chí Minh"
        if (!dest.contains(" ") && dest.length() < 3) return null;
        return dest;
    }

    private String stripSearchModifiers(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\b(thang\\s*\\d{1,2}|t\\d{1,2}|tuan\\s*sau|gan\\s*nhat|som\\s*nhat)\\b.*", "")
                .replaceAll("\\b\\d+\\s*(nguoi\\s*lon|nguoi|khach|adult|child|tre\\s*em).*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractLocation(String norm, LocationResolverService.Role role) {
        return locationResolver.resolve(norm, role)
                .map(LocationResolverService.ResolvedLocation::name)
                .orElse(null);
    }

    private RetrievalTask mapResolvedTask(String resolvedIntent) {
        if (resolvedIntent == null) return null;
        return switch (resolvedIntent) {
            case "ASK_SLOT" -> RetrievalTask.SLOT;
            case "ASK_PRICE" -> RetrievalTask.PRICE;
            case "ASK_CHILD_PRICE" -> RetrievalTask.CHILD_PRICE;
            case "ASK_DEPARTURE_DATE" -> RetrievalTask.DEPARTURE_DATE;
            case "ASK_ITINERARY" -> RetrievalTask.ITINERARY;
            case "ASK_POLICY" -> RetrievalTask.POLICY;
            default -> null;
        };
    }

    private IntentResult buildResult(Intent intent, String source, double confidence) {
        return IntentResult.builder().intent(intent).rawSource(source).confidence(confidence).build();
    }

    private String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9/\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
