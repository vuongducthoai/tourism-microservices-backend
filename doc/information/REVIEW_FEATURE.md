# Review Feature — Microservices Implementation Report

## 0. Test Results (latest run)

| Service | Test class | Total | Pass | Fail |
|---|---|---|---|---|
| booking-service | `BookingServiceImplTest` | **41** | ✅ 41 | 0 |
| tour-catalog-service | `ReviewServiceImplTest` | **29** | ✅ 29 | 0 |

No restart needed — both containers are already running with the new JARs.

---

## 1. Overview

Implements the full review flow in the microservices architecture, replicating the monolith's review logic with these key differences:
- **No Cloudinary** — images are stored on local disk inside the container.
- **No WebSocket notification** to admin after review (low priority, can add later).
- **Cross-service calls via OpenFeign** — tour-catalog-service calls booking-service and iam-service.

---

## 2. Services Modified / Created

### 2.1 `booking-service` (minor additions)

| File | Change |
|------|--------|
| `dto/response/BookingBriefResponse.java` | **NEW** — lightweight DTO: `bookingID`, `bookingCode`, `bookingStatus`, `userId` |
| `service/BookingService.java` | Added `getBookingById(Integer)` and `updateBookingStatus(Integer, String)` |
| `service/impl/BookingServiceImpl.java` | Implemented both new methods |
| `controller/BookingController.java` | Added `GET /api/bookings/{bookingID}` and `PATCH /api/bookings/{bookingID}/status` |

### 2.2 `tour-catalog-service` (main changes)

| File | Change |
|------|--------|
| `feign/BookingFeignClient.java` | **NEW** — calls `GET /api/bookings/{id}` and `PATCH /api/bookings/{id}/status` |
| `feign/IamFeignClient.java` | **NEW** — calls `GET /api/users/{id}` and `POST /api/users/{id}/coins` |
| `feign/dto/UserBriefResponse.java` | **NEW** — `userID`, `fullName`, `avatar`, `email`, `coinBalance` |
| `service/FileStorageService.java` | **NEW** — saves files to `uploads/review-images/<uuid>.<ext>`, returns `/uploads/review-images/...` URL |
| `dto/response/TourReviewListResponse.java` | **NEW** — for `GET /api/reviews/tour/{tourCode}` pagination |
| `dto/response/ReviewStatisticsResponse.java` | **NEW** — for `GET /api/reviews/tour/{tourCode}/statistics` |
| `repository/ReviewRepository.java` | Added 4 new query methods (tour listing, avg rating, count by star, total count) |
| `service/ReviewService.java` | Added `getReviewsByTour()` and `getReviewStatistics()` |
| `service/impl/ReviewServiceImpl.java` | Full rewrite — replaced Cloudinary with local storage, added Feign calls, coin logic |
| `controller/ReviewController.java` | Added 2 new endpoints; made `userId` optional |
| `resources/application.yml` | Added `spring.web.resources.static-locations`, `app.upload.dir`, booking-service Feign timeout |

---

## 3. API Endpoints

### booking-service (new internal endpoints)

```
GET  /api/bookings/{bookingID}
     → BookingBriefResponse { bookingID, bookingCode, bookingStatus, userId }
     Used by tour-catalog-service to get userId and bookingCode during review creation.

PATCH /api/bookings/{bookingID}/status?status=REVIEWED
     → 200 OK
     Called by tour-catalog-service after a review is saved.
```

### tour-catalog-service (all review endpoints)

```
POST /api/reviews
     Content-Type: multipart/form-data
     Fields: rating (int), comment (string), tourID (int), bookingID (int),
             userId (int, optional), images[] (files, optional)
     → 201 Created: ReviewResponse

GET  /api/reviews/{bookingID}
     → 200 OK: ReviewResponse
     → 404 if no review for that booking

GET  /api/reviews/tour/{tourCode}?page=0&size=5
     → 200 OK: Page<TourReviewListResponse>
     Each item: reviewId, rating, comment, createdAt,
                user { userId, fullName, avatar, email }, images[]

GET  /api/reviews/tour/{tourCode}/statistics
     → 200 OK: ReviewStatisticsResponse
     Fields: averageRating, totalReviews,
             fiveStars/fourStars/threeStars/twoStars/oneStar,
             fiveStarsPercent/.../oneStarPercent
```

---

## 4. Review Submission Flow

```
Frontend (ReviewComponent)
  │
  ▼
POST /api/reviews  (multipart/form-data via API Gateway :8080)
  │
  ▼
tour-catalog-service ReviewServiceImpl.submitReview()
  │
  ├── 1. Validate rating (1–5)
  ├── 2. Find Tour by tourID  (local DB)
  ├── 3. Check duplicate: existsByBookingId()
  │
  ├── 4. Feign → booking-service GET /api/bookings/{bookingID}
  │         → get userId, bookingCode
  │
  ├── 5. Build Review entity (bookingId, userId, tour)
  │
  ├── 6. Save images to local disk via FileStorageService
  │         Path: uploads/review-images/<uuid>.ext
  │         URL:  /uploads/review-images/<uuid>.ext
  │
  ├── 7. Calculate coin reward points:
  │         comment < 10 chars  → 0 pts
  │         comment ≥ 10, no images → 5 pts
  │         comment ≥ 10, 1 image  → 7 pts
  │         comment ≥ 10, 2+ images → 10 pts
  │
  ├── 8. Save Review (cascade → ImageReview rows)
  │
  ├── 9. [fire-and-forget] Feign → booking-service PATCH /status?status=REVIEWED
  │
  └── 10. [fire-and-forget] Feign → iam-service POST /coins?amount=(points × 1000)
              → User coin balance += points × 1,000 VND
```

