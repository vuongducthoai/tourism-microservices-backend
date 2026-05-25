# KẾ HOẠCH NÂNG CẤP CHATBOT HỆ THỐNG FUTURE TRAVEL
## Phân tích so sánh với Vietravel (travel.com.vn) & Phương án kiến trúc (RabbitMQ, Redis)

Tài liệu này phân tích hệ thống Chatbot hiện tại của Future Travel so với Chatbot **Tripi** của Vietravel (`travel.com.vn`), từ đó đưa ra câu trả lời chi tiết về việc sử dụng RabbitMQ & Redis, cùng kế hoạch triển khai nâng cấp chi tiết cho cả Frontend (`tourism_frontend`) và Backend (`tourism-microservices-backend`).

---

## 1. ĐỌC HIỂU & SO SÁNH HỆ THỐNG CHATBOT

### 1.1 Hiện trạng Chatbot Future Travel
Hệ thống chatbot của chúng ta đang sử dụng kiến trúc **RAG (Retrieval-Augmented Generation)** cơ bản:
*   **Backend (`analytics-service`)**:
    *   Sử dụng regex để phát hiện ý định khuyến mãi/coupon của người dùng (`DISCOUNT_PATTERN`, `COUPON_PATTERN`).
    *   Sử dụng Pinecone Vector DB (với index `llama-text-embed-v2`, 1024d) để tìm kiếm các tài liệu có độ tương đồng ngữ nghĩa lớn nhất (từ các namespace: `TOUR_SUMMARY`, `TOUR_DEPARTURE`, `LOCATION`, `REVIEW`, `COUPON`).
    *   Gọi mô hình **Gemini 2.0 Flash** để tổng hợp câu trả lời dựa trên context tìm được từ Pinecone và trả về cho người dùng.
    *   Có hỗ trợ trả về danh sách Tour gợi ý (`tourSuggestions`) và hành động nhanh (`quickActions`).
*   **Frontend (`tourism_frontend/client-side`)**:
    *   Component `ChatbotWidget.jsx` hiển thị mascot hoạt hình chạy video green-screen và loại bỏ nền teal bằng HTML5 Canvas (ChromaKey).
    *   Hỗ trợ bong bóng lời thoại chào hỏi xoay vòng và cửa sổ chat Markdown.
    *   **Hạn chế lớn**: Phần hiển thị Tour Cards và Quick Actions hiện đang bị comment lại trong JSX, chưa được kích hoạt sử dụng thực tế.

### 1.2 So sánh với Chatbot Tripi (travel.com.vn) của Vietravel
Tripi không chỉ là chatbot hỏi đáp thông tin (Q&A) mà đóng vai trò như một **phễu bán hàng (sales funnel) khép kín** trực tiếp trong chat box:

| Tính năng | Future Travel (Hiện tại) | Tripi (Vietravel) | Khoảng cách & Giải pháp nâng cấp |
| :--- | :--- | :--- | :--- |
| **Bối cảnh hội thoại (Multi-turn Context)** | ❌ **Không**: Mỗi câu hỏi của user là độc lập, không nhớ câu nói trước đó. | ✅ **Có**: Nhớ ngữ cảnh xuyên suốt session (địa điểm khởi hành, số người, thời gian đi...). | **LỚN**: Cần lưu trữ trạng thái hội thoại (`ConversationState`) dựa trên `sessionId`. |
| **Thu thập thông tin (Slot-filling)** | ❌ **Không**: Nếu thiếu thông tin, bot chỉ trả lời chung chung hoặc không tìm được tour phù hợp. | ✅ **Có**: Tự động nhận diện thông tin và chủ động hỏi những phần còn thiếu (Ví dụ: đi đâu? tháng mấy? bao nhiêu người?). | **LỚN**: Sử dụng Prompt Engineering để điều hướng Gemini tự động hỏi thêm thông tin còn thiếu thay vì code rule engine phức tạp. |
| **Tour Cards gợi ý** | ❌ **Bị comment**: Có code nhưng đang bị comment lại trên frontend. | ✅ **Hoạt động tốt**: Cards hiển thị ảnh full-width, mã tour, điểm đi, giá đỏ nổi bật và link chi tiết. | **TRUNG BÌNH**: Bỏ comment code frontend, điều chỉnh CSS cho responsive với cửa sổ chat, bổ sung list ngày khởi hành. |
| **Đặt tour trực tiếp (Booking Flow)** | ❌ **Không**: Khách hàng phải tự tìm và đặt ngoài web. | ✅ **Có**: Cho phép đặt tour ngay trong chat (Hỏi tên, SĐT, Email -> Xác nhận -> Tạo booking -> Trả về mã booking và link thanh toán). | **RẤT LỚN**: Tích hợp Feign Client kết nối sang `booking-service` để tạo đơn đặt tour và trả về link thanh toán. |
| **Tra cứu đơn hàng** | ❌ **Không**: Phải tra cứu thủ công ngoài hệ thống. | ✅ **Có**: Cho phép nhập mã booking + Họ tên để kiểm tra trạng thái đơn hàng và thanh toán trực tiếp. | **TRUNG BÌNH**: Thêm Feign client gọi sang endpoint tìm kiếm của `booking-service`. |
| **Welcome chips & gợi ý câu hỏi** | ❌ **Không**: Không có gợi ý câu hỏi ban đầu. | ✅ **Có**: Có panel nút bấm gợi ý câu hỏi thường gặp ở dưới cùng. | **NHỎ**: Thêm các chip gợi ý (ví dụ: "Tour giá tốt hè này", "Có coupon nào không?") khi mở chat. |
| **Like/Dislike feedback** | ❌ **Không** | ✅ **Có** | **NHỎ**: Lưu đánh giá của user vào bảng `chatbot_feedback` để phân tích chất lượng AI. |

