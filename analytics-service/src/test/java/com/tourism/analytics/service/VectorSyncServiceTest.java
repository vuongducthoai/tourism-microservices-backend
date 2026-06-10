package com.tourism.analytics.service;

import com.tourism.analytics.dto.feign.LocationSyncDTO;
import com.tourism.analytics.dto.feign.ReviewSyncDTO;
import com.tourism.analytics.dto.feign.TourSyncDTO;
import com.tourism.analytics.feign.BookingFeignClient;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests cho VectorSyncService.
 *
 * Coverage:
 * - syncAll: gọi cả 3 sub-sync
 * - syncAllTours: gọi Feign, upsert đúng số docs
 * - syncAllLocations: skip locations empty
 * - syncAllReviews: skip review không có comment
 * - scheduledSync: delegate sang syncAll
 * - error resilience: Feign throws → trả về 0 không throw
 */
@ExtendWith(MockitoExtension.class)
class VectorSyncServiceTest {

    @Mock
    private TourCatalogFeignClient tourCatalogFeignClient;

    @Mock
    private BookingFeignClient bookingFeignClient;

    @Mock
    private VectorService vectorService;

    @Mock
    private ChatbotVectorSyncRunService syncRunService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private VectorSyncService vectorSyncService;

    // ─────────────────────────────────────────────
    // syncAll
    // ─────────────────────────────────────────────

    @Test
    void syncAll_callsAllThreeSubSyncs() {
        // Arrange
        when(tourCatalogFeignClient.getAllToursForChatbotSync()).thenReturn(List.of());
        when(tourCatalogFeignClient.getLocationsForChatbotSync()).thenReturn(List.of());
        when(tourCatalogFeignClient.getAllVisibleReviews()).thenReturn(List.of());
        when(bookingFeignClient.getCouponsForChatbotSync()).thenReturn(List.of());

        // Act
        assertThatNoException().isThrownBy(() -> vectorSyncService.syncAllWithoutHistory());

        // Assert: all three Feign methods called
        verify(tourCatalogFeignClient).getAllToursForChatbotSync();
        verify(tourCatalogFeignClient).getLocationsForChatbotSync();
        verify(tourCatalogFeignClient).getAllVisibleReviews();
        verify(bookingFeignClient).getCouponsForChatbotSync();
    }

    // ─────────────────────────────────────────────
    // syncAllTours
    // ─────────────────────────────────────────────

    @Test
    void syncAllTours_tourWithOneFutureDeparture_upsertsFactDocsToo() {
        // Arrange: 1 tour + 1 future departure
        TourSyncDTO.DepartureSyncDTO dep = new TourSyncDTO.DepartureSyncDTO(
                10, "2030-06-15", 5, 2500000.0, 3000000.0,
                null, null, null, null,
                List.of(), List.of());

        TourSyncDTO tour = new TourSyncDTO(
                1, "HN001", "Tour Hà Nội 3N2Đ", "3 ngày 2 đêm", "Máy bay",
                "Hà Nội", 1, "Đà Nẵng", 2,
                "Hoàn Kiếm, Hồ Tây", "Sáng + Tối", "4 sao",
                "https://img.com/hn.jpg", 4.5, 10,
                List.of(dep), List.of());

        when(tourCatalogFeignClient.getAllToursForChatbotSync()).thenReturn(List.of(tour));
        when(vectorService.createEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f));

        // Act
        int count = vectorSyncService.syncAllTours();

        // Assert: old docs stay, additional fact docs are synced too
        assertThat(count).isEqualTo(5);
        verify(vectorService, times(5)).upsertVector(any());
    }

    @Test
    void syncAllTours_emptyEmbedding_zeroUpserts() {
        // Arrange: embedding fails
        TourSyncDTO tour = new TourSyncDTO(
                1, "HN001", "Tour Hà Nội", "3N2Đ", "Xe",
                "Hà Nội", 1, "Hạ Long", 3,
                "Vịnh Hạ Long", null, null,
                null, null, null,
                List.of(), List.of());

        when(tourCatalogFeignClient.getAllToursForChatbotSync()).thenReturn(List.of(tour));
        when(vectorService.createEmbedding(anyString())).thenReturn(List.of()); // empty!

        // Act
        int count = vectorSyncService.syncAllTours();

        // Assert: nothing upserted
        assertThat(count).isZero();
        verify(vectorService, never()).upsertVector(any());
    }

    @Test
    void syncAllTours_feignThrows_returnsZero() {
        // Arrange
        when(tourCatalogFeignClient.getAllToursForChatbotSync())
                .thenThrow(new RuntimeException("Service unavailable"));

        // Act
        int count = vectorSyncService.syncAllTours();

        // Assert: graceful degradation
        assertThat(count).isZero();
        verify(vectorService, never()).upsertVector(any());
    }

    // ─────────────────────────────────────────────
    // syncAllLocations
    // ─────────────────────────────────────────────

    @Test
    void syncAllLocations_syncsOneLocation() {
        // Arrange
        LocationSyncDTO loc = new LocationSyncDTO(
                1, "Hà Nội", "https://img.com/hn.jpg",
                "Thủ đô Việt Nam", "NORTH", "HAN", "Nội Bài");

        when(tourCatalogFeignClient.getLocationsForChatbotSync()).thenReturn(List.of(loc));
        when(vectorService.createEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f));

        // Act
        int count = vectorSyncService.syncAllLocations();

        // Assert
        assertThat(count).isEqualTo(1);
        verify(vectorService, times(1)).upsertVector(any());
    }

    @Test
    void syncAllLocations_emptyList_zeroUpserts() {
        when(tourCatalogFeignClient.getLocationsForChatbotSync()).thenReturn(List.of());

        int count = vectorSyncService.syncAllLocations();

        assertThat(count).isZero();
        verify(vectorService, never()).upsertVector(any());
    }

    // ─────────────────────────────────────────────
    // syncAllReviews
    // ─────────────────────────────────────────────

    @Test
    void syncAllReviews_skipsReviewWithNullComment() {
        // Arrange: 1 review có comment, 1 review không có comment
        ReviewSyncDTO withComment    = new ReviewSyncDTO(1, 42, "HN001", "Tour Hà Nội", 5, "Rất tuyệt vời!");
        ReviewSyncDTO withoutComment = new ReviewSyncDTO(2, 42, "HN001", "Tour Hà Nội", 4, null);

        when(tourCatalogFeignClient.getAllVisibleReviews())
                .thenReturn(List.of(withComment, withoutComment));
        when(vectorService.createEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f));

        // Act
        int count = vectorSyncService.syncAllReviews();

        // Assert: only 1 upserted
        assertThat(count).isEqualTo(1);
        verify(vectorService, times(1)).upsertVector(any());
    }

    @Test
    void syncAllReviews_feignThrows_returnsZero() {
        when(tourCatalogFeignClient.getAllVisibleReviews())
                .thenThrow(new RuntimeException("Connection error"));

        int count = vectorSyncService.syncAllReviews();

        assertThat(count).isZero();
    }
}
