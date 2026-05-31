# Plan: Tour Route Map — Bản đồ lộ trình tour với pin theo Itinerary Day (Leaflet + OpenStreetMap)

> **Trạng thái**: 📋 Đang lên kế hoạch
> **Effort dự kiến**: 2 ngày
> **Phạm vi**: `tour-catalog-service` (backend — schema + endpoint) + `client-side/src/components/TourDetailComponent` (frontend — embed Leaflet map) + Admin tour management (nhập lat/lng)
> **Mục tiêu**: User vào trang tour detail nhìn thấy bản đồ với các pin (điểm dừng) theo từng ngày của Itinerary; có line nối lộ trình; click pin xem thông tin ngắn.
>
> **Lựa chọn tech**: **Leaflet + OpenStreetMap** — 100% miễn phí, không cần API key, không Billing, không thẻ tín dụng. Đủ tốt cho hiển thị pin + polyline + InfoWindow.

---

## 1. Vấn đề & Giải pháp

### 1.1. Vấn đề
- Tour detail hiện chỉ có **`attractions`** (text dài) và **`itineraryDays`** (mỗi ngày có `title` + `details` text).
- User không có hình dung không gian: tour qua những điểm nào, chặng đường bao xa, có gần điểm A mình đã đi không.
- Không có cách so sánh trực quan giữa các tour có cùng vùng địa lý.

### 1.2. Giải pháp
Nhúng Leaflet map trong trang tour detail:
- **Pin từng điểm dừng** (place of interest) theo thứ tự trong Itinerary, gắn nhãn "Ngày 1", "Ngày 2"...
- **Đường nối** giữa các pin theo thứ tự lộ trình (Polyline đơn giản — không dùng routing service ngoài để tránh phụ thuộc).
- **Popup** click pin: tên điểm, ngày, mô tả ngắn (1-2 dòng từ itinerary).
- **Filter theo ngày**: chip "Tất cả / Ngày 1 / Ngày 2..." để focus 1 ngày.
- **Fallback**: tour chưa có toạ độ → ẩn widget hoàn toàn (không hiển thị placeholder rỗng).

### 1.3. Tech stack
- **Leaflet** (`leaflet` ^1.9) — thư viện bản đồ JS mã nguồn mở, không cần API key.
- **React wrapper** (`react-leaflet` ^4.x) — bindings React: `<MapContainer>`, `<Marker>`, `<Popup>`, `<Polyline>`, `<TileLayer>`.
- **Tile provider mặc định**: **OpenStreetMap** (`https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png`) — miễn phí cho dự án nhỏ-vừa (≤ 100 req/s; nếu vượt → tự host tile hoặc đổi sang Carto/Stadia free tier).
- **Database**: thêm bảng mới `tour_stops` (1 tour có nhiều stop, mỗi stop thuộc 1 day).
- **KHÔNG geocode tự động**: admin nhập tay lat/lng (chính xác hơn, không phụ thuộc Nominatim rate-limit).

**Vì sao Leaflet thay Google Maps?**
| | Leaflet + OSM | Google Maps |
|---|---|---|
| Chi phí | $0 vĩnh viễn | $7/1k loads (28k miễn phí/tháng) |
| API key | Không cần | Bắt buộc + restrict referrer |
| Billing account | Không cần | Bắt buộc nhập thẻ tín dụng |
| Quota | Fair-use OSM tile (đủ cho project học/SMB) | $200 credit/tháng |
| Bản đồ Việt Nam | Đủ chi tiết tới cấp huyện | Chi tiết hơn ở khu vực ít người |
| Satellite view | Không có mặc định (cần tile provider khác) | Có sẵn |
| Render performance | Nhẹ (~40KB) | Nặng hơn (~150KB) |
| Tùy biến UI | Cao (CSS thẳng) | Hạn chế |

Với scope plan này (hiển thị pin + polyline + popup), Leaflet thừa sức đáp ứng.

---

## 2. Thiết kế Backend (tour-catalog-service)

### 2.1. Entity mới — `TourStop`