---

## 2. PHÂN TÍCH KIẾN TRÚC: CÓ CẦN RABBITMQ VÀ REDIS KHÔNG?

### 2.1 RabbitMQ: ❌ KHÔNG CẦN CHO CHATBOT CORE
*   **Hiện trạng RabbitMQ**: RabbitMQ đang được sử dụng rất tốt trong luồng Event-Driven Architecture của hệ thống (Outbox Relay từ `booking-service` sang `notification-service` để gửi thông báo và sang `iam-service` để hoàn tiền xu). `analytics-service` có dependency `spring-boot-starter-amqp` nhưng hiện chưa triển khai listener nào.
*   **Có cần cho chatbot không?**
    *   **Luồng hội thoại và Đặt tour**: Chatbot hoạt động theo cơ chế **Request-Response đồng bộ (Synchronous)**. Khi người dùng gửi tin nhắn hoặc ấn "Xác nhận đặt tour", họ cần nhận lại phản hồi ngay lập tức (câu trả lời AI, mã booking, link thanh toán). Do đó, luồng này gọi API REST trực tiếp qua OpenFeign từ `analytics-service` sang `booking-service` là phù hợp và tối ưu nhất. Sử dụng hàng đợi tin nhắn (Message Queue) cho việc này sẽ làm tăng độ trễ (latency) và độ phức tạp không đáng có.
    *   **Đồng bộ dữ liệu Vector (Pinecone)**: Hiện tại dữ liệu đang được đồng bộ định kỳ lúc 2:00 AM hằng ngày qua `VectorSyncService` (gọi Feign sang các service khác). Nếu muốn cập nhật chỗ trống (`availableSlots`) hoặc giá tour tức thời lên Pinecone, ta có thể dùng RabbitMQ lắng nghe sự kiện thay đổi từ `tour-catalog-service`. Tuy nhiên, du lịch không yêu cầu đồng bộ real-time từng giây. Phương án đơn giản hơn nhiều là nâng tần suất chạy cron job lên **mỗi 1 giờ** (hoặc 30 phút), vừa đảm bảo dữ liệu cập nhật, vừa tránh quá tải hệ thống và không cần lập trình hàng đợi.
*   **Kết luận**: **Không cần sử dụng thêm RabbitMQ** cho các tính năng nâng cấp chatbot.

