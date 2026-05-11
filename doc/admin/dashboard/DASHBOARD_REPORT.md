# DASHBOARD REPORT — Admin Dashboard Microservices

**Dự án**: Tourism Microservices Backend  
**Phạm vi**: Migration Admin Dashboard từ monolith → microservices  
**Ngày hoàn thành**: 2026-05-11  
**Trạng thái**: ✅ HOÀN THÀNH — tất cả endpoints hoạt động, không lỗi  

---

## 1. Tóm tắt

Đã triển khai đầy đủ tính năng Admin Dashboard cho `http://localhost:3000/admin/dashboard` trên hệ thống microservices. Trước đây frontend nhận "Network Error" vì không có endpoint tương ứng. Sau khi triển khai, tất cả dữ liệu thống kê được tổng hợp từ 3 service (iam, booking, tour-catalog) thông qua analytics-service.

---

## 2. Kiến trúc

```
Frontend (React)
    └─ GET http://localhost:3000/admin/dashboard
           │
           └─ axios: GET http://localhost:8080/api/admin/dashboard/statistics
                      │
           ┌──────────┴──────────────────────────────────┐
           │          API Gateway (:8080)                 │
           │  Route: /api/admin/dashboard/**              │
           │  RewritePath → /api/dashboard/**             │
           └──────────┬──────────────────────────────────┘
                      │
           ┌──────────┴──────────────────────────────────┐
           │     analytics-service (:8087)                │
           │  GET /api/dashboard/statistics               │
           │  GET /api/dashboard/analysis                 │
           │                                              │
           │  Feign clients (Eureka lb://)                │
           │    IamFeignClient        → iam-service       │
           │    BookingFeignClient    → booking-service   │
           │    TourCatalogFeignClient→ tour-catalog      │
           └──┬───────────────┬──────────────────────────┘
              │               │               │
     iam-service          booking-service   tour-catalog-service
     (:8081)              (:8083)           (:8082)
     GET /api/admin/      GET /api/admin/   GET /api/admin/
     users/stats          bookings/stats    tours/stats
```

---

## 3. Files đã tạo/sửa đổi

### 3.1 iam-service

| File | Thay đổi |
|------|----------|
| `repository/UserRepository.java` | +6 queries: countByRole, countByStatusAndRole, countByRoleAndCreatedAtBetween, countByRoleAndCreatedAtBefore, getDailyNewUsersCounts, findTop5ByRoleOrderByCreatedAtDesc |
| `dto/response/stats/UserStatsResponse.java` | **Tạo mới** — response DTO với totalUsers, activeUsers, lockedUsers, dailyGrowth, recentUsers |
| `dto/response/stats/DailyUserGrowthItem.java` | **Tạo mới** — { date, newUsers, totalUsers } |
| `dto/response/stats/RecentUserItem.java` | **Tạo mới** — { fullName, email, createdAt } |
| `controller/AdminUserStatsController.java` | **Tạo mới** — GET /api/admin/users/stats |

### 3.2 booking-service

| File | Thay đổi |
|------|----------|
| `repository/BookingRepository.java` | +8 queries: sumTotalPriceByStatus, sumRevenueByDateAndStatus, getDailyRevenueCounts, getBookingStatusDistribution, countByBookingStatus, countByBookingDateBetween, findTop5ByBookingStatusOrderByCreatedAtDesc, getTopDeparturesByBookingCount |
| `dto/response/stats/BookingStatsResponse.java` | **Tạo mới** — full response với tất cả booking/revenue counts |
| `dto/response/stats/DailyRevenueItem.java` | **Tạo mới** |
| `dto/response/stats/BookingStatusCountItem.java` | **Tạo mới** |
| `dto/response/stats/HotTourRawItem.java` | **Tạo mới** |
| `dto/response/stats/TourAttentionRawItem.java` | **Tạo mới** |
| `dto/response/stats/RecentBookingItem.java` | **Tạo mới** |
| `controller/AdminBookingStatsController.java` | **Tạo mới** — GET /api/admin/bookings/stats |

### 3.3 tour-catalog-service

| File | Thay đổi |
|------|----------|
| `repository/TourRepository.java` | +3 queries: countByStatus, countAllDepartures, countUpcomingDepartures |
| `repository/ReviewRepository.java` | +1 query: calculateAverageRating |
| `dto/response/stats/TourStatsResponse.java` | **Tạo mới** |
| `dto/response/stats/TourPerformanceItem.java` | **Tạo mới** |
| `controller/AdminTourStatsController.java` | **Tạo mới** — GET /api/admin/tours/stats |

