# Báo cáo kiến trúc sync dữ liệu chatbot vector

## 1. Mục tiêu

Hệ thống sync dữ liệu chatbot có nhiệm vụ đưa dữ liệu nghiệp vụ từ các microservice lên Pinecone để chatbot có thể tìm kiếm và trả lời theo dữ liệu thật trong hệ thống.

Phạm vi hiện tại gồm:

- Tour, lịch khởi hành, lịch trình, ẩm thực, khách sạn, điểm tham quan.
- Địa điểm du lịch.
- Review/đánh giá của khách hàng.
- Coupon/khuyến mãi.
- Lịch sử sync và thống kê sync cho dashboard admin.

Nguyên tắc triển khai:

- Không sửa luồng booking/search cũ đang hoạt động tốt.
- Không sync ngay mỗi lần dữ liệu thay đổi nhỏ, mà gom sự kiện 5 phút rồi sync incremental.
- Manual sync và manual clear vẫn có để admin chủ động thao tác.
- Mọi lần sync/clear đều ghi lịch sử vào database để dashboard thống kê.

## 2. Kiến trúc tổng quan

```text
Admin/User thao tác dữ liệu
        |
        v
Tour Catalog / Booking Service
        |
        | publish chatbot.sync.*
        v
RabbitMQ exchange: tourism.events
        |
        v
analytics.chatbot-sync.queue
        |
        v
ChatbotSyncEventListener
        |
        v
Redis pending keys, gom thay đổi 5 phút
        |
        v
ChatbotSyncDebounceService scheduler mỗi 30 giây
        |
        v
VectorIncrementalSyncService
        |
        | fetch dữ liệu by-id từ source service
        | delete vector cũ theo filter
        | upsert vector mới
        v
Pinecone
        |
        v
Chatbot dùng vector để tìm kiếm/trả lời
```

Ngoài sync tự động, admin có thể thao tác thủ công:

```text
Admin Dashboard
   |-- Sync ngay  -> VectorSyncService.syncAllDetailed("MANUAL")
   |-- Clear      -> VectorService.deleteAll() + ghi run CLEAR
   |-- Summary    -> ChatbotVectorSyncRunService.summary()
```

## 3. Các loại document được sync lên Pinecone

| Loại document | Nguồn dữ liệu | Mục đích |
| --- | --- | --- |
| `TOUR_SUMMARY` | Tour catalog | Tìm tour tổng quát theo tên, mã tour, điểm đi/đến, giá, rating. |
| `TOUR_AMENITY` | Tour catalog | Tìm theo ẩm thực, khách sạn, điểm tham quan, nhu cầu tự do như buffet, hải sản, Safari. |
| `TOUR_ITINERARY_DAY` | Tour catalog | Trả lời lịch trình từng ngày của tour. |
| `TOUR_START_LOCATION` | Tour catalog | Tìm tour theo nơi khởi hành như HCM, Hà Nội, Đà Nẵng. |
| `TOUR_DEPARTURE` | Tour catalog | Tìm lịch khởi hành cơ bản. |
| `TOUR_DEPARTURE_FULL` | Tour catalog | Tìm tour có đủ thông tin để chuyển sang luồng đặt tour. |
| `LOCATION` | Tour catalog | Hỏi đáp về địa điểm du lịch. |
| `REVIEW` | Tour catalog | Hỏi đáp liên quan review/đánh giá. |
| `COUPON` | Booking service | Hỏi đáp khuyến mãi/coupon. |

Metadata Pinecone được flatten thêm các khóa như:

- `tourId`, `tourID`, `tourCode`
- `departureID`, `departureId`
- `reviewID`
- `couponID`
- `locationID`

Các metadata này giúp xóa chính xác vector cũ khi dữ liệu thay đổi.

## 4. Full sync hoạt động như thế nào

Full sync dùng khi:

- Scheduled sync 2:00 AM hằng ngày.
- Admin bấm `Sync ngay`.
- API cũ `/api/chatbot/admin/sync` được gọi.

Luồng xử lý:

```text
VectorSyncService.syncAll()
        |
        v
VectorSyncService.syncAllDetailed(triggerType)
        |
        | tạo ChatbotVectorSyncRun RUNNING
        | lấy Redis lock chatbot-sync:lock
        v
syncAllWithoutHistory()
        |
        | syncAllTours()
        | syncAllLocations()
        | syncAllReviews()
        | syncAllCoupons()
        v
Pinecone upsert
        |
        v
markSuccess hoặc markFailed
```