### 2.2 Redis:  CẦN THIẾT CHO PRODUCTION (PHASE 2 TRỞ ĐI)
*   **Hiện trạng Redis**: Hệ thống đã có sẵn container `tourism-redis` chạy ở cổng `6379`. Các service như IAM, API Gateway và Booking đã tích hợp Redis. `analytics-service` hiện chưa khai báo Redis trong `pom.xml`.
*   **Tại sao Chatbot cần Redis?**
    1.  **Lưu trữ trạng thái hội thoại (Conversation State)**: Để chatbot nhớ ngữ cảnh (multi-turn), ta phải lưu lại lịch sử tin nhắn và thông tin thu thập được (slots: destination, departure, month...) theo `sessionId`.
        *   *Nếu lưu in-memory (`ConcurrentHashMap`)*: Chỉ chạy được khi có duy nhất 1 instance `analytics-service`. Nếu triển khai thực tế (Production) chạy nhiều bản sao (horizontal scaling), request tiếp theo của người dùng có thể bị Gateway chuyển hướng sang instance khác, dẫn đến mất sạch lịch sử trò chuyện.
        *   *Nếu dùng Redis*: Lịch sử hội thoại được lưu tập trung vào Redis dưới dạng `chatbot:session:{sessionId}` với thời hạn tự hủy (TTL) là 2 tiếng. Bất kỳ instance nào cũng có thể đọc/ghi, đảm bảo tính nhất quán.
    2.  **Bộ nhớ đệm ngữ nghĩa (Semantic Cache)**: Tránh việc gửi câu hỏi trùng lặp hoặc tương đương lên Gemini API (giúp tiết kiệm chi phí token và tăng tốc độ phản hồi từ ~3 giây xuống <0.1 giây). Ta tính hash câu hỏi của user và lưu câu trả lời vào Redis với TTL 30 phút.
    3.  **Giới hạn tần suất gửi tin (Rate Limiting)**: Chống spam tấn công Gemini API làm tăng vọt chi phí hóa đơn bằng cách đếm số request của mỗi IP trong 1 phút bằng Redis.
*   **Kết luận**: **Redis là bắt buộc (hoặc cực kỳ cần thiết)** để hiện thực hóa tính năng multi-turn context ổn định trên môi trường Productive.

---

## 3. HƯỚNG GIẢI QUYẾT & LỘ TRÌNH TRIỂN KHAI (4 PHA)

Để nâng cấp hệ thống chatbot đạt trải nghiệm tương tự như Vietravel mà không gây rủi ro phá vỡ kiến trúc hiện tại, chúng ta chia lộ trình thành 4 giai đoạn rõ ràng:

```
                  ┌───────────────────────────────────────────────┐
                  │ Giai đoạn 1: Quick Wins (Không cần Redis)      │
                  │ - Uncomment Tour Cards & Quick Actions        │
                  │ - Thêm Welcome Chips & Like/Dislike feedback  │
                  │ - Chuyển API Keys của Gemini & Pinecone ra env │
                  └──────────────────────┬────────────────────────┘
                                         │
                                         ▼
                  ┌───────────────────────────────────────────────┐
                  │ Giai đoạn 2: Multi-turn & Slot-filling        │
                  │ - Tích hợp Redis vào analytics-service         │
                  │ - Tạo ConversationState lưu vào Redis         │
                  │ - Cải tiến Prompt Gemini nhận diện thông tin  │
                  └──────────────────────┬────────────────────────┘
                                         │
                                         ▼
                  ┌───────────────────────────────────────────────┐
                  │ Giai đoạn 3: Đặt Tour Trực Tiếp (Booking Flow)│
                  │ - Feign Client: analytics -> booking-service  │
                  │ - Chatbot Booking State Machine               │
                  │ - Component xác nhận BookingConfirmCard       │
                  └──────────────────────┬────────────────────────┘
                                         │
                                         ▼
                  ┌───────────────────────────────────────────────┐
                  │ Giai đoạn 4: Tra Cứu Đơn & Tối Ưu Hóa         │
                  │ - API Tra cứu trạng thái đơn hàng             │
                  │ - Tích hợp Auth (pre-fill thông tin user)     │
                  │ - Semantic cache & Rate limiting bằng Redis   │
                  └───────────────────────────────────────────────┘
```

### PHASE 1: Quick Wins — Kích hoạt tính năng có sẵn (1-2 tuần)
*Mục tiêu: Đưa chatbot từ dạng Text đơn thuần lên giao diện giàu thông tin trực quan.*

