# Plan: Đồng bộ Lịch trình tour ↔ Lộ trình bản đồ — Sync Itinerary với Tour Route Map

> **Trạng thái**: 📋 Đang lên kế hoạch
> **Effort dự kiến**: 1.5 ngày
>
> **Quyết định thiết kế đã chốt:**
> - **Title day**: KHÔNG đụng `title` admin gõ tay. BE compute thêm `autoSubtitle` on-the-fly từ stops, FE render dưới title trong accordion header. Stop names → join bằng "→".
> - **Thứ tự stops trong day**: Stop mới append cuối day (`stopOrder = max + 1`). Admin có thể drag-reorder sau (out of scope phase đầu).
> **Phạm vi**:
> - `tour-catalog-service` (backend — validate + sync logic + endpoint trả đồng thời 2 nguồn)
> - `client-side/.../TourDetailComponent` (frontend — render itinerary có badge số pin khớp + scroll-to-map)
> - `client-side/.../AdminComponent/Pages/ToursPage` (admin — editor 2 trong 1: nhập day & stops cùng 1 chỗ)

---

## 1. Vấn đề hiện tại

### 1.1. 2 nguồn dữ liệu rời rạc
| Nguồn | Cấu trúc | Quản lý ở | Display ở |
|---|---|---|---|
| **Lịch trình** (`itinerary_days`) | `dayNumber`, `title`, `meals`, `details` (HTML) | `ToursForm > ItineraryTab` | `<TourItinerary>` accordion |
| **Lộ trình bản đồ** (`tour_stops`) | `name`, `lat/lng`, `stopOrder`, `dayNumber`, `description` | `/admin/tours/:id/stops` (TourStopsEditor) | `<TourRouteMap>` |

→ Admin phải nhập trùng lặp ở 2 chỗ, dễ thiếu hoặc lệch:
- Itinerary nói "Ngày 1: thăm Hang Sửng Sốt + Hang Luồn", map chỉ có Hang Sửng Sốt.
- Map có pin "Cảng Tuần Châu" (day 1), itinerary day 1 không nhắc đến.
- Đổi `dayNumber` của Itinerary (drag-reorder) → stop vẫn trỏ `dayNumber` cũ → lệch.

### 1.2. Trải nghiệm user kém
- User đọc itinerary "Ngày 1: thăm Hang Luồn" nhưng không biết vị trí nó trên bản đồ ở chỗ nào.
- Click pin trên map thì popup chỉ có tên + mô tả ngắn — không liên kết về dòng itinerary tương ứng.

### 1.3. Vấn đề kỹ thuật đã quan sát
- Bug hôm nay: đổi cột "Ngày" trong TourStopsEditor → save → reload mất (chưa wire `dayNumber → itineraryDayId`). Đã sửa BE nhưng vẫn là dấu hiệu **đồng bộ giữa 2 schema yếu**.

---

## 2. Mục tiêu

1. **1 nguồn sự thật cho lịch trình theo ngày**: cấu trúc Day → list Stops, không có Stop "mồ côi" hoặc Day "không có pin".
2. **Admin nhập 1 lần**: Một editor duy nhất "Quản lý ngày + điểm dừng" — tạo Day → trong Day đó thêm Stops.
3. **User thấy 2 view khớp nhau**: Trong itinerary mỗi Day hiển thị danh sách điểm dừng có **số thứ tự khớp pin trên map**; click vào dòng điểm → map zoom + mở popup pin tương ứng.
4. **Cảnh báo lệch**: Admin form hiện badge cảnh báo nếu một Day trong itinerary không có stop nào.

---

## 3. Thiết kế Backend

### 3.1. Không thay schema
Giữ nguyên `itinerary_days` và `tour_stops`. Sync hoàn toàn qua **logic ứng dụng + UX**, không migrate DB.

