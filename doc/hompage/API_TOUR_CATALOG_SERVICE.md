# API Documentation — tour-catalog-service

**Service:** `tour-catalog-service`  
**Port nội bộ:** `8082`  
**Truy cập qua API Gateway:** `http://localhost:8080/api/...`  
**Auth:** Không yêu cầu (public endpoints)

---

## Tổng quan kiến trúc

```
Frontend (React)
     │  HTTP GET / POST  http://localhost:8080/api/...
     ▼
API Gateway  :8080
     │  route → TOUR-CATALOG-SERVICE (Eureka discovery)
     ▼
tour-catalog-service  :8082
     │
     ├── Controller  (nhận request, trả response)
     ├── Service     (business logic, @Transactional)
     ├── Repository  (JPQL queries, JPA)
     ├── Converter   (ModelMapper — map Entity → DTO)
     └── PostgreSQL  tour_catalog_db
```

---

## Danh sách endpoints

| # | Method | Path | Mô tả |
|---|--------|------|-------|
| 1 | GET | `/api/tours/display` | Tất cả tour active — trang danh sách / homepage |
| 2 | GET | `/api/tours/deepest-discount` | Top 10 tour giảm giá sâu nhất — homepage |
| 3 | GET | `/api/tours/search` | Tìm kiếm tour với filter động |
| 4 | GET | `/api/locations/start-location` | Danh sách điểm khởi hành |
| 5 | GET | `/api/locations/end-location` | Danh sách điểm đến |
| 6 | POST | `/api/locations/destinations-by-region` | Điểm đến nổi bật theo vùng miền |

---

## Chi tiết từng API

---

### 1. GET `/api/tours/display`

**Mục đích:** Lấy toàn bộ tour đang active để hiển thị trên trang danh sách và banner homepage.

**Frontend sử dụng:** Hook `useFeaturedTours` → component `Banner.jsx`

**Request:** Không có param.

**Response mẫu:**
```json
[
  {
    "tourID": 1,
    "tourCode": "HN-HL-3N2D",
    "tourName": "Hà Nội - Hạ Long 3 Ngày 2 Đêm",
    "endPointName": "Hạ Long",
    "transportation": "Xe khách",
    "duration": "3 ngày 2 đêm",
    "departureDate": ["2027-03-10", "2027-04-15"],
    "money": 2800000,
    "image": "https://..."
  }
]
```

**Luồng xử lý:**

```
Request
  └─► TourController.getToursForDisplay()
        └─► TourServiceImpl.getAllToursForDisplay()
              └─► TourRepository.findAllActiveWithDetails()
                    JPQL:
                      SELECT DISTINCT t FROM Tour t
                      LEFT JOIN FETCH t.endLocation
                      LEFT JOIN FETCH t.startLocation
                      LEFT JOIN FETCH t.departures
                      WHERE t.status = true
                    → List<Tour>
              └─► ModelMapper.map(tour, TourDisplayResponse.class)
                    → TourToDisplayResponseConverter.convert()
                          endPointName   ← tour.endLocation.name
                          departureDate  ← departures[status=true].departureDate
                                           → toLocalDate().toString() "yyyy-MM-dd"
                          money          ← MIN(pricings[ADULT].price) qua tất cả departures
                          image          ← tour.images[0].imageUrl (lazy-load)
  └─► ResponseEntity<List<TourDisplayResponse>> 200 OK
```

**SQL chính:**
```sql
SELECT DISTINCT t.*, el.*, sl.*, d.*
FROM tours t
LEFT JOIN locations el ON el.locationid = t.end_location_id
LEFT JOIN locations sl ON sl.locationid = t.start_location_id
LEFT JOIN tour_departures d ON d.tour_id = t.tourid
WHERE t.status = true
```

---

### 2. GET `/api/tours/deepest-discount`

**Mục đích:** Lấy top 10 **tour** có % giảm giá ADULT cao nhất, dùng cho section "Ưu đãi đặc biệt" trên homepage. Mỗi tour chỉ xuất hiện **1 lần** — nếu 1 tour có nhiều ngày khởi hành đều giảm giá thì chỉ lấy ngày có discount sâu nhất đại diện.

**Frontend sử dụng:** Hook `useSpecialTours` → component `SpecialTours.jsx`

**Request:** Không có param.

**Response mẫu:**
```json
[
  {
    "departureID": 7,
    "tourID": 4,
    "tourName": "TP. Hồ Chí Minh - Vũng Tàu 2 Ngày 1 Đêm",
    "tourCode": "HCM-VT-2N1D",
    "startLocationName": "TP. Hồ Chí Minh",
    "duration": "2 ngày 1 đêm",
    "departureDate": "2027-03-05",
    "availableSlots": 30,
    "salePrice": 1500000.00,
    "originalPrice": 1800000.00,
    "discountPercentage": 16,
    "image": null
  }
]
```