### 3.4 analytics-service

| File | Thay đổi |
|------|----------|
| `feign/IamFeignClient.java` | **Tạo mới** — GET /api/admin/users/stats |
| `feign/BookingFeignClient.java` | +method getBookingStats() |
| `feign/TourCatalogFeignClient.java` | +method getTourStats() |
| `dto/dashboard/DashboardStatsDTO.java` | **Tạo mới** — 15 inner classes (UserStats, RevenueStats, BookingStats, TourStats, RecentActivity, AIAnalysis, ChartsData, + subtypes) |
| `dto/dashboard/feign/UserStatsResponse.java` | **Tạo mới** — mirror iam-service response |
| `dto/dashboard/feign/BookingStatsResponse.java` | **Tạo mới** — mirror booking-service response |
| `dto/dashboard/feign/TourStatsResponse.java` | **Tạo mới** — mirror tour-catalog response |
| `dto/dashboard/feign/` *(7 item DTOs)* | **Tạo mới** — DailyUserGrowthItem, RecentUserItem, DailyRevenueItem, BookingStatusCountItem, HotTourRawItem, TourAttentionRawItem, RecentBookingItem, TourPerformanceItem |
| `service/GeminiAIService.java` | **Tạo mới** — interface |
| `service/impl/GeminiAIServiceImpl.java` | **Tạo mới** — Gemini 2.0 Flash API integration |
| `service/DashboardService.java` | **Tạo mới** — interface |
| `service/impl/DashboardServiceImpl.java` | **Tạo mới** — aggregation logic từ 3 Feign clients |
| `controller/DashboardController.java` | **Tạo mới** — 2 endpoints |

### 3.5 api-gateway

| File | Thay đổi |
|------|----------|
| `src/main/resources/application.yml` | +Route `analytics-service-admin-dashboard`: `/api/admin/dashboard/**` → RewritePath → `/api/dashboard/**` |

---

## 4. Endpoints đã triển khai

### Internal Service Endpoints

| Service | Port | Endpoint | Mô tả |
|---------|------|----------|-------|
| iam-service | 8081 | `GET /api/admin/users/stats` | Thống kê user (chỉ CUSTOMER role) |
| booking-service | 8083 | `GET /api/admin/bookings/stats` | Thống kê booking + revenue |
| tour-catalog-service | 8082 | `GET /api/admin/tours/stats` | Thống kê tour + departures |
| analytics-service | 8087 | `GET /api/dashboard/statistics` | Tổng hợp đầy đủ |
| analytics-service | 8087 | `GET /api/dashboard/analysis` | Phân tích AI Gemini |

### Public Endpoints (qua API Gateway :8080)

| Endpoint | Mô tả | Frontend call |
|----------|-------|---------------|
| `GET /api/admin/dashboard/statistics` | Full dashboard data | `api.get('/admin/dashboard/statistics')` |
| `GET /api/admin/dashboard/analysis` | AI Analysis | `api.get('/admin/dashboard/analysis')` |

---

## 5. Kết quả Test API

### Test 1: iam-service User Stats
```
GET http://localhost:8081/api/admin/users/stats
✅ HTTP 200
{
  "totalUsers": 7,
  "activeUsers": 5,
  "lockedUsers": 2,
  "newUsersToday": 0,
  "dailyGrowth": [...31 days...],
  "recentUsers": [...5 users...]
}
```

### Test 2: booking-service Booking Stats
```
GET http://localhost:8083/api/admin/bookings/stats
✅ HTTP 200
{
  "totalBookings": 8,
  "paidBookings": 1,
  "totalRevenue": 3740000.00,
  "dailyRevenue": [...],
  "hotTours": [...],
  "statusDistribution": [...]
}
```

### Test 3: tour-catalog-service Tour Stats
```
GET http://localhost:8082/api/admin/tours/stats
✅ HTTP 200
{
  "totalTours": 9,
  "activeTours": 9,
  "totalDepartures": 15,
  "upcomingDepartures": 15,
  "averageRating": 4.0,
  "tourPerformance": [...9 tours by rating...]
}
```

### Test 4: Dashboard Statistics (qua API Gateway)
```
GET http://localhost:8080/api/admin/dashboard/statistics
✅ HTTP 200
{
  "userStats":    { totalUsers:7, activeUsers:5 },
  "bookingStats": { totalBookings:8, paidBookings:1 },
  "tourStats":    { totalTours:9, activeTours:9, averageRating:4.0 },
  "revenueStats": { totalRevenue:3740000.00 },
  "chartsData": {
    revenueChart:   [...31 items...],
    userGrowthChart: [...31 items...]
  }
}
```