Full sync hiện tại không tự clear trước. Nó upsert lại document theo ID cố định. Nếu document ID trùng, Pinecone sẽ ghi đè nội dung mới lên document cũ.

Lý do không clear tự động:

- An toàn hơn khi admin bấm sync.
- Tránh trường hợp clear xong sync lỗi làm chatbot mất toàn bộ dữ liệu.
- Nếu cần làm sạch hoàn toàn, admin dùng `Clear vector`, sau đó bấm `Sync ngay`.

## 5. Manual clear hoạt động như thế nào

Manual clear dùng khi admin muốn xóa toàn bộ dữ liệu vector trong Pinecone.

API:

- `DELETE /api/dashboard/vector-sync/manual-clear`
- Qua gateway admin: `DELETE /api/admin/dashboard/vector-sync/manual-clear`
- API cũ: `/api/chatbot/admin/clear`

Luồng xử lý:

```text
Admin bấm Clear vector
        |
        v
VectorService.deleteAll()
        |
        v
Pinecone xóa toàn bộ vector
        |
        v
ChatbotVectorSyncRunService.recordClearSuccess()
```

Manual clear không tự sync lại. Sau clear, admin cần bấm `Sync ngay` để nạp lại dữ liệu.

## 6. Incremental sync qua RabbitMQ và debounce 5 phút

Incremental sync dùng khi dữ liệu nghiệp vụ thay đổi.

### 6.1. Event payload

Các service publish event dạng:

```json
{
  "eventId": "uuid",
  "sourceService": "tour-catalog-service",
  "entityType": "tour",
  "entityId": 12,
  "parentTourId": 12,
  "operation": "UPDATE",
  "occurredAt": "2026-06-09T17:00:00"
}
```

Các routing key dùng dạng:

```text
chatbot.sync.tour
chatbot.sync.departure
chatbot.sync.itinerary
chatbot.sync.location
chatbot.sync.review
chatbot.sync.coupon
```

### 6.2. Vì sao gom 5 phút

Một thao tác admin thường tạo nhiều thay đổi liên tiếp, ví dụ sửa tour, sửa lịch trình, sửa ảnh, sửa ngày khởi hành. Nếu mỗi thay đổi sync ngay thì:

- Tốn request Pinecone.
- Dễ sync dữ liệu chưa ổn định.
- Có thể sync nhiều lần cho cùng một tour trong vài giây.

Vì vậy listener chỉ ghi pending event vào Redis, chưa sync ngay.

Redis key:

```text
chatbot-sync:pending:{entityType}:{entityId}
```

Mỗi key lưu:

- `lastEventAt`
- `eventCount`
- `operation`
- `parentTourId`
- payload event mới nhất

TTL mặc định: 24 giờ.

Scheduler chạy mỗi 30 giây. Nếu event đã im lặng đủ 5 phút thì mới sync.

### 6.3. Luồng debounce

```text
Rabbit event
        |
        v
ChatbotSyncEventListener.handle()
        |
        v
ChatbotSyncDebounceService.enqueue()
        |
        v
Redis pending key
        |
        v
flushReadyEvents() mỗi 30 giây
        |
        | chỉ lấy event đã im lặng >= 5 phút
        v
VectorSyncService.runEventSync(...)
        |
        v
VectorIncrementalSyncService.syncEvents(events)
```

Nếu sync thành công, pending keys được xóa. Nếu sync lỗi, pending keys vẫn còn để lần sau có thể xử lý lại.

## 7. Incremental sync có ghi đè hay xóa trước không

Có hai cơ chế:

### 7.1. Upsert ghi đè document cùng ID

Pinecone `upsert` sẽ ghi đè nếu document ID đã tồn tại. Ví dụ:

```text
tour-summary-12
tour-amenity-12
tour-departure-full-100
```

Nếu sync lại cùng ID, nội dung mới thay nội dung cũ.

### 7.2. Delete trước rồi sync lại để tránh dữ liệu thừa

Với incremental update, hệ thống ưu tiên xóa vector cũ theo filter rồi nạp lại dữ liệu mới.

Ví dụ sửa tour:

```text
delete vectors where tourId = 12
fetch /api/tours/chatbot-sync/12
build lại toàn bộ docs của tour 12
upsert docs mới lên Pinecone
```