**Luồng xử lý:**

```
Request
  └─► TourController.getDeepestDiscountTours()
        └─► TourServiceImpl.getTop10DeepestDiscountTours()
              └─► TourDepartureRepository.findActiveDiscountedDepartures(LocalDateTime.now())
                    JPQL:
                      SELECT DISTINCT d FROM TourDeparture d
                      JOIN FETCH d.tour t
                      LEFT JOIN FETCH t.startLocation
                      LEFT JOIN FETCH d.pricings
                      WHERE d.status = true
                        AND d.departureDate >= :today
                        AND EXISTS (
                          SELECT p FROM DeparturePricing p
                          WHERE p.tourDeparture = d
                            AND p.passengerType = 'ADULT'
                            AND p.originalPrice > p.salePrice
                        )
                    → List<TourDeparture>
              └─► ModelMapper.map(departure, TourSpecialResponse.class)
                    → TourDepartureToSpecialConverter.convert()
                          startLocationName  ← departure.tour.startLocation.name
                          salePrice          ← pricings[ADULT].price    (cột DB: price)
                          originalPrice      ← pricings[ADULT].originalPrice
                          discountPercentage ← (original - sale) / original × 100
                          departureDate      ← departure.departureDate
                                               → toLocalDate().toString() "yyyy-MM-dd"
                          image              ← tour.images[0].imageUrl (lazy-load)
              └─► Java stream:
                    .map(departure → TourSpecialResponse)
                    .filter(discountPercentage > 0)
                    // Dedup: 1 tour có nhiều departure → giữ departure discount cao nhất
                    .collect(toMap(tourCode, r, (a,b) → max(discountPercentage)))
                    .values().stream()
                    .sorted(discountPercentage DESC)
                    .limit(10)
  └─► ResponseEntity<List<TourSpecialResponse>> 200 OK
```

**Lưu ý:** Sort được thực hiện trong Java stream (không ORDER BY trong JPQL) vì discountPercentage được tính từ BigDecimal division — không thể ORDER BY đơn giản trong JPQL.

---

### 3. GET `/api/tours/search`

**Mục đích:** Tìm kiếm tour với nhiều filter tuỳ chọn — trang `/tours`.

**Frontend sử dụng:** `FilterAndSearchInput.jsx` → `searchToursApi()` → URL query params

**Request params (tất cả đều optional):**

| Param | Kiểu | Mô tả |
|-------|------|-------|
| `searchNameTour` | String | Tên tour / điểm đến (fuzzy LIKE) |
| `startPrice` | BigDecimal | Giá ADULT tối thiểu (VND) |
| `endPrice` | BigDecimal | Giá ADULT tối đa (VND) |
| `startLocationID` | Integer | ID điểm khởi hành |
| `endLocationID` | Integer | ID điểm đến |
| `transportation` | String | Phương tiện (fuzzy LIKE) |
| `rating` | Integer | Đánh giá tối thiểu 1–5 (0 = bỏ qua) |

**Response:** Cùng shape với `/api/tours/display` — `TourDisplayResponse[]`

**Luồng xử lý:**

```
GET /api/tours/search?searchNameTour=Hạ+Long&startPrice=1000000
  └─► TourController.searchTours(@ModelAttribute SearchToursRequest)
        └─► TourServiceImpl.searchTours(request)
              └─► TourRepositoryCustomImpl.searchToursDynamically(request)
                    Chuẩn hoá: rỗng/blank → null (để bỏ qua filter)
                    Xây JPQL động:
                      SELECT DISTINCT t FROM Tour t
                      LEFT JOIN FETCH ...
                      WHERE t.status = true
                        AND (:nameParam IS NULL OR LOWER(t.tourName) LIKE ...)
                        AND (:startLocId IS NULL OR t.startLocation.locationID = :startLocId)
                        AND (:endLocId IS NULL OR t.endLocation.locationID = :endLocId)
                        AND (:transportParam IS NULL OR t.transportation LIKE ...)
                        AND (:minRating IS NULL OR EXISTS (AVG review >= minRating))
                        AND (:minPrice IS NULL OR EXISTS (MIN adult price >= minPrice))
                        AND (:maxPrice IS NULL OR EXISTS (MIN adult price <= maxPrice))
                    EntityManager.createQuery(jpql).setParameters(...)
                    → List<Tour>
              └─► ModelMapper → TourToDisplayResponseConverter (giống /display)
  └─► ResponseEntity<List<TourDisplayResponse>> 200 OK
```

**Ví dụ gọi:**
```
GET /api/tours/search?searchNameTour=Hạ Long&startPrice=2000000&endPrice=5000000
GET /api/tours/search?transportation=Máy bay&rating=4
GET /api/tours/search?startLocationID=1&endLocationID=2
```