**Lý do**: 2 bảng đã production, có data thật. Migrate sang JSON column hay parent-child mới sẽ nguy hiểm + tốn effort. Quan hệ FK `tour_stops.itinerary_day_id → itinerary_days.itinerary_dayid` đã có (nullable) — đủ để liên kết.

### 3.2. Bổ sung endpoint composite

**Mục tiêu**: 1 request → đủ data render cả `<TourItinerary>` + `<TourRouteMap>` khớp nhau.

```java
@GetMapping("/api/tours/{tourCode}/itinerary-with-route")
public ResponseEntity<?> getItineraryWithRoute(@PathVariable String tourCode) {
    return ResponseEntity.ok(Map.of("success", true,
            "data", itineraryRouteService.getCombined(tourCode)));
}
```

Response shape:
```json
{
  "tourCode": "NDNHA7861",
  "days": [
    {
      "itineraryDayId": 12,
      "dayNumber": 1,
      "title": "SB Nội Bài - Hà Nội",
      "autoSubtitle": "Cảng Tuần Châu → Hang Sửng Sốt → Hang Luồn",
      "meals": "Trưa, Tối",
      "details": "<p>HTML rich text...</p>",
      "color": "#1e40af",
      "stops": [
        {
          "stopId": 14,
          "name": "Cảng Tuần Châu",
          "latitude": 20.910,
          "longitude": 106.996,
          "stopOrder": 1,
          "globalIndex": 1,
          "description": "Điểm bắt đầu",
          "stopType": "START"
        },
        { "stopId": 15, "name": "Hang Sửng Sốt", "globalIndex": 2, ... }
      ]
    },
    {
      "itineraryDayId": 13, "dayNumber": 2, "title": "Hà Nội - Hạ Long",
      "stops": [ { "globalIndex": 4, ... } ]
    }
  ],
  "orphanStops": [],
  "missingStopDays": [],
  "bounds": { "minLat":..., "maxLat":..., "minLng":..., "maxLng":... }
}
```

**Trường mới quan trọng:**
- `globalIndex` — số thứ tự pin toàn cục (1..N) khớp với badge trên map. FE dùng để render badge cạnh tên stop trong itinerary.
- `orphanStops` — list stop có `dayNumber` null hoặc trỏ vào day không tồn tại. Admin cần sửa.
- `missingStopDays` — list `dayNumber` có trong `itinerary_days` nhưng chưa có stop nào → admin cần thêm pin.
- `color` — màu day (cycle theo `dayNumber - 1 % 6`), BE compute để FE/map dùng nhất quán.

### 3.3. Service mới

```java
@Service
@RequiredArgsConstructor
public class ItineraryRouteService {
    private final TourRepository tourRepo;
    private final ItineraryDayRepository dayRepo;
    private final TourStopRepository stopRepo;

    public CombinedResponse getCombined(String tourCode) {
        Tour tour = tourRepo.findDetailByTourCode(tourCode).orElseThrow(...);
        List<ItineraryDay> days = dayRepo.findByTourIdOrdered(tour.getTourID());
        List<TourStop> stops = stopRepo.findByTourCodeOrdered(tourCode);

        // Index globalIndex theo thứ tự appear (đã sort COALESCE(day,0), stopOrder)
        AtomicInteger gi = new AtomicInteger(1);
        Map<Integer, List<StopDTO>> stopsByDayNumber = new LinkedHashMap<>();
        List<StopDTO> orphans = new ArrayList<>();
        for (TourStop s : stops) {
            Integer dn = s.getItineraryDay() != null ? s.getItineraryDay().getDayNumber() : null;
            StopDTO dto = mapStop(s, gi.getAndIncrement());
            if (dn == null) orphans.add(dto);
            else stopsByDayNumber.computeIfAbsent(dn, k -> new ArrayList<>()).add(dto);
        }

        // Build day blocks
        List<DayBlock> blocks = days.stream().map(d -> DayBlock.builder()
            .itineraryDayId(d.getItineraryDayID())
            .dayNumber(d.getDayNumber())
            .title(d.getTitle())
            .meals(d.getMeals())
            .details(d.getDetails())
            .color(dayColor(d.getDayNumber()))
            .stops(stopsByDayNumber.getOrDefault(d.getDayNumber(), List.of()))
            .build()
        ).toList();

        // Missing: day có trong itinerary nhưng list stop rỗng
        List<Integer> missing = blocks.stream()
            .filter(b -> b.getStops().isEmpty())
            .map(DayBlock::getDayNumber)
            .toList();

        return CombinedResponse.builder()
            .tourCode(tourCode)
            .days(blocks)
            .orphanStops(orphans)
            .missingStopDays(missing)
            .bounds(computeBounds(stops))
            .build();
    }

    private static String dayColor(Integer n) {
        String[] COLORS = {"#1e40af","#dc2626","#059669","#d97706","#7c3aed","#0891b2"};
        return n == null ? "#64748b" : COLORS[(n - 1) % COLORS.length];
    }
}
```

