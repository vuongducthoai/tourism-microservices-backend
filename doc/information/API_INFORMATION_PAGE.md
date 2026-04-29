# API Documentation — /information Page

All requests go through the API Gateway at `http://localhost:8080`.

---

## 1. GET /api/users/{userID}

**Service:** iam-service  
**Purpose:** Fetch user profile for the /information page header and form.

**Response:**
```json
{
  "userID": 1,
  "fullName": "Nguyễn Văn A",
  "phone": "0901234567",
  "dateOfBirth": "1990-05-20",
  "email": "user@gmail.com",
  "coinBalance": 500.00,
  "avatar": "https://res.cloudinary.com/...",
  "status": true,
  "role": "CUSTOMER"
}
```

---

## 2. PUT /api/users/{userID}

**Service:** iam-service  
**Content-Type:** `multipart/form-data`  
**Purpose:** Update user name, phone, date of birth, and/or avatar.

**Request parts:**
| Field | Type | Required |
|---|---|---|
| fullName | text | No |
| phone | text | No — must be 10-11 digits starting with 0 |
| dateOfBirth | text (yyyy-MM-dd) | No |
| avatar | file | No — uploaded to Cloudinary `tourism_avatars` |

**Logic:**
- Validates phone format: `^0\d{9,10}$`
- Validates phone uniqueness across users
- Parses `dateOfBirth` as `LocalDate` via `DateTimeFormatter.ISO_LOCAL_DATE`
- If `avatar` file provided → uploads to Cloudinary → stores `secure_url`
- Returns updated `UserDetailResponse`

---

## 3. GET /api/favorite-tours/user/{userId}

**Service:** tour-catalog-service  
**Purpose:** Get all tours favorited by a user. Used in the "Yêu thích" tab.

**Response:** `TourSearchResponse[]`
```json
[
  {
    "tourID": 1,
    "tourCode": "HN-HL-3N2D",
    "tourName": "Hà Nội - Hạ Long 3 Ngày 2 Đêm",
    "startPointName": "Hà Nội",
    "duration": "3 Ngày 2 Đêm",
    "transportation": "Xe khách",
    "money": 2800000,
    "image": "https://...",
    "isFavorite": true,
    "departureDates": [
      { "departureID": 1, "departureDate": "2027-03-10" }
    ]
  }
]
```

---

## 4. POST /api/favorite-tours/add?userId=&tourId=

**Service:** tour-catalog-service  
**Purpose:** Add a tour to user's favorites.  
**Logic:** Idempotent — silently skips if already favorited.  
**Response:** `"Added to favorites"` (200)

---

## 5. DELETE /api/favorite-tours/remove?userId=&tourId=

**Service:** tour-catalog-service  
**Purpose:** Remove a tour from user's favorites.  
**Response:** `"Removed from favorites"` (200)

---

## 6. GET /api/bookings/user/{userID}?bookingStatus=

**Service:** booking-service  
**Purpose:** Fetch all bookings for a user. Used in "Lịch sử giao dịch" tab.

**Query param:** `bookingStatus` (optional) — one of:
`PENDING_PAYMENT | OVERDUE_PAYMENT | PENDING_CONFIRMATION | PAID | CANCELLED | PENDING_REVIEW | REVIEWED | PENDING_REFUND`

**Logic:**
1. Queries `bookings` table by `userId` (filtered by `bookingStatus` if provided)
2. For each booking: calls `tour-catalog-service GET /api/departures/{departureId}` → fills `tourCode`, `tourName`, `departureDate`, `image`, `duration`
3. For each booking: calls `payment-service GET /api/payment/by-booking/{bookingId}` → fills `timeLimit`, `paymentMethod`, `paymentStatus`
4. Maps passengers and refund info

**Response:** `BookingResponse[]` — see fields in `BookingResponse.java`

---

## 7. POST /api/bookings/cancel

**Service:** booking-service  
**Purpose:** Cancel a booking.

**Request body:**
```json
{ "bookingID": 5, "cancelReason": "Thay đổi kế hoạch" }
```

**Logic (from monolith):**
| Current status | Action |
|---|---|
| PENDING_PAYMENT | → CANCELLED, refundAmount=0 |
| PENDING_CONFIRMATION / PAID | Calculate fee then: refundAmount > 0 → PENDING_REFUND, else → CANCELLED |

