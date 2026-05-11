# KẾ HOẠCH XÂY DỰNG ADMIN DASHBOARD — MICROSERVICES
## Future Travel Tourism — `http://localhost:3000/admin/dashboard`

---

## 1. HIỆN TRẠNG & VẤN ĐỀ

### 1.1 Root Cause của "Network Error" trên Dashboard

| Bước | Thực tế |
|---|---|
| Frontend gọi | `GET http://localhost:8080/api/admin/dashboard/statistics` |
| `axiosCustomize.js` base URL | `http://localhost:8080/api` |
| Path trong `dashboard.ts` | `/admin/dashboard/statistics` |
| API Gateway route hiện có | `/api/dashboard/**` → analytics-service ✅ |
| **Vấn đề thực sự** | analytics-service **không có** `DashboardController` → 404 |

> **Kết luận:** API Gateway đã route đúng. Chỉ cần implement dashboard endpoints trong `analytics-service`.
> Frontend path `/admin/dashboard/statistics` → full URL `/api/admin/dashboard/statistics` → **cần thêm route gateway** cho prefix `/api/admin/dashboard/**`.

### 1.2 Nguồn Logic Gốc — Monolith `Tourism_Backend`

| File | Dòng code | Vai trò |
|---|---|---|
| `DashboardController.java` | ~30 | 2 REST endpoints |
| `DashboardServiceImpl.java` | ~380 | Tổng hợp tất cả thống kê |
| `DashboardStatsDTO.java` | ~280 | 15 inner class response model |
| `BookingRepository.java` | +8 queries | Revenue, hot tours, activities |
| `UserRepository.java` | +6 queries | User growth, counts by role |
| `TourRepository.java` | +4 queries | Performance, departures |
| `ReviewRepository.java` | +1 query | Average rating |

### 1.3 Trạng Thái Microservices Hiện Tại

| Service | Port | DB | Trạng thái Dashboard |
|---|---|---|---|
| `iam-service` | 8081 | `iam_db` | Chưa có stats endpoint |
| `tour-catalog-service` | 8083 | `tour_catalog_db` | Chưa có stats endpoint |
| `booking-service` | 8084 | `booking_db` | Chưa có stats endpoint |
| `analytics-service` | 8087 | `analytics_db` | Có entity tables nhưng **không có DashboardController** |
| `api-gateway` | 8080 | — | Có route `/api/dashboard/**` nhưng thiếu `/api/admin/dashboard/**` |

**analytics-service đã có sẵn:**
- `BookingFeignClient`, `TourCatalogFeignClient` (dùng cho chatbot)
- Gemini AI key được cấu hình: `${GEMINI_API_KEY}`
- Entity tables: `daily_revenue_stats`, `tour_performance_stats`, `user_growth_stats`

---

## 2. KIẾN TRÚC GIẢI PHÁP

### 2.1 Luồng Dữ Liệu

```
Browser (React :3000)
  │
  │  GET /api/admin/dashboard/statistics
  │  GET /api/admin/dashboard/analysis
  ▼
API Gateway (:8080)
  │  Route mới: /api/admin/dashboard/** → analytics-service
  │  RewritePath: /api/admin/dashboard/{seg} → /api/dashboard/{seg}
  ▼
analytics-service (:8087)
  │  DashboardController
  │      ├─ GET /api/dashboard/statistics → DashboardStatsDTO
  │      └─ GET /api/dashboard/analysis   → AIAnalysis (Gemini)
  │
  │  DashboardServiceImpl (aggregator)
  │      │
  │      ├─ IamFeignClient (MỚI)
  │      │     └─ GET /api/admin/users/stats → UserStatsResponse
  │      │
  │      ├─ BookingFeignClient (MỞ RỘNG)
  │      │     └─ GET /api/admin/bookings/stats → BookingStatsResponse
  │      │
  │      └─ TourCatalogFeignClient (MỞ RỘNG)
  │            └─ GET /api/admin/tours/stats → TourStatsResponse
  ▼
iam-service (:8081)         booking-service (:8084)       tour-catalog-service (:8083)
AdminUserStatsController    AdminBookingStatsController   AdminTourStatsController
  └─ /api/admin/users/stats    └─ /api/admin/bookings/stats   └─ /api/admin/tours/stats
     Query iam_db                 Query booking_db               Query tour_catalog_db
```