Một row = một pin trên bản đồ. Liên kết với ItineraryDay (nullable — cho phép stop không gắn ngày cụ thể, hoặc tour không có itinerary chia ngày).

File: `tour-catalog-service/src/main/java/com/tourism/tourcatalog/entity/TourStop.java`

```java
@Entity
@Table(name = "tour_stops", indexes = {
    @Index(name = "idx_stop_tour", columnList = "tour_id"),
    @Index(name = "idx_stop_day",  columnList = "itinerary_day_id")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TourStop {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer stopId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id", nullable = false)
    private Tour tour;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_day_id")
    private ItineraryDay itineraryDay;          // nullable — stop chưa gắn day cụ thể

    @Column(nullable = false)
    private String name;                         // "Vịnh Hạ Long", "Chùa Bái Đính"

    @Column(name = "lat", nullable = false, precision = 10)
    private Double latitude;                     // -90..90

    @Column(name = "lng", nullable = false, precision = 11)
    private Double longitude;                    // -180..180

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;                   // thứ tự trong ngày (1,2,3...)

    @Column(columnDefinition = "TEXT")
    private String description;                  // mô tả ngắn cho info window

    @Column(name = "stop_type", length = 30)
    private String stopType;                     // ATTRACTION | HOTEL | RESTAURANT | TRANSPORT | START | END
}
```

**Tại sao nullable `itineraryDay`?**
Tour có thể có lộ trình tự do (tour 1 ngày, tour open-ended) không chia ngày. Cũng phòng khi admin tạo stop trước khi xác định day.

**Tại sao `Double` không `BigDecimal`?**
Google Maps API nhận `number` JavaScript. `Double` đủ chính xác cho hiển thị bản đồ (~11cm precision với 7 chữ số thập phân). Không phải tính toán tài chính.

**Tại sao có `stop_type`?**
FE có thể đổi màu/icon pin theo loại (xanh = attraction, đỏ = hotel, vàng = restaurant). Không enum để tránh migration mỗi khi thêm loại.

### 2.2. Repository

```java
public interface TourStopRepository extends JpaRepository<TourStop, Integer> {
    @Query("SELECT s FROM TourStop s LEFT JOIN FETCH s.itineraryDay " +
           "WHERE s.tour.tourID = :tourId " +
           "ORDER BY COALESCE(s.itineraryDay.dayNumber, 0), s.stopOrder")
    List<TourStop> findByTourIdOrdered(@Param("tourId") Integer tourId);

    @Query("SELECT s FROM TourStop s LEFT JOIN FETCH s.itineraryDay " +
           "WHERE s.tour.tourCode = :tourCode " +
           "ORDER BY COALESCE(s.itineraryDay.dayNumber, 0), s.stopOrder")
    List<TourStop> findByTourCodeOrdered(@Param("tourCode") String tourCode);

    void deleteByTour_TourID(Integer tourId);
}
```

Sort: `COALESCE(dayNumber, 0)` trước (null → 0 = không gắn day → hiển thị đầu), rồi `stopOrder` trong cùng day.

