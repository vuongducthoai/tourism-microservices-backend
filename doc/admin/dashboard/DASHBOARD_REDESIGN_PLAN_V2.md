# DASHBOARD REDESIGN PLAN v2.0
**Dự án**: Tourism Admin Dashboard — Thiết kế lại toàn diện  
**Ngày lập kế hoạch**: 2026-05-11  
**Trạng thái**: 📋 PLAN — Chưa triển khai code  

---

## 📌 Vấn đề hiện tại (Pain Points)

| # | Vấn đề | Ảnh hưởng |
|---|--------|-----------|
| 1 | Khoảng thời gian cố định 30 ngày — không thể tuỳ chọn | Kém linh hoạt phân tích |
| 2 | AI Analysis không có context ngày tháng | Kết quả AI không chính xác theo thời kỳ |
| 3 | Tên khách hàng bị lỗi encoding UTF-8 (`V? Th? Lan`) | Hiển thị sai tiếng Việt |
| 4 | Nút Refresh bị comment out | Không thể refresh thủ công |
| 5 | Không có skeleton loading — màn hình trắng khi load | UX kém |
| 6 | Không có dark mode | Thiếu tính năng hiện đại |
| 7 | Không có export (PDF/Excel) | Không thể báo cáo |
| 8 | Charts không có drill-down | Không thể xem chi tiết |
| 9 | Không có KPI targets / goals | Không theo dõi được mục tiêu |
| 10 | Mobile responsive kém | Không dùng được trên tablet/phone |

---

## 🎯 Mục tiêu Redesign

> **Tầm nhìn**: Dashboard hiện đại, tốc độ cao, thông minh — tương đương Vercel / Linear Analytics

### Design Language
- **Style**: Clean minimalist với accent màu gradient (`#6366f1` → `#8b5cf6` → `#06b6d4`)
- **Font**: Inter / system-ui
- **Card system**: Glass morphism nhẹ, shadow tinh tế
- **Màu thương hiệu chính**: `#6366f1` (Indigo) thay cho `#667eea`
- **Dark mode**: CSS variables cho light/dark switching

---

## 🗂️ Cấu trúc File Mới (Sau Redesign)

```
DashboardPage/
├── DashboardPage.jsx               ← Đã có, sửa lại
├── DashboardPage.module.scss       ← Viết lại hoàn toàn
├── _variables.scss                 ← MỚI: CSS variables & design tokens
├── _mixins.scss                    ← MỚI: Responsive mixins
└── components/
    ├── DashboardHeader/            ← MỚI: Header + DateRangePicker + Actions
    │   ├── DashboardHeader.jsx
    │   └── DashboardHeader.module.scss
    ├── DateRangePicker/            ← MỚI: Bộ chọn khoảng ngày
    │   ├── DateRangePicker.jsx
    │   └── DateRangePicker.module.scss
    ├── StatsOverview/              ← Đã có, nâng cấp KPI + Sparklines
    │   ├── StatsOverview.jsx
    │   └── StatsOverview.module.scss
    ├── KPIGoalWidget/              ← MỚI: Widget theo dõi KPI target
    │   ├── KPIGoalWidget.jsx
    │   └── KPIGoalWidget.module.scss
    ├── ChartsSection/              ← Đã có, thêm date range + drill-down
    │   ├── ChartsSection.jsx
    │   ├── ChartsSection.module.scss
    │   ├── RevenueChart/           ← Nâng cấp: multi-series + date range
    │   ├── UserGrowthChart/        ← Nâng cấp: trend line
    │   ├── BookingStatusChart/     ← Nâng cấp: interactive donut
    │   ├── TourPerformanceChart/   ← Nâng cấp: horizontal bar
    │   ├── FunnelChart/            ← MỚI: Booking conversion funnel
    │   └── RevenueByTourChart/     ← MỚI: Treemap doanh thu theo tour
    ├── AIAnalysisSection/          ← Nâng cấp: date range + context
    │   ├── AIAnalysisSection.jsx
    │   └── AIAnalysisSection.module.scss
    ├── HotToursSection/            ← Nâng cấp: leaderboard style
    │   ├── HotToursSection.jsx
    │   └── HotToursSection.module.scss
    ├── RecentActivities/           ← Nâng cấp: infinite scroll + filter
    │   ├── RecentActivities.jsx
    │   └── RecentActivities.module.scss
    ├── AttentionSection/           ← Nâng cấp: action buttons trực tiếp
    │   ├── AttentionSection.jsx
    │   └── AttentionSection.module.scss
    ├── QuickActionsPanel/          ← MỚI: Shortcuts hành động nhanh
    │   ├── QuickActionsPanel.jsx
    │   └── QuickActionsPanel.module.scss
    ├── HealthMonitor/              ← MỚI: Service health status
    │   ├── HealthMonitor.jsx
    │   └── HealthMonitor.module.scss
    ├── LoadingSpinner/             ← Thay bằng Skeleton
    │   ├── SkeletonDashboard.jsx   ← MỚI
    │   └── SkeletonDashboard.module.scss
    └── ErrorDisplay/               ← Nâng cấp với retry + error details
```