### 2.2 Nguyên Tắc Thiết Kế

1. **Port nguyên logic từ monolith** — không thay đổi công thức tính, chỉ tái cấu trúc nguồn dữ liệu
2. **analytics-service là aggregator** — nhận raw data từ 3 service, build `DashboardStatsDTO`
3. **Mỗi service tự query DB của mình** — không cross-DB
4. **Frontend không thay đổi** — giữ nguyên `DashboardStatsDTO.ts` và `dashboard.ts`

---

## 3. CÁC FILE CẦN TẠO / SỬA

### 3.1 `iam-service` — Thêm User Stats Endpoint

#### **[TẠO MỚI]** `dto/response/stats/UserStatsResponse.java`
```
Fields:
  Long totalUsers          — COUNT WHERE role=CUSTOMER
  Long activeUsers         — COUNT WHERE role=CUSTOMER AND status=true
  Long lockedUsers         — COUNT WHERE role=CUSTOMER AND status=false
  Long newUsersToday       — COUNT createdAt IN today
  Long newUsersThisWeek    — COUNT createdAt IN last 7 days
  Long newUsersThisMonth   — COUNT createdAt IN last 30 days
  Long newUsersLastMonth   — COUNT createdAt IN [60-30 days ago] (để tính growth)
  List<DailyUserGrowthItem> dailyGrowth   — last 30 days chart data
  List<RecentUserItem>      recentUsers   — top 5 CUSTOMER mới nhất (cho RecentActivities)
  Long baseUserCountBefore30Days          — để tính cumulative total trong chart
```

#### **[TẠO MỚI]** `dto/response/stats/DailyUserGrowthItem.java`
```
  String date      — format yyyy-MM-dd
  Long newUsers
  Long totalUsers  — sẽ được tính cumulative trong analytics-service
```

#### **[TẠO MỚI]** `dto/response/stats/RecentUserItem.java`
```
  String fullName
  String email
  String createdAt  — ISO datetime string
```

#### **[SỬA]** `repository/UserRepository.java` — Thêm 6 queries
```java
Long countByRole(Role role)
Long countByStatusAndRole(Boolean status, Role role)
Long countByRoleAndCreatedAtBetween(Role role, LocalDateTime start, LocalDateTime end)
List<Object[]> getDailyNewUsersCounts(LocalDateTime start, LocalDateTime end, Role role)  // date, count
Long countByRoleAndCreatedAtBefore(Role role, LocalDateTime date)
List<User> findTop5ByRoleOrderByCreatedAtDesc(Role role)
```

#### **[TẠO MỚI]** `controller/AdminUserStatsController.java`
```
GET /api/admin/users/stats
→ Returns UserStatsResponse
→ @Tag("Admin Stats") @Operation(summary="User statistics for dashboard")
```

---

### 3.2 `booking-service` — Thêm Booking Stats Endpoint

#### **[TẠO MỚI]** `dto/response/stats/BookingStatsResponse.java`
```
Fields booking counts:
  Long totalBookings, paidBookings, pendingConfirmation
  Long pendingPayment, pendingRefund, cancelledBookings
  Long todayBookings, thisWeekBookings

Fields revenue:
  BigDecimal totalRevenue        — SUM where PAID
  BigDecimal pendingConfirmRevenue, pendingPayRevenue
  BigDecimal pendingRefundRevenue, cancelledRevenue
  BigDecimal todayRevenue, thisWeekRevenue
  BigDecimal thisMonthRevenue, lastMonthRevenue

Fields charts:
  List<DailyRevenueItem>        dailyRevenue       — last 30 days
  List<BookingStatusCountItem>  statusDistribution

Fields top tours & attention:
  List<HotTourRawItem>          hotTours           — top 5 (có tourCode từ TourDeparture)
  List<TourAttentionRawItem>    toursNeedingAttention

Fields recent activities:
  List<RecentBookingItem>       recentPendingConfirmation  — top 5
  List<RecentBookingItem>       recentRefundRequests       — top 5
```

#### **[TẠO MỚI]** `dto/response/stats/DailyRevenueItem.java`
```
  String date
  BigDecimal revenue
  Long bookingCount
```