### 3.4. Validate khi upsert stops

Trong `TourRouteServiceImpl.upsertStops`, thêm step validate sau khi resolve `dayNumber → ItineraryDay`:

```java
if (req.getDayNumber() != null && day == null) {
    // dayNumber không tồn tại trong itinerary — vẫn cho lưu nhưng log warn
    log.warn("Tour {} stop '{}' references dayNumber {} không tồn tại trong itinerary_days",
             tourId, req.getName(), req.getDayNumber());
}
```

Không reject (mềm) vì admin có thể tạo stop trước khi có itinerary. Nhưng response trả về số `orphan` để FE hiển thị cảnh báo.

### 3.5. Endpoint phụ trợ cho admin editor

```java
@GetMapping("/api/admin/tours/{tourId}/days")
public ResponseEntity<?> getDays(@PathVariable Integer tourId) {
    // Trả [{itineraryDayId, dayNumber, title}] để dropdown "Ngày" trong stops editor hiển thị tên day,
    // không phải chỉ số khô khan.
}
```

### 3.6. Endpoint check trùng (optional)

```java
@GetMapping("/api/admin/tours/{tourId}/sync-status")
public ResponseEntity<?> syncStatus(@PathVariable Integer tourId) {
    // Trả: { daysTotal, daysWithStops, stopsTotal, orphanStops, warnings: [...] }
    // Dashboard admin có thể list các tour có vấn đề.
}
```

---

## 4. Thiết kế Frontend Public — Sync 2 view

### 4.1. Đổi endpoint TourDetail dùng

Trang chi tiết tour hiện gọi:
- `GET /tours/{code}` → trả tour kèm itinerary
- `GET /tours/{code}/route` → trả stops cho map

**Đổi thành**: gọi thêm `/tours/{code}/itinerary-with-route` để có `globalIndex` đồng bộ. Không bỏ 2 endpoint cũ (BE backward compat); FE component mới đọc endpoint composite.

### 4.2. `<TourItinerary>` thêm danh sách điểm dừng

Bên trong mỗi day accordion body, **trên** phần `details` HTML, render list điểm dừng nếu có:

```jsx
{day.stops.length > 0 && (
    <div className={styles.stopList}>
        <h5 className={styles.stopListTitle}>
            <MapPin size={13} /> Điểm dừng trong ngày ({day.stops.length})
        </h5>
        {day.stops.map(stop => (
            <button key={stop.stopId}
                    className={styles.stopRow}
                    onClick={() => scrollToMapAndOpenPin(stop.globalIndex)}>
                <span className={styles.stopBadge}
                      style={{ background: day.color }}>
                    {stop.globalIndex}
                </span>
                <div className={styles.stopMeta}>
                    <strong>{stop.name}</strong>
                    {stop.description && <span>{stop.description}</span>}
                </div>
                <span className={styles.stopJump}>Xem trên bản đồ →</span>
            </button>
        ))}
    </div>
)}
<div dangerouslySetInnerHTML={{ __html: day.details }} />
```