---

## 5. Coin Reward System

| Comment length | Images | Points | Coins added |
|---|---|---|---|
| < 10 chars | any | 0 | 0 |
| ≥ 10 chars | 0 | 5 | 5,000 |
| ≥ 10 chars | 1 | 7 | 7,000 |
| ≥ 10 chars | ≥ 2 | 10 | 10,000 |

Coins are added via `POST /api/users/{userId}/coins?amount=X` on iam-service.
Coin failures are logged but do **not** roll back the review transaction.

---

## 6. Image Storage

- **Storage path:** `uploads/review-images/<uuid>.<ext>` (relative to the service's working directory inside the container)
- **Served at:** `http://localhost:8082/uploads/review-images/<uuid>.<ext>` (direct) or via gateway at `/uploads/review-images/<uuid>.<ext>`
- **Config:** `app.upload.dir=${UPLOAD_DIR:uploads}` — can override via Docker env var `UPLOAD_DIR=/data/uploads` for a persistent volume mount
- **Spring static resources:** `spring.web.resources.static-locations: file:uploads/,classpath:/static/`

> **Production note:** Mount a Docker volume to persist images across container restarts:
> ```yaml
> volumes:
>   - ./review-images:/app/uploads/review-images
> ```

---

## 7. Database Schema (auto-created by Hibernate)

### `reviews` table
```sql
CREATE TABLE reviews (
    review_id    SERIAL PRIMARY KEY,
    rating       INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment      TEXT,
    is_visible   BOOLEAN DEFAULT true,
    booking_id   INTEGER NOT NULL UNIQUE,   -- FK to booking-service (cross-service, no constraint)
    tour_id      INTEGER NOT NULL REFERENCES tours(tour_id),
    user_id      INTEGER NOT NULL,          -- FK to iam-service (cross-service, no constraint)
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    is_deleted   BOOLEAN DEFAULT false
);
```

### `image_reviews` table
```sql
CREATE TABLE image_reviews (
    image_review_id SERIAL PRIMARY KEY,
    image_url       VARCHAR(255),
    review_id       INTEGER REFERENCES reviews(review_id),
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    is_deleted      BOOLEAN DEFAULT false
);
```

---

## 8. Frontend Compatibility

No frontend files were changed. The existing components call these exact endpoints:

| Component | API call |
|---|---|
| `ReviewComponent.jsx` | `POST /api/reviews` (FormData: rating, comment, tourID, bookingID, images[]) |
| `ViewReviewModal.jsx` | `GET /api/reviews/{bookingID}` |
| `TourReviews.jsx` | `GET /api/reviews/tour/{tourCode}?page=&size=` |
| `TourReviews.jsx` | `GET /api/reviews/tour/{tourCode}/statistics` |

Response field names match exactly what the frontend expects:
- `ReviewResponse`: `reviewID`, `rating`, `comment`, `bookingCode`, `tourCode`, `imageUrls`
- `TourReviewListResponse`: `reviewId`, `rating`, `comment`, `createdAt`, `user.fullName`, `user.avatar`, `images`
- `ReviewStatisticsResponse`: `averageRating`, `totalReviews`, `fiveStars`…`oneStar`, `fiveStarsPercent`…`oneStarPercent`

---

## 9. Error Handling

| Scenario | Behavior |
|---|---|
| Invalid rating (not 1–5) | `400 Bad Request` with message |
| Tour not found | `500` with "Tour not found: {id}" |
| Duplicate review for same booking | `500` with "Review already submitted for booking: {id}" |
| booking-service Feign fails | Warning logged, userId falls back to value from request body |
| iam-service coins Feign fails | Error logged, review is **not** rolled back |
| booking-service status update fails | Error logged, review is **not** rolled back |

---

## 10. Files Changed Summary

```
booking-service/
  src/main/java/com/tourism/booking/
    controller/BookingController.java           (modified)
    service/BookingService.java                 (modified)
    service/impl/BookingServiceImpl.java        (modified)
    dto/response/BookingBriefResponse.java      (NEW)

tour-catalog-service/
  src/main/java/com/tourism/tourcatalog/
    feign/
      BookingFeignClient.java                   (NEW)
      IamFeignClient.java                       (NEW)
      dto/UserBriefResponse.java                (NEW)
    service/
      FileStorageService.java                   (NEW)
      ReviewService.java                        (modified)
      impl/ReviewServiceImpl.java               (rewritten)
    repository/ReviewRepository.java            (modified)
    controller/ReviewController.java            (modified)
    dto/response/
      TourReviewListResponse.java               (NEW)
      ReviewStatisticsResponse.java             (NEW)
  src/main/resources/application.yml           (modified)
```