#### **[TẠO MỚI]** `dto/response/stats/BookingStatusCountItem.java`
```
  String status
  Long count
  BigDecimal revenue
```

#### **[TẠO MỚI]** `dto/response/stats/HotTourRawItem.java`
```
  String tourCode
  String tourName
  Long bookingCount
  BigDecimal revenue
```
> Note: `tourName` lấy từ `DepartureInfoResponse` (đã có trong booking-service Feign)
> hoặc join trực tiếp qua `departureId` → gọi lại TourCatalogFeignClient trong stats service

#### **[TẠO MỚI]** `dto/response/stats/TourAttentionRawItem.java`
```
  String tourCode
  String tourName
  String reason        — REFUND_REQUEST | LOW_BOOKING
  String urgency       — HIGH | MEDIUM | LOW
```

#### **[TẠO MỚI]** `dto/response/stats/RecentBookingItem.java`
```
  String bookingCode
  String description
  String createdAt
  String type          — BOOKING | REFUND
  String severity      — WARNING | URGENT
```

#### **[SỬA]** `repository/BookingRepository.java` — Thêm 8 queries
```java
@Query("SELECT SUM(b.totalPrice) FROM Booking b WHERE b.bookingStatus = :status")
BigDecimal sumTotalPriceByStatus(BookingStatus status)

@Query("SELECT SUM(b.totalPrice) FROM Booking b WHERE b.bookingDate BETWEEN :start AND :end AND b.bookingStatus = :status")
BigDecimal sumRevenueByDateAndStatus(LocalDateTime start, LocalDateTime end, BookingStatus status)

@Query("SELECT CAST(b.bookingDate AS LocalDate), SUM(b.totalPrice), COUNT(b) FROM Booking b WHERE b.bookingDate BETWEEN :start AND :end AND b.bookingStatus = :status GROUP BY CAST(b.bookingDate AS LocalDate) ORDER BY CAST(b.bookingDate AS LocalDate)")
List<Object[]> getDailyRevenueCounts(LocalDateTime start, LocalDateTime end, BookingStatus status)

@Query("SELECT CAST(b.bookingStatus AS string), COUNT(b), SUM(b.totalPrice) FROM Booking b GROUP BY b.bookingStatus")
List<Object[]> getBookingStatusDistribution()

Long countByBookingStatus(BookingStatus status)

Long countByBookingDateBetween(LocalDateTime start, LocalDateTime end)

List<Booking> findTop5ByBookingStatusOrderByCreatedAtDesc(BookingStatus status)

@Query("...GROUP BY b.departureId ORDER BY COUNT(b.bookingID) DESC")
List<Object[]> getHotDeparturesByBookingCount(BookingStatus status, Pageable pageable)
```

#### **[TẠO MỚI]** `controller/AdminBookingStatsController.java`
```
GET /api/admin/bookings/stats
→ Returns BookingStatsResponse
```

---

### 3.3 `tour-catalog-service` — Thêm Tour Stats Endpoint

#### **[TẠO MỚI]** `dto/response/stats/TourStatsResponse.java`
```
  Long totalTours
  Long activeTours
  Long totalDepartures
  Long upcomingDepartures
  Double averageRating
  List<TourPerformanceItem> tourPerformance   — top 10 by avgRating
```

#### **[TẠO MỚI]** `dto/response/stats/TourPerformanceItem.java`
```
  String tourName
  Long bookings      — 0 (booking-service có data này, tour-catalog không có)
  BigDecimal revenue — 0
  Double rating      — AVG review.rating
```

#### **[SỬA]** `repository/TourRepository.java` — Thêm 3 queries
```java
Long countByStatus(Boolean status)

@Query("SELECT COUNT(td) FROM TourDeparture td")
Long countAllDepartures()

@Query("SELECT COUNT(td) FROM TourDeparture td WHERE td.departureDate > :now")
Long countUpcomingDepartures(LocalDateTime now)
```

#### **[SỬA]** `repository/ReviewRepository.java` — Thêm 1 query
```java
@Query("SELECT AVG(r.rating) FROM Review r WHERE r.isVisible = true")
Double calculateAverageRating()
```