Cách này tránh lỗi document cũ còn sót. Ví dụ tour trước có 5 ngày lịch trình, sau admin sửa còn 4 ngày. Nếu chỉ upsert, document ngày 5 có thể vẫn còn. Delete trước giúp xóa sạch các document con cũ.

Với entity riêng như location/review/coupon:

```text
delete vectors where locationID = id
fetch location by id
upsert location mới
```

Nếu entity bị xóa, inactive, hidden, hoặc by-id endpoint trả 404, analytics sẽ xóa vector cũ và không upsert lại.

## 8. Các tình huống dữ liệu thay đổi

### 8.1. Admin thêm tour

Service phát event:

```text
entityType = tour
operation = CREATE
parentTourId = tourId
```

Sau 5 phút im lặng:

```text
syncTourById(tourId)
delete vectors where tourId = tourId
fetch tour chatbot-sync by id
build TOUR_SUMMARY, TOUR_AMENITY, itinerary, departure docs
upsert Pinecone
```

Kết quả: chatbot tìm được tour mới sau khi debounce flush xong.

### 8.2. Admin sửa tour

Các thay đổi như tên tour, giá, mô tả, điểm tham quan, ẩm thực, khách sạn, ảnh đại diện đều publish event `tour UPDATE`.

Sau debounce:

```text
delete toàn bộ vector cũ của tour
fetch dữ liệu tour mới nhất
upsert lại toàn bộ docs của tour
```

Kết quả: chatbot dùng dữ liệu mới, không giữ bản cũ.

### 8.3. Admin sửa lịch trình tour

Itinerary là dữ liệu con của tour. Khi itinerary thay đổi:

```text
entityType = itinerary
parentTourId = tourId
```

Analytics không sync riêng một ngày lịch trình, mà sync lại tour cha để đảm bảo:

- `TOUR_ITINERARY_DAY` đúng.
- `TOUR_SUMMARY` và `TOUR_AMENITY` không lệch nếu có dữ liệu liên quan.
- Document ngày cũ bị xóa nếu admin xóa bớt ngày.

### 8.4. Admin thêm/sửa/xóa lịch khởi hành

Departure thay đổi cũng sync lại tour cha:

```text
entityType = departure
parentTourId = tourId
```

Vì chatbot cần cả danh sách tour và context đặt tour, sync lại tour cha sẽ cập nhật:

- `TOUR_DEPARTURE`
- `TOUR_DEPARTURE_FULL`
- ngày khởi hành trong tour suggestions
- departure context để người dùng có thể đặt tour

Nếu departure bị xóa, delete trước theo `tourId` giúp document departure cũ không còn sót.

### 8.5. Admin thêm/sửa/xóa location

Location là entity riêng:

```text
entityType = location
entityId = locationId
```

Sau debounce:

```text
delete vectors where locationID = locationId
fetch /api/locations/chatbot-sync/{locationId}
upsert LOCATION nếu còn active
```

Nếu location bị xóa hoặc inactive, vector cũ bị xóa và không upsert lại.

### 8.6. User đánh giá tour

Khi user tạo review:

```text
entityType = review
operation = CREATE
entityId = reviewId
```

Đồng thời service cũng phát event cập nhật tour cha để rating trung bình/review count của tour được làm mới.

Sau debounce:

- Review mới được sync thành document `REVIEW`.
- Tour cha được sync lại để `avgRating`, `reviewCount`, nội dung summary mới chính xác.

Với câu hỏi như `các tour được review cao`, handler ranking hiện dùng dữ liệu live từ tour catalog để tránh phụ thuộc hoàn toàn vào vector. Tuy vậy vector vẫn cần sync để các câu hỏi review/RAG khác có dữ liệu mới.

### 8.7. Admin thêm/sửa/xóa coupon

Coupon thuộc booking service:

```text
entityType = coupon
entityId = couponId
```

Sau debounce:

```text
delete vectors where couponID = couponId
fetch /api/bookings/coupons/chatbot-sync/{couponId}
upsert COUPON nếu còn active
```

Nếu coupon bị xóa hoặc inactive, vector cũ bị xóa.

## 9. Dashboard admin phần Đồng bộ Chatbot

Dashboard admin có section `Đồng bộ Chatbot`.

API:

