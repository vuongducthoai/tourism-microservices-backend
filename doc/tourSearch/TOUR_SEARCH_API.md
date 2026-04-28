# Tour Search API — `tour-catalog-service`

## Tổng quan

API tìm kiếm tour cho trang `/tours` của frontend. Endpoint trả về danh sách tour đang hoạt động, có ít nhất một ngày khởi hành trong tương lai, kèm giá thấp nhất và các ngày khởi hành khả dụng.

---

## Endpoint

### `GET /api/tours/search`

Tìm kiếm tour theo các tiêu chí tuỳ chọn. Tất cả tham số đều có thể bỏ qua (null = không lọc).

**Base URL (qua API Gateway):** `http://localhost:8080`  
**Direct (service):** `http://localhost:8082`

---

## Query Parameters

| Tham số | Kiểu | Bắt buộc | Mô tả |
|---|---|---|---|
| `searchNameTour` | `String` | Không | Tìm theo tên tour (case-insensitive LIKE `%value%`) |
| `startPrice` | `BigDecimal` | Không | Giá tối thiểu (VNĐ) — lọc theo giá ADULT thấp nhất |
| `endPrice` | `BigDecimal` | Không | Giá tối đa (VNĐ) — lọc theo giá ADULT thấp nhất |
| `startLocationID` | `Integer` | Không | ID điểm khởi hành (`locations.locationid`) |
| `endLocationID` | `Integer` | Không | ID điểm đến (`locations.locationid`) |
| `transportation` | `String` | Không | Phương tiện di chuyển (LIKE `%value%`) |
| `rating` | `Integer` | Không | Điểm đánh giá tối thiểu (1–5). `0` = bỏ qua |

---

## Response

**HTTP 200 OK** — `application/json`

```json
[
  {
    "tourID": 1,
    "tourCode": "HN-HL-3N2D",
    "tourName": "Hà Nội - Hạ Long 3 Ngày 2 Đêm",
    "startPointName": "Hà Nội",
    "transportation": "Xe khách giường nằm",
    "duration": "3 Ngày 2 Đêm",
    "departureDates": [
      { "departureID": 1, "departureDate": "2027-03-10" },
      { "departureID": 2, "departureDate": "2027-04-15" }
    ],
    "money": 2800000,
    "image": "https://images.unsplash.com/photo-...",
    "isFavorite": false
  }
]
```

### Mô tả các trường

| Trường | Kiểu | Mô tả |
|---|---|---|
| `tourID` | `Integer` | ID tour trong DB |
| `tourCode` | `String` | Mã tour (ví dụ: `HN-HL-3N2D`) |
| `tourName` | `String` | Tên tour đầy đủ |
| `startPointName` | `String` | Tên điểm khởi hành (`tour.startLocation.name`) |
| `transportation` | `String` | Phương tiện di chuyển |
| `duration` | `String` | Thời gian (ví dụ: `3 Ngày 2 Đêm`) |
| `departureDates` | `Array` | Danh sách ngày khởi hành trong tương lai (status=true, chưa qua ngày hôm nay), sắp xếp tăng dần |
| `departureDates[].departureID` | `Integer` | ID ngày khởi hành — dùng để điều hướng `/tour/{code}?departureId={id}` |
| `departureDates[].departureDate` | `String` | Ngày khởi hành định dạng `yyyy-MM-dd` |
| `money` | `Long` | Giá thấp nhất loại ADULT (VNĐ) trong tất cả departure active |
| `image` | `String` | URL ảnh đại diện (ảnh đầu tiên trong `tour_images`) |
| `isFavorite` | `Boolean` | Luôn `false` (chưa có auth trong tour-catalog-service) |

---

## Ví dụ Request

### Lấy tất cả tour (không filter)
```http
GET /api/tours/search
```

### Tìm theo tên
```http
GET /api/tours/search?searchNameTour=Hà Nội
```

### Lọc theo khoảng giá
```http
GET /api/tours/search?startPrice=1000000&endPrice=5000000
```

### Kết hợp nhiều filter
```http
GET /api/tours/search?startLocationID=1&transportation=máy bay&rating=4
```