**Cancellation fee by days until departure:**
| Days | Fee % |
|---|---|
| > 15 | 10% |
| > 5 | 50% |
| > 2 | 70% |
| >= 0 | 90% |
| past | 100% |

`refundAmount = totalPrice × (1 - feePercent / 100)`

---

## 8. POST /api/bookings/refund-request/{bookingID}

**Service:** booking-service  
**Purpose:** Submit bank account info for refund after cancellation.

**Request body:**
```json
{ "accountName": "NGUYEN VAN A", "accountNumber": "123456789", "bank": "Vietcombank" }
```

**Logic:**
- Booking must be in `PENDING_REFUND` status
- Creates `RefundInformation` with `refundStatus = "PENDING"` (admin processes manually)
- Cannot submit twice

---

## 9. POST /api/reviews

**Service:** tour-catalog-service  
**Content-Type:** `multipart/form-data`  
**Purpose:** Submit a review for a completed tour.

**Request parts:**
| Field | Type | Required |
|---|---|---|
| rating | text (1-5) | Yes |
| comment | text | Yes |
| tourID | text | Yes |
| bookingID | text | Yes |
| userId | text | Yes |
| images | file[] | No — uploaded to Cloudinary `tourism_reviews` |

**Logic:**
1. Validates rating (1-5)
2. Checks tour exists
3. Prevents duplicate review per booking (`bookingId` is unique)
4. Uploads images to Cloudinary
5. Saves `Review` + `ImageReview` entities
6. Returns `ReviewResponse` (201)

> **TODO:** When booking-service is fully integrated, validate `bookingStatus = PAID` before allowing review. Currently skipped.

**Response:**
```json
{
  "reviewID": 1,
  "rating": 5,
  "comment": "Tuyệt vời!",
  "bookingCode": "1",
  "tourCode": "HN-HL-3N2D",
  "imageUrls": ["https://res.cloudinary.com/..."]
}
```

---

## 10. GET /api/reviews/{bookingID}

**Service:** tour-catalog-service  
**Purpose:** Check if a review already exists for a given booking (prevents double review UI).

**Returns 404** if not reviewed yet.

---

## 11. GET /api/departures/{departureId} (Internal)

**Service:** tour-catalog-service (internal endpoint)  
**Used by:** booking-service via Feign  
**Purpose:** Resolve tour + departure info by departure ID.

**Response:**
```json
{
  "departureID": 1,
  "departureDate": "2027-03-10T00:00",
  "tourID": 1,
  "tourCode": "HN-HL-3N2D",
  "tourName": "Hà Nội - Hạ Long 3 Ngày 2 Đêm",
  "image": "https://...",
  "duration": "3 Ngày 2 Đêm"
}
```

---

## 12. GET /api/payment/by-booking/{bookingId} (Internal)

**Service:** payment-service (internal endpoint)  
**Used by:** booking-service via Feign  
**Purpose:** Get payment info (timeLimit, amount, method) for a booking.

Returns **404** if no payment yet (booking still PENDING_PAYMENT).

---

## Frontend Mock (Development)

`d:\HK8\tourism_frontend\client-side\.env.local`:
```env
REACT_APP_DEV_USER_ID=1
```

When this is set, `AuthContext.jsx` skips JWT auth and mounts a mock user with `id/userId/userID = 1`.  
To disable the mock and use real auth, remove or set `REACT_APP_DEV_USER_ID=0`.

---

## Test Checklist

| API | Status |
|---|---|
| GET /api/users/1 | ✅ Tested |
| GET /api/bookings/user/3 | ✅ Tested (returns 2 bookings with tour + payment data) |
| GET /api/favorite-tours/user/1 | ✅ Tested (returns empty array) |
| POST /api/favorite-tours/add | ⬜ Not tested |
| DELETE /api/favorite-tours/remove | ⬜ Not tested |
| PUT /api/users/{id} | ⬜ Not tested |
| POST /api/reviews | ⬜ Not tested |
| GET /api/reviews/{bookingID} | ⬜ Not tested |
| POST /api/bookings/cancel | ⬜ Not tested |
| POST /api/bookings/refund-request/{id} | ⬜ Not tested |
