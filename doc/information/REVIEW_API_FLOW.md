# Review Feature — API Flow & Function Guide

## 1. Tổng quan luồng

```
Browser / Frontend
       │
       ▼  HTTP (qua API Gateway :8080)
  api-gateway
       │
       ├──► tour-catalog-service :8082   (review endpoints, tour endpoints)
       │         │
       │         ├── BookingFeignClient ──► booking-service :8083
       │         └── IamFeignClient     ──► iam-service     :8081
       │
       └──► booking-service :8083        (booking endpoints)
```

---

## 2. POST /api/reviews — Gửi đánh giá

### 2.1 Frontend gửi gì

```
POST /api/reviews
Content-Type: multipart/form-data

rating    = "5"
comment   = "Chuyến đi rất tuyệt vời!"
tourID    = "10"
bookingID = "100"
userId    = "42"          ← optional (backend tự lấy từ booking-service)
images    = [file1, file2]  ← optional
```

### 2.2 Luồng chi tiết qua từng hàm

```
ReviewController.submitReview()
│  ├─ Parse multipart fields → ReviewRequest
│  └─ Gọi reviewService.submitReview(req, images)
         │
         ▼
ReviewServiceImpl.submitReview(ReviewRequest, List<MultipartFile>)
│
├── [1] Kiểm tra rating hợp lệ (1–5)
│       Nếu sai → throw IllegalArgumentException("Rating must be between 1 and 5")
│
├── [2] TourRepository.findById(req.getTourID())
│       Nếu không tìm thấy → throw RuntimeException("Tour not found: X")
│
├── [3] ReviewRepository.existsByBookingId(req.getBookingID())
│       Nếu đã tồn tại → throw IllegalStateException("Review already submitted for booking: X")
│
├── [4] BookingFeignClient.getBookingById(bookingID)
│       → GET /api/bookings/{bookingID} trên booking-service
│       Kết quả: BookingBriefResponse { bookingID, bookingCode, bookingStatus, userId }
│       Nếu Feign thất bại → warn log, dùng userId từ request làm fallback
│
├── [5] Tạo Review entity:
│       review.rating, review.comment, review.bookingId, review.userId, review.tour
│
├── [6] Vòng lặp qua images:
│       FileStorageService.saveFile(file)
│       → Ghi file vào uploads/review-images/<uuid>.<ext>
│       → Trả về URL: "/uploads/review-images/<uuid>.<ext>"
│       → Tạo ImageReview entity, gắn vào review.images
│
├── [7] calculateCoinPoints(commentLength, imageCount)
│       comment.length < 10  → 0 điểm
│       comment.length ≥ 10, 0 ảnh → 5 điểm
│       comment.length ≥ 10, 1 ảnh → 7 điểm
│       comment.length ≥ 10, ≥2 ảnh → 10 điểm
│
├── [8] ReviewRepository.save(review)
│       → INSERT reviews + INSERT image_reviews (cascade)
│
├── [9] BookingFeignClient.updateBookingStatus(bookingID, "REVIEWED")
│       → PATCH /api/bookings/{bookingID}/status?status=REVIEWED
│       [fire-and-forget: lỗi được log, KHÔNG rollback review]
│
├── [10] IamFeignClient.addCoins(userId, coinPoints × 1000)
│       → POST /api/users/{userId}/coins?amount=X
│       [fire-and-forget: lỗi được log, KHÔNG rollback review]
│
└── [11] toResponse(saved) → ReviewResponse
         { reviewID, rating, comment, bookingCode, tourCode, imageUrls }
```

### 2.3 Hàm trong booking-service nhận PATCH

```
BookingController.updateBookingStatus(bookingID, status)
└── BookingService.updateBookingStatus(bookingID, status)
        └── BookingServiceImpl.updateBookingStatus()
                ├── BookingRepository.findById(bookingID)
                │       Nếu không có → throw RuntimeException("Booking not found: X")
                ├── BookingStatus.valueOf(status.toUpperCase())
                │       Nếu sai enum → throw IllegalArgumentException("Invalid booking status: X")
                ├── booking.setBookingStatus(newStatus)
                └── BookingRepository.save(booking)
```

### 2.4 Hàm trong iam-service nhận addCoins

```
UserController.addCoins(userID, amount)
└── UserService.addCoins(userId, amount)
        └── UserServiceImpl.addCoins()
                ├── UserRepository.findById(userId)
                ├── user.coinBalance += amount
                └── UserRepository.save(user)
```

### 2.5 Response trả về

```json
HTTP 201 Created
{
  "reviewID": 5,
  "rating": 5,
  "comment": "Chuyến đi rất tuyệt vời!",
  "bookingCode": "BK-ABC123",
  "tourCode": "TOUR-HAN-01",
  "imageUrls": [
    "/uploads/review-images/uuid1.jpg",
    "/uploads/review-images/uuid2.jpg"
  ]
}
```

---

## 3. GET /api/reviews/{bookingID} — Xem đánh giá đã gửi