---

## 🔥 Tính năng Mới Chi tiết

### 1. 📅 Date Range Picker (QUAN TRỌNG NHẤT)

**Vị trí**: Header của Dashboard, luôn hiển thị

**Các preset**:
```
[Hôm nay]  [Hôm qua]  [7 ngày]  [30 ngày]  [3 tháng]  [6 tháng]  [Năm nay]  [Tuỳ chọn...]
```

**Tùy chọn date range**:
- Calendar picker 2 tháng (from/to)
- Quick shortcuts (bên trái calendar)
- So sánh kỳ trước (toggle: "So sánh với kỳ trước")

**Ảnh hưởng toàn bộ Dashboard**:
- Tất cả stats cards cập nhật theo range
- Charts render lại dữ liệu trong range
- AI Analysis có context ngày tháng chính xác
- HotTours/Activities lọc theo range

**Backend API cần thêm params**:
```
GET /api/admin/dashboard/statistics?from=2026-01-01&to=2026-05-11
GET /api/admin/dashboard/analysis?from=2026-01-01&to=2026-05-11
```

---

### 2. 📊 Stats Overview Cards Nâng cấp

**Từ hiện tại**: 6 cards đơn giản (số + icon)

**Nâng cấp thành**:
- **Mini Sparkline chart** trong mỗi card (7 ngày gần nhất)
- **So sánh kỳ trước**: hiển thị `+12% vs kỳ trước` với mũi tên màu
- **Trend indicator**: xanh/đỏ tự động theo growth rate
- **Hover tooltip**: chi tiết thêm khi hover
- **Click to drill down**: click card → filter chart phía dưới

**8 KPI Cards** (thêm 2 card mới):
```
[👥 Tổng Users]     [💰 Tổng Doanh thu]   [📋 Tổng Bookings]  [⭐ Rating TB]
[🗓️ Bookings hôm nay] [💵 DT hôm nay]    [🔄 Tỷ lệ Convert]  [🌍 Tours Active]
```

---

### 3. 🎯 KPI Goals Widget (MỚI)

Một widget nhỏ cho phép admin đặt mục tiêu tháng:
- **Monthly Revenue Target**: Progress bar `3.7M / 50M VNĐ (7.4%)`
- **Monthly Booking Target**: `8 / 100 bookings (8%)`
- **New User Target**: `0 / 50 users (0%)`
- Lưu target vào `localStorage` (không cần backend)
- Màu progress: green > 70%, orange 40-70%, red < 40%

---

### 4. 📈 Charts Nâng cấp

#### Revenue Chart
- **Thêm**: Nút toggle giữa Line / Bar / Area chart
- **Thêm**: Multi-series: hiển thị cả `Revenue` và `Booking Count` (dual Y-axis)
- **Thêm**: Zoom in/out khi kéo chuột (recharts ReferenceArea)
- **Thêm**: Export PNG button trên mỗi chart

#### Booking Status Chart
- **Thay đổi**: Từ Bar chart → Donut chart (interactive)
- **Thêm**: Click vào slice → navigate to /admin/bookings?status=xxx
- **Thêm**: Legend bên phải với % và số lượng

#### Funnel Chart (MỚI)
- Visualize conversion funnel:
  `Tổng → Paid → Pending → Cancelled`
- Hiển thị tỷ lệ chuyển đổi giữa các bước

#### Revenue by Tour — Treemap (MỚI)
- Hiển thị doanh thu phân bổ theo tour
- Size của block = doanh thu, màu = rating

---

### 5. 🤖 AI Analysis Nâng cấp

**Thêm DateRange context**:
```jsx
// Khi click "Phân tích", gửi kèm date range
GET /api/admin/dashboard/analysis?from=2026-04-01&to=2026-05-11
```

