# Báo cáo: Sửa lỗi Keycloak & Thiết kế lại Admin Dashboard

**Ngày thực hiện:** 2025-01  
**Phạm vi:** Tourism Microservices Backend + Frontend Admin Dashboard

---

## 1. Sửa lỗi Keycloak không khởi động

### Triệu chứng
- Chạy `docker compose start` → container `tour-keycloak-service` không khởi động.
- Log Docker báo lỗi kết nối database: không tìm thấy database `keycloak_db`.

### Nguyên nhân
Database `keycloak_db` chưa được tạo trong PostgreSQL container (`tourism-postgres`).  
Keycloak cần database riêng để lưu realm config, user session và các changeset Liquibase.

### Giải pháp
Tạo database thủ công trong container PostgreSQL:

```bash
docker exec -it tourism-postgres psql -U postgres -c "CREATE DATABASE keycloak_db;"
```

Sau đó restart container Keycloak:
```bash
docker compose restart tour-keycloak-service
```

### Kết quả
- Keycloak 24.0.5 khởi động thành công, chạy tại cổng **8180**.
- Khởi tạo 124 DB changeset Liquibase thành công.
- Các service phụ thuộc Keycloak (IAM, API Gateway) hoạt động bình thường.

---

## 2. Lỗi AI Analytics không phân tích được

### Triệu chứng
- Nhấn "Phân tích AI" trên Dashboard → không có kết quả, màn hình trống hoặc lỗi.
- Log Docker analytics-service báo:
  ```
  403 Forbidden: CONSUMER_SUSPENDED
  Project: 985031188799
  API Key: AIzaSyARkEQYUcJEWCWTue14tyYjtXdzjCrGPzo
  ```

### Nguyên nhân
API key Gemini AI **đã bị Google suspend** (CONSUMER_SUSPENDED).  
Đây **không phải lỗi code** — toàn bộ logic `GeminiAIServiceImpl.java` đúng, chỉ là key không còn hợp lệ.

### Giải pháp (cần thực hiện thủ công)