### 2.3. DTO Response

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TourStopResponse {
    private Integer stopId;
    private String name;
    private Double latitude;
    private Double longitude;
    private Integer stopOrder;
    private String description;
    private String stopType;

    // Thông tin day (nullable)
    private Integer dayNumber;
    private String dayTitle;
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TourRouteResponse {
    private String tourCode;
    private List<TourStopResponse> stops;
    // Bounding box để FE auto-fit map
    private Double minLat, maxLat, minLng, maxLng;
    // Số ngày có stop để FE render chip filter
    private List<Integer> availableDays;
}
```

### 2.4. Service

```java
public interface TourRouteService {
    TourRouteResponse getRoute(String tourCode);
    List<TourStopResponse> upsertStops(Integer tourId, List<TourStopRequest> stops);
    void deleteStop(Integer stopId);
}
```

**Logic `getRoute`:**
1. Query `findByTourCodeOrdered`.
2. Map sang DTO.
3. Tính bounding box (min/max lat/lng) → FE dùng `map.fitBounds()`.
4. Trả list ngày unique (dayNumber not null, distinct, sorted) cho chip filter.

**Logic `upsertStops` (admin):**
Nhận list stops mới, xoá tất cả stops cũ của tour, lưu list mới. Atomic trong 1 transaction. Đơn giản hơn diff-based update — admin nhập lại toàn bộ mỗi lần edit.

### 2.5. DTO Request (admin nhập)

```java
@Data @NoArgsConstructor @AllArgsConstructor
public class TourStopRequest {
    @NotBlank private String name;
    @NotNull @DecimalMin("-90") @DecimalMax("90")   private Double latitude;
    @NotNull @DecimalMin("-180") @DecimalMax("180") private Double longitude;
    @NotNull private Integer stopOrder;
    private String description;
    private String stopType;
    private Integer itineraryDayId;   // null nếu chưa gắn day
}
```

### 2.6. Endpoints

```java
// Public — FE tour detail
@GetMapping("/api/tours/{tourCode}/route")
public ResponseEntity<?> getRoute(@PathVariable String tourCode) {...}

// Admin — quản lý pin
@GetMapping("/api/admin/tours/{tourId}/stops")
public ResponseEntity<?> getStops(@PathVariable Integer tourId) {...}

@PutMapping("/api/admin/tours/{tourId}/stops")
public ResponseEntity<?> upsertStops(@PathVariable Integer tourId,
                                     @Valid @RequestBody List<TourStopRequest> body) {...}

@DeleteMapping("/api/admin/tours/stops/{stopId}")
public ResponseEntity<?> deleteStop(@PathVariable Integer stopId) {...}
```

### 2.7. Gateway route

`/api/tours/**` và `/api/admin/tours/**` đã route đến `tour-catalog-service` — không cần sửa gateway.

### 2.8. Migration data

Sau khi deploy entity mới, Hibernate `ddl-auto: update` tự tạo bảng. Admin sẽ nhập dần các stop cho từng tour qua UI. **Không seed sẵn** — toạ độ phải chính xác, không tự đoán được.

---

## 3. Thiết kế Frontend (client-side) — Public

### 3.1. Install package

```bash
cd client-side
npm install leaflet react-leaflet
```

Không cần `.env`, không cần API key, không cần Billing.

### 3.2. Import CSS Leaflet (1 lần ở entry)

Mở `client-side/src/index.tsx` (hoặc `App.tsx`):

```jsx
import 'leaflet/dist/leaflet.css';   // ← THÊM 1 dòng ở đầu file
```

Bắt buộc — nếu thiếu CSS, marker icon bị vỡ + tile xếp lệch.

### 3.3. API service

`client-side/src/services/tour/tourRouteApi.js`:
```javascript
import axios from '../../utils/axiosCustomize';
const tourRouteApi = {
    getRoute: (tourCode) => axios.get(`/tours/${tourCode}/route`)
        .then(r => r.data?.data ?? r.data),
};
export default tourRouteApi;
```

### 3.4. Component `<TourRouteMap />`

`client-side/src/components/TourDetailComponent/TourRoute/TourRouteMap.jsx`:

```jsx
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { MapContainer, TileLayer, Marker, Popup, Polyline, useMap } from 'react-leaflet';
import L from 'leaflet';
import { MapPin, Calendar } from 'lucide-react';
import tourRouteApi from '../../../services/tour/tourRouteApi';
import styles from './TourRouteMap.module.scss';

const DAY_COLORS = ['#1e40af', '#dc2626', '#059669', '#d97706', '#7c3aed', '#0891b2'];

/**
 * Tạo custom marker icon: chấm tròn có số thứ tự bên trong, màu theo day.
 * Leaflet's L.divIcon = HTML thuần → CSS hóa thoải mái.
 */
const makeIcon = (number, color) => L.divIcon({
    className: styles.markerWrap,
    html: `<div class="${styles.markerPin}" style="background:${color}">
             <span>${number}</span>
           </div>`,
    iconSize: [32, 32],
    iconAnchor: [16, 16],     // center the marker on the coord
    popupAnchor: [0, -16],
});

/** Helper: auto-fit map vào bounds các stop. Gọi qua useMap (chỉ chạy trong MapContainer). */
const FitBounds = ({ stops }) => {
    const map = useMap();
    useEffect(() => {
        if (stops.length === 0) return;
        if (stops.length === 1) {
            map.setView([stops[0].latitude, stops[0].longitude], 13);
            return;
        }
        const bounds = L.latLngBounds(stops.map(s => [s.latitude, s.longitude]));
        map.fitBounds(bounds, { padding: [40, 40] });
    }, [stops, map]);
    return null;
};

const TourRouteMap = ({ tourCode }) => {
    const [route, setRoute] = useState(null);
    const [selectedDay, setSelectedDay] = useState(null);

    useEffect(() => {
        if (!tourCode) return;
        tourRouteApi.getRoute(tourCode).then(setRoute).catch(() => setRoute(null));
    }, [tourCode]);

    const visibleStops = useMemo(() => {
        if (!route?.stops) return [];
        return selectedDay == null
            ? route.stops
            : route.stops.filter(s => s.dayNumber === selectedDay);
    }, [route, selectedDay]);

    if (!route || route.stops.length === 0) return null;   // Ẩn nếu tour chưa có stop

    const center = [route.stops[0].latitude, route.stops[0].longitude];
    const polylinePath = visibleStops.map(s => [s.latitude, s.longitude]);

    return (
        <div className={styles.routeSection}>
            <h3 className={styles.title}><MapPin size={18} /> Lộ trình tour trên bản đồ</h3>

            {/* Chip filter theo ngày */}
            {route.availableDays?.length > 0 && (
                <div className={styles.chips}>
                    <button
                        className={`${styles.chip} ${selectedDay == null ? styles.chipActive : ''}`}
                        onClick={() => setSelectedDay(null)}>
                        Tất cả ({route.stops.length})
                    </button>
                    {route.availableDays.map(day => {
                        const count = route.stops.filter(s => s.dayNumber === day).length;
                        const color = DAY_COLORS[(day - 1) % DAY_COLORS.length];
                        return (
                            <button key={day}
                                style={{ '--accent': color }}
                                className={`${styles.chip} ${selectedDay === day ? styles.chipActive : ''}`}
                                onClick={() => setSelectedDay(day)}>
                                <Calendar size={12} /> Ngày {day} ({count})
                            </button>
                        );
                    })}
                </div>
            )}

            <MapContainer
                center={center}
                zoom={10}
                scrollWheelZoom={false}
                className={styles.mapContainer}
            >
                <TileLayer
                    attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
                    url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                    maxZoom={19}
                />

                <FitBounds stops={visibleStops} />

                {visibleStops.map((s, idx) => {
                    const color = s.dayNumber
                        ? DAY_COLORS[(s.dayNumber - 1) % DAY_COLORS.length]
                        : '#64748b';
                    return (
                        <Marker
                            key={s.stopId}
                            position={[s.latitude, s.longitude]}
                            icon={makeIcon(idx + 1, color)}
                        >
                            <Popup>
                                <div className={styles.popup}>
                                    <h4>{s.name}</h4>
                                    {s.dayNumber && (
                                        <span className={styles.popupDay}>
                                            Ngày {s.dayNumber}{s.dayTitle ? `: ${s.dayTitle}` : ''}
                                        </span>
                                    )}
                                    {s.description && <p>{s.description}</p>}
                                </div>
                            </Popup>
                        </Marker>
                    );
                })}

                {visibleStops.length >= 2 && (
                    <Polyline
                        positions={polylinePath}
                        pathOptions={{ color: '#1e40af', weight: 3, opacity: 0.7 }}
                    />
                )}
            </MapContainer>

            <div className={styles.legend}>
                Số trên pin = thứ tự dừng. Click pin để xem chi tiết.
                Đường xanh nối các điểm theo thứ tự lộ trình
                (chỉ minh hoạ thứ tự, không phải đường đi thực).
            </div>
        </div>
    );
};

export default TourRouteMap;
```

### 3.5. Style — `TourRouteMap.module.scss`

```scss
.routeSection {
    margin: 24px 0;
    padding: 18px;
    background: #fff;
    border-radius: 12px;
    border: 1px solid #e2e8f0;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.title {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 16px;
    font-weight: 700;
    color: #1e293b;
    margin: 0 0 14px;
}

.chips {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    margin-bottom: 12px;
}

.chip {
    --accent: #64748b;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 5px 12px;
    border-radius: 999px;
    border: 1px solid #e2e8f0;
    background: #f8fafc;
    color: #475569;
    font-size: 12.5px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.18s;
    &:hover { background: #f1f5f9; }
}

.chipActive {
    background: var(--accent);
    color: #fff;
    border-color: var(--accent);
}

.mapContainer {
    width: 100%;
    height: 480px;
    border-radius: 10px;
    z-index: 0;   // Tránh đè lên modal/dropdown khác

    @media (max-width: 768px) {
        height: 320px;
    }
}

/* Custom marker — bypass default leaflet icon */
.markerWrap { background: transparent !important; border: none !important; }

:global(.leaflet-div-icon) {
    background: transparent;
    border: none;
}

.markerPin {
    width: 32px; height: 32px;
    border-radius: 50%;
    border: 3px solid #fff;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.25);
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    font-weight: 700;
    font-size: 13px;
}

.popup {
    min-width: 180px;
    max-width: 240px;
    h4 { margin: 0 0 6px; font-size: 14px; color: #1e293b; }
    p  { margin: 6px 0 0; font-size: 12.5px; color: #475569; line-height: 1.4; }
}

.popupDay {
    display: inline-block;
    font-size: 11px;
    font-weight: 600;
    color: #1e40af;
    background: #eff6ff;
    padding: 2px 8px;
    border-radius: 999px;
}

.legend {
    margin-top: 10px;
    font-size: 11.5px;
    font-style: italic;
    color: #64748b;
}
```

### 3.6. Gắn vào `TourDetail.jsx`

Vị trí: trên `<TourItinerary>` — user nhìn bản đồ tổng quan trước, rồi đọc chi tiết itinerary text.

```jsx
<TourInformation />
<TourRouteMap tourCode={tourData.tourCode} />   {/* ← NEW */}
<TourItinerary />
<TourPolicy />
<TourReviews />
```

### 3.7. Lưu ý vận hành OpenStreetMap

- OSM **tile usage policy**: yêu cầu hiển thị attribution (đã có sẵn ở `TileLayer attribution=...`) và không tự ý cache/scrape tile.
- Soft cap ≈ 100 req/giây từ 1 IP. Với trang tour detail bình thường (load ~30 tile mỗi view), giới hạn này quá thoải mái.
- Nếu sau này traffic lớn (>100k page view/ngày), cân nhắc:
  - **Carto Voyager**: free 75k map view/tháng (cần đăng ký, không thẻ).
  - **Stadia Maps**: free 200k map view/tháng (cần đăng ký, không thẻ).
  - Tự host tile bằng [tileserver-gl](https://github.com/maptiler/tileserver-gl).

---

## 4. Thiết kế Frontend — Admin

### 4.1. Trang quản lý stops cho 1 tour

Thêm tab "Bản đồ lộ trình" trong trang admin tour edit.

UI:
- Bảng: STT | Tên điểm | Lat | Lng | Loại | Ngày | Mô tả | [Xoá]
- Mỗi row có input lat/lng — có thể paste từ Google Maps (right-click → copy coordinates).
- Nút "+ Thêm điểm" thêm row mới.
- Nút "Lưu tất cả" gọi PUT upsert.
- Sidebar bên phải: bản đồ preview live (Marker theo input hiện tại).

### 4.2. Helper paste toạ độ

User flow (lấy toạ độ nhanh — vẫn dùng Google Maps web thường, KHÔNG cần API key vì chỉ copy số):
1. Mở [google.com/maps](https://google.com/maps) bằng trình duyệt thường.
2. Click chuột phải vào điểm muốn pin.
3. Bấm vào cặp số toạ độ ở đầu menu (Google Maps copy "20.910, 107.184" vào clipboard).
4. Paste vào ô "Lat, Lng" trong admin → tự split thành 2 ô.

Hoặc cách thay thế (không cần Google):
- Mở [www.openstreetmap.org](https://www.openstreetmap.org), click chuột phải vào điểm → "Show address" → copy lat/lng từ URL.
- Hoặc dùng chính preview Leaflet trong admin: click 1 điểm bất kỳ trên map → tự fill lat/lng vào row hiện active (xem 4.3).

```jsx
const handlePasteCoords = (e, idx) => {
    const text = e.clipboardData.getData('text');
    const match = text.match(/(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)/);
    if (match) {
        e.preventDefault();
        updateStop(idx, { latitude: parseFloat(match[1]), longitude: parseFloat(match[2]) });
    }
};
```

### 4.3. Click-to-pin trên preview map (riêng cho Leaflet)

Leaflet expose event `click` của `MapContainer` qua hook `useMapEvents` — admin click bất kỳ điểm nào trên bản đồ preview → fill lat/lng vào row đang được chọn. Tiện hơn nhiều so với paste từ Google Maps:

```jsx
import { useMapEvents } from 'react-leaflet';

const ClickToPin = ({ onPick }) => {
    useMapEvents({
        click: (e) => onPick(e.latlng.lat, e.latlng.lng),
    });
    return null;
};

// Trong admin editor:
<MapContainer center={[16.0, 108.0]} zoom={6}>
    <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
    <ClickToPin onPick={(lat, lng) => updateStop(activeIdx, { latitude: lat, longitude: lng })} />
    {stops.map(s => (
        <Marker key={s.tempId} position={[s.latitude || 0, s.longitude || 0]} />
    ))}
</MapContainer>
```

Workflow admin tối ưu:
1. Click "+ Thêm điểm" → row trống được active.
2. Click vào vị trí trên map → tự fill lat/lng.
3. Nhập tên + mô tả + chọn day.
4. Lặp lại cho stop tiếp theo.

Nhanh hơn paste rất nhiều, không cần switch tab.

### 4.4. Validate

- Lat trong [-90, 90], Lng trong [-180, 180] — block save với toast cảnh báo.
- Tên không trống.
- Cảnh báo (không block) nếu 2 stop cách nhau > 500km (giảm sai sót copy nhầm).

### 4.5. RBAC

Endpoint admin → `AdminAuthInterceptor` đã có sẵn (tour-catalog-service). Add `@RequireAdmin` nếu muốn chỉ ADMIN sửa stop (MODERATOR chỉ xem). Mặc định plan: ai vào admin tour edit đều sửa được.

---

## 5. Phase rollout

| Phase | Task | Effort |
|---|---|---|
| **Phase 1 — Backend foundation** | Entity + Repository + DTO + Migration auto | 0.25 ngày |
| **Phase 2 — Backend service + endpoints** | `TourRouteService` + 4 endpoints + bounding box logic | 0.25 ngày |
| **Phase 3 — Backend build + deploy + verify** | Test với data mẫu insert thủ công | 0.25 ngày |
| **Phase 4 — Frontend public** | Install `@react-google-maps/api`, component `<TourRouteMap>` + chip filter + polyline | 0.5 ngày |
| **Phase 5 — Frontend admin** | Trang quản lý stops + paste coord + preview map | 0.5 ngày |
| **Phase 6 — Seed data + polish** | Nhập stops cho 5-10 tour phổ biến, fine-tune màu/kích thước pin | 0.25 ngày |
| **Tổng** | | **2 ngày** |

---

## 6. Edge case & rủi ro

### 6.1. Edge cases

| Case | Xử lý |
|---|---|
| Tour chưa có stop nào | `<TourRouteMap>` return `null` — không render gì |
| Tour có 1 stop duy nhất | Hiển thị pin + ẩn polyline (cần ≥ 2 điểm); `FitBounds` zoom level 13 |
| Stop không gắn day | Hiển thị bình thường, popup không có dòng "Ngày X", không có trong chip filter |
| Day có 0 stop (chip filter rỗng) | Disable chip + tooltip "Chưa có điểm dừng" |
| Lat/Lng = 0,0 (Null Island bug — null/default) | Validate ở admin (block save); FE filter `stop.latitude !== 0 \|\| stop.longitude !== 0` |
| Tour nhiều stop chồng lên nhau (cùng toạ độ) | Leaflet xếp chồng marker; popup vẫn click riêng được. Nếu cần spider cluster → dùng `leaflet.markercluster` (out of scope) |
| User mobile portrait | Map height giảm còn 320px qua media query; chip filter scroll ngang; `scrollWheelZoom={false}` tránh khóa scroll trang |
| Tile OSM load chậm/timeout | Leaflet auto-retry; user vẫn thấy marker + polyline trên nền xám (acceptable) |
| Marker icon default bị vỡ (404 marker-icon.png) | Đã giải quyết bằng `L.divIcon` custom — không phụ thuộc icon assets của Leaflet |

### 6.2. Rủi ro

| Rủi ro | Xác suất | Tác động | Giảm thiểu |
|---|---|---|---|
| OSM tile usage policy: vượt soft cap | Rất thấp với traffic hiện tại | Trung bình | OSM cho phép ~100 req/s/IP. Nếu vượt → đổi sang Carto/Stadia free tier (vẫn không cần thẻ); hoặc tự host tileserver |
| Admin nhập sai toạ độ → pin lệch | Trung bình | Thấp | Click-to-pin trên preview map live + cảnh báo nếu 2 stop > 500km |
| Itinerary thay đổi nhưng stops không update | Trung bình | Thấp | Admin UI nhắc nhở "Tour có 3 ngày itinerary nhưng chỉ 2 ngày có stop" |
| Polyline đi xuyên núi/biển (không thực tế) | Cao | Thấp | Đây là feature, không phải bug — plan vẽ đường thẳng nối thứ tự để minh hoạ. Đã có disclaimer ở legend: "chỉ minh hoạ thứ tự, không phải đường đi thực" |
| Leaflet CSS không import → marker icon vỡ | Cao nếu quên | Thấp | Bắt buộc `import 'leaflet/dist/leaflet.css'` ở `index.tsx`; đã ghi rõ trong section 3.2 |
| OSM ngừng phục vụ public tile (chính sách đổi) | Rất thấp | Cao | Lưu lựa chọn fallback Carto/Stadia trong code (TileLayer URL configurable qua env var) |

### 6.3. Quyết định kiến trúc đáng lưu ý

- **Vì sao Leaflet + OSM thay vì Google Maps?** Không cần API key, Billing account, thẻ tín dụng. Miễn phí vĩnh viễn. Đủ tính năng cho hiển thị pin + polyline + popup. Bundle nhẹ hơn ~3 lần.
- **Vì sao không dùng routing service (OSRM/GraphHopper) vẽ đường đi thực?** Phức tạp thêm 1 service phụ thuộc; rate-limit; UX value không nhiều hơn polyline thẳng. Plan giữ đơn giản. Phase sau nếu cần.
- **Vì sao không tự geocode tên địa danh?** Nominatim (OSM) hạn chế 1 req/s; tiếng Việt cho địa danh nhỏ (vd "Hang Sửng Sốt") chính xác kém. Admin paste/click chính xác hơn.
- **Vì sao bảng riêng `tour_stops` thay vì JSON column trên `tours`?** Query/filter theo day dễ; index theo tour_id; cascade delete; xuất CSV/report dễ hơn.
- **Vì sao dùng `L.divIcon` thay vì marker mặc định?** (1) Bypass bug "marker icon 404" khét tiếng của Leaflet với webpack; (2) Custom màu theo day; (3) Hiển thị số thứ tự bên trong pin — không làm được với icon image.
- **Vì sao không cluster marker?** Với tour 5-15 stop thì cluster không có giá trị. Phức tạp thêm code. Out of scope.
- **Vì sao `scrollWheelZoom={false}` mặc định?** Trang tour detail thường dài → user scroll qua map sẽ bị "kẹt" zoom map thay vì scroll trang. Tắt scroll wheel zoom, user vẫn dùng nút +/- ở góc map được.
- **Vì sao chip filter theo `dayNumber` thay vì `itineraryDayId`?** dayNumber stable hơn — admin xoá/tạo lại ItineraryDay vẫn giữ chip "Ngày 1" đúng nghĩa.

---

## 7. Verification

### 7.1. Test thủ công sau implement
1. Insert 5 stop cho tour ID=1 qua admin UI (paste coord từ Google Maps).
2. GET `/api/tours/{tourCode}/route` → JSON có 5 stops, bounding box đúng.
3. Vào trang tour detail → thấy map render đủ 5 pin + polyline nối + chip "Ngày 1, 2".
4. Click chip "Ngày 1" → chỉ pin của ngày 1 hiển thị + map auto-fit zoom.
5. Click 1 pin → InfoWindow hiện tên + day + description.
6. Tour chưa có stop → không hiển thị section map (return null).

### 7.2. Test cấu hình
- Verify `leaflet/dist/leaflet.css` đã import: marker icon hiển thị đúng kích cỡ, tile xếp đúng hàng.
- Verify attribution OSM hiển thị ở góc phải dưới map (tuân thủ usage policy).
- Verify mobile responsive: map height 320px, chip filter scroll ngang.

---

## 8. Tác động & Sprint sau

- **SEO**: Google Maps load qua JS → không boost SEO. Nếu cần SEO, dùng Static Maps API trả PNG cho server-rendered (out of scope).
- **Mobile app**: nếu sau có app, dùng cùng endpoint `/route` → render bằng `react-native-maps`. Schema không đổi.
- **Sprint sau có thể mở rộng**:
  - Heatmap density tour (cho admin biết tour cùng vùng).
  - Tour đề xuất "Tour gần đây" dùng bounding box query.
  - Calculate distance giữa các stop hiển thị "~12 km tới điểm tiếp theo".

---

## 9. Files cần tạo/sửa

**Backend (tour-catalog-service):**
- ✨ `entity/TourStop.java`
- ✨ `repository/TourStopRepository.java`
- ✨ `dto/request/TourStopRequest.java`
- ✨ `dto/response/TourStopResponse.java`
- ✨ `dto/response/TourRouteResponse.java`
- ✨ `service/TourRouteService.java` + `impl/TourRouteServiceImpl.java`
- ✨ `controller/TourRouteController.java`

**Frontend public (client-side):**
- ✨ `services/tour/tourRouteApi.js`
- ✨ `components/TourDetailComponent/TourRoute/TourRouteMap.jsx`
- ✨ `components/TourDetailComponent/TourRoute/TourRouteMap.module.scss`
- ✏ `components/TourDetailComponent/TourDetail.jsx` — gắn `<TourRouteMap>`
- ✏ `src/index.tsx` (hoặc `App.tsx`) — `import 'leaflet/dist/leaflet.css'`
- ✏ `package.json` — `leaflet`, `react-leaflet`

**Frontend admin (client-side):**
- ✨ `components/AdminComponent/Pages/ToursPage/TourStopsEditor/TourStopsEditor.jsx`
- ✨ `components/AdminComponent/Pages/ToursPage/TourStopsEditor/TourStopsEditor.module.scss`
- ✨ `services/tour/adminTourRouteApi.js`
- ✏ Trang admin tour edit hiện có — thêm tab "Bản đồ lộ trình"

---

## 10. Setup trước khi code

**Tin tốt**: Leaflet + OpenStreetMap không cần đăng ký account, không cần API key, không cần Billing — chỉ install package và code.

Checklist nhanh:
- [ ] `npm install leaflet react-leaflet` trong `client-side/`
- [ ] Thêm `import 'leaflet/dist/leaflet.css'` ở `src/index.tsx` (hoặc `App.tsx`)
- [ ] Verify trong dev: tạo MapContainer test → thấy map render + có chữ "© OpenStreetMap" ở góc.

Tùy chọn (chỉ nếu sau này traffic cao):
- [ ] Cân nhắc đăng ký **Stadia Maps** (free 200k/tháng, không thẻ) hoặc **Carto** để có style đẹp hơn.
- [ ] Cấu hình `TileLayer url` đọc từ env var để dễ đổi provider sau này.