#### **[TẠO MỚI]** `controller/AdminTourStatsController.java`
```
GET /api/admin/tours/stats
→ Returns TourStatsResponse
```

---

### 3.4 `analytics-service` — Dashboard Aggregator (PHẦN CHÍNH)

#### **[TẠO MỚI]** `dto/dashboard/DashboardStatsDTO.java`
Port nguyên từ `Tourism_Backend/DashboardStatsDTO.java` — 15 inner classes:
```
Root: userStats, revenueStats, bookingStats, tourStats, recentActivities, aiAnalysis, chartsData

Inner classes:
  UserStats       — totalUsers, activeUsers, lockedUsers, newUsers(Today/Week/Month), userGrowthRate, dailyGrowth
  RevenueStats    — totalRevenue, pendingConfirmation/Payment/Refund, cancelledRevenue,
                    todayRevenue, thisWeek/Month/LastMonth, revenueGrowthRate, dailyRevenue, revenueByTour
  BookingStats    — totalBookings, paidBookings, pending(Confirmation/Payment/Refund), cancelledBookings,
                    todayBookings, thisWeekBookings, conversionRate, statusDistribution
  TourStats       — totalTours, activeTours, totalDepartures, upcomingDepartures,
                    hotTours, toursNeedingAttention, averageRating
  RecentActivity  — type, description, timestamp, severity, relatedCode
  AIAnalysis      — summary, insights, predictions, recommendations
  ChartsData      — revenueChart, userGrowthChart, bookingStatusChart, tourPerformanceChart
  DailyUserGrowth — date, newUsers, totalUsers
  DailyRevenue    — date, revenue, bookingCount
  BookingStatusCount — status, count, revenue
  HotTour         — tourId, tourCode, tourName, bookingCount, revenue, averageRating
  TourNeedingAttention — tourId, tourCode, tourName, reason, urgency
  TourPerformance — tourName, bookings, revenue, rating
  Insight         — title, description, type (POSITIVE/NEUTRAL/NEGATIVE), priority
  Prediction      — metric, prediction, confidence, timeframe
  Recommendation  — title, description, action, impact
```

#### **[TẠO MỚI]** `dto/dashboard/feign/UserStatsResponse.java`
Nhận từ iam-service, chứa đủ data để build `UserStats` + `DailyUserGrowth` chart.

#### **[TẠO MỚI]** `dto/dashboard/feign/BookingStatsResponse.java`
Nhận từ booking-service, chứa đủ data để build `RevenueStats` + `BookingStats` + `HotTour` + `RecentActivity`.

#### **[TẠO MỚI]** `dto/dashboard/feign/TourStatsResponse.java`
Nhận từ tour-catalog-service, chứa đủ data để build `TourStats`.

#### **[TẠO MỚI]** `feign/IamFeignClient.java`
```java
@FeignClient(name = "iam-service")
public interface IamFeignClient {
    @GetMapping("/api/admin/users/stats")
    UserStatsResponse getUserStats();
}
```

#### **[SỬA]** `feign/BookingFeignClient.java` — Thêm 1 method
```java
@GetMapping("/api/admin/bookings/stats")
BookingStatsResponse getBookingStats();
```

#### **[SỬA]** `feign/TourCatalogFeignClient.java` — Thêm 1 method
```java
@GetMapping("/api/admin/tours/stats")
TourStatsResponse getTourStats();
```

#### **[TẠO MỚI]** `service/GeminiAIService.java` (interface)
```java
String generateDashboardSummary(String context)
List<DashboardStatsDTO.Insight> generateInsights(String context)
List<DashboardStatsDTO.Prediction> generatePredictions(String context)
List<DashboardStatsDTO.Recommendation> generateRecommendations(String context)
```

#### **[TẠO MỚI]** `service/impl/GeminiAIServiceImpl.java`
Port nguyên từ `Tourism_Backend/GeminiAIServiceImpl.java`:
- Dùng `${gemini.api.key}` (đã có trong `application.yml`)
- Model `gemini-2.0-flash`
- Prompt bằng tiếng Việt
- Parse JSON response với `ObjectMapper`