---

## Logic Nghiệp Vụ

### 1. Lọc tour đủ điều kiện (JPQL)
- `tours.status = true` — chỉ tour đang hoạt động
- Lọc theo `searchNameTour`: `LOWER(tour_name) LIKE LOWER('%value%')`
- Lọc theo `startLocationID` / `endLocationID`: khớp chính xác ID
- Lọc theo `transportation`: `LIKE '%value%'`
- Lọc theo `rating`: EXISTS subquery kiểm tra `AVG(reviews.rating) >= minRating`
- Lọc theo `startPrice` / `endPrice`: subquery trên `departure_pricings` (passenger_type='ADULT', departure status=true), `HAVING MIN(price) >= startPrice AND MIN(price) <= endPrice`

### 2. Build `TourSearchResponse` (Converter)
`TourToSearchResponseConverter` xử lý:
- `startPointName` ← `tour.startLocation.name`
- `image` ← ảnh đầu tiên trong `tour.images`
- `departureDates` ← lọc từ `tour.departures`:
  - Bỏ departure có `status != true`
  - Bỏ departure có `departureDate` đã qua (so với `LocalDate.now()`)
  - Mỗi item: `{departureID, departureDate (yyyy-MM-dd)}`
  - Sắp xếp tăng dần theo ngày
- `money` ← `MIN(salePrice)` trong tất cả pricing ADULT của các departure hợp lệ

### 3. Lọc sau khi map
Bỏ qua tour không có `departureDates` (tức là tất cả chuyến đã qua hoặc bị huỷ).

---

## Các file liên quan

| File | Vai trò |
|---|---|
| `controller/TourController.java` | Endpoint `GET /api/tours/search` |
| `service/TourService.java` | Interface khai báo `searchTours()` |
| `service/impl/TourServiceImpl.java` | Gọi repository + map + filter |
| `repository/impl/TourRepositoryCustomImpl.java` | JPQL động với tất cả filter |
| `dto/request/SearchToursRequest.java` | Request DTO (bind từ query params) |
| `dto/response/TourSearchResponse.java` | Response DTO cho trang /tours |
| `dto/response/DepartureDateItem.java` | DTO con: `{departureID, departureDate}` |
| `convert/TourToSearchResponseConverter.java` | ModelMapper converter Tour → TourSearchResponse |
| `config/AppConfig.java` | Đăng ký converter vào ModelMapper bean |

---

## Liên kết Frontend

Frontend gọi API tại `services/tours/tours.ts`:

```typescript
// searchToursApi() → GET /api/tours/search
export const searchToursApi = async (payload: SearchToursPayload) => {
  const response = await api.get('/tours/search', { params: payload });
  return response.data.map(TourResponseDTO.fromApiResponse);
};
```

`TourResponseDTO.fromApiResponse` map các trường:
- `data.tourID` → `tourID`
- `data.tourName` → `tourName`
- `data.startPointName` → `startPointName` (điểm khởi hành hiển thị trên card)
- `data.departureDates` → `departureDates` (mảng `{departureID, departureDate}`)
- `data.money` → `money` (giá hiển thị)
- `data.image` → `image`
- `data.isFavorite` → `isFavorite`

---

## Ghi chú kỹ thuật

- **JPQL với null parameter và PostgreSQL**: Hibernate 6 + PostgreSQL JDBC driver bind `null` String parameters thành `bytea`, gây lỗi `function lower(bytea) does not exist`. Giải pháp: dùng `CAST(:param AS string)` trong JPQL để Hibernate sinh `cast(? as text)`.
- **GROUP BY subquery**: PostgreSQL strict mode yêu cầu SELECT trong GROUP BY chỉ chứa cột được aggregate hoặc có trong GROUP BY. Dùng `SELECT r.tour.tourID` thay vì `SELECT r`.
- **Encoding**: Dữ liệu tiếng Việt lưu UTF-8. PowerShell terminal hiển thị sai (Latin-1 decode artifact) nhưng API response bytes đúng UTF-8.