| API | Chức năng |
| --- | --- |
| `GET /api/dashboard/vector-sync/summary?from&to` | Lấy thống kê sync. |
| `POST /api/dashboard/vector-sync/manual-sync` | Sync toàn bộ dữ liệu lên Pinecone. |
| `DELETE /api/dashboard/vector-sync/manual-clear` | Clear toàn bộ vector trong Pinecone. |
| `GET /api/admin/dashboard/vector-sync/summary?from&to` | Route qua gateway cho admin frontend. |
| `POST /api/admin/dashboard/vector-sync/manual-sync` | Route qua gateway cho admin frontend. |
| `DELETE /api/admin/dashboard/vector-sync/manual-clear` | Route qua gateway cho admin frontend. |

Dashboard hiển thị:

- Số lần sync hôm nay.
- Số lần thành công/thất bại.
- Lần sync gần nhất.
- Trạng thái đang sync hay không.
- Tổng số docs đã sync.
- Số pending events đang chờ debounce.
- Lịch sử sync gần nhất.
- Nút `Sync ngay`.
- Nút `Clear vector` có confirm.

## 10. Các file quan trọng và trách nhiệm

### 10.1. Analytics service

| File | Vai trò |
| --- | --- |
| `entity/ChatbotVectorSyncRun.java` | Entity lưu lịch sử từng lần sync/clear. |
| `repository/ChatbotVectorSyncRunRepository.java` | Query last run, recent runs, count success/fail theo ngày. |
| `service/ChatbotVectorSyncRunService.java` | Tạo run `RUNNING`, mark `SUCCESS/FAILED`, record clear, build summary DTO. |
| `service/VectorSyncService.java` | Full sync, scheduled sync, ghi history, Redis lock, build và upsert document. |
| `service/VectorIncrementalSyncService.java` | Sync incremental theo event, delete vector cũ theo filter, fetch by-id, upsert dữ liệu mới. |
| `service/VectorService.java` | Upsert Pinecone, delete all, delete theo filter, flatten metadata. |
| `service/ChatbotSyncEventListener.java` | Lắng nghe Rabbit queue và đưa event vào debounce service. |
| `service/ChatbotSyncDebounceService.java` | Ghi pending event vào Redis, flush event đã im lặng đủ 5 phút. |
| `config/ChatbotSyncRabbitConfig.java` | Khai báo queue, DLQ, exchange binding cho chatbot sync. |
| `dto/sync/ChatbotSyncEventDTO.java` | DTO payload event sync. |
| `dto/sync/VectorSyncRunDTO.java` | DTO lịch sử sync trả về dashboard. |
| `dto/sync/VectorSyncSummaryDTO.java` | DTO thống kê sync dashboard. |
| `controller/VectorSyncDashboardController.java` | API dashboard summary/manual sync/manual clear. |
| `controller/ChatbotController.java` | API chatbot cũ; admin sync/clear cũ vẫn dùng được và có ghi history. |
| `service/TourRankingAnswerService.java` | Handler riêng cho câu hỏi tour đánh giá cao/review cao/rating cao. |
| `service/ChatbotService.java` | Gọi ranking/fact/booking/RAG theo thứ tự, giữ luồng cũ. |

### 10.2. Hàm chính trong analytics service

| Hàm | File | Chức năng |
| --- | --- | --- |
| `syncAll()` | `VectorSyncService` | API public cũ, gọi full sync có ghi history. |
| `syncAllDetailed(triggerType)` | `VectorSyncService` | Full sync có lock, run history, thống kê docs. |
| `syncAllWithoutHistory()` | `VectorSyncService` | Thực hiện sync tours/locations/reviews/coupons. |
| `syncTour(TourSyncDTO tour)` | `VectorSyncService` | Build toàn bộ document của một tour rồi upsert. |
| `runEventSync(eventCount, entityTypes, work)` | `VectorSyncService` | Bọc sync incremental bằng history và lock. |
| `scheduledSync()` | `VectorSyncService` | Scheduled full sync 2:00 AM. |
| `syncEvents(events)` | `VectorIncrementalSyncService` | Gom event theo entity và gọi sync/delete tương ứng. |
| `syncTourById(tourId)` | `VectorIncrementalSyncService` | Delete vector tour cũ rồi fetch/upsert tour mới. |
| `deleteTourVectors(tourId)` | `VectorIncrementalSyncService` | Xóa toàn bộ vector liên quan tour theo metadata filter. |
| `deleteVectorsByFilter(filter)` | `VectorService` | Xóa vector Pinecone theo metadata filter. |
| `enqueue(event)` | `ChatbotSyncDebounceService` | Ghi hoặc merge pending event vào Redis. |
| `flushReadyEvents()` | `ChatbotSyncDebounceService` | Scheduler xử lý event đã im lặng đủ 5 phút. |
| `pendingCount()` | `ChatbotSyncDebounceService` | Đếm số pending events cho dashboard. |
| `tryAnswer(...)` | `TourRankingAnswerService` | Trả tour đánh giá cao và lưu context để đặt tour. |