#### **[TẠO MỚI]** `service/DashboardService.java` (interface)
```java
DashboardStatsDTO getDashboardStatistics()
DashboardStatsDTO.AIAnalysis getDashboardAIAnalysis()
```

#### **[TẠO MỚI]** `service/impl/DashboardServiceImpl.java` — Logic chính
```
getDashboardStatistics():
  1. iamFeignClient.getUserStats()         → UserStatsResponse ur
  2. bookingFeignClient.getBookingStats()  → BookingStatsResponse br
  3. tourCatalogFeignClient.getTourStats() → TourStatsResponse tr
  4. buildUserStats(ur)      → UserStats
  5. buildRevenueStats(br)   → RevenueStats
  6. buildBookingStats(br)   → BookingStats
  7. buildTourStats(tr, br)  → TourStats
  8. buildRecentActivities(br, ur) → List<RecentActivity>
  9. buildChartsData(ur, br, tr)   → ChartsData
  10. return DashboardStatsDTO.builder()...build()

getDashboardAIAnalysis():
  1. Fetch stats (bước 1-7 như trên)
  2. Format context string (port từ monolith)
  3. Call GeminiAIService
  4. return AIAnalysis
```

**Các hàm helper (port từ monolith):**
```
calculateGrowthRate(BigDecimal current, BigDecimal previous) → Double
calculateGrowthRate(Long current, Long previous) → Double
buildDailyGrowthCumulative(List<DailyUserGrowthItem> raw, Long baseBefore) → List<DailyUserGrowth>
```

#### **[TẠO MỚI]** `controller/DashboardController.java`
```java
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "Admin Dashboard Analytics")
public class DashboardController {

    @GetMapping("/statistics")
    @Operation(summary = "Toàn bộ thống kê dashboard")
    public ResponseEntity<DashboardStatsDTO> getDashboardStatistics()

    @GetMapping("/analysis")
    @Operation(summary = "Phân tích AI bằng Gemini")
    public ResponseEntity<DashboardStatsDTO.AIAnalysis> getDashboardAIAnalysis()
}
```

---

### 3.5 `api-gateway` — Thêm Route

#### **[SỬA]** `src/main/resources/application.yml`
```yaml
# Thêm trước route analytics-service hiện tại:
- id: analytics-service-admin-dashboard
  uri: lb://analytics-service
  predicates:
    - Path=/api/admin/dashboard/**
  filters:
    - RewritePath=/api/admin/dashboard/(?<seg>.*), /api/dashboard/${seg}
```

> **Tại sao cần thêm:** Frontend gọi `/api/admin/dashboard/statistics`, gateway route hiện tại chỉ match `/api/dashboard/**`. Route mới sẽ rewrite path trước khi forward đến analytics-service.

---

## 4. LOGIC TÍNH TOÁN (Port Nguyên Từ Monolith)

### 4.1 Growth Rate
```java
// BigDecimal version:
if (previous == 0): return current > 0 ? 100.0 : 0.0
else: return (current - previous) / previous * 100

// Long version tương tự
```

### 4.2 User Statistics
```
Chỉ tính Role.CUSTOMER (không tính ADMIN)
totalUsers     = COUNT WHERE role=CUSTOMER
activeUsers    = COUNT WHERE role=CUSTOMER AND status=true
lockedUsers    = COUNT WHERE role=CUSTOMER AND status=false
newUsersToday  = COUNT WHERE role=CUSTOMER AND createdAt IN [today 00:00, tomorrow 00:00)
newUsersThisWeek  = COUNT WHERE role=CUSTOMER AND createdAt IN [7 days ago, tomorrow 00:00)
newUsersThisMonth = COUNT WHERE role=CUSTOMER AND createdAt IN [30 days ago, tomorrow 00:00)
newUsersLastMonth = COUNT WHERE role=CUSTOMER AND createdAt IN [60 days ago, 30 days ago)
userGrowthRate = calculateGrowthRate(newUsersThisMonth, newUsersLastMonth)
```

