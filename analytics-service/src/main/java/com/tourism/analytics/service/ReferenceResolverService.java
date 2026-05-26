package com.tourism.analytics.service;

import com.tourism.analytics.dto.chatbot.ConversationState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;

/**
 * Resolves contextual references like "tour do", "tour nay", "con may slot"
 * to the current tour/departure stored in ConversationState.
 */
@Service
@Slf4j
public class ReferenceResolverService {

    public record ResolvedContext(
            Integer tourId,
            Integer departureId,
            String resolvedFrom,
            boolean isAmbiguous,
            String resolvedIntent
    ) {}

    public boolean isPronounReference(String message) {
        String lower = normalize(message);
        if (lower.isBlank()) return false;
        return lower.matches("(?s).*(tour\\s*do|tour\\s*nay|chuyen\\s*do|chuyen\\s*nay|"
                + "cai\\s*do|cai\\s*nay|\\bno\\b|don\\s*do|booking\\s*do|"
                + "tour\\s*so\\s*[123]|cai\\s*[123]|chuyen\\s*so\\s*[123]).*");
    }

    public boolean isContextualShortQuestion(String message) {
        String lower = normalize(message);
        if (lower.isBlank() || lower.length() > 60) return false;
        return lower.matches("(?s).*(con\\s*(may|bao\\s*nhieu)\\s*(slot|cho|ve|cho\\s*khong)?|"
                + "gia\\s*(bao\\s*nhieu|may|tien)|tre\\s*em\\s*gia|em\\s*be\\s*gia|"
                + "lich\\s*trinh|dat\\s*coc|huy\\s*duoc\\s*khong|"
                + "di\\s*(ngay|thang)\\s*may|khoi\\s*hanh\\s*khi\\s*nao|"
                + "may\\s*ngay\\s*may\\s*dem|bao\\s*gom\\s*gi|"
                + "con\\s*cho\\s*khong|slot\\s*con).*");
    }

    public ResolvedContext resolve(String message, ConversationState state) {
        String lower = normalize(message);
        String resolvedIntent = detectIntent(lower);

        boolean isDepartureRef = lower.matches("(?s).*(chuyen|ngay|departure|don|booking|cho|slot).*");
        if (isDepartureRef && state.getLastMentionedDepartureId() != null) {
            log.debug("Resolved departure from lastMentionedDepartureId: {}", state.getLastMentionedDepartureId());
            return new ResolvedContext(state.getLastMentionedTourId(),
                    state.getLastMentionedDepartureId(), "lastMentionedDepartureId", false, resolvedIntent);
        }

        if (isDepartureRef && state.getSelectedDepartureId() != null) {
            log.debug("Resolved departure from selectedDepartureId: {}", state.getSelectedDepartureId());
            return new ResolvedContext(state.getSelectedTourId(),
                    state.getSelectedDepartureId(), "selectedDepartureId", false, resolvedIntent);
        }

        if (state.getLastMentionedTourId() != null) {
            Integer depId = state.getLastMentionedDepartureId() != null
                    ? state.getLastMentionedDepartureId()
                    : findFirstDepartureForTour(state.getLastMentionedTourId(), state);
            log.debug("Resolved tour from lastMentionedTourId: {}", state.getLastMentionedTourId());
            return new ResolvedContext(state.getLastMentionedTourId(), depId,
                    "lastMentionedTourId", false, resolvedIntent);
        }

        if (state.getLastSearchResults() != null && !state.getLastSearchResults().isEmpty()) {
            ConversationState.TourGroupDisplay first = state.getLastSearchResults().get(0);
            Integer depId = first.getDepartures() != null && !first.getDepartures().isEmpty()
                    ? first.getDepartures().get(0).getDepartureId() : null;
            log.debug("Resolved tour from lastSearchResults[0]: {}", first.getTourId());
            return new ResolvedContext(first.getTourId(), depId, "lastSearchResults[0]", false, resolvedIntent);
        }

        log.debug("Cannot resolve reference: no context available");
        return new ResolvedContext(null, null, "ambiguous", true, null);
    }

    private String detectIntent(String lower) {
        if (lower.matches("(?s).*(con\\s*(may|bao\\s*nhieu)\\s*(slot|cho|ve)?|slot\\s*con|con\\s*cho).*")) return "ASK_SLOT";
        if (lower.matches("(?s).*(tre\\s*em\\s*gia|em\\s*be\\s*gia|gia\\s*(tre|em)).*")) return "ASK_CHILD_PRICE";
        if (lower.matches("(?s).*(gia\\s*(bao\\s*nhieu|may|tien)|bao\\s*nhieu\\s*tien).*")) return "ASK_PRICE";
        if (lower.matches("(?s).*(lich\\s*trinh|may\\s*ngay\\s*may\\s*dem|bao\\s*gom\\s*gi|chuong\\s*trinh).*")) return "ASK_ITINERARY";
        if (lower.matches("(?s).*(dat\\s*coc|huy|chinh\\s*sach|dieu\\s*kien|hoan\\s*tien).*")) return "ASK_POLICY";
        if (lower.matches("(?s).*(di\\s*(ngay|thang)\\s*may|khoi\\s*hanh|ngay\\s*nao).*")) return "ASK_DEPARTURE_DATE";
        return null;
    }

    private Integer findFirstDepartureForTour(Integer tourId, ConversationState state) {
        if (state.getLastDepartures() == null || tourId == null) return null;
        if (state.getLastSearchResults() != null) {
            return state.getLastSearchResults().stream()
                    .filter(g -> tourId.equals(g.getTourId()))
                    .findFirst()
                    .flatMap(g -> g.getDepartures() != null && !g.getDepartures().isEmpty()
                            ? java.util.Optional.of(g.getDepartures().get(0).getDepartureId())
                            : java.util.Optional.empty())
                    .orElse(null);
        }
        return null;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
