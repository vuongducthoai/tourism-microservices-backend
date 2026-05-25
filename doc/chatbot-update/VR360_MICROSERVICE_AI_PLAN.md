# KẾ HOẠCH PHÁT TRIỂN MICROSERVICE VR360 ĐỘC LẬP TÍCH HỢP AI HƯỚNG DẪN VIÊN
## Giải pháp tách biệt dịch vụ (Decoupled Microservice) & Nguồn ảnh VR360 miễn phí

Tài liệu này đề xuất phương án xây dựng một Microservice độc lập hoàn toàn tên là **`virtual-tour-service`** để tránh ảnh hưởng đến mã nguồn hiện tại của dự án, đồng thời tích hợp **AI hướng dẫn viên giọng nói** và hướng dẫn các **nguồn lấy ảnh VR360 miễn phí** chất lượng cao.

---

## 1. KIẾN TRÚC MICROSERVICE ĐỘC LẬP (`virtual-tour-service`)

Để tính năng du lịch ảo chạy độc lập không ảnh hưởng đến các service cũ, chúng ta sẽ tạo một service mới đặt tên là `virtual-tour-service`.

```
                  [api-gateway :8080]
                           │
             ┌─────────────┴─────────────┐
             ▼ (các route cũ)             ▼ (route mới: /api/virtual-tours/**)
    [tour-catalog-service]      [virtual-tour-service :8089] 
             │                           │
    [PostgreSQL: catalog_db]    [PostgreSQL: virtual_tour_db] (hoặc MongoDB)
```

### Các bước thiết lập độc lập:
1.  **Khởi tạo service**: Tạo một project Spring Boot mới trong thư mục root của dự án.
2.  **Khai báo CSDL riêng**: Dùng một database riêng biệt (ví dụ: `virtual_tour_db`) để quản lý các bảng: `virtual_tours`, `virtual_scenes`, `virtual_hotspots`.
3.  **Đăng ký Eureka**: Tích hợp client Eureka giống như các service khác để đăng ký dịch vụ lên `service-discovery`.
4.  **Cấu hình API Gateway**: Thêm cấu hình định tuyến trong file `application.yml` của `api-gateway`:
    ```yaml
    - id: virtual-tour-service
      uri: lb://virtual-tour-service
      predicates:
        - Path=/api/virtual-tours/**
    ```

---

## 2. TÍCH HỢP AI HƯỚNG DẪN VIÊN DU LỊCH ẢO (GIỌNG NÓI + LỊCH SỬ)

Khi người dùng click vào một địa danh (Hotspot), thay vì chỉ đọc chữ, **AI Hướng dẫn viên du lịch** sẽ tự động kể câu chuyện lịch sử văn hóa của địa danh đó bằng giọng nói tự nhiên.

```
[User Click Hotspot] 
       │
       ▼ (gửi Landmark ID / Tên di tích)
[virtual-tour-service] 
       │
       ├── 1. Gọi Gemini API: Sinh văn bản lịch sử văn hóa ngắn (150 từ) dạng kể chuyện
       ▼
[Văn bản tiếng Việt]
       │
       ├── 2. Gọi Text-To-Speech (TTS) API: Chuyển văn bản thành File âm thanh (.mp3)
       ▼
[Audio Stream / MP3 URL]
       │
       ▼ (Trả về client)
[React Frontend] -> Phát âm thanh qua thẻ HTML5 <audio> + Hiển thị hoạt họa sóng nhạc (Audio Waves)
```

### Chi tiết cách tích hợp:

#### Bước 2.1: Gọi Gemini tạo nội dung thuyết minh lịch sử
Sử dụng mô hình **Gemini 2.0 Flash** (giá rẻ, tốc độ sinh cực nhanh ~1.5 giây).
*   **System Prompt gợi ý:**
    > *"Bạn là một hướng dẫn viên du lịch ảo chuyên nghiệp, ấm áp và am hiểu sâu sắc về lịch sử văn hóa Việt Nam. Hãy thuyết minh ngắn gọn (khoảng 100-150 từ) về địa danh sau đây: {landmark_name}. Giọng điệu kể chuyện lịch sử hào hùng hoặc truyền cảm. Kết thúc bằng một câu gợi ý tham quan hoặc chúc chuyến đi vui vẻ."*
*   **Tối ưu hiệu năng**: Lưu (Cache) lại kết quả thuyết minh của các địa danh phổ biến vào Redis hoặc DB để tránh gọi Gemini liên tục gây tốn chi phí.

#### Bước 2.2: Tích hợp công nghệ chuyển văn bản thành giọng nói (Text-To-Speech - TTS)
Có 3 giải pháp phù hợp cho ngôn ngữ tiếng Việt (có bản miễn phí):

1.  **FPT.AI Text-to-Speech (Khuyên dùng cho Việt Nam) ✅**:
    *   *Ưu điểm*: Giọng đọc tiếng Việt tự nhiên nhất thị trường (đầy đủ giọng Bắc - Trung - Nam, giọng Ban Mai hay Minh Quang đọc cực kỳ truyền cảm).
    *   *Chi phí*: Miễn phí **100.000 ký tự/tháng** (quá đủ cho dự án học tập, nghiên cứu).
    *   *Cách dùng*: Gửi text qua API của FPT -> Nhận về link file `.mp3` -> Frontend phát file này.
2.  **Google Cloud Text-to-Speech (Neural2 Voice)**:
    *   *Ưu điểm*: Hạ tầng ổn định, tốc độ chuyển đổi nhanh. Giọng `vi-VN-Neural2-A` hoặc `B` nghe rất mượt.
    *   *Chi phí*: Miễn phí **4.000.000 ký tự (Standard)** hoặc **1.000.000 ký tự (WaveNet/Neural)** mỗi tháng.
