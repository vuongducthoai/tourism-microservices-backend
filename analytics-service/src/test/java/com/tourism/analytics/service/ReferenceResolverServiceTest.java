package com.tourism.analytics.service;

import com.tourism.analytics.dto.chatbot.ConversationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceResolverServiceTest {

    private ReferenceResolverService resolver;
    private ConversationState state;

    @BeforeEach
    void setUp() {
        resolver = new ReferenceResolverService();
        state = ConversationState.builder()
                .stage(ConversationState.Stage.IDLE)
                .recentTurns(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("'tour do' is pronoun reference")
    void tourDo() {
        assertThat(resolver.isPronounReference("tour do")).isTrue();
    }

    @Test
    @DisplayName("'chuyen nay' is pronoun reference")
    void chuyenNay() {
        assertThat(resolver.isPronounReference("chuyen nay gia bao nhieu")).isTrue();
    }

    @Test
    @DisplayName("'tour so 2' is pronoun reference")
    void tourSo2() {
        assertThat(resolver.isPronounReference("tour so 2")).isTrue();
    }

    @Test
    @DisplayName("Normal search query is not pronoun")
    void notPronoun() {
        assertThat(resolver.isPronounReference("toi muon di da nang")).isFalse();
    }

    @Test
    @DisplayName("'con may cho' is contextual short")
    void conMayCho() {
        assertThat(resolver.isContextualShortQuestion("con may cho khong")).isTrue();
    }

    @Test
    @DisplayName("'gia bao nhieu' is contextual short")
    void giaBaoNhieu() {
        assertThat(resolver.isContextualShortQuestion("gia bao nhieu")).isTrue();
    }

    @Test
    @DisplayName("Long tour search is not contextual short")
    void notShort() {
        String longQuery = "toi muon tim mot tour du lich sang trong di da nang vao thang 8 cho 4 nguoi lon";
        assertThat(resolver.isContextualShortQuestion(longQuery)).isFalse();
    }

    @Test
    @DisplayName("Resolves from lastMentionedDepartureId for departure-related question")
    void resolvesLastMentionedDep() {
        state.setLastMentionedTourId(100);
        state.setLastMentionedDepartureId(500);

        var ctx = resolver.resolve("chuyen do con cho khong?", state);

        assertThat(ctx.tourId()).isEqualTo(100);
        assertThat(ctx.departureId()).isEqualTo(500);
        assertThat(ctx.isAmbiguous()).isFalse();
    }

    @Test
    @DisplayName("Resolves from lastMentionedTourId when no departure context")
    void resolvesLastMentionedTour() {
        state.setLastMentionedTourId(200);

        var ctx = resolver.resolve("tour do gia bao nhieu?", state);

        assertThat(ctx.tourId()).isEqualTo(200);
        assertThat(ctx.isAmbiguous()).isFalse();
    }

    @Test
    @DisplayName("Resolves from lastSearchResults[0] when no lastMentionedTourId")
    void resolvesFromSearchResults() {
        ConversationState.TourGroupDisplay tour = new ConversationState.TourGroupDisplay();
        tour.setTourId(300);
        ConversationState.DepartureMeta dep = new ConversationState.DepartureMeta();
        dep.setDepartureId(800);
        tour.setDepartures(List.of(dep));
        state.setLastSearchResults(List.of(tour));

        var ctx = resolver.resolve("tour do con slot?", state);

        assertThat(ctx.tourId()).isEqualTo(300);
        assertThat(ctx.departureId()).isEqualTo(800);
        assertThat(ctx.resolvedFrom()).isEqualTo("lastSearchResults[0]");
    }

    @Test
    @DisplayName("Returns ambiguous when no context available")
    void ambiguousWithNoContext() {
        var ctx = resolver.resolve("tour do gia bao nhieu?", state);
        assertThat(ctx.isAmbiguous()).isTrue();
        assertThat(ctx.tourId()).isNull();
    }

    @Test
    @DisplayName("detectIntent: ASK_SLOT")
    void detectAskSlot() {
        state.setLastMentionedTourId(100);
        var ctx = resolver.resolve("con may cho?", state);
        assertThat(ctx.resolvedIntent()).isEqualTo("ASK_SLOT");
    }
}