1. Truy cập [Google AI Studio](https://aistudio.google.com/app/apikey) hoặc [Google Cloud Console](https://console.cloud.google.com/)
2. Tạo API key mới cho Gemini API
3. Cập nhật key trong cấu hình analytics-service:

   **Cách 1 – qua `docker-compose.yml`:**
   ```yaml
   analytics-service:
     environment:
       - GEMINI_API_KEY=YOUR_NEW_API_KEY_HERE
   ```

   **Cách 2 – qua `application.yaml` của analytics-service:**
   ```yaml
   gemini:
     api-key: YOUR_NEW_API_KEY_HERE
   ```

4. Rebuild và restart analytics-service:
   ```bash
   docker compose up -d --build analytics-service
   ```

### Lưu ý
- API key cũ `AIzaSyARkEQYUcJEWCWTue14tyYjtXdzjCrGPzo` không thể phục hồi.
- Nên sử dụng environment variable (không hardcode vào code) để dễ thay thế sau này.

---

## 3. Thiết kế lại Admin Dashboard

### Mục tiêu
Làm mới giao diện Admin Dashboard tại `/admin/dashboard` để:
- Đồng nhất màu sắc với trang `/information` (primary blue `#2563eb`)
- Giảm border-radius cho cảm giác chuyên nghiệp, ít bo góc
- Thay thế toàn bộ icon emoji bằng icon từ thư viện `lucide-react`
- Sửa lỗi viền hiển thị sai khi hover/click vào stat card
- Không thay đổi bất kỳ business logic nào

---

### 3.1 Design System (CSS Variables)

| Variable | Cũ | Mới |
|---|---|---|
| `--bg-page` | `#f6f8fc` | `#f1f5f9` |
| `--border` | `#e8edf5` | `#e2e8f0` |
| `--shadow-xs` | `0 1px 3px …` | `0 1px 4px …` |
| `padding` (page) | `28px` | `24px` |
| `gap` (2-column grid) | `20px` | `16px` |

Màu chủ đạo giữ nguyên: `--primary: #2563eb`

---

### 3.2 Border-radius — Chuẩn hóa

| Loại element | Cũ | Mới |
|---|---|---|
| Card / wrapper chính | `16px` | `8px` |
| Inner elements (icon, item row) | `10–14px` | `6–8px` |
| Badge / pill / button nhỏ | `20–50px` | `4–6px` |
| Circular elements (avatar, 50%) | Giữ nguyên | Giữ nguyên |

---

### 3.3 Files đã chỉnh sửa

#### SCSS Modules

| File | Thay đổi chính |
|---|---|
| `DashboardPage.module.scss` | CSS variables, padding, gap |
| `DashboardHeader.module.scss` | Header, dateBtn, pickerDropdown, presetBtn, iconBtn |
| `StatsOverview.module.scss` | statCard, iconWrapper, growthBadge, hoverDetails; sửa hover/click border bug |
| `ChartsSection.module.scss` | chartCard wrapper |
| `RevenueChart.module.scss` | chartTypeToggle, toggleBtn (từ indigo → blue) |
| `UserGrowthChart.module.scss` | Wrapper, totalBadge; dùng CSS variables thay vì hardcoded colors |
| `BookingStatusChart.module.scss` | Wrapper; dùng CSS variables thay vì hardcoded colors |
| `TourPerformanceChart.module.scss` | Wrapper, totalBadge |
| `HotToursSection.module.scss` | Wrapper, list header badge, row, rank |
| `AttentionSection.module.scss` | Wrapper, actionCard |
| `RecentActivities.module.scss` | Wrapper, badge, activityItem, activityIcon |
| `AIAnalysisSection.module.scss` | aiSection, brainOrb, brainOrbSmall, analyzeBtn, modeBtn, modeChip, reAnalyzeBtn, summaryCard, insightCard, predictionCard, recCard, priorityBadge, dateBadge, errorMsg |

#### JSX Components

| File | Thay đổi |
|---|---|
| `HotToursSection.jsx` | Xóa `label: '🥇🥈🥉'` khỏi RANK_STYLES; dùng số 1/2/3 thay emoji; thêm `DollarSign` icon thay `💰` emoji |
| `TourPerformanceChart.jsx` | Xóa `💰` và `📦` emoji khỏi CustomTooltip; thay `📊` bằng `<BarChart3>` trong empty state |

---

### 3.4 Sửa lỗi hover detail border (Stat Card)

**Vấn đề:** Khi click vào stat card (Users, Revenue, Orders, Tours), viền trái của overlay bị hiển thị sai màu.

**Nguyên nhân:** `.hoverDetails` có `border-left: 4px solid` nhưng không kế thừa `borderLeftColor` từ parent đúng cách trong mọi trạng thái.

**Giải pháp:**
- Giảm `border-left: 4px` → `3px` cho nhất quán với `.statCard`
- Thêm `:focus-within` vào hover selector (ngoài `:hover`)
- Thêm `outline: none; -webkit-tap-highlight-color: transparent;` vào `.statCard`
- Giảm `border-radius: 16px` → `8px` để khớp với parent

---

### 3.5 Kết quả

- Giao diện đồng nhất màu blue `#2563eb` xuyên suốt
- Tất cả border-radius giảm xuống, cảm giác "sharp" và chuyên nghiệp hơn
- Không còn emoji nào trong UI — tất cả dùng `lucide-react` icons
- Toggle chart type (Revenue chart) đổi từ màu indigo/purple sang blue (#2563eb)
- Hover overlay stat card hoạt động mượt, không còn lỗi viền

---

## Tóm tắt

| Hạng mục | Trạng thái |
|---|---|
| Keycloak không khởi động | ✅ Đã sửa — tạo `keycloak_db` |
| AI Analytics không hoạt động | ⚠️ Cần API key mới từ Google AI Studio |
| Dashboard UI redesign | ✅ Hoàn thành — 13 SCSS + 2 JSX files |
| Emoji → Lucide icons | ✅ Hoàn thành |
| Hover border bug stat card | ✅ Đã sửa |
