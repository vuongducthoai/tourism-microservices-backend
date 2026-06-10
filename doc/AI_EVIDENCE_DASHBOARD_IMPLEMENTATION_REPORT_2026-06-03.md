# Báo cáo triển khai: Kiểm chứng số liệu cho phân tích AI

Ngày thực hiện: 03/06/2026

## Mục tiêu

Thiết kế lại phần phân tích AI để quản trị viên không cần biết kỹ thuật vẫn hiểu được:

- AI đang kết luận điều gì.
- Kết luận đó dựa trên những con số nào.
- Con số lấy từ đâu trong hệ thống.
- Cách tính ra sao theo ngôn ngữ nghiệp vụ.
- Số liệu nào đang so sánh với giai đoạn nào.

## Cách hiểu giai đoạn

Trong màn hình dashboard:

- **Giai đoạn đang xem** là khoảng ngày admin chọn trên bộ lọc. Ví dụ: `07/05/2026 đến 31/05/2026`.
- **Giai đoạn so sánh** là khoảng thời gian liền trước đó, có cùng số ngày. Ví dụ: nếu giai đoạn đang xem dài 25 ngày, hệ thống lấy 25 ngày ngay trước đó: `12/04/2026 đến 06/05/2026`.

Vì vậy, khi AI nói khách mới giảm, admin sẽ thấy rõ:

- Khách mới trong giai đoạn đang xem: `1`
- Khách mới trong giai đoạn so sánh: `3`
- Cách tính: `(1 - 3) / 3 x 100 = -66.67%`

## Backend đã sửa

- Sửa logic thống kê ở `booking-service` và `iam-service` để giai đoạn so sánh luôn có cùng số ngày với giai đoạn đang xem.
- Sửa logic `booking-service` để các chỉ số booking chính như tổng booking, booking đã thanh toán, booking chờ thanh toán, booking hoàn tiền và booking hủy được tính theo đúng khoảng ngày admin chọn, không lấy nhầm tổng toàn hệ thống.
- Chuẩn hóa cách gọi nghiệp vụ:
  - `Doanh thu tiềm năng chưa thu`: tiền đang treo ở bước thanh toán, chưa phải doanh thu chắc chắn.
  - `Số tiền có thể phải hoàn`: khoản có khả năng phải trả lại khách.
  - `Doanh thu tiềm năng mất do hủy`: phần doanh thu không còn cơ hội ghi nhận vì booking đã hủy.
- Sửa `analytics-service` để tạo bảng kiểm chứng gồm 4 nhóm:
  - Doanh thu
  - Người dùng
  - Booking
  - Tour
- Mỗi số liệu kiểm chứng có:
  - Tên số liệu dễ hiểu
  - Kết quả trong giai đoạn đang xem
  - Kết quả giai đoạn so sánh nếu có
  - Chênh lệch
  - Cách tính
  - Nguồn dữ liệu
  - AI dùng số liệu đó ở nhận định/khuyến nghị nào
  - Trạng thái kiểm chứng
- Sửa prompt Gemini để AI không viết mơ hồ kiểu “kỳ này/kỳ trước”, mà dùng “giai đoạn đang xem” và “giai đoạn so sánh”.
- AI bắt buộc trả `usedMetricKeys`; nếu một nội dung AI không có số liệu chứng minh thì backend sẽ đánh dấu chưa có dẫn chứng.

## Frontend đã sửa

- Tab **Kiểm chứng số liệu** giải thích ngay trên màn hình:
  - Giai đoạn đang xem là gì.
  - Giai đoạn so sánh là gì.
  - Vì sao có vài số liệu không cần so sánh.
- Đổi bảng kỹ thuật thành bảng nghiệp vụ:
  - `Số liệu`
  - `Kết quả trong giai đoạn đang xem`
  - `So sánh để hiểu tăng/giảm`
  - `Ý nghĩa nghiệp vụ`
  - `AI dùng ở đâu`
  - `Kiểm chứng`
- Trên mỗi thẻ AI có phần **Những con số làm căn cứ** để admin thấy ngay AI dựa trên dữ liệu nào.
- Khi bấm vào số liệu căn cứ, màn hình mở đúng dòng kiểm chứng tương ứng.
- Phần chi tiết dùng chữ **Nguồn dữ liệu hệ thống** thay vì thuật ngữ kỹ thuật.
- Nút mở rộng trong bảng đổi thành **Giải thích / Ẩn** để không bị vỡ layout.
- Phần mở rộng chỉ còn diễn giải nghiệp vụ:
  - Một đoạn ngắn nói số liệu đó là gì.
  - Nó đang chỉ ra vấn đề hoặc tín hiệu nghiệp vụ nào.
  - Admin nên dùng số liệu đó để quyết định việc gì.
  Không còn hiển thị endpoint hoặc mã kỹ thuật trong phần diễn giải.