### 3.1 Mục đích
Frontend dùng để hiển thị lại đánh giá mà người dùng đã gửi (ViewReviewModal).

### 3.2 Luồng

```
ReviewController.getReview(bookingID)
└── ReviewService.getReviewByBookingId(bookingID)
        └── ReviewServiceImpl.getReviewByBookingId()
                ├── ReviewRepository.findByBookingId(bookingID)
                │       SELECT * FROM reviews WHERE booking_id = ?
                │       Nếu không tìm thấy → throw RuntimeException("Review not found for booking: X")
                │
                └── toResponse(review)
                        ├── review.reviewID, rating, comment
                        ├── review.tour.tourCode
                        ├── review.bookingId → bookingCode (fallback: String.valueOf(bookingId))
                        └── review.images → List<String> imageUrls
```

### 3.3 Response

```json
HTTP 200 OK
{
  "reviewID": 5,
  "rating": 4,
  "comment": "Hướng dẫn viên nhiệt tình",
  "bookingCode": "BK-ABC123",
  "tourCode": "TOUR-HAN-01",
  "imageUrls": ["/uploads/review-images/abc.jpg"]
}
```

---

## 4. GET /api/reviews/tour/{tourCode}?page=0&size=5 — Danh sách đánh giá của tour

### 4.1 Mục đích
Hiển thị trang đánh giá trong chi tiết tour (TourReviews.jsx).

### 4.2 Luồng

```
ReviewController.getReviewsByTour(tourCode, page, size)
├── Tạo PageRequest.of(page, size, Sort.by("createdAt").descending())
└── ReviewService.getReviewsByTour(tourCode, pageable)
        └── ReviewServiceImpl.getReviewsByTour()
                ├── ReviewRepository.findByTourCodeAndVisible(tourCode, pageable)
                │       JPQL:
                │       SELECT DISTINCT r FROM Review r
                │       LEFT JOIN FETCH r.images
                │       WHERE r.tour.tourCode = :tourCode AND r.isVisible = true
                │       (ORDER BY được xử lý bởi Pageable)
                │
                └── page.map(this::toListResponse)
                        └── toListResponse(review)
                                ├── reviewId, rating, comment, createdAt, images[]
                                └── IamFeignClient.getUserById(review.userId)
                                        → GET /api/users/{userId} trên iam-service
                                        → UserBriefResponse { userID, fullName, avatar, email }
                                        Nếu Feign thất bại → user.fullName = "Khách hàng"
```

### 4.3 Response

```json
HTTP 200 OK
{
  "content": [
    {
      "reviewId": 5,
      "rating": 5,
      "comment": "Tuyệt vời!",
      "createdAt": "2026-04-30T15:00:00",
      "user": {
        "userId": 42,
        "fullName": "Nguyễn Văn A",
        "avatar": "https://...",
        "email": "a@example.com"
      },
      "images": ["/uploads/review-images/abc.jpg"]
    }
  ],
  "totalPages": 3,
  "totalElements": 15,
  "number": 0,
  "size": 5
}
```

---

## 5. GET /api/reviews/tour/{tourCode}/statistics — Thống kê đánh giá

### 5.1 Mục đích
Hiển thị điểm trung bình và thanh tiến trình theo sao trong TourReviews.jsx.

### 5.2 Luồng

```
ReviewController.getReviewStatistics(tourCode)
└── ReviewService.getReviewStatistics(tourCode)
        └── ReviewServiceImpl.getReviewStatistics()
                │
                ├── ReviewRepository.countByTourCode(tourCode)
                │       SELECT COUNT(r) FROM Review r
                │       WHERE r.tour.tourCode = :tourCode AND r.isVisible = true
                │
                ├── ReviewRepository.getAverageRatingByTourCode(tourCode)
                │       SELECT AVG(r.rating) FROM Review r
                │       WHERE r.tour.tourCode = :tourCode AND r.isVisible = true
                │
                ├── ReviewRepository.countByTourCodeAndRating(tourCode, 5)  → fiveStars
                ├── ReviewRepository.countByTourCodeAndRating(tourCode, 4)  → fourStars
                ├── ReviewRepository.countByTourCodeAndRating(tourCode, 3)  → threeStars
                ├── ReviewRepository.countByTourCodeAndRating(tourCode, 2)  → twoStars
                ├── ReviewRepository.countByTourCodeAndRating(tourCode, 1)  → oneStar
                │
                └── Tính phần trăm:
                        xStarsPercent = round(xStars * 100.0 / total, 1 decimal)
                        Nếu total = 0 → tất cả percent = 0.0 (tránh chia cho 0)
```

### 5.3 Response

```json
HTTP 200 OK
{
  "averageRating": 4.2,
  "totalReviews": 10,
  "fiveStars": 4,
  "fourStars": 3,
  "threeStars": 2,
  "twoStars": 1,
  "oneStar": 0,
  "fiveStarsPercent": 40.0,
  "fourStarsPercent": 30.0,
  "threeStarsPercent": 20.0,
  "twoStarsPercent": 10.0,
  "oneStarPercent": 0.0
}
```