1.  **Frontend (`tourism_frontend`)**:
    *   **Bật Tour Cards & Quick Actions**: Mở comment khối `{message.tourSuggestions...}` và `{message.quickActions...}` trong [ChatbotWidget.jsx](file:///D:/HK8/tourism_frontend/client-side/src/components/ChatbotWidget/ChatbotWidget.jsx).
    *   **Chỉnh sửa CSS responsive**: Cập nhật file `.module.scss` của `ChatbotWidget` đảm bảo Grid của Tour Cards hiển thị đẹp mắt, tự động co giãn khi kích thước khung chat thay đổi (chiều ngang khoảng ~380px).
    *   **Thêm Welcome Chips**: Hiển thị các nút bấm câu hỏi mẫu ban đầu khi người dùng mới mở khung chat (Ví dụ: "Ưu đãi tuần này 🎁", "Tour Đà Nẵng 🏖️", "Cách đặt tour thế nào?").
    *   **Like/Dislike Feedback**: Thêm nút 👍/👎 dưới mỗi câu trả lời của Bot. Khi bấm, gửi request lưu vào database của `analytics-service`.
2.  **Backend (`tourism-microservices-backend`)**:
    *   **Bảng đánh giá chatbot**: Viết controller, service và tạo bảng `chatbot_feedback` trong database `analytics_db` để lưu trữ dữ liệu Like/Dislike.
    *   **Bảo mật API Keys**: Chuyển các giá trị API Key của Gemini và Pinecone đang bị hardcode trong file `application.yml` của `analytics-service` ra biến môi trường (`environment`) trong [docker-compose.yml](file:///D:/HK8/tourism-microservices-backend/docker-compose.yml).
    *   **Sửa lỗi Slots Pinecone**: Tiến hành vá lỗi giải phóng slot của `booking-service` khi khách hàng hủy đơn (như đã phân tích tại tài liệu `PLAN-chatbot-deploy-2026-05-18.md`) để chatbot không bị đọc sai số chỗ còn trống từ Pinecone.

### PHASE 2: Multi-turn Context & Slot-filling (3-4 tuần)
*Mục tiêu: Chatbot nhớ lịch sử trò chuyện, tự động hỏi thông tin còn thiếu tương tự Vietravel.*

1.  **Tích hợp Redis vào `analytics-service`**:
    *   Khai báo dependency `spring-boot-starter-data-redis` trong file [pom.xml](file:///D:/HK8/tourism-microservices-backend/analytics-service/pom.xml).
    *   Cấu hình kết nối Redis host trong file cấu hình.
2.  **Quản lý Trạng thái Hội thoại (`ConversationState`)**:
    *   Tạo class `ConversationState` chứa:
        *   `history`: Danh sách tối đa 10 lượt chat gần nhất `[{role: "user/bot", content: "..."}]`.
        *   `slots`: Các thông tin đã biết (`destination`, `departureCity`, `travelMonth`, `numberOfPeople`).
        *   `stage`: Trạng thái hiện tại của cuộc chat (`GREETING`, `COLLECTING`, `SEARCHING`, `CONFIRMING`, `BOOKED`).
    *   Tạo `ConversationStateService` để lưu trữ đối tượng này vào Redis với key dạng `chatbot:session:{sessionId}` và TTL 2 giờ.
3.  **Cập nhật Prompt & Logic xử lý RAG**:
    *   Mỗi khi có request gửi lên, load `ConversationState` từ Redis dựa trên `sessionId`.
    *   Chèn toàn bộ lịch sử chat vào system prompt gửi cho Gemini.
    *   Cập nhật system prompt: Hướng dẫn Gemini tự động phân tích và trích xuất thông tin (điểm đi, điểm đến, thời gian, số người) từ tin nhắn mới nhất để cập nhật vào `slots`. Nếu thiếu thông tin cần tìm tour, hướng dẫn Gemini hỏi lại khách một cách tự nhiên (hỏi từng câu một, không dồn dập).
4.  **Nâng cấp Tour Cards trên Frontend**:
    *   Bổ sung danh sách các nút bấm ngày khởi hành bên dưới Tour Card (Ví dụ: `[25/05]`, `[02/06]`). Khi click, mở link tour kèm tham số ngày đã chọn để chuyển hướng người dùng tới trang đặt phòng phù hợp.

### PHASE 3: Đặt Tour Trực Tiếp Trong Chat (4-6 tuần)
*Mục tiêu: Người dùng nhập thông tin và nhận mã đặt tour kèm link thanh toán ngay tại cửa sổ chat.*

1.  **Kết nối Feign Client**:
    *   Tại `analytics-service`, tạo `ChatbotBookingFeignClient` kết nối tới `booking-service` thông qua Eureka để gọi API đặt tour.
2.  **State Machine Đặt Tour**:
    *   Khi người dùng bấm "Đặt tour này" hoặc chat "Tôi muốn đặt tour Phú Quốc ngày 25/05":
        *   Chuyển `stage` trong `ConversationState` thành `COLLECTING_BOOKING_INFO`.
        *   Bot lần lượt hỏi các thông tin liên hệ: Họ tên khách hàng, Số điện thoại, Email.
    *   Khi thu thập đầy đủ thông tin:
        *   Chuyển `stage` thành `CONFIRMING`.
        *   Backend trả về cấu trúc dữ liệu booking chi tiết kèm cờ báo trạng thái xác nhận.
3.  **Component Xác Nhận Đơn Hàng (Frontend)**:
    *   Xây dựng component `BookingConfirmCard` hiển thị dạng hóa đơn tóm tắt gồm: Tên tour, Ngày khởi hành, Số hành khách, Tổng tiền, Họ tên & SĐT liên hệ.
    *   Có hai nút hành động:
        *   `[Xác nhận đặt]`: Gọi API `POST /api/chatbot/booking` -> tạo booking thực tế ở backend -> trả về mã đặt chỗ và link thanh toán -> Bot hiển thị thông báo thành công kèm nút bấm thanh toán tiện lợi.
        *   `[Hủy]`: Quay lại luồng tư vấn bình thường.

### PHASE 4: Tra Cứu Đơn Hàng & Tối Ưu Hóa (6-8 tuần)
*Mục tiêu: Bổ sung tính năng phụ trợ và tăng tốc hiệu năng hệ thống.*

1.  **Tra cứu đơn hàng**:
    *   Khi khách hỏi "Kiểm tra đơn hàng của tôi":
        *   Bot yêu cầu cung cấp Mã booking và Họ tên khách hàng.
        *   `analytics-service` gọi Feign Client sang `booking-service` tra cứu.
        *   Hiển thị kết quả tóm tắt: Tên tour, ngày khởi hành, tổng số tiền, trạng thái thanh toán (Đã thanh toán / Chờ thanh toán) ngay trong cửa sổ chat.
2.  **Tích hợp Xác thực (Keycloak Auth)**:
    *   Nếu khách hàng đã đăng nhập hệ thống, frontend sẽ tự động gửi kèm `userId` (Keycloak ID) trong request chatbot.
    *   Backend tự động lấy thông tin Profile (Họ tên, SĐT, Email) của user từ DB để điền sẵn (pre-fill) khi đặt tour, không cần bot phải hỏi lại.
3.  **Semantic Cache & Rate Limiting (Redis)**:
    *   Áp dụng Redis cache để lưu các câu trả lời của Gemini. Sử dụng cơ chế đo khoảng cách Cosine trên Pinecone để phát hiện câu hỏi tương tự (>92% trùng khớp ngữ nghĩa) để trả ngay câu trả lời đã lưu mà không cần gọi API Gemini.
    *   Tích hợp bộ lọc rate limit bằng Redis để bảo vệ hệ thống khỏi các cuộc tấn công spam API.

---

## 4. CHI TIẾT CÁC FILE CẦN SỬA ĐỔI / THÊM MỚI

### 4.1 Backend (`tourism-microservices-backend`)

#### [MODIFY] [pom.xml](file:///D:/HK8/tourism-microservices-backend/analytics-service/pom.xml)
*   Thêm dependency Redis phục vụ lưu trữ State và Caching:
    ```xml
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    ```

#### [NEW] [ConversationState.java](file:///D:/HK8/tourism-microservices-backend/analytics-service/src/main/java/com/tourism/analytics/entity/ConversationState.java)
*   Định nghĩa POJO lưu trữ lịch sử trò chuyện và thông tin slots phục vụ thu thập dữ liệu khách hàng.

#### [NEW] [ConversationStateService.java](file:///D:/HK8/tourism-microservices-backend/analytics-service/src/main/java/com/tourism/analytics/service/impl/ConversationStateService.java)
*   Triển khai dịch vụ đọc/ghi trạng thái hội thoại từ Redis thông qua `StringRedisTemplate` với TTL 2 giờ.

#### [MODIFY] [ChatbotService.java](file:///D:/HK8/tourism-microservices-backend/analytics-service/src/main/java/com/tourism/analytics/service/ChatbotService.java)
*   Nạp `ConversationState` từ Redis theo `sessionId` ở đầu hàm `handleUserMessage`.
*   Truyền `history` vào hàm `buildEnhancedPrompt` để thiết lập cuộc hội thoại có ngữ cảnh.
*   Cập nhật System Prompt để hướng dẫn Gemini cách thực hiện Slot-filling tự động.
*   Sau khi nhận phản hồi từ Gemini, cập nhật câu trả lời mới vào `history` và lưu lại trạng thái vào Redis.

#### [NEW] [ChatbotBookingFeignClient.java](file:///D:/HK8/tourism-microservices-backend/analytics-service/src/main/java/com/tourism/analytics/feign/ChatbotBookingFeignClient.java)
*   Định nghĩa client OpenFeign kết nối đồng bộ sang `booking-service` để tạo đơn đặt tour.

#### [MODIFY] [docker-compose.yml](file:///D:/HK8/tourism-microservices-backend/docker-compose.yml)
*   Chuyển các biến cấu hình khóa bí mật của Gemini và Pinecone sang biến môi trường của container `analytics-service` để đảm bảo an toàn bảo mật.

---

### 4.2 Frontend (`tourism_frontend`)

#### [MODIFY] [ChatbotWidget.jsx](file:///D:/HK8/tourism_frontend/client-side/src/components/ChatbotWidget/ChatbotWidget.jsx)
*   Bỏ comment hiển thị Tour Cards và Quick Actions.
*   Thêm mảng `WELCOME_CHIPS` và render các nút bấm gợi ý câu hỏi khi `messages.length === 1` (chỉ có câu chào của bot).
*   Thêm icon 👍/👎 dưới bubble của bot và hàm gọi API gửi feedback đánh giá.
*   (Phase 3) Thêm component con `BookingConfirmCard` để người dùng xác nhận thông tin đặt tour và nút bấm chuyển hướng thanh toán trực tuyến.

#### [MODIFY] [ChatbotWidget.module.scss](file:///D:/HK8/tourism_frontend/client-side/src/components/ChatbotWidget/ChatbotWidget.module.scss)
*   Tối ưu hóa class CSS của Tour Cards, Grid hiển thị ảnh, giá tour và ngày khởi hành đảm bảo responsive tuyệt đối trên các thiết bị di động và các kích thước khung chat nhỏ.
*   Định dạng giao diện bắt mắt cho các Welcome Chips và nút Like/Dislike.

---

## 5. TỔNG KẾT & PHÂN BỔ THỨ TỰ ƯU TIÊN

*   **RabbitMQ**: **Không cần thiết** cho chatbot. Hãy giữ nguyên thiết kế cron job đồng bộ Pinecone hiện tại (có thể tăng chu kỳ lên 1 tiếng/lần) để đảm bảo hệ thống tinh gọn, ổn định.
*   **Redis**: **Cực kỳ quan trọng**. Cần tích hợp Redis vào `analytics-service` từ Phase 2 để lưu trữ Conversation State, giúp chatbot có khả năng nhớ ngữ cảnh hội thoại ổn định trên môi trường sản xuất có tính năng mở rộng ngang.

Dưới đây là bảng phân bổ thứ tự ưu tiên thực hiện các công việc:

| Thứ tự | Công việc | Giai đoạn | Độ khó | Độ ảnh hưởng |
| :---: | :--- | :---: | :---: | :---: |
| **1** | Bật Tour Cards & Quick Actions trên Frontend (Uncomment) | Phase 1 | Dễ | 🔥 Rất cao |
| **2** | Chuyển API Keys của Gemini & Pinecone ra env vars | Phase 1 | Dễ | 🔒 Bảo mật |
| **3** | Sửa bug hoàn trả slot khi hủy đơn ở backend | Phase 1 | Vừa | Cao |
| **4** | Thêm Welcome Chips và phản hồi Like/Dislike | Phase 1 | Dễ | Trung bình |
| **5** | Tích hợp Redis & Xây dựng lưu trữ Conversation State | Phase 2 | Vừa | 🔥 Rất cao |
| **6** | Cập nhật prompt Gemini nhớ history & tự động slot-filling | Phase 2 | Vừa | Rất cao |
| **7** | Thiết lập Feign Client kết nối sang `booking-service` | Phase 3 | Vừa | 🔥 Rất cao |
| **8** | Xây dựng giao diện xác nhận đặt tour trong chat (`BookingConfirmCard`) | Phase 3 | Khó | Rất cao |
| **9** | Phát triển API và luồng Tra cứu đơn hàng qua mã booking | Phase 4 | Vừa | Trung bình |
| **10** | Tích hợp Keycloak Auth để tự động lấy profile khách hàng | Phase 4 | Vừa | Trung bình |
| **11** | Tối ưu hóa Semantic Cache & Rate Limiting bằng Redis | Phase 4 | Khó | Trung bình |

---
*Kế hoạch này được tổng hợp dựa trên kiến trúc microservices thực tế của dự án và mô hình trải nghiệm người dùng của Vietravel.*