### 4.3 Revenue Statistics
```
Chỉ tính BookingStatus.PAID cho revenue chính
totalRevenue       = SUM(totalPrice) WHERE status=PAID
todayRevenue       = SUM(totalPrice) WHERE status=PAID AND bookingDate IN today
thisWeekRevenue    = SUM(totalPrice) WHERE status=PAID AND bookingDate IN [7 days ago, now]
thisMonthRevenue   = SUM(totalPrice) WHERE status=PAID AND bookingDate IN [30 days ago, now]
lastMonthRevenue   = SUM(totalPrice) WHERE status=PAID AND bookingDate IN [60-30 days ago]
revenueGrowthRate  = calculateGrowthRate(thisMonthRevenue, lastMonthRevenue)
dailyRevenue       = GROUP BY date, SUM(totalPrice), COUNT(*) — 30 ngày cuối, status=PAID

Các status khác (hiển thị riêng):
pendingConfirmation = SUM WHERE status=PENDING_CONFIRMATION
pendingPayment      = SUM WHERE status=PENDING_PAYMENT
pendingRefund       = SUM WHERE status=PENDING_REFUND
cancelledRevenue    = SUM WHERE status=CANCELLED
```

### 4.4 Booking Statistics
```
conversionRate = (paidBookings / totalBookings) * 100
statusDistribution = GROUP BY bookingStatus → count + SUM(totalPrice)
```

### 4.5 Hot Tours (Top 5)
```
JOIN booking → departure → tour (qua departureId → TourCatalogFeignClient)
GROUP BY tourCode/tourName
ORDER BY bookingCount DESC, revenue DESC
WHERE status=PAID
```

### 4.6 Tours Needing Attention
```
REFUND_REQUEST: tours có nhiều booking PENDING_REFUND nhất → urgency=HIGH
LOW_BOOKING: tours có departure upcoming nhưng < 3 bookings → urgency=MEDIUM
```

### 4.7 Recent Activities (top 10, sort by timestamp DESC)
```
Type BOOKING   (severity WARNING): PENDING_CONFIRMATION bookings — top 5
Type REFUND    (severity URGENT) : PENDING_REFUND bookings — top 5
Type USER      (severity INFO)   : CUSTOMER mới nhất — top 5
```

### 4.8 Daily User Growth (Cumulative)
```java
Long base = countByRoleAndCreatedAtBefore(CUSTOMER, startDate)  // users trước khoảng thời gian
for each day in dailyGrowth:
    base += day.newUsers
    day.totalUsers = base
```

---

## 5. THIẾT KẾ GIAO DIỆN (Frontend — Không Thay Đổi Code)

Giao diện đã được code đầy đủ tại `DashboardPage.jsx`. Chỉ cần backend trả đúng data.

### 5.1 Layout Tổng Thể
```
┌─────────────────────────────────────────────────────────┐
│  Dashboard Analytics                        [Auto-refresh]│
├────────────┬────────────┬────────────┬──────────────────┤
│ Total Users│Total Revenue│Tot Bookings│  Active Tours    │
│ +growth%   │ +thisMonth  │+conv rate  │  +upcoming dept  │
├─────────────────────────────────────────────────────────┤
│  AI Analysis (Insights | Predictions | Recommendations) │
├─────────────────────────────────────────────────────────┤
│         Revenue Chart (Area+Line — 30 ngày)             │
├────────────────────────┬────────────────────────────────┤
│   User Growth Chart    │   Booking Status Pie Chart      │
├─────────────────────────────────────────────────────────┤
│       Tour Performance Bar Chart (dual Y-axis)          │
├──────────────────────┬──────────────────────────────────┤
│     Hot Tours        │     Recent Activities            │
├──────────────────────┴──────────────────────────────────┤
│              Tours Needing Attention                     │
└─────────────────────────────────────────────────────────┘
```

### 5.2 Charts (dùng Recharts — đã cài)
| Chart | Type | Data | Height |
|---|---|---|---|
| Revenue | AreaChart + Line | `chartsData.revenueChart` (30 days) | 350px |
| User Growth | LineChart | `chartsData.userGrowthChart` (30 days) | 300px |
| Booking Status | PieChart | `chartsData.bookingStatusChart` | 300px |
| Tour Performance | BarChart dual Y | `chartsData.tourPerformanceChart` | 350px |

---

## 6. THỨ TỰ TRIỂN KHAI (Phase)

### Phase 1 — Stats endpoints (3 services song song)