---

## 6. GET /api/bookings/{bookingID} — Lấy thông tin booking (internal)

### 6.1 Ai gọi
`ReviewServiceImpl.submitReview()` gọi qua `BookingFeignClient.getBookingById()`.

### 6.2 Luồng

```
BookingController.getBookingById(bookingID)
└── BookingService.getBookingById(bookingID)
        └── BookingServiceImpl.getBookingById()
                ├── BookingRepository.findById(bookingID)
                │       Nếu không có → throw RuntimeException("Booking not found: X")
                └── return new BookingBriefResponse(
                            booking.bookingID,
                            booking.bookingCode,
                            booking.bookingStatus.name(),   ← null-safe
                            booking.userId
                        )
```

---

## 7. FileStorageService — Lưu ảnh nội bộ

```
FileStorageService.saveFile(MultipartFile file)
├── Lấy extension từ originalFilename  (e.g., ".jpg")
├── Tạo tên file ngẫu nhiên: UUID.randomUUID() + ext
├── Tạo thư mục nếu chưa có: uploads/review-images/
├── Files.copy(file.getInputStream(), destPath, REPLACE_EXISTING)
└── Trả về: "/uploads/review-images/<uuid>.jpg"
```

File được serve bởi Spring's static resource handler:
```yaml
spring.web.resources.static-locations: file:uploads/, classpath:/static/
```
→ URL `/uploads/review-images/abc.jpg` → file tại `uploads/review-images/abc.jpg` trong container.

---

## 8. Bảng tổng hợp — Mỗi endpoint đi qua hàm nào

| Endpoint | Controller | Service | Repository | Feign |
|---|---|---|---|---|
| `POST /api/reviews` | `ReviewController.submitReview()` | `ReviewServiceImpl.submitReview()` | `existsByBookingId`, `save` | Booking `getBookingById`, `updateBookingStatus`; Iam `addCoins` |
| `GET /api/reviews/{bookingID}` | `ReviewController.getReview()` | `ReviewServiceImpl.getReviewByBookingId()` | `findByBookingId` | — |
| `GET /api/reviews/tour/{tourCode}` | `ReviewController.getReviewsByTour()` | `ReviewServiceImpl.getReviewsByTour()` | `findByTourCodeAndVisible` | Iam `getUserById` (per review) |
| `GET /api/reviews/tour/{tourCode}/statistics` | `ReviewController.getReviewStatistics()` | `ReviewServiceImpl.getReviewStatistics()` | `countByTourCode`, `getAverageRating`, `countByTourCodeAndRating` ×5 | — |
| `GET /api/bookings/{bookingID}` | `BookingController.getBookingById()` | `BookingServiceImpl.getBookingById()` | `findById` | — |
| `PATCH /api/bookings/{bookingID}/status` | `BookingController.updateBookingStatus()` | `BookingServiceImpl.updateBookingStatus()` | `findById`, `save` | — |

---

## 9. Trạng thái booking liên quan đến review

```
PAID ──(sau khi tour kết thúc)──► nút "Đánh giá" hiện ra (frontend)
  │
  ▼  user bấm + gửi form
POST /api/reviews
  │
  ├── review được lưu vào DB
  ├── booking.status → REVIEWED  (qua Feign, fire-and-forget)
  └── user.coinBalance += coinPoints × 1000

REVIEWED ──► nút "Xem đánh giá" hiện ra  (frontend gọi GET /api/reviews/{bookingID})
```

---

## 10. Test Coverage

### booking-service — BookingServiceImplTest (41 tests)

| Nested class | Số test | Nội dung |
|---|---|---|
| `CancelBookingTests` | 12 | Hủy tour → coin refund, fee tiers, IAM down, notification fail |
| `SubmitRefundRequestTests` | 7 | Bank refund path, duplicate, invalid status |
| `FeeTierBoundaryTests` | 8 | Ranh giới phí: 16/15/6/5/3/2/0/-1 ngày |
| `GetBookingsByUserTests` | 6 | Filter theo status, refund info, payment mapping |
| **`GetBookingByIdTests`** | **3** | Happy path, not found, null status |
| **`UpdateBookingStatusTests`** | **5** | REVIEWED, lowercase, not found, invalid, all valid statuses |

### tour-catalog-service — ReviewServiceImplTest (29 tests)

| Nested class | Số test | Nội dung |
|---|---|---|
| `SubmitReviewTests` | 13 | Happy path (0/1/2 ảnh), coin short comment, rating invalid, tour not found, duplicate, Feign failures |
| `GetReviewByBookingIdTests` | 3 | Found, not found, no images |
| `GetReviewsByTourTests` | 4 | User info enriched, IAM down fallback, empty tour, multiple reviews |
| `GetReviewStatisticsTests` | 5 | 10 reviews, empty, null counts, 100% single star, average rounding |
| `CoinPointTests` | 4 | Boundary: 9/10 chars, 10+1img, 10+3imgs |