### Test 5: Dashboard AI Analysis (qua API Gateway)
```
GET http://localhost:8080/api/admin/dashboard/analysis
✅ HTTP 200 (fallback empty khi không có Gemini API key)
{
  "summary": "",
  "insights": [],
  "predictions": [],
  "recommendations": []
}
```
*Note: Cần set `GEMINI_API_KEY` trong environment để nhận AI analysis đầy đủ.*

---

## 6. Tình trạng Docker Containers sau Deploy

```
tourism-iam-service            Up (healthy)   :8081
tourism-booking-service        Up (healthy)   :8083
tourism-tour-catalog-service   Up (healthy)   :8082
tourism-analytics-service      Up (healthy)   :8087
tourism-api-gateway            Up (healthy)   :8080
```

---

## 7. Vấn đề đã giải quyết

### Problem 1: Network Error trên Frontend
**Nguyên nhân**: Frontend gọi `GET /api/admin/dashboard/statistics` nhưng API Gateway không có route cho path đó.  
**Giải pháp**: Thêm route `analytics-service-admin-dashboard` vào `api-gateway/application.yml` với RewritePath.

### Problem 2: analytics-service không có DashboardController
**Nguyên nhân**: analytics-service chỉ có chatbot endpoints, không có dashboard.  
**Giải pháp**: Port logic từ monolith `Tourism_Backend`, thêm toàn bộ DTOs, Feign clients, Services, Controller.

### Problem 3: Docker build dùng JAR cũ (cache)
**Nguyên nhân**: Dockerfile copy từ `target/*.jar`, nhưng build đã dùng cache nên JAR không được recompile.  
**Giải pháp**: Chạy `mvn package -DskipTests` trước, sau đó `docker compose build --no-cache`.

---

## 8. Response Structure — DashboardStatsDTO

```json
{
  "userStats": {
    "totalUsers": Long,
    "activeUsers": Long,
    "lockedUsers": Long,
    "newUsersToday": Long,
    "newUsersThisWeek": Long,
    "newUsersThisMonth": Long,
    "userGrowthRate": Double,
    "dailyGrowth": [{ "date": "yyyy-MM-dd", "newUsers": Long, "totalUsers": Long }]
  },
  "revenueStats": {
    "totalRevenue": BigDecimal,
    "thisMonthRevenue": BigDecimal,
    "lastMonthRevenue": BigDecimal,
    "revenueGrowthRate": Double,
    "dailyRevenue": [{ "date": "...", "revenue": BigDecimal, "bookingCount": Long }]
  },
  "bookingStats": {
    "totalBookings": Long,
    "paidBookings": Long,
    "pendingConfirmation": Long,
    "conversionRate": Double,
    "statusDistribution": [{ "status": "...", "count": Long, "revenue": BigDecimal }]
  },
  "tourStats": {
    "totalTours": Long,
    "activeTours": Long,
    "totalDepartures": Long,
    "upcomingDepartures": Long,
    "averageRating": Double,
    "hotTours": [{ "tourCode": "...", "tourName": "...", "bookingCount": Long }],
    "toursNeedingAttention": [{ "tourCode": "...", "reason": "...", "urgency": "HIGH|MEDIUM" }]
  },
  "recentActivities": [{ "type": "...", "description": "...", "timestamp": "...", "severity": "..." }],
  "chartsData": { "revenueChart": [...], "userGrowthChart": [...], "bookingStatusChart": [...], "tourPerformanceChart": [...] },
  "aiAnalysis": { "summary": "...", "insights": [...], "predictions": [...], "recommendations": [...] }
}
```

---

## 9. Lưu ý kỹ thuật

1. **Tất cả queries dùng `isDeleted = false OR isDeleted IS NULL`** để đảm bảo soft-delete chính xác.
2. **analytics-service có fallback pattern**: nếu Feign client lỗi thì trả về `empty()` object thay vì throw exception — dashboard luôn load được dù một service down.
3. **Gemini AI key**: Cần set env var `GEMINI_API_KEY` trong docker-compose.yml hoặc environment để nhận phân tích AI. Khi không có key, endpoint `/analysis` vẫn trả 200 với empty content.
4. **UTF-8 encoding**: Tên tour trong DB dùng tiếng Việt, hiển thị đúng khi gọi từ browser/Postman (vấn đề encoding chỉ xuất hiện trong PowerShell console output).
5. **Booking port**: booking-service dùng port 8083 (không phải 8084 như payment-service).