**iam-service:**
- [ ] Thêm queries vào `UserRepository.java`
- [ ] Tạo `UserStatsResponse.java` + `DailyUserGrowthItem.java` + `RecentUserItem.java`
- [ ] Tạo `AdminUserStatsController.java` + `AdminUserStatsService.java`

**booking-service:**
- [ ] Thêm queries vào `BookingRepository.java`
- [ ] Tạo các stats DTOs (6 files)
- [ ] Tạo `AdminBookingStatsController.java` + `AdminBookingStatsService.java`

**tour-catalog-service:**
- [ ] Thêm queries vào `TourRepository.java` + `ReviewRepository.java`
- [ ] Tạo `TourStatsResponse.java` + `TourPerformanceItem.java`
- [ ] Tạo `AdminTourStatsController.java`

### Phase 2 — analytics-service aggregator

- [ ] Tạo `DashboardStatsDTO.java` (port từ monolith)
- [ ] Tạo Feign response DTOs: `UserStatsResponse`, `BookingStatsResponse`, `TourStatsResponse`
- [ ] Tạo `IamFeignClient.java` (mới)
- [ ] Mở rộng `BookingFeignClient.java` + `TourCatalogFeignClient.java`
- [ ] Tạo `GeminiAIService.java` + `GeminiAIServiceImpl.java`
- [ ] Tạo `DashboardService.java` + `DashboardServiceImpl.java`
- [ ] Tạo `DashboardController.java`

### Phase 3 — Gateway + Verify

- [ ] Thêm route `/api/admin/dashboard/**` vào `api-gateway/application.yml`
- [ ] Build lại các service bị thay đổi
- [ ] Test từng endpoint thủ công
- [ ] Verify frontend dashboard hoạt động

---

## 7. DANH SÁCH TẤT CẢ FILES CẦN TẠO/SỬA

### Files Tạo Mới (24 files)

| # | Service | File Path | Mô Tả |
|---|---|---|---|
| 1 | iam-service | `dto/response/stats/UserStatsResponse.java` | Stats response DTO |
| 2 | iam-service | `dto/response/stats/DailyUserGrowthItem.java` | Chart item |
| 3 | iam-service | `dto/response/stats/RecentUserItem.java` | Recent user item |
| 4 | iam-service | `service/AdminUserStatsService.java` | Interface |
| 5 | iam-service | `service/impl/AdminUserStatsServiceImpl.java` | Logic |
| 6 | iam-service | `controller/AdminUserStatsController.java` | Endpoint |
| 7 | booking-service | `dto/response/stats/BookingStatsResponse.java` | Stats response DTO |
| 8 | booking-service | `dto/response/stats/DailyRevenueItem.java` | Revenue chart item |
| 9 | booking-service | `dto/response/stats/BookingStatusCountItem.java` | Status distribution |
| 10 | booking-service | `dto/response/stats/HotTourRawItem.java` | Hot tour data |
| 11 | booking-service | `dto/response/stats/TourAttentionRawItem.java` | Attention tour |
| 12 | booking-service | `dto/response/stats/RecentBookingItem.java` | Recent activity |
| 13 | booking-service | `service/AdminBookingStatsService.java` | Interface |
| 14 | booking-service | `service/impl/AdminBookingStatsServiceImpl.java` | Logic |
| 15 | booking-service | `controller/AdminBookingStatsController.java` | Endpoint |
| 16 | tour-catalog-service | `dto/response/stats/TourStatsResponse.java` | Stats response DTO |
| 17 | tour-catalog-service | `dto/response/stats/TourPerformanceItem.java` | Performance item |
| 18 | tour-catalog-service | `controller/AdminTourStatsController.java` | Endpoint |
| 19 | analytics-service | `dto/dashboard/DashboardStatsDTO.java` | Full response (15 inner classes) |
| 20 | analytics-service | `dto/dashboard/feign/UserStatsResponse.java` | Feign response |
| 21 | analytics-service | `dto/dashboard/feign/BookingStatsResponse.java` | Feign response |
| 22 | analytics-service | `dto/dashboard/feign/TourStatsResponse.java` | Feign response |
| 23 | analytics-service | `feign/IamFeignClient.java` | Feign client |
| 24 | analytics-service | `service/GeminiAIService.java` | AI interface |
| 25 | analytics-service | `service/impl/GeminiAIServiceImpl.java` | AI implementation |
| 26 | analytics-service | `service/DashboardService.java` | Interface |
| 27 | analytics-service | `service/impl/DashboardServiceImpl.java` | Aggregation logic |
| 28 | analytics-service | `controller/DashboardController.java` | 2 endpoints |