### 10.3. Tour catalog service

| File | Vai trò |
| --- | --- |
| `config/ChatbotSyncRabbitConfig.java` | Cấu hình RabbitTemplate JSON và exchange. |
| `event/ChatbotSyncEventDTO.java` | DTO event publish sang analytics. |
| `service/ChatbotSyncEventPublisher.java` | Publish event `chatbot.sync.*`, lỗi chỉ log warning. |
| `controller/TourController.java` | API full sync và by-id sync tour. |
| `controller/LocationController.java` | API by-id sync location. |
| `controller/ReviewController.java` | API by-id sync review. |
| `service/impl/AdminTourServiceImpl.java` | Publish event khi admin thêm/sửa/xóa tour, itinerary, media. |
| `service/impl/AdminDepartureServiceImpl.java` | Publish event khi admin thêm/sửa/xóa departure. |
| `service/impl/LocationServiceImpl.java` | Publish event khi location thay đổi. |
| `service/impl/ReviewServiceImpl.java` | Publish event khi user review và tour rating thay đổi. |

### 10.4. Booking service

| File | Vai trò |
| --- | --- |
| `event/ChatbotSyncEventDTO.java` | DTO event coupon. |
| `service/ChatbotSyncEventPublisher.java` | Publish event coupon sang RabbitMQ. |
| `controller/BookingController.java` | API by-id sync coupon. |
| `service/impl/AdminCouponServiceImpl.java` | Publish event khi admin thêm/sửa/xóa coupon. |

### 10.5. API gateway

| File | Vai trò |
| --- | --- |
| `config/GatewayRoutesConfig.java` | Route `/api/admin/dashboard/**` sang analytics `/api/dashboard/**`. |

### 10.6. Frontend admin

| File | Vai trò |
| --- | --- |
| `DashboardPage.jsx` | Gắn section `VectorSyncSection` vào dashboard. |
| `components/VectorSyncSection/VectorSyncSection.jsx` | UI thống kê sync, nút sync ngay, nút clear vector. |
| `components/VectorSyncSection/VectorSyncSection.module.scss` | Style section sync. |
| `apis/dashboard.ts` | API client cho summary/manual sync/manual clear. |
| `types/DashboardStatsDTO.ts` | TypeScript types cho sync run và summary. |

## 11. Tour đánh giá cao hoạt động như thế nào

Lỗi cũ: câu như `các tour được review cao` bị route sang RAG chung, không tạo `tourSuggestions`, không lưu context đặt tour, nên chatbot trả fallback.

Cách xử lý hiện tại:

```text
ChatbotService
        |
        v
TourRankingAnswerService.tryAnswer()
        |
        | nhận câu có tín hiệu: đánh giá cao, review cao, rating cao, tốt nhất, khách khen
        v
TourCatalogFeignClient.getAllToursForChatbotSync()
        |
        | lọc avgRating >= 4.0 và reviewCount > 0
        | sort avgRating desc, reviewCount desc, minPrice asc
        v
trả TOUR_SUGGESTIONS
        |
        v
lưu lastSearchResults/lastDepartures
```

Sau response này, các câu sau đi vào luồng cũ bình thường:

- `xem chi tiết tour 1`
- `đặt tour 1`
- `đặt tour trên`

Điểm quan trọng: handler ranking chỉ thêm nhánh trước RAG, không sửa booking flow cũ.

## 12. Đồng bộ có ảnh hưởng luồng booking cũ không

Không. Các thay đổi sync nằm ở:

- Service sync vector.
- Rabbit event/debounce.
- Dashboard admin.
- Handler riêng cho tour đánh giá cao.

Luồng booking cũ vẫn là nơi xử lý:

- `tour đi Sa Pa không`
- chọn `1`
- chọn ngày khởi hành
- nhập người lớn/trẻ em
- xác nhận booking
- hủy flow