**Số badge = `globalIndex`** giống số trên pin → user nhìn thấy "Hang Sửng Sốt #2" trong itinerary và "#2" trên map → biết chính xác.

### 4.3. Scroll + open pin từ itinerary

Cơ chế:
1. `<TourItinerary>` và `<TourRouteMap>` chia sẻ context (lift state lên `<TourDetail>` hoặc dùng React Context riêng).
2. Click row stop trong itinerary → set `highlightedStopGlobalIndex = N`.
3. `<TourRouteMap>` nhận prop `highlightedIndex` → useEffect khi đổi:
   - Scroll page tới map container (`mapRef.current.scrollIntoView({ behavior: 'smooth' })`).
   - Gọi `map.flyTo([lat, lng], 14)` rồi `marker.openPopup()`.

### 4.4. Click pin → highlight dòng itinerary

Ngược lại: click pin trong map → set `expandedDayNumber = pin.dayNumber` + scroll itinerary tới row stop tương ứng. Day accordion tự mở.

### 4.5. State chia sẻ — pattern

Trong `TourDetail.jsx`:

```jsx
const [combined, setCombined] = useState(null);   // data từ /itinerary-with-route
const [highlightedStop, setHighlightedStop] = useState(null);   // {stopId, globalIndex}

// load 1 lần thay vì 2 fetch riêng
useEffect(() => {
    if (!tourCode) return;
    tourRouteApi.getCombined(tourCode).then(setCombined);
}, [tourCode]);

<TourRouteMap combined={combined} highlighted={highlightedStop}
              onPinClick={(stop) => setHighlightedStop(stop)} />
<TourItinerary combined={combined} highlighted={highlightedStop}
               onStopClick={(stop) => setHighlightedStop(stop)} />
```

`<TourItinerary>` cũ nhận prop `itinerary={tourData.itinerary}` — giữ backward compat: nếu prop `combined` có thì dùng; không thì fallback render itinerary plain.

---

## 5. Thiết kế Frontend Admin — Editor 2-trong-1

### 5.1. Hợp nhất Itinerary + Stops vào 1 trang

Hiện tại admin có 2 chỗ rời:
- `ToursForm > ItineraryTab` (tab trong tour form): nhập day text.
- `/admin/tours/:id/stops`: nhập pin map.

→ Đổi thành **1 trang** `/admin/tours/:id/itinerary` với UX:

```
┌─────────────────────────────────────────────────────────────┐
│ [← Quay về]    Lịch trình + Bản đồ tour #1     [Lưu (12)]  │
├─────────────────────────────────────────────────────────────┤
│ ┌─ List Days ──────────────┐ ┌─ Map preview ──────────────┐ │
│ │ ▼ NGÀY 1: Hà Nội → Hạ.. │ │                            │ │
│ │   • #1 Cảng Tuần Châu   │ │   [map]                    │ │
│ │   • #2 Hang Sửng Sốt    │ │                            │ │
│ │   • #3 Hang Luồn        │ │                            │ │
│ │   [+ Thêm điểm dừng]    │ │                            │ │
│ │   📝 Chi tiết HTML...   │ │                            │ │
│ │                          │ │                            │ │
│ │ ▼ NGÀY 2: Bái Đính ...  │ │                            │ │
│ │   ...                    │ │                            │ │
│ │ [+ Thêm ngày]            │ │                            │ │
│ └──────────────────────────┘ └────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 5.2. Cấu trúc state

```jsx
const [days, setDays] = useState([
    {
        tempId, itineraryDayId, dayNumber, title, meals, details,
        stops: [
            { tempId, stopId, name, latitude, longitude, stopOrder, description, stopType },
            ...
        ]
    }
]);
```

Stops giờ là **sub-array của day** thay vì list phẳng. Stop không gắn day = "orphan", admin phải gán hoặc xoá trước khi save.

### 5.3. Lưu — 2 request tuần tự

Save call:
1. `PUT /admin/tours/{tourId}/itinerary` — lưu days (text + meals + details). Trả về `[{itineraryDayId, dayNumber}]` để FE map.
2. `PUT /admin/tours/{tourId}/stops` — lưu stops, mỗi stop có `itineraryDayId` resolved từ bước 1 (theo `dayNumber`).

Hoặc gộp thành 1 endpoint `PUT /admin/tours/{tourId}/itinerary-with-stops` atomic transaction — đề xuất.

### 5.4. Tương tác trong editor

| Hành động | Hiệu ứng |
|---|---|
| Click row stop trong day | Active row + map zoom đến pin |
| Click pin trên map | Active stop tương ứng (đổi màu card) |
| Click vào map khi 1 stop đang active | Gán toạ độ vào stop active |
| Click "Thêm điểm dừng" trong 1 day | Tạo stop mới gán dayNumber của day đó luôn |
| Drag-reorder day | Re-number cả `dayNumber` của day + cập nhật `dayNumber` của stops thuộc day đó |
| Drag stop từ day A sang day B | Đổi `dayNumber` của stop |
| Xoá day | Warn nếu day có stop: "Xoá day sẽ làm các stop trở thành orphan" → option (a) xoá luôn stop, (b) chuyển sang day khác |

### 5.5. Validate khi save

Block save nếu:
- Day không có `title`.
- Stop có `dayNumber` nhưng không tìm thấy day tương ứng.
- Stop chưa có lat/lng.

Cảnh báo (không block) nếu:
- Day không có stop nào → "Ngày X chưa có điểm dừng trên bản đồ".
- Itinerary text mention "đi thăm X" nhưng không có stop tên X (chỉ check khi `details` plain text chứa từ khoá phổ biến — out of scope plan này, optional).

### 5.6. Migration UX

ItineraryTab cũ giữ lại trong tour form (cho tạo tour mới — chưa cần map ngay), nhưng thêm banner:
> "Sau khi lưu tour, vào trang **Lịch trình + Bản đồ** để gán điểm dừng cho từng ngày."

Với tour đã có itinerary cũ + stops cũ rời rạc:
- Trang editor mới load: tự gom stop có `dayNumber=N` vào day có `dayNumber=N`.
- Stop orphan (`dayNumber=null`) hiển thị box riêng "Điểm chưa gắn ngày" — admin kéo thả vào day.

---

## 6. Phase rollout

| Phase | Task | Effort |
|---|---|---|
| **Phase 1 — BE composite endpoint** | `ItineraryRouteService.getCombined()` + endpoint `/itinerary-with-route` + `/days` + DTO | 0.25 ngày |
| **Phase 2 — BE atomic save** | `PUT /itinerary-with-stops` transactional + validate logic | 0.25 ngày |
| **Phase 3 — FE public sync** | TourDetail dùng combined endpoint, TourItinerary render stop list với badge, scroll-to-map + open popup, ngược lại | 0.5 ngày |
| **Phase 4 — FE admin editor merge** | Trang `/admin/tours/:id/itinerary` mới — Day có sub-list Stops, click-to-pin, validate | 0.5 ngày |
| **Phase 5 — Cảnh báo lệch + migration** | Banner "missing stop day", box orphan stops, link từ ToursForm cũ sang editor mới | 0.25 ngày |
| **Tổng** | | **1.75 ngày** (~2 ngày làm tròn) |

---

## 7. Edge cases & rủi ro

### 7.1. Edge cases

| Case | Xử lý |
|---|---|
| Tour có itinerary nhưng chưa có stop nào | `<TourRouteMap>` ẩn (return null); `<TourItinerary>` render bình thường không có stop list |
| Tour có stops nhưng dayNumber tất cả null | Render hết stops trong section "Điểm dừng" trên đầu map, không có chip filter |
| Stop có dayNumber=99 nhưng itinerary chỉ có 2 ngày | BE đưa stop vào `orphanStops`; admin editor cảnh báo đỏ |
| Day có dayNumber=3 nhưng không có stop | Render itinerary day 3 bình thường, không có stop list block; admin banner gợi ý |
| Admin xoá day có 5 stops | Confirm modal: "Day 1 có 5 điểm dừng. Bạn muốn (a) xoá luôn 5 điểm, (b) chuyển sang ngày khác?" |
| Drag-reorder day → đổi dayNumber → stop dayNumber lệch | Hệ thống tự cập nhật stop.dayNumber khi day đổi dayNumber |
| 2 stops khác day cùng tọa độ (vd điểm về cùng cảng) | Hiển thị 2 marker chồng — Leaflet auto offset; popup riêng từng pin |
| Map filter "Ngày 1" → itinerary nên thu gọn Day 2, 3? | Không tự thu — user có thể đang đọc Day 2 vẫn muốn xem map Day 1; chỉ highlight row đang chọn |

### 7.2. Rủi ro

| Rủi ro | Xác suất | Tác động | Giảm thiểu |
|---|---|---|---|
| Migration data cũ: stop dayNumber không khớp day mới | Trung bình | Trung bình | Editor mới hiển thị orphan box rõ ràng + 1 lần chạy admin sweep mỗi tour |
| `globalIndex` lệch sau khi BE đổi sort order | Thấp | Cao (badge map vs itinerary sai số) | Test verify: assert `combined.days.flatMap(stops).map(globalIndex) === [1,2,3,...,N]` |
| FE 2 view share state phức tạp → re-render quá nhiều | Trung bình | Trung bình | Dùng `useMemo` cho `flatStops`, chỉ pass `highlightedStopId` (primitive) không pass object |
| Admin xoá nhầm orphan stops | Trung bình | Cao (mất data) | Confirm 2 bước, hoặc dùng soft-delete sẵn có |
| Endpoint composite trả payload to (tour có 30 days × 5 stops) | Thấp | Thấp | Vẫn < 50KB JSON; bình thường web |

### 7.3. Quyết định kiến trúc

- **Vì sao không gộp `tour_stops` thành JSON column trong `itinerary_days`?** Đã có data production; FK đã có; query/filter theo lat/lng hay theo type vẫn dễ hơn JSON. Migration không cần.
- **Vì sao sync qua `dayNumber` thay vì luôn `itineraryDayId`?** `dayNumber` dễ debug, dễ hiển thị trên UI, ổn định khi soft-delete day. `itineraryDayId` chỉ là tiện ích nội bộ.
- **Vì sao `globalIndex` tính ở BE thay vì FE?** Đảm bảo cùng số trên map (FE A) và trong itinerary (FE B) — nếu mỗi FE tính riêng, race condition khi data partial sẽ làm số lệch.
- **Vì sao trang editor mới thay vì tab trong form?** Form tour có 5 tab rồi (info, itinerary, departure, policy, ...). Lịch trình + bản đồ cần không gian lớn (map preview). Trang riêng tốt hơn.
- **Vì sao giữ ItineraryTab cũ?** Tạo tour mới chưa có id để vào URL `/admin/tours/:id/itinerary`. Vẫn cần workflow tạo tour → có id → edit.

---

## 8. Verification

### 8.1. Test thủ công sau implement
1. Tour 1 (Hạ Long): trong itinerary, mỗi Day có list stops với badge số khớp pin trên map (#1 Cảng Tuần Châu, #2 Hang Sửng Sốt, ...).
2. Click "Hang Sửng Sốt" trong itinerary → page scroll lên map + map zoom + popup Hang Sửng Sốt mở.
3. Click pin "Chùa Bái Đính" trên map → Day 2 trong itinerary tự mở + row "Chùa Bái Đính" highlight.
4. Trong admin editor mới: tạo 1 day mới → thêm 3 stops → click bản đồ gán toạ độ → save → reload → cả 3 stops vẫn ở đúng day.
5. Tour có 1 day không có stop → admin form hiện banner cảnh báo "Ngày X chưa có điểm dừng".
6. Drag day 2 lên trên day 1 → save → stops thuộc day 2 cũ (giờ là day 1) vẫn đúng nhãn ngày trên map.

### 8.2. Test data integrity
```sql
-- Mọi stop phải có itinerary_day_id NULL hoặc trỏ đến day cùng tour
SELECT s.stop_id, s.tour_id, s.itinerary_day_id, d.tour_id AS day_tour
FROM tour_stops s
LEFT JOIN itinerary_days d ON s.itinerary_day_id = d.itinerary_dayid
WHERE s.itinerary_day_id IS NOT NULL AND d.tour_id != s.tour_id;
-- Expected: 0 rows
```

---

## 9. Files cần tạo/sửa

**Backend (tour-catalog-service):**
- ✨ `service/ItineraryRouteService.java` + impl
- ✨ `dto/response/CombinedItineraryRouteResponse.java`
- ✨ `dto/response/DayWithStopsResponse.java`
- ✨ `dto/request/ItineraryWithStopsRequest.java`
- ✏ `controller/TourRouteController.java` — thêm 3 endpoint (`/itinerary-with-route`, `/days`, `/itinerary-with-stops` atomic)
- ✏ `service/impl/TourRouteServiceImpl.java` — validate dayNumber + warn orphan

**Frontend public:**
- ✏ `services/tour/tourRouteApi.js` — thêm `getCombined(tourCode)`
- ✏ `components/TourDetailComponent/TourDetail.jsx` — fetch combined + state `highlightedStop`
- ✏ `components/TourDetailComponent/TourItinerary/TourItinerary.jsx` — render stop list + onStopClick
- ✏ `components/TourDetailComponent/TourItinerary/TourItinerary.module.scss` — style stopRow + badge
- ✏ `components/TourDetailComponent/TourRoute/TourRouteMap.jsx` — accept `highlighted` prop + scroll/flyTo

**Frontend admin:**
- ✨ `components/AdminComponent/Pages/ToursPage/ItineraryRouteEditor/` (folder mới)
  - ✨ `ItineraryRouteEditor.jsx`
  - ✨ `ItineraryRouteEditor.module.scss`
  - ✨ `DayBlock.jsx` (subcomponent)
  - ✨ `StopRowInline.jsx`
- ✏ `components/AdminComponent/AdminComponent.jsx` — route `/admin/tours/:tourId/itinerary` mới
- ✏ `components/AdminComponent/Pages/ToursPage/ToursPage.jsx` — đổi icon MapPin link sang `/itinerary` thay `/stops` (giữ `/stops` legacy)
- ✏ `components/AdminComponent/Pages/ToursPage/ToursForm/ItineraryTab.jsx` — thêm banner "Edit nâng cao tại trang Lịch trình + Bản đồ" sau khi tour có id
- ✏ `services/tour/tourRouteApi.js` — thêm `getDays`, `upsertItineraryWithStops`

---

## 10. Sau khi xong, lợi ích đo lường được

| Trước | Sau |
|---|---|
| Admin nhập 2 chỗ rời rạc → trùng/thiếu | 1 chỗ duy nhất, validate ngay |
| User đọc itinerary phải đoán địa điểm trên map | Click 1 phát → map zoom + popup |
| Day không có pin / pin orphan → silent bug | Banner cảnh báo + box orphan đỏ |
| Đổi dayNumber → stop lệch | BE resolve qua dayNumber, FE drag tự sync |
| Code 2 component riêng share data qua localStorage hoặc refetch | 1 endpoint composite, 1 source of truth |

Kết quả: 2 trang FE (itinerary section + map section) luôn khớp, admin chỉ cần 1 workflow, không còn rủi ro mất đồng bộ.