---

### 4. GET `/api/locations/start-location`

**Mục đích:** Trả về danh sách điểm khởi hành có ít nhất 1 tour đang active. Dùng để populate dropdown "Khởi hành từ" trong Banner và FilterAndSearchInput.

**Frontend sử dụng:** Hook `useLocations` (gọi song song với end-location)

**Request:** Không có param.

**Response mẫu:**
```json
[
  {
    "locationID": 1,
    "name": "Hà Nội",
    "imageUrl": "https://res.cloudinary.com/demo/image/upload/hanoi.jpg",
    "description": "Thủ đô ngàn năm văn hiến"
  }
]
```

**Luồng xử lý:**

```
Request
  └─► LocationController.getStartLocations()
        └─► LocationServiceImpl.getStartLocations()
              └─► LocationRepository.findDistinctStartLocations()
                    JPQL:
                      SELECT DISTINCT l FROM Location l
                      JOIN l.startPoint t        ← quan hệ Location → Tour (startLocation)
                      WHERE t.status = true
                        AND l.status = true
                    → List<Location>
              └─► ModelMapper.map(location, LocationResponse.class)
                    TypeMap (AppConfig):
                      imageUrl ← location.image   (tên field khác nhau)
                      name, locationID, description → tự động match
  └─► ResponseEntity<List<LocationResponse>> 200 OK
```

---

### 5. GET `/api/locations/end-location`

**Mục đích:** Trả về danh sách điểm đến có ít nhất 1 tour đang active. Dùng cho dropdown "Điểm đến" trong Banner và FilterAndSearchInput.

**Frontend sử dụng:** Hook `useLocations` (gọi song song với start-location)

**Request:** Không có param.

**Response:** Cùng shape với `/start-location` — `LocationResponse[]`

**Luồng xử lý:**

```
Request
  └─► LocationController.getEndLocations()
        └─► LocationServiceImpl.getEndLocations()
              └─► LocationRepository.findDistinctEndLocations()
                    JPQL:
                      SELECT DISTINCT l FROM Location l
                      JOIN l.endPoint t           ← quan hệ Location → Tour (endLocation)
                      WHERE t.status = true
                        AND l.status = true
                    → List<Location>
              └─► ModelMapper → LocationResponse (giống /start-location)
  └─► ResponseEntity<List<LocationResponse>> 200 OK
```

---

### 6. POST `/api/locations/destinations-by-region`

**Mục đích:** Lấy danh sách điểm đến nổi bật theo vùng miền (Bắc / Trung / Nam), dùng cho section "Điểm đến yêu thích" trên homepage.

**Frontend sử dụng:** Hook `useFavoriteDestinations` — gọi với `region: "NORTH" | "CENTRAL" | "SOUTH"`

**Request body:**
```json
{ "region": "NORTH" }
```

| Giá trị | Vùng |
|---------|------|
| `NORTH` | Miền Bắc |
| `CENTRAL` | Miền Trung |
| `SOUTH` | Miền Nam |

**Response mẫu:**
```json
[
  {
    "locationID": 1,
    "endPoint": "Hà Nội",
    "listImage": "https://res.cloudinary.com/demo/image/upload/hanoi.jpg",
    "region": "NORTH"
  }
]
```

**Lỗi có thể xảy ra:**
- `400 Bad Request` — `region` không phải `NORTH`, `CENTRAL`, hoặc `SOUTH`

**Luồng xử lý:**

```
POST /api/locations/destinations-by-region
Body: {"region": "NORTH"}
  └─► LocationController.getDestinationsByRegion(@Valid @RequestBody RegionRequest)
        @Valid → kiểm tra @NotBlank trên region field
        └─► LocationServiceImpl.getDestinationsByRegion(request)
              Region region = Region.valueOf("NORTH")
              └─► LocationRepository.findByRegionActive(Region.NORTH)
                    JPQL:
                      SELECT l FROM Location l
                      WHERE l.region = :region
                        AND l.status = true
                    → List<Location>
              └─► ModelMapper.map(location, DestinationResponse.class)
                    TypeMap (AppConfig):
                      endPoint  ← location.name
                      listImage ← location.image
                    Manual mapping (LocationServiceImpl):
                      region    ← location.region.name()   ← "NORTH" / "CENTRAL" / "SOUTH"
  └─► ResponseEntity<List<DestinationResponse>> 200 OK
```

**Lý do map `region` thủ công:** ModelMapper không hỗ trợ chained lambda `src -> src.getRegion().name()` trong `addMappings` — sẽ throw `ErrorsException` lúc khởi động. Field `region` được set thủ công sau khi ModelMapper map các field còn lại.

---

## Mapping Entity → DTO (ModelMapper)

### TourToDisplayResponseConverter