Các handler mới khi trả danh sách tour sẽ lưu context tương thích với booking flow cũ. Vì vậy người dùng có thể hỏi theo fact/rating rồi đặt tour bằng flow cũ.

## 13. Kết quả test đã chạy

### 13.1. Backend unit test

Command đã chạy:

```powershell
mvn -pl analytics-service "-Dtest=TourRankingAnswerServiceTest,TourFactAnswerServiceTest,ChatbotServiceTest,VectorSyncServiceTest,ChatbotControllerTest" test
```

Kết quả:

```text
BUILD SUCCESS
Tests run: 40
Failures: 0
Errors: 0
Skipped: 0
```

### 13.2. Build backend

Command đã chạy:

```powershell
mvn -pl analytics-service,tour-catalog-service,booking-service,api-gateway -DskipTests package
```

Kết quả:

```text
BUILD SUCCESS
```

### 13.3. Build frontend

Command đã chạy:

```powershell
npm run build
```

Kết quả:

```text
Build success
```

Có warning sẵn của project, không chặn build.

### 13.4. Container

Đã rebuild/restart các service:

- `analytics-service`
- `tour-catalog-service`
- `booking-service`
- `api-gateway`

Trạng thái kiểm tra:

- Các service healthy.
- Rabbit queue `analytics.chatbot-sync.queue`: `0`.
- DLQ `analytics.chatbot-sync.dlq`: `0`.

### 13.5. Live API chatbot

Các case đã test:

| Câu hỏi | Kết quả |
| --- | --- |
| `các tour được review cao` | Trả `TOUR_SUGGESTIONS`, stage `SHOWING_SEARCH_RESULTS`, có 2 tour. |
| `đặt tour 1` sau câu review cao | Vào stage `SELECTING_DEPARTURE`. |
| `tôi muốn ăn bufet` | Trả tour buffet, đặt tour 1 vào được booking flow. |
| `tour nào mà hải sản tươi mỗi bữa` | Ưu tiên đúng tour `HCM-PQ-5N4D`. |
| `tour đi sapa ko` | Vẫn trả tour `HN-SA-4N3D`, luồng cũ không bị cướp. |

### 13.6. Dashboard sync API

Đã test:

- Manual sync: thành công, sync 107 docs.
- Manual clear: thành công.
- Manual sync lại sau clear: thành công, sync 107 docs.
- Summary sau test:

```text
todaySyncCount = 3
successCount = 3
failedCount = 0
pendingEventCount = 0
syncRunning = false
```

## 14. Lưu ý vận hành

- Debounce mặc định là 5 phút, scheduler flush mỗi 30 giây.
- Pending event TTL là 24 giờ.
- Redis lock `chatbot-sync:lock` tránh manual/scheduled/event sync chạy song song.
- Manual sync không clear trước.
- Manual clear là thao tác nguy hiểm, nên dashboard bắt confirm.
- Sau manual clear nên bấm `Sync ngay` để chatbot có dữ liệu lại.
- Publish event lỗi chỉ log warning, không rollback nghiệp vụ admin/user.
- By-id endpoint hiện có thể reuse dữ liệu từ full sync list rồi filter. Sau này nếu dữ liệu lớn, nên tối ưu thành query repository trực tiếp.
- Phần quét pending Redis hiện phù hợp quy mô hiện tại. Nếu production có rất nhiều event, nên đổi sang Redis set/sorted set hoặc SCAN có cursor để tối ưu hơn.

## 15. Kết luận

Thiết kế sync hiện tại tách rõ ba lớp:

- Full sync an toàn cho scheduled/manual.
- Incremental sync qua RabbitMQ + Redis debounce để tối ưu thay đổi nhỏ.
- Dashboard admin để quan sát và thao tác thủ công.

Khi dữ liệu thay đổi, hệ thống không ghi đè mù. Với update incremental, nó xóa vector cũ theo metadata filter rồi fetch dữ liệu mới nhất để upsert lại. Nhờ vậy tránh được dữ liệu thừa như lịch trình/ngày khởi hành đã bị xóa nhưng vẫn còn trong Pinecone.

Luồng booking/search chatbot cũ vẫn được giữ nguyên. Các nhánh mới như tour đánh giá cao, fact search, ẩm thực, điểm tham quan chỉ bổ sung context để người dùng có thể xem chi tiết và đặt tour bằng flow cũ.