**Thêm Analysis Mode Selector**:
```
[🔍 Phân tích Tổng quan]  [💰 Tập trung Doanh thu]  [👥 Tập trung Users]  [🌍 Tập trung Tours]
```

**Thêm Export AI Report**:
- Nút "Xuất PDF" — tạo PDF report từ AI analysis
- Nút "Copy text" — copy summary ra clipboard

**Cải thiện UX Loading**:
- Stream response (typing effect cho summary)
- Progress bar với các bước: "Đang thu thập dữ liệu... Đang phân tích... Đang tạo khuyến nghị..."

---

### 6. ⚡ Quick Actions Panel (MỚI)

Thanh shortcut ngang dưới header:
```
[+ Tạo Tour mới]  [👁 Xem Bookings chờ]  [💳 Xem Hoàn tiền]  [📊 Xuất báo cáo]  [🔔 Thông báo (3)]
```

---

### 7. 🏆 Hot Tours — Leaderboard Style

**Từ hiện tại**: List đơn giản

**Nâng cấp**:
- **Podium effect**: Top 1/2/3 có background gradient khác
- **Animated rank badge**: #1 có halo animation
- **Mini bar chart** thể hiện bookings tương đối
- **Rating stars** hiển thị đúng từ tour-catalog
- **Filter tabs**: [Top 5 Bookings] [Top 5 Doanh thu] [Top 5 Rating]

---

### 8. 🔔 Recent Activities Nâng cấp

**Từ hiện tại**: List tĩnh 10 items

**Nâng cấp**:
- **Filter buttons**: [Tất cả] [Booking] [Hoàn tiền] [User mới]
- **Auto-refresh**: cứ 60s tự refresh activities (polling)
- **Mark as read** functionality
- **Severity color coding**: border-left màu theo severity

---

### 9. 🩺 Service Health Monitor (MỚI)

Widget nhỏ ở góc dashboard hiển thị status của các microservices:
```
● iam-service        ✅ Healthy  (72ms)
● booking-service    ✅ Healthy  (45ms)
● tour-catalog       ✅ Healthy  (88ms)
● analytics-service  ✅ Healthy  (12ms)
```
Gọi `/actuator/health` của từng service qua gateway.

---

### 10. 🌙 Dark Mode (MỚI)

Toggle button ở header. Sử dụng CSS custom properties:
```scss
:root {
  --bg-primary: #ffffff;
  --bg-secondary: #f8fafc;
  --text-primary: #1a202c;
  --border: #e2e8f0;
}
[data-theme="dark"] {
  --bg-primary: #0f172a;
  --bg-secondary: #1e293b;
  --text-primary: #f1f5f9;
  --border: #334155;
}
```

---

### 11. 📤 Export / Report (MỚI)

**Export dropdown** ở header:
- 📊 Xuất Excel (CSV)
- 📄 Xuất PDF (browser print)
- 📋 Copy summary text
- 🔗 Share link (URL với params ngày tháng)

---

### 12. 🔧 Fix Bugs Hiện tại

| Bug | Fix |
|-----|-----|
| Tên tiếng Việt bị lỗi (`V? Th? Lan`) | Fix charset encoding trong BookingRepository/UserRepository query — FUNCTION('CONVERT') hoặc đảm bảo DB collation UTF-8 |
| Nút Refresh bị comment out | Uncomment + thêm loading state |
| `revenueByTour` luôn trả về `{}` empty | Implement trong DashboardServiceImpl |
| Revenue chart hiển thị `0đ` dù có data | Debug query getDailyRevenueCounts — kiểm tra BookingStatus.PAID filter |

---

## 🏗️ Kế hoạch Backend cần làm

### 1. Date Range params cho analytics-service

```java
// DashboardController.java — thêm params
@GetMapping("/statistics")
ResponseEntity<DashboardStatsDTO> getDashboardStatistics(
    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to
)

@GetMapping("/analysis")
ResponseEntity<DashboardStatsDTO.AIAnalysis> getDashboardAIAnalysis(
    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
    @RequestParam(defaultValue="OVERVIEW") String mode  // OVERVIEW | REVENUE | USERS | TOURS
)
```

### 2. Pass date range qua Feign clients

```java
// IamFeignClient — thêm params
@GetMapping("/api/admin/users/stats")
UserStatsResponse getUserStats(
    @RequestParam(required=false) String from,
    @RequestParam(required=false) String to
);
```