```
Tour entity                        TourDisplayResponse DTO
──────────────────────────         ─────────────────────────
tour.tourID                   →    tourID
tour.tourCode                 →    tourCode
tour.tourName                 →    tourName
tour.transportation           →    transportation
tour.duration                 →    duration
tour.endLocation.name         →    endPointName
tour.images[0].imageUrl       →    image
tour.departures[status=true]
  .departureDate.toLocalDate()→    departureDate[]  (List<String> "yyyy-MM-dd")
MIN(departures.pricings
  [ADULT].price)              →    money (Long, VND)
```

### TourDepartureToSpecialConverter

```
TourDeparture entity               TourSpecialResponse DTO
──────────────────────────         ─────────────────────────
departure.departureID         →    departureID
departure.availableSlots      →    availableSlots
departure.departureDate
  .toLocalDate().toString()   →    departureDate (String "yyyy-MM-dd")
departure.tour.tourID         →    tourID
departure.tour.tourName       →    tourName
departure.tour.tourCode       →    tourCode
departure.tour.duration       →    duration
departure.tour.startLocation.name→ startLocationName
departure.tour.images[0]
  .imageUrl                   →    image
pricings[ADULT].price         →    salePrice
pricings[ADULT].originalPrice →    originalPrice
(original-sale)/original×100  →    discountPercentage (Integer %)
```

### Location → LocationResponse (TypeMap)

```
Location entity                    LocationResponse DTO
──────────────────────────         ─────────────────────────
location.locationID           →    locationID       (auto)
location.name                 →    name             (auto)
location.image                →    imageUrl         (TypeMap addMappings)
location.description          →    description      (auto)
```

### Location → DestinationResponse (TypeMap + manual)

```
Location entity                    DestinationResponse DTO
──────────────────────────         ─────────────────────────
location.locationID           →    locationID       (auto)
location.name                 →    endPoint         (TypeMap addMappings)
location.image                →    listImage        (TypeMap addMappings)
location.region.name()        →    region           (manual set trong service)
```

---

## Cấu trúc file liên quan

```
tour-catalog-service/src/main/java/com/tourism/tourcatalog/
├── controller/
│   ├── TourController.java          ← endpoint /api/tours/*
│   └── LocationController.java      ← endpoint /api/locations/*
├── service/
│   ├── TourService.java             ← interface
│   ├── LocationService.java         ← interface
│   └── impl/
│       ├── TourServiceImpl.java     ← business logic
│       └── LocationServiceImpl.java ← business logic + manual region mapping
├── repository/
│   ├── TourRepository.java          ← JPQL: findAllActiveWithDetails
│   ├── TourDepartureRepository.java ← JPQL: findActiveDiscountedDepartures
│   ├── LocationRepository.java      ← JPQL: findDistinct*, findByRegionActive
│   ├── TourRepositoryCustom.java    ← interface dynamic search
│   └── impl/
│       └── TourRepositoryCustomImpl.java ← EntityManager dynamic JPQL
├── convert/
│   ├── TourToDisplayResponseConverter.java      ← Tour → TourDisplayResponse
│   └── TourDepartureToSpecialConverter.java     ← TourDeparture → TourSpecialResponse
├── config/
│   └── AppConfig.java               ← ModelMapper @Bean + TypeMaps
├── dto/
│   ├── request/
│   │   ├── RegionRequest.java
│   │   └── SearchToursRequest.java
│   └── response/
│       ├── TourDisplayResponse.java
│       ├── TourSpecialResponse.java
│       ├── LocationResponse.java
│       └── DestinationResponse.java
└── entity/
    ├── Tour.java
    ├── TourDeparture.java
    ├── TourImage.java
    ├── DeparturePricing.java
    ├── DepartureTransport.java
    ├── Location.java
    ├── PolicyTemplate.java
    └── Review.java
```

---

## Lưu ý kỹ thuật

| Vấn đề | Giải pháp |
|--------|-----------|
| `departure_date` trong DB là `timestamp` | Entity dùng `LocalDateTime`, converter gọi `.toLocalDate().toString()` để ra `"yyyy-MM-dd"` |
| `price` trong DB vs `salePrice` trong entity | `@Column(name="price")` trên field `salePrice` — Hibernate map đúng cột |
| `tour_guide_info` và `age_description` chưa có trong seed | Thêm thủ công qua `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (nullable) |
| ModelMapper không hỗ trợ chained lambda | `region` field trong `DestinationResponse` map thủ công trong `LocationServiceImpl` |
| `deepest-discount` sort theo % giảm giá | Sort trong Java stream, không ORDER BY JPQL — BigDecimal division không portable trong JPQL |
| `tour_images` bảng trống (seed chưa có) | `image` field trả về `null` — frontend cần xử lý fallback image |