- Toàn bộ 27 số liệu trong tab **Kiểm chứng số liệu** đã có định nghĩa riêng, không dùng câu fallback chung chung.
- Dòng dưới tên số liệu không còn ghi `Nguồn nghiệp vụ: Dữ liệu đặt tour`; thay bằng định nghĩa ngắn dễ hiểu, ví dụ:
  - `Tiền tiềm năng từ booking khách chưa thanh toán.`
  - `Tour có booking đã thanh toán trong giai đoạn đang xem.`
  - `Tour có dấu hiệu vận hành cần kiểm tra.`
- Tiêu chí tour đã được làm rõ:
  - **Tour bán chạy trong giai đoạn đang xem**: tour có ít nhất một booking `PAID` trong khoảng ngày admin đang xem; xếp theo số booking đã thanh toán, nếu bằng nhau thì theo doanh thu cao hơn.
  - **Tour cần xử lý hiện tại**: tour có booking chờ hoàn tiền hoặc dấu hiệu vận hành cần admin kiểm tra; đây là cảnh báo xử lý, không phải bảng xếp hạng bán chạy.

## Build

Frontend:

```powershell
npm run build
```

Kết quả: thành công. Có các cảnh báo lint cũ của dự án, không phải lỗi build.

Backend:

```powershell
mvn -pl booking-service,iam-service,analytics-service -am clean package -DskipTests
mvn -pl booking-service,analytics-service -am clean package -DskipTests
```

Kết quả: thành công. Ba service đã tạo jar mới.

## Docker

Đã build image mới:

```powershell
docker compose build iam-service booking-service analytics-service
docker compose build booking-service analytics-service
```

Đã restart container:

```powershell
docker compose up -d iam-service booking-service analytics-service
docker compose up -d booking-service analytics-service
```

Trạng thái sau restart:

- `tourism-iam-service`: healthy
- `tourism-booking-service`: healthy
- `tourism-analytics-service`: healthy
- `tourism-api-gateway`: healthy

Frontend hiện không có Dockerfile/compose riêng trong project ngoài các Dockerfile nằm trong `node_modules`, nên phần container áp dụng cho backend services trong `tourism-microservices-backend/docker-compose.yml`.

## API test

Đã test trực tiếp analytics-service:

```http
GET http://localhost:8087/api/dashboard/analysis?from=2026-05-07&to=2026-05-31&mode=REVENUE
```

Kết quả: `200 OK`.

Đã test qua API Gateway:

```http
GET http://localhost:8080/api/admin/dashboard/analysis?from=2026-05-07&to=2026-05-31&mode=REVENUE
```

Kết quả: `200 OK`.

Kết quả kiểm chứng chính:

- Có `4` nhóm dữ liệu đối chiếu.
- Có `27` số liệu kiểm chứng.
- Tóm tắt kiểm chứng: `Đã kiểm chứng 10 nội dung AI: 10 đã xác minh, 0 cần đọc kèm ghi chú, 0 chưa có dẫn chứng.`
- Ví dụ số liệu khách mới:
  - Tên: `Khách mới trong giai đoạn đang xem`
  - Kết quả: `1`
  - Giai đoạn so sánh: `3`
  - Chênh lệch: `-2`
  - Tỷ lệ: `-66.67%`
  - Nguồn: `Dữ liệu khách hàng`

Test bổ sung sau khi sửa logic booking theo giai đoạn:

```http
GET http://localhost:8080/api/admin/dashboard/analysis?from=2026-05-25&to=2026-05-29&mode=REVENUE
```

Kết quả: `200 OK`.

- `Tổng booking trong giai đoạn đang xem`: `7`
- `Booking đã hủy`: `0`
- `Tỷ lệ hủy booking`: `0.00%`
- `Doanh thu tiềm năng chưa thu`: `46,100,000 VND`
- Không còn phát hiện wording cũ `kỳ này/kỳ trước` trong JSON trả về.

Test bổ sung sau khi sửa tiêu chí tour:

```http
GET http://localhost:8080/api/admin/dashboard/statistics?from=2026-05-25&to=2026-05-29
GET http://localhost:8080/api/admin/dashboard/analysis?from=2026-05-25&to=2026-05-29&mode=REVENUE
```

Kết quả:

- `booking.total`: `7`
- `booking.pendingPayment`: `7`
- `revenue.pendingPayment`: `46,100,000 VND`
- `tour.hotTop3`: `0`, đúng vì giai đoạn này không có booking đã thanh toán.
- `tour.needingAttention`: `1`, đúng vì đang có tour thuộc nhóm cần xử lý do yêu cầu hoàn tiền/cảnh báo vận hành.
- `aiEvidenceDashboard`: `27` số liệu.
- `verificationSummary`: `Đã kiểm chứng 10 nội dung AI: 10 đã xác minh, 0 cần đọc kèm ghi chú, 0 chưa có dẫn chứng.`

## Kết luận

Phần AI hiện không còn là “nói suông”. Admin có thể đọc từng nhận định, bấm vào số liệu căn cứ, xem cách tính và nguồn dữ liệu ngay trên dashboard. Cách trình bày đã chuyển từ kỹ thuật sang nghiệp vụ để người quản trị chỉ cần hiểu kinh doanh vẫn kiểm tra được AI đang nói đúng dựa trên dữ liệu nào.