Tương tự BookingFeignClient và TourCatalogFeignClient.

### 3. Revenue by Tour — Implement

```java
// DashboardServiceImpl — revenueByTour
Map<String, BigDecimal> revenueByTour = br.getHotTours().stream()
    .collect(Collectors.toMap(
        HotTourRawItem::getTourName,
        h -> safeDecimal(h.getRevenue())
    ));
```

### 4. Fix encoding — check DB

```sql
-- Kiểm tra collation của bảng users, bookings
SELECT table_name, table_collation FROM information_schema.tables
WHERE table_schema = 'tourism_iam';
```

---

## 🎨 Design Tokens (Design System)

```scss
// _variables.scss

// ── Colors ──
$indigo-400: #818cf8;
$indigo-500: #6366f1;
$indigo-600: #4f46e5;
$violet-500: #8b5cf6;
$cyan-500:   #06b6d4;
$emerald-500: #10b981;
$rose-500:   #f43f5e;
$amber-500:  #f59e0b;

// ── Gradients ──
$gradient-primary: linear-gradient(135deg, $indigo-500, $violet-500);
$gradient-success: linear-gradient(135deg, #10b981, #059669);
$gradient-danger:  linear-gradient(135deg, #f43f5e, #e11d48);
$gradient-warm:    linear-gradient(135deg, $amber-500, #ea580c);

// ── Shadows ──
$shadow-sm:  0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04);
$shadow-md:  0 4px 6px rgba(0,0,0,0.07), 0 2px 4px rgba(0,0,0,0.04);
$shadow-lg:  0 10px 15px rgba(0,0,0,0.08), 0 4px 6px rgba(0,0,0,0.04);
$shadow-card: 0 0 0 1px rgba(0,0,0,0.04), 0 4px 12px rgba(0,0,0,0.06);

// ── Border Radius ──
$radius-sm: 8px;
$radius-md: 12px;
$radius-lg: 16px;
$radius-xl: 24px;
$radius-full: 9999px;

// ── Spacing ──
$space-base: 16px;
$gap-cards: 20px;
$gap-sections: 28px;

// ── Breakpoints ──
$bp-sm: 640px;
$bp-md: 768px;
$bp-lg: 1024px;
$bp-xl: 1280px;
```

---

## 📐 Layout Mới