3.  **Web Speech API (Trình duyệt tự phát - Free 100%)**:
    *   *Ưu điểm*: Hoàn toàn miễn phí, không cần gọi API từ server. Frontend gọi trực tiếp đối tượng `window.speechSynthesis` của trình duyệt.
    *   *Nhược điểm*: Giọng đọc máy móc, phụ thuộc vào hệ điều hành của thiết bị người dùng (trên Windows/Android nghe sẽ khác trên iOS).

---

## 3. NGUỒN LẤY ẢNH VR360 MIỄN PHÍ (KHÔNG CẦN CÓ CAMERA)

Vì bạn không có máy ảnh 360 chuyên dụng hoặc flycam để tự chụp, bạn có thể lấy ảnh VR360 miễn phí hợp pháp từ các nguồn sau:

### Nguồn 1: Trích xuất từ Google Street View (Rất nhiều địa danh Việt Nam)
Google đã chụp ảnh 360 độ cho hầu hết các con đường, di tích lịch sử ngoài trời, chùa chiền tại Việt Nam. Bạn có thể tải các bức ảnh này ở độ phân giải siêu cao (lên tới 8K) bằng công cụ miễn phí:
1.  **Street View Download 360 (SVD360)** (Phần mềm GUI miễn phí cho Windows/Mac):
    *   Bạn chỉ cần lên Google Maps, tìm điểm di tích đó, kéo icon "người màu vàng" vào xem ảnh 360.
    *   Copy đường link của trang Google Maps đó dán vào phần mềm SVD360.
    *   Phần mềm sẽ tự động tải các mảnh ảnh nhỏ (tiles) và ghép thành một bức ảnh Panorama 360 độ hoàn chỉnh chất lượng 8K cho bạn sử dụng.
2.  **Street View Image API**: Sử dụng API chính thức của Google để lấy ảnh theo vĩ độ/kinh độ (`latitude`, `longitude`).

### Nguồn 2: Sử dụng Trí tuệ nhân tạo (AI) để tạo ảnh 360 độ (AI Generative)
Nếu bạn muốn tạo không gian giả lập (ví dụ: không gian bảo tàng lịch sử thời xưa, phong cảnh rừng núi cổ kính, hoặc nội thất phòng triển lãm ảo):
1.  **Skybox AI by Blockade Labs (Khuyên dùng) ✅**:
    *   Trang web: `skybox.blockadelabs.com`
    *   Cho phép bạn gõ prompt tiếng Anh (ví dụ: *"an ancient Vietnamese temple courtyard inside a historic forest, 8k resolution, photorealistic"*) và chọn phong cách "Digital Painting" hoặc "Realism".
    *   Hệ thống sẽ sinh ra ảnh Panorama 360 độ (tỷ lệ 2:1) chuẩn chỉnh để tải về miễn phí.
2.  **Stable Diffusion với LoRA Panorama**:
    *   Sử dụng mã nguồn mở Stable Diffusion kết hợp với prompt `equirectangular, 360 view` để tự tạo ảnh không giới hạn.

### Nguồn 3: Tải ảnh CC0 (Không bản quyền) từ cộng đồng
1.  **Poly Haven / HDRI Haven**:
    *   Trang web cung cấp hàng ngàn bức ảnh 360 độ (HDRI) chất lượng cực cao (lên tới 16K) về các cảnh quan thiên nhiên, kiến trúc cổ kính, đường phố.
    *   Giấy phép CC0: Bạn có thể tải về, chỉnh sửa và sử dụng hoàn toàn miễn phí cho mục đích thương mại hoặc học tập mà không cần xin phép tác giả.
2.  **Flickr (Tìm kiếm nâng cao)**:
    *   Gõ từ khóa: `equirectangular 360 vietnam` hoặc `360 panorama historical`.
    *   Chọn bộ lọc bản quyền (License) là **"Commercial use & mods allowed"** (Cho phép sử dụng thương mại và chỉnh sửa) hoặc **"No known copyright restrictions"** để tải về hợp pháp.
3.  **Wikimedia Commons**:
    *   Kho tư liệu mở có hàng ngàn bức ảnh toàn cảnh 360 độ của các di tích lịch sử nổi tiếng toàn cầu được tặng bản quyền công cộng.

---

## 4. BẢN PHÁC THẢO API CHO SERVICE MỚI (`virtual-tour-service`)

### API quản lý Tour ảo:
*   `GET /api/virtual-tours`: Lấy tất cả tour ảo.
*   `GET /api/virtual-tours/{id}`: Trả về toàn bộ Scenes + Hotspots để frontend render.

### API AI Hướng dẫn viên (Virtual Tour Guide):
*   `GET /api/virtual-tours/guide/narrate?landmark={landmarkName}`
    *   **Logic xử lý**:
        1.  Check DB xem địa danh `{landmarkName}` đã có file audio thuyết minh lưu sẵn chưa. Nếu có -> Trả về luôn link file `.mp3`.
        2.  Nếu chưa có:
            *   Gọi Gemini API để sinh đoạn văn bản lịch sử ngắn.
            *   Gửi văn bản đó sang FPT.AI hoặc Google Cloud TTS API để sinh giọng đọc.
            *   Tải file `.mp3` trả về từ TTS API và lưu vào cloud storage (Cloudinary).
            *   Lưu link `.mp3` và văn bản thuyết minh vào database để làm bộ nhớ đệm (Cache) cho các lần gọi sau.
            *   Trả về link file `.mp3` cho client.
