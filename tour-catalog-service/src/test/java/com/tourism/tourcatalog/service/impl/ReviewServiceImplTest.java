package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.ReviewRequest;
import com.tourism.tourcatalog.dto.response.ReviewResponse;
import com.tourism.tourcatalog.dto.response.ReviewStatisticsResponse;
import com.tourism.tourcatalog.dto.response.TourReviewListResponse;
import com.tourism.tourcatalog.entity.ImageReview;
import com.tourism.tourcatalog.entity.Review;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.feign.BookingFeignClient;
import com.tourism.tourcatalog.feign.IamFeignClient;
import com.tourism.tourcatalog.feign.dto.BookingBriefResponse;
import com.tourism.tourcatalog.feign.dto.UserBriefResponse;
import com.tourism.tourcatalog.repository.ReviewRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReviewServiceImpl.
 *
 * Covers:
 *  1. submitReview       — normal flow, coin rewards, local image upload, Feign calls
 *  2. getReviewByBookingId
 *  3. getReviewsByTour   — paginated listing with user info enrichment
 *  4. getReviewStatistics — star counts, percentages, average
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceImplTest {

    @Mock ReviewRepository   reviewRepository;
    @Mock TourRepository     tourRepository;
    @Mock FileStorageService fileStorageService;
    @Mock BookingFeignClient bookingClient;
    @Mock IamFeignClient     iamClient;

    @InjectMocks ReviewServiceImpl service;

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Tour makeTour(int id, String code) {
        Tour t = new Tour();
        t.setTourID(id);
        t.setTourCode(code);
        t.setTourName("Tour " + code);
        return t;
    }

    private ReviewRequest makeRequest(int rating, String comment, int tourID, int bookingID) {
        ReviewRequest r = new ReviewRequest();
        r.setRating(rating);
        r.setComment(comment);
        r.setTourID(tourID);
        r.setBookingID(bookingID);
        r.setUserId(99);
        return r;
    }

    private Review makeSavedReview(int reviewId, int rating, String comment, Tour tour, int bookingId, int userId) {
        Review r = new Review();
        r.setReviewID(reviewId);
        r.setRating(rating);
        r.setComment(comment);
        r.setTour(tour);
        r.setBookingId(bookingId);
        r.setUserId(userId);
        r.setIsVisible(true);
        r.setImages(new ArrayList<>());
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private MultipartFile mockFile(String name) throws IOException {
        MultipartFile f = mock(MultipartFile.class);
        lenient().when(f.isEmpty()).thenReturn(false);
        lenient().when(f.getOriginalFilename()).thenReturn(name);
        lenient().when(f.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream("fake-image-data".getBytes()));
        return f;
    }

    private BookingBriefResponse mockBookingBrief(int bookingId, int userId, String code) {
        BookingBriefResponse b = new BookingBriefResponse();
        b.setBookingID(bookingId);
        b.setUserId(userId);
        b.setBookingCode(code);
        b.setBookingStatus("PAID");
        return b;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. submitReview
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submitReview()")
    class SubmitReviewTests {

        @BeforeEach
        void stubCommon() {
            when(reviewRepository.save(any())).thenAnswer(inv -> {
                Review r = inv.getArgument(0);
                r.setReviewID(1);
                return r;
            });
        }

        @Test
        @DisplayName("HAPPY PATH: no images — returns response, updates booking REVIEWED, adds 5000 coins")
        void submitReview_noImages_5pts() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(100)).thenReturn(false);
            when(bookingClient.getBookingById(100)).thenReturn(mockBookingBrief(100, 42, "BK-ABC"));

            ReviewRequest req = makeRequest(5, "Chuyến đi rất tuyệt vời!", 10, 100);
            ReviewResponse res = service.submitReview(req, null);

            assertThat(res.getReviewID()).isEqualTo(1);
            assertThat(res.getRating()).isEqualTo(5);
            assertThat(res.getTourCode()).isEqualTo("TOUR-01");
            assertThat(res.getBookingCode()).isEqualTo("BK-ABC");
            assertThat(res.getImageUrls()).isEmpty();

            verify(bookingClient).updateBookingStatus(100, "REVIEWED");
            ArgumentCaptor<BigDecimal> coinCap = ArgumentCaptor.forClass(BigDecimal.class);
            verify(iamClient).addCoins(eq(42), coinCap.capture());
            assertThat(coinCap.getValue()).isEqualByComparingTo("5000"); // 5 pts × 1000
        }

        @Test
        @DisplayName("HAPPY PATH: 1 image — adds 7000 coins")
        void submitReview_oneImage_7pts() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(101)).thenReturn(false);
            when(bookingClient.getBookingById(101)).thenReturn(mockBookingBrief(101, 42, "BK-XYZ"));
            when(fileStorageService.saveFile(any())).thenReturn("/uploads/review-images/img1.jpg");

            ReviewRequest req = makeRequest(4, "Hướng dẫn viên nhiệt tình!", 10, 101);
            service.submitReview(req, List.of(mockFile("photo.jpg")));

            ArgumentCaptor<BigDecimal> coinCap = ArgumentCaptor.forClass(BigDecimal.class);
            verify(iamClient).addCoins(eq(42), coinCap.capture());
            assertThat(coinCap.getValue()).isEqualByComparingTo("7000"); // 7 pts × 1000
        }

        @Test
        @DisplayName("HAPPY PATH: 2 images — adds 10000 coins")
        void submitReview_twoImages_10pts() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(102)).thenReturn(false);
            when(bookingClient.getBookingById(102)).thenReturn(mockBookingBrief(102, 42, "BK-2IMG"));
            when(fileStorageService.saveFile(any()))
                    .thenReturn("/uploads/review-images/img1.jpg")
                    .thenReturn("/uploads/review-images/img2.jpg");

            ReviewRequest req = makeRequest(5, "Tour rất chất lượng tốt!", 10, 102);
            ReviewResponse res = service.submitReview(req, List.of(mockFile("a.jpg"), mockFile("b.jpg")));

            assertThat(res.getImageUrls()).hasSize(2);
            ArgumentCaptor<BigDecimal> coinCap = ArgumentCaptor.forClass(BigDecimal.class);
            verify(iamClient).addCoins(eq(42), coinCap.capture());
            assertThat(coinCap.getValue()).isEqualByComparingTo("10000"); // 10 pts × 1000
        }

        @Test
        @DisplayName("SHORT COMMENT: < 10 chars → 0 points, coins NOT called")
        void submitReview_shortComment_noCoins() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(103)).thenReturn(false);
            when(bookingClient.getBookingById(103)).thenReturn(mockBookingBrief(103, 42, "BK-SHORT"));

            ReviewRequest req = makeRequest(3, "OK", 10, 103); // "OK" = 2 chars
            service.submitReview(req, null);

            verify(iamClient, never()).addCoins(anyInt(), any());
        }

        @Test
        @DisplayName("BOUNDARY: comment exactly 10 chars → 5 pts")
        void submitReview_exactly10Chars_5pts() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(104)).thenReturn(false);
            when(bookingClient.getBookingById(104)).thenReturn(mockBookingBrief(104, 42, "BK-BOUND"));

            ReviewRequest req = makeRequest(5, "1234567890", 10, 104); // 10 chars exactly
            service.submitReview(req, null);

            ArgumentCaptor<BigDecimal> coinCap = ArgumentCaptor.forClass(BigDecimal.class);
            verify(iamClient).addCoins(eq(42), coinCap.capture());
            assertThat(coinCap.getValue()).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("INVALID RATING 0: throws IllegalArgumentException")
        void submitReview_rating0_throws() {
            ReviewRequest req = makeRequest(0, "some comment here", 10, 105);
            assertThatThrownBy(() -> service.submitReview(req, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rating");
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("INVALID RATING 6: throws IllegalArgumentException")
        void submitReview_rating6_throws() {
            ReviewRequest req = makeRequest(6, "some comment here", 10, 106);
            assertThatThrownBy(() -> service.submitReview(req, null))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("TOUR NOT FOUND: throws RuntimeException, never saves")
        void submitReview_tourNotFound_throws() {
            when(tourRepository.findById(999)).thenReturn(Optional.empty());
            ReviewRequest req = makeRequest(5, "Chuyến đi tuyệt vời!", 999, 100);
            assertThatThrownBy(() -> service.submitReview(req, null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("999");
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("DUPLICATE REVIEW: same bookingID → throws IllegalStateException")
        void submitReview_duplicate_throws() {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(200)).thenReturn(true);

            ReviewRequest req = makeRequest(5, "Chuyến đi tuyệt vời đáng nhớ", 10, 200);
            assertThatThrownBy(() -> service.submitReview(req, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("200");
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("BOOKING FEIGN FAILS: falls back to userId in request, review still saved")
        void submitReview_bookingFeignFails_fallbackToRequestUserId() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(300)).thenReturn(false);
            when(bookingClient.getBookingById(300)).thenThrow(new RuntimeException("booking-service down"));

            ReviewRequest req = makeRequest(4, "Rất hài lòng với dịch vụ!", 10, 300);
            req.setUserId(77);

            ReviewResponse res = service.submitReview(req, null);

            // Review still saved despite booking feign failure
            verify(reviewRepository).save(any());
            assertThat(res.getReviewID()).isEqualTo(1);
        }

        @Test
        @DisplayName("IAM COIN FEIGN FAILS: coins silently logged, review still saved")
        void submitReview_iamFeignFails_reviewSavedAnyway() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(400)).thenReturn(false);
            when(bookingClient.getBookingById(400)).thenReturn(mockBookingBrief(400, 42, "BK-IAM"));
            doThrow(new RuntimeException("iam-service down"))
                    .when(iamClient).addCoins(anyInt(), any());

            ReviewRequest req = makeRequest(5, "Tour tuyệt vời, rất đáng tiền!", 10, 400);
            assertThatNoException().isThrownBy(() -> service.submitReview(req, null));
            verify(reviewRepository).save(any()); // review still persisted
        }

        @Test
        @DisplayName("BOOKING STATUS FEIGN FAILS: booking status not updated, review still saved")
        void submitReview_bookingStatusFeignFails_reviewSavedAnyway() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(500)).thenReturn(false);
            when(bookingClient.getBookingById(500)).thenReturn(mockBookingBrief(500, 42, "BK-BSTATUS"));
            doThrow(new RuntimeException("booking-service timeout"))
                    .when(bookingClient).updateBookingStatus(anyInt(), any());

            ReviewRequest req = makeRequest(5, "Hướng dẫn viên rất nhiệt tình!", 10, 500);
            assertThatNoException().isThrownBy(() -> service.submitReview(req, null));
            verify(reviewRepository).save(any());
        }

        @Test
        @DisplayName("NULL images list: treated same as no images")
        void submitReview_nullImages_noNPE() throws IOException {
            Tour tour = makeTour(10, "TOUR-01");
            when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            when(reviewRepository.existsByBookingId(600)).thenReturn(false);
            when(bookingClient.getBookingById(600)).thenReturn(mockBookingBrief(600, 42, "BK-NULL"));

            ReviewRequest req = makeRequest(5, "Khách sạn rất sạch sẽ thoáng mát!", 10, 600);
            assertThatNoException().isThrownBy(() -> service.submitReview(req, null));
            verify(fileStorageService, never()).saveFile(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. getReviewByBookingId
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getReviewByBookingId()")
    class GetReviewByBookingIdTests {

        @Test
        @DisplayName("HAPPY PATH: returns ReviewResponse with all fields")
        void getReview_found_returnsResponse() {
            Tour tour = makeTour(1, "TOUR-ABC");
            Review review = makeSavedReview(5, 4, "Rất hài lòng!", tour, 10, 99);
            ImageReview img = new ImageReview();
            img.setImageUrl("/uploads/review-images/test.jpg");
            img.setReview(review);
            review.setImages(List.of(img));

            when(reviewRepository.findByBookingId(10)).thenReturn(Optional.of(review));

            ReviewResponse res = service.getReviewByBookingId(10);

            assertThat(res.getReviewID()).isEqualTo(5);
            assertThat(res.getRating()).isEqualTo(4);
            assertThat(res.getComment()).isEqualTo("Rất hài lòng!");
            assertThat(res.getTourCode()).isEqualTo("TOUR-ABC");
            assertThat(res.getImageUrls()).hasSize(1).contains("/uploads/review-images/test.jpg");
        }

        @Test
        @DisplayName("NO REVIEW: throws RuntimeException with bookingId in message")
        void getReview_notFound_throws() {
            when(reviewRepository.findByBookingId(999)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getReviewByBookingId(999))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("999");
        }

        @Test
        @DisplayName("NO IMAGES: imageUrls is empty list (not null)")
        void getReview_noImages_emptyList() {
            Tour tour = makeTour(1, "TOUR-ABC");
            Review review = makeSavedReview(7, 5, "Tour rất tốt", tour, 20, 99);

            when(reviewRepository.findByBookingId(20)).thenReturn(Optional.of(review));

            ReviewResponse res = service.getReviewByBookingId(20);

            assertThat(res.getImageUrls()).isNotNull().isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. getReviewsByTour
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getReviewsByTour()")
    class GetReviewsByTourTests {

        @Test
        @DisplayName("HAPPY PATH: returns page with user info enriched from iam-service")
        void getReviewsByTour_enrichesUserInfo() {
            Tour tour = makeTour(1, "TOUR-XYZ");
            Review r = makeSavedReview(1, 5, "Tuyệt vời!", tour, 10, 42);

            when(reviewRepository.findByTourCodeAndVisible(eq("TOUR-XYZ"), any()))
                    .thenReturn(new PageImpl<>(List.of(r)));

            UserBriefResponse user = new UserBriefResponse();
            user.setUserID(42);
            user.setFullName("Nguyễn Văn A");
            user.setAvatar("https://example.com/avatar.jpg");
            user.setEmail("a@example.com");
            when(iamClient.getUserById(42)).thenReturn(user);

            Page<TourReviewListResponse> page = service.getReviewsByTour("TOUR-XYZ",
                    PageRequest.of(0, 5));

            assertThat(page.getContent()).hasSize(1);
            TourReviewListResponse item = page.getContent().get(0);
            assertThat(item.getReviewId()).isEqualTo(1);
            assertThat(item.getRating()).isEqualTo(5);
            assertThat(item.getUser().getFullName()).isEqualTo("Nguyễn Văn A");
            assertThat(item.getUser().getAvatar()).isEqualTo("https://example.com/avatar.jpg");
        }

        @Test
        @DisplayName("IAM FEIGN FAILS: user info gracefully falls back to 'Khách hàng'")
        void getReviewsByTour_iamDown_fallback() {
            Tour tour = makeTour(1, "TOUR-XYZ");
            Review r = makeSavedReview(2, 4, "Khá tốt!", tour, 11, 55);

            when(reviewRepository.findByTourCodeAndVisible(eq("TOUR-XYZ"), any()))
                    .thenReturn(new PageImpl<>(List.of(r)));
            when(iamClient.getUserById(55)).thenThrow(new RuntimeException("iam-service down"));

            Page<TourReviewListResponse> page = service.getReviewsByTour("TOUR-XYZ",
                    PageRequest.of(0, 5));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).getUser().getFullName()).isEqualTo("Khách hàng");
        }

        @Test
        @DisplayName("EMPTY TOUR: returns empty page without calling iam-service")
        void getReviewsByTour_emptyTour_emptyPage() {
            when(reviewRepository.findByTourCodeAndVisible(eq("TOUR-EMPTY"), any()))
                    .thenReturn(Page.empty());

            Page<TourReviewListResponse> page = service.getReviewsByTour("TOUR-EMPTY",
                    PageRequest.of(0, 5));

            assertThat(page.getContent()).isEmpty();
            verify(iamClient, never()).getUserById(anyInt());
        }

        @Test
        @DisplayName("MULTIPLE REVIEWS: each one independently enriched")
        void getReviewsByTour_multipleReviews_eachEnriched() {
            Tour tour = makeTour(1, "TOUR-MULTI");
            Review r1 = makeSavedReview(10, 5, "Rất hài lòng với dịch vụ!", tour, 1, 10);
            Review r2 = makeSavedReview(11, 3, "Ổn nhưng phòng nhỏ quá!", tour, 2, 20);

            when(reviewRepository.findByTourCodeAndVisible(eq("TOUR-MULTI"), any()))
                    .thenReturn(new PageImpl<>(List.of(r1, r2)));

            UserBriefResponse u1 = new UserBriefResponse();
            u1.setUserID(10); u1.setFullName("User A");
            UserBriefResponse u2 = new UserBriefResponse();
            u2.setUserID(20); u2.setFullName("User B");
            when(iamClient.getUserById(10)).thenReturn(u1);
            when(iamClient.getUserById(20)).thenReturn(u2);

            Page<TourReviewListResponse> page = service.getReviewsByTour("TOUR-MULTI",
                    PageRequest.of(0, 5));

            assertThat(page.getContent()).hasSize(2);
            assertThat(page.getContent().get(0).getUser().getFullName()).isEqualTo("User A");
            assertThat(page.getContent().get(1).getUser().getFullName()).isEqualTo("User B");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. getReviewStatistics
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getReviewStatistics()")
    class GetReviewStatisticsTests {

        @Test
        @DisplayName("HAPPY PATH: 10 reviews — correct counts, percentages, average")
        void getStats_10reviews_correct() {
            // 4×5⭐, 3×4⭐, 2×3⭐, 1×2⭐, 0×1⭐  → avg = (20+12+6+2)/10 = 4.0
            when(reviewRepository.countByTourCode("TOUR-STATS")).thenReturn(10);
            when(reviewRepository.getAverageRatingByTourCode("TOUR-STATS")).thenReturn(4.0);
            when(reviewRepository.countByTourCodeAndRating("TOUR-STATS", 5)).thenReturn(4);
            when(reviewRepository.countByTourCodeAndRating("TOUR-STATS", 4)).thenReturn(3);
            when(reviewRepository.countByTourCodeAndRating("TOUR-STATS", 3)).thenReturn(2);
            when(reviewRepository.countByTourCodeAndRating("TOUR-STATS", 2)).thenReturn(1);
            when(reviewRepository.countByTourCodeAndRating("TOUR-STATS", 1)).thenReturn(0);

            ReviewStatisticsResponse stats = service.getReviewStatistics("TOUR-STATS");

            assertThat(stats.getTotalReviews()).isEqualTo(10);
            assertThat(stats.getAverageRating()).isEqualTo(4.0);
            assertThat(stats.getFiveStars()).isEqualTo(4);
            assertThat(stats.getFourStars()).isEqualTo(3);
            assertThat(stats.getThreeStars()).isEqualTo(2);
            assertThat(stats.getTwoStars()).isEqualTo(1);
            assertThat(stats.getOneStar()).isEqualTo(0);
            assertThat(stats.getFiveStarsPercent()).isEqualTo(40.0);
            assertThat(stats.getFourStarsPercent()).isEqualTo(30.0);
            assertThat(stats.getThreeStarsPercent()).isEqualTo(20.0);
            assertThat(stats.getTwoStarsPercent()).isEqualTo(10.0);
            assertThat(stats.getOneStarPercent()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("EMPTY TOUR: 0 reviews → all zeros, no division by zero")
        void getStats_noReviews_allZero() {
            when(reviewRepository.countByTourCode("TOUR-EMPTY")).thenReturn(0);
            when(reviewRepository.getAverageRatingByTourCode("TOUR-EMPTY")).thenReturn(null);
            when(reviewRepository.countByTourCodeAndRating(eq("TOUR-EMPTY"), anyInt())).thenReturn(0);

            ReviewStatisticsResponse stats = service.getReviewStatistics("TOUR-EMPTY");

            assertThat(stats.getTotalReviews()).isEqualTo(0);
            assertThat(stats.getAverageRating()).isEqualTo(0.0);
            assertThat(stats.getFiveStarsPercent()).isEqualTo(0.0);
            assertThat(stats.getFourStarsPercent()).isEqualTo(0.0);
            assertThat(stats.getThreeStarsPercent()).isEqualTo(0.0);
            assertThat(stats.getTwoStarsPercent()).isEqualTo(0.0);
            assertThat(stats.getOneStarPercent()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("NULL COUNTS: repository returns null → treated as 0")
        void getStats_nullCounts_treated0() {
            when(reviewRepository.countByTourCode("TOUR-NULL")).thenReturn(null);
            when(reviewRepository.getAverageRatingByTourCode("TOUR-NULL")).thenReturn(null);
            when(reviewRepository.countByTourCodeAndRating(eq("TOUR-NULL"), anyInt())).thenReturn(null);

            assertThatNoException().isThrownBy(() -> service.getReviewStatistics("TOUR-NULL"));
        }

        @Test
        @DisplayName("SINGLE REVIEW 5 STARS: 100% fiveStarsPercent")
        void getStats_singleFiveStar_100percent() {
            when(reviewRepository.countByTourCode("TOUR-ONE")).thenReturn(1);
            when(reviewRepository.getAverageRatingByTourCode("TOUR-ONE")).thenReturn(5.0);
            when(reviewRepository.countByTourCodeAndRating("TOUR-ONE", 5)).thenReturn(1);
            when(reviewRepository.countByTourCodeAndRating("TOUR-ONE", 4)).thenReturn(0);
            when(reviewRepository.countByTourCodeAndRating("TOUR-ONE", 3)).thenReturn(0);
            when(reviewRepository.countByTourCodeAndRating("TOUR-ONE", 2)).thenReturn(0);
            when(reviewRepository.countByTourCodeAndRating("TOUR-ONE", 1)).thenReturn(0);

            ReviewStatisticsResponse stats = service.getReviewStatistics("TOUR-ONE");

            assertThat(stats.getFiveStarsPercent()).isEqualTo(100.0);
            assertThat(stats.getFourStarsPercent()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("AVERAGE ROUNDING: 3.456 → 3.5 (1 decimal place)")
        void getStats_averageRounding() {
            when(reviewRepository.countByTourCode("TOUR-ROUND")).thenReturn(3);
            when(reviewRepository.getAverageRatingByTourCode("TOUR-ROUND")).thenReturn(3.456);
            when(reviewRepository.countByTourCodeAndRating(eq("TOUR-ROUND"), anyInt())).thenReturn(1);

            ReviewStatisticsResponse stats = service.getReviewStatistics("TOUR-ROUND");

            assertThat(stats.getAverageRating()).isEqualTo(3.5);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Coin calculation edge cases (via submitReview)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Coin point calculation boundaries")
    class CoinPointTests {

        @BeforeEach
        void stubBase() {
            Tour tour = makeTour(10, "TOUR-01");
            lenient().when(tourRepository.findById(10)).thenReturn(Optional.of(tour));
            lenient().when(reviewRepository.existsByBookingId(anyInt())).thenReturn(false);
            lenient().when(bookingClient.getBookingById(anyInt()))
                    .thenAnswer(inv -> mockBookingBrief(inv.getArgument(0), 42, "BK-COIN"));
            lenient().when(reviewRepository.save(any())).thenAnswer(inv -> {
                Review r = inv.getArgument(0); r.setReviewID(1); return r;
            });
        }

        @Test @DisplayName("9 chars → 0 pts → no coins")
        void coins_9chars_0pts() throws IOException {
            ReviewRequest req = makeRequest(5, "123456789", 10, 701);
            service.submitReview(req, null);
            verify(iamClient, never()).addCoins(anyInt(), any());
        }

        @Test @DisplayName("10 chars, no image → 5 pts → 5000 coins")
        void coins_10chars_noImg_5pts() throws IOException {
            ReviewRequest req = makeRequest(5, "1234567890", 10, 702);
            service.submitReview(req, null);
            verify(iamClient).addCoins(eq(42), eq(new BigDecimal("5000")));
        }

        @Test @DisplayName("10 chars, 1 image → 7 pts → 7000 coins")
        void coins_10chars_1img_7pts() throws IOException {
            when(fileStorageService.saveFile(any())).thenReturn("/uploads/review-images/x.jpg");
            ReviewRequest req = makeRequest(5, "1234567890", 10, 703);
            service.submitReview(req, List.of(mockFile("x.jpg")));
            verify(iamClient).addCoins(eq(42), eq(new BigDecimal("7000")));
        }

        @Test @DisplayName("10 chars, 3 images → 10 pts → 10000 coins (≥2 images capped at 10)")
        void coins_10chars_3imgs_10pts() throws IOException {
            when(fileStorageService.saveFile(any()))
                    .thenReturn("/uploads/review-images/a.jpg")
                    .thenReturn("/uploads/review-images/b.jpg")
                    .thenReturn("/uploads/review-images/c.jpg");
            ReviewRequest req = makeRequest(5, "1234567890", 10, 704);
            service.submitReview(req, List.of(mockFile("a.jpg"), mockFile("b.jpg"), mockFile("c.jpg")));
            verify(iamClient).addCoins(eq(42), eq(new BigDecimal("10000")));
        }
    }
}