```
┌─────────────────────────────────────────────────────────────────┐
│  HEADER: "Dashboard Analytics"  │  DateRangePicker  │  Actions  │
├─────────────────────────────────────────────────────────────────┤
│  Quick Actions Bar (shortcuts)                                   │
├─────────────────────────────────────────────────────────────────┤
│  [KPI Card 1] [KPI Card 2] [KPI Card 3] [KPI Card 4]           │
│  [KPI Card 5] [KPI Card 6] [KPI Card 7] [KPI Card 8]           │
├──────────────────────────────────┬──────────────────────────────┤
│  KPI Goals Progress              │  Service Health Monitor      │
├──────────────────────────────────┴──────────────────────────────┤
│  Revenue Chart (Full width) — Line/Bar/Area toggle + date range │
├──────────────────────┬──────────────────────────────────────────┤
│  User Growth Chart   │  Booking Status Donut Chart              │
├──────────────────────┴──────────────────────────────────────────┤
│  Booking Conversion Funnel (Full width)                         │
├──────────────────────┬──────────────────────────────────────────┤
│  Tour Performance    │  Revenue by Tour Treemap                 │
├──────────────────────┴──────────────────────────────────────────┤
│  AI Analysis Section (Full width) — with date range & mode      │
├──────────────────────┬──────────────────────────────────────────┤
│  Hot Tours           │  Recent Activities (with filter)         │
│  (Leaderboard)       │  (auto-refresh 60s)                      │
├──────────────────────┴──────────────────────────────────────────┤
│  Attention Section — Action cards + Tours needing attention      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📦 Dependencies Cần Thêm (Frontend)

| Package | Dùng cho | Version |
|---------|----------|---------|
| `react-datepicker` | DateRangePicker calendar | ^6.9.0 |
| `date-fns` | Xử lý ngày tháng | ^3.6.0 |
| `recharts` | Charts (đã có) | ^2.x |
| `jspdf` | Export PDF | ^2.5.x |
| `xlsx` | Export Excel/CSV | ^0.18.x |
| `react-hot-toast` | Notifications | ^2.4.x |

*Kiểm tra `package.json` trước khi cài để tránh trùng lặp.*

---

## 📋 Sprint Plan

### Sprint 1 — Backend: Date Range API (2-3h)
- [ ] Thêm `from/to` params vào `DashboardController` (analytics-service)
- [ ] Pass params qua Feign clients → iam, booking, tour-catalog services
- [ ] Cập nhật các Repository queries để nhận date range params
- [ ] Test API với curl/Postman
- [ ] Build Docker lại

### Sprint 2 — Frontend: Date Range Picker + Hook (2h)
- [ ] Cài `react-datepicker` + `date-fns`
- [ ] Tạo `DateRangePicker` component
- [ ] Cập nhật `useDashboard.ts` hook nhận `dateRange` state
- [ ] Cập nhật `dashboard.ts` service truyền `from/to` params
- [ ] Cập nhật `DashboardPage.jsx` — state management cho date range

### Sprint 3 — Frontend: UI Redesign Header + KPI Cards (3h)
- [ ] Viết lại `DashboardPage.module.scss` + `_variables.scss`
- [ ] Tạo `DashboardHeader` component
- [ ] Tạo `QuickActionsPanel` component
- [ ] Nâng cấp `StatsOverview` — sparklines + comparison + 8 cards
- [ ] Tạo `KPIGoalWidget` component

### Sprint 4 — Frontend: Charts Nâng cấp (3h)
- [ ] `RevenueChart` — multi-series + toggle Line/Bar/Area
- [ ] `BookingStatusChart` — Donut interactive
- [ ] Tạo `FunnelChart` mới
- [ ] Tạo `RevenueByTourChart` (Treemap hoặc grouped bar)
- [ ] Tạo `HealthMonitor` widget

### Sprint 5 — Frontend: AI + Activities + Dark Mode (3h)
- [ ] `AIAnalysisSection` — date range + mode selector + export
- [ ] `HotToursSection` — leaderboard + filter tabs
- [ ] `RecentActivities` — filter + auto-refresh + severity
- [ ] Dark mode CSS variables + toggle button
- [ ] Export CSV/PDF functionality

### Sprint 6 — Bug Fixes + Polish (1-2h)
- [ ] Fix encoding tiếng Việt trong tên khách hàng
- [ ] Fix Revenue chart hiển thị `0đ`
- [ ] Implement `revenueByTour` trong backend
- [ ] Skeleton loading thay cho spinner
- [ ] Responsive mobile/tablet

### Sprint 7 — Testing + Docker rebuild (1h)
- [ ] Test tất cả features trên Chrome
- [ ] Test dark mode
- [ ] Test date range với các preset
- [ ] Docker rebuild nếu có thay đổi backend
- [ ] Viết report cuối

---

## 🎨 Mockup Wireframe — Stats Card

```
┌─────────────────────────────────────────┐
│  💰  Tổng Doanh thu           ↑ +24.5%  │
│                                          │
│  3,740,000 đ                            │
│                                          │
│  ▁▂▃▄▅▃▄▅▆▇  (sparkline 7 ngày)        │
│                                          │
│  vs tháng trước: 0 đ                    │
└─────────────────────────────────────────┘
```

## 🎨 Mockup Wireframe — Date Range Picker

```
┌────────────────────────────────────────────────────────────────┐
│  Dashboard Analytics                                            │
│  Thống kê tổng quan...           ┌─────────────────────────┐   │
│                                  │ 📅 11/04/2026 → 11/05/2026 ▼│
│                                  └─────────────────────────┘   │
│  [Hôm nay][7 ngày][30 ngày][3 tháng][Năm nay][Tuỳ chọn]       │
└────────────────────────────────────────────────────────────────┘
```

---

## ✅ Definition of Done

- [ ] Tất cả charts hiển thị đúng dữ liệu theo date range chọn
- [ ] AI Analysis có context ngày tháng trong prompt
- [ ] Dark mode toggle hoạt động, persist qua localStorage
- [ ] KPI Goals lưu và hiển thị đúng
- [ ] Export CSV tải về file hợp lệ
- [ ] Responsive trên 768px+
- [ ] Tên tiếng Việt hiển thị đúng (không còn `?`)
- [ ] Loading skeleton hiển thị khi fetch data
- [ ] Tất cả links navigation hoạt động đúng
- [ ] Không có console errors

---

*File này là kế hoạch — code sẽ được triển khai theo từng Sprint sau khi plan được approve.*