### Files Sửa Đổi (6 files)

| # | Service | File | Thay Đổi |
|---|---|---|---|
| 1 | iam-service | `repository/UserRepository.java` | +6 queries |
| 2 | booking-service | `repository/BookingRepository.java` | +8 queries |
| 3 | tour-catalog-service | `repository/TourRepository.java` | +3 queries |
| 4 | tour-catalog-service | `repository/ReviewRepository.java` | +1 query (calculateAverageRating all) |
| 5 | analytics-service | `feign/BookingFeignClient.java` | +1 method `getBookingStats()` |
| 6 | analytics-service | `feign/TourCatalogFeignClient.java` | +1 method `getTourStats()` |
| 7 | api-gateway | `src/main/resources/application.yml` | +1 route `/api/admin/dashboard/**` |

---

## 8. ĐIỂM KIỂM TRA SAU KHI TRIỂN KHAI

### Test Thủ Công Từng Bước

```bash
# Bước 1: Test stats endpoints trực tiếp
GET http://localhost:8081/api/admin/users/stats
→ Expect: JSON với totalUsers, activeUsers, dailyGrowth[]

GET http://localhost:8084/api/admin/bookings/stats
→ Expect: JSON với totalRevenue, dailyRevenue[], hotTours[]

GET http://localhost:8083/api/admin/tours/stats
→ Expect: JSON với totalTours, activeTours, averageRating

# Bước 2: Test qua Gateway
GET http://localhost:8080/api/admin/dashboard/statistics
→ Expect: Full DashboardStatsDTO với tất cả nested objects

GET http://localhost:8080/api/admin/dashboard/analysis
→ Expect: AIAnalysis với summary, insights[], predictions[], recommendations[]
```

### Verify Frontend
```
1. Mở http://localhost:3000/admin/dashboard
2. Không còn "Network Error"
3. 4 stat cards hiển thị số liệu thực
4. 4 charts render (revenue, user growth, booking status, tour performance)
5. AI Analysis section load sau 3-5s (Gemini API call)
6. Hot Tours section hiển thị top 5
7. Recent Activities hiển thị 10 sự kiện gần nhất
8. Attention Section hiển thị tours cần xử lý
```

---

## 9. GHI CHÚ KỸ THUẬT

### HotTours Cross-Service Challenge
`getHotTours()` trong monolith JOIN Booking + TourDeparture + Tour + Review trong 1 DB.

**Giải pháp trong microservices:**
```
booking-service → group by departureId → count + revenue
analytics-service → với mỗi departureId, call TourCatalogFeignClient.getDepartureById()
                 → lấy tourCode, tourName, rồi group lại
                 → hoặc: booking-service JOIN TourDeparture (nếu có entity local)
```

**booking-service hiện có:** `departureId` (foreign key), không có TourDeparture entity local.
→ analytics-service sẽ resolve tourCode/tourName qua `TourCatalogFeignClient` (đã có method `getDepartureById`).

### Error Handling — Feign Fallback
Nếu 1 service down, không crash toàn bộ dashboard:
```java
try {
    ur = iamFeignClient.getUserStats();
} catch (Exception e) {
    log.warn("iam-service unavailable: {}", e.getMessage());
    ur = UserStatsResponse.empty();  // return zeros
}
```

### Null Safety (Port từ Monolith)
```java
// Tất cả BigDecimal query có thể null:
totalRevenue = result != null ? result : BigDecimal.ZERO;
// Tất cả Long query có thể null:
totalUsers = result != null ? result : 0L;
```

### Swagger Annotations
Tất cả controller mới cần:
```java
@Tag(name = "Admin Stats")
@Operation(summary = "...")
@ApiResponse(responseCode = "200", ...)
```

---

*Kế hoạch được tạo ngày 11/05/2026 — Future Travel Dashboard v2.0 Microservices Migration*
