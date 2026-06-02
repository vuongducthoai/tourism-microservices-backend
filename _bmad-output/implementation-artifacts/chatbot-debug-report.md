# Báo cáo điều tra & sửa lỗi Chatbot Du lịch

> **Ngày**: 2026-06-01
> **Service**: `analytics-service` (chatbot RAG: Gemini + Pinecone)
> **Triệu chứng ban đầu**: Chatbot trả lời SAI điểm đến ("tour Hạ Long" → "Quảng Bình", "tour Đà Nẵng" → "Cao Bằng") và một số câu báo "Xin lỗi, tôi đang gặp sự cố kỹ thuật". Trên máy khác lại chạy đúng.
> **Phương pháp**: điều tra từng tầng (container → Pinecone → DB → intent → location resolver → Gemini) bằng 1 phiên hội thoại dài, không đoán mò.

---

## 1. Tổng quan kết quả

| # | Lỗi | Loại | Trạng thái |
|---|---|---|---|
| 1 | `buildEnhancedContext` thiếu nhánh xử lý `TOUR_SUMMARY` | Code | ✅ Đã fix |
| 2 | Levenshtein fuzzy match location quá lỏng → resolve sai điểm đến | Code | ✅ Đã fix |
| 3 | `GEMINI_API_KEY` rỗng → Gemini 403 → "sự cố kỹ thuật" | Config | ✅ Đã fix (qua `.env`) |
| 4 | "Vũng Tàu" không resolve do tên DB là "Bà Rịa - Vũng Tàu" | Code | ✅ Đã fix (alias) |
| 5 | Tour Đà Nẵng/Vũng Tàu "chưa tìm được tour" | Data (thiếu departure) | ⚠️ Cần admin tạo departure |

---

## 2. Quy trình điều tra (đã loại trừ)

Trước khi tìm ra lỗi, đã verify các tầng **KHÔNG** phải nguyên nhân:

| Tầng | Cách verify | Kết quả |
|---|---|---|
| Container chạy code cũ? | So timestamp source vs JAR vs image | ✅ Container = code mới nhất |
| Pinecone rỗng? | `describe_index_stats` | ✅ 127 vectors (72 tour + 32 loc + 20 review + 3 coupon) |
| DB thiếu tour? | Query `tours` join `locations` | ✅ Có 5 tour Hạ Long, 1 Vũng Tàu, 1 Đà Nẵng |
| Embedding sai? | Gọi `/embed` + `/query` Pinecone trực tiếp | ✅ "tour đi hạ long" → match TOUR_SUMMARY_34 (đúng) |
| Metadata thiếu? | `vectors/fetch` 1 vector | ✅ Có đủ tourId/tourCode/tourName trong nested JSON |
| Network/DNS? | `nslookup` từ container | ✅ OK (đã fix DNS commit trước) |

→ Kết luận: lỗi nằm ở **logic xử lý** trong code + **cấu hình key**, không phải hạ tầng/dữ liệu Pinecone.

---

## 3. Chi tiết từng lỗi

### Lỗi 1 — `buildEnhancedContext` thiếu nhánh `TOUR_SUMMARY`

**File**: `analytics-service/.../service/ChatbotService.java` — method `buildEnhancedContext()`

**Nguyên nhân**: Pinecone sync chủ yếu document loại `TOUR_SUMMARY` (mỗi tour 1 summary). Nhưng vòng lặp build context chỉ có nhánh xử lý cho `TOUR_DEPARTURE`, `LOCATION`, `COUPON` — **không có `TOUR_SUMMARY`**. Hệ quả: context gửi cho Gemini chỉ có raw `content` text, thiếu structured metadata (tourCode, tourName, giá, điểm đến) → Gemini không "nhận ra" tour → trả lời generic.

**Fix**: thêm nhánh `else if ("TOUR_SUMMARY".equals(doc.getType()))` extract tourName/tourCode/duration/startLoc/endLoc/minPrice/rating vào context. Thêm cả nhánh gộp cho `TOUR_ITINERARY_DAY/TOUR_POLICY/FAQ/REVIEW` (kèm tên tour để Gemini gắn ngữ cảnh).

---

### Lỗi 2 — Levenshtein fuzzy match quá lỏng (NGHIÊM TRỌNG NHẤT)

**File**: `analytics-service/.../service/LocationResolverService.java` — method `containsTokenized()`

**Nguyên nhân**: Hàm fuzzy match cho phép Levenshtein distance ≤1 với token ≤5 ký tự. Tên tỉnh tiếng Việt sau khi bỏ dấu rất ngắn và na ná nhau:

```
levenshtein("nang", "bang") = 1   → "Đà NẴNG" khớp nhầm "Cao BẰNG"
levenshtein("vung", "hung") = 1   → "VŨNG Tàu" khớp nhầm địa danh khác
```

Với `maxAllowed=1` → match SAI tràn lan. Đây là lý do mọi điểm đến bị resolve nhầm.

**Fix**: siết fuzzy chỉ áp dụng token **≥6 ký tự**, distance ≤1 (chỉ cho typo từ dài). Giữ nguyên exact token boundary match (đã đủ cho "da nang", "ha long", "sapa"...).

```java
// Trước: vt.length() < 4 continue; maxAllowed = vt.length() <= 5 ? 1 : 2;
// Sau:   vt.length() < 6 continue; if (levenshtein(vt, tt) <= 1) return true;
```

---

### Lỗi 3 — `GEMINI_API_KEY` rỗng (gây "sự cố kỹ thuật")

**File**: `docker-compose.yml` dòng 433

**Nguyên nhân**: dòng config `GEMINI_API_KEY=` để trống (không tham chiếu biến). Container nhận key rỗng → gọi Gemini với `?key=` rỗng → **HTTP 403 PERMISSION_DENIED** ("Method doesn't allow unregistered callers"). Các câu cần Gemini tổng hợp (RAG general: chính sách, giá rẻ nhất, khởi hành HCM) fail; các câu fast-path search vẫn OK nên triệu chứng "lúc được lúc không".

**Fix** (theo yêu cầu — KHÔNG hardcode key vào yml):
1. Thêm `GEMINI_API_KEY=<key>` vào file `.env` (đã gitignored, không commit)
2. Sửa docker-compose dòng 433: `GEMINI_API_KEY=${GEMINI_API_KEY}` (đọc từ `.env`)
3. `application.yml` đã dùng `${GEMINI_API_KEY:}` — không hardcode

**Verify**: sau fix, gọi Gemini trả **503 (high demand)** thay vì 403 → key đã hợp lệ (503 chỉ là quá tải tạm thời, code có retry + fallback model).

**Bảo mật**: key chỉ nằm trong `.env` (gitignored). Không có file nào commit chứa key thật.

---

### Lỗi 4 — "Vũng Tàu" không resolve

**File**: `LocationResolverService.java` — method `toCandidate()`

**Nguyên nhân**: location trong DB tên đầy đủ "Bà Rịa - Vũng Tàu". matchKeys cũ chỉ có tên đầy đủ + compact ("bariavungtau") + acronym. Query rút gọn "vung tau" không khớp cụm liền nào.

**Fix**: tách tên theo dấu phân tách `-` / `/` thành từng phần, mỗi phần (≥4 ký tự) làm alias matchKey. "Bà Rịa - Vũng Tàu" → thêm alias "ba ria" + "vung tau".

**Verify**: sau fix, "co tour di vung tau khong" → resolve đúng "Bà Rịa - Vũng Tàu" (trước đó "Hải Phòng").

---

### Lỗi 5 — Tour Đà Nẵng/Vũng Tàu "chưa tìm được tour" (DATA, không phải code)

**Nguyên nhân**: 2 tour này **không có departure nào** trong DB:

```
tour_code | num_departures
NDNHA7861 | 15   ← chatbot tìm được
NDSGN178  |  9   ← chatbot tìm được
NDSGN3361 |  0   ← Đà Nẵng: "chưa tìm được tour"
NDSGN813  |  0   ← Vũng Tàu: "chưa tìm được tour"
```

Search filter yêu cầu tour có departure trong tương lai → tour 0 departure bị loại đúng logic. **Đây là behavior đúng** — không phải bug chatbot.

**Cần làm**: Admin tạo lịch khởi hành (departure) cho tour NDSGN3361 (Đà Nẵng) và NDSGN813 (Vũng Tàu) thì chatbot sẽ tự tìm thấy.

---

## 4. Phiên hội thoại test sau khi fix

Session đơn, 10 lượt liên tiếp:

| Lượt | Câu hỏi | Kết quả |
|---|---|---|
| 1 | "xin chao" | ✅ Chào + menu hỗ trợ |
| 2 | "co tour di ha long khong" | ✅ 3 tour (NDNHA7151, SN100126VN, NDNHA7861) |
| 3 | "co tour di vung tau khong" | ✅ Resolve đúng "Bà Rịa - Vũng Tàu" (0 tour vì thiếu departure) |
| 4 | "co tour di da nang khong" | ✅ Resolve đúng "Đà Nẵng" (0 tour vì thiếu departure) |
| 5 | "co tour di sapa khong" | ✅ 1 tour (NDSGN178) |
| 6 | "tour khoi hanh tu ho chi minh" | ✅ Gemini trả lời (không còn "sự cố kỹ thuật") |
| 7 | "tour gia re nhat" | ✅ Gemini trả lời |
| 8 | "tour giam gia" | ✅ "chưa thấy tour giảm giá" (đúng — data không có discount) |
| 9 | "chinh sach huy tour the nao" | ✅ Gemini trả lời |
| 10 | "thanh toan bang cach nao" | ✅ Hỏi mã booking BK... |

**So sánh trước/sau:**

| Câu | Trước | Sau |
|---|---|---|
| Hạ Long | ❌ "Quảng Bình" | ✅ 3 tour |
| Vũng Tàu | ❌ "Hải Phòng" | ✅ Resolve đúng |
| Đà Nẵng | ❌ "Cao Bằng" | ✅ Resolve đúng |
| Khởi hành HCM | ❌ "sự cố kỹ thuật" | ✅ Gemini trả lời |
| Giá rẻ nhất | ❌ "sự cố kỹ thuật" | ✅ Gemini trả lời |
| Chính sách hủy | ❌ "sự cố kỹ thuật" | ✅ Gemini trả lời |

---

## 5. Files đã thay đổi

| File | Thay đổi |
|---|---|
| `analytics-service/.../service/ChatbotService.java` | Thêm nhánh TOUR_SUMMARY + bổ trợ trong `buildEnhancedContext` |
| `analytics-service/.../service/LocationResolverService.java` | Siết Levenshtein fuzzy (≥6 ký tự) + alias tách tên kép |
| `docker-compose.yml` | `GEMINI_API_KEY=${GEMINI_API_KEY}` (đọc từ .env) |
| `.env` | Thêm `GEMINI_API_KEY=...` (gitignored, không commit) |

**Build + deploy**: `mvn clean package` + `docker-compose up -d --build analytics-service`. Verify container nhận đúng key qua `printenv`.

---

## 6. Vì sao "máy khác chạy được"?

2 khác biệt môi trường khả dĩ:
1. **GEMINI_API_KEY**: máy khác có key hợp lệ trong docker-compose/`.env`, máy này để trống → 403.
2. **LocationResolver fuzzy**: lỗi này phụ thuộc dữ liệu locations cụ thể. Nếu máy khác có bộ locations khác (ít tên na ná nhau hơn) thì fuzzy ít va chạm hơn. Nhưng đây vẫn là bug tiềm ẩn — đã fix triệt để.

---

## 7. Khuyến nghị tiếp theo

1. **Tạo departure** cho tour Đà Nẵng (NDSGN3361) + Vũng Tàu (NDSGN813) để chatbot tìm thấy.
2. **Monitor Gemini quota**: key free tier dễ bị 503 high-demand giờ cao điểm. Code đã có retry + fallback 3 model, nhưng nếu quota cạn nên nâng cấp tier.
3. **Bổ sung alias location**: nếu thêm tỉnh có tên kép (vd "Thừa Thiên - Huế", "Phan Rang - Tháp Chàm") thì fix alias đã tự xử lý.
4. **Cân nhắc bỏ Levenshtein hoàn toàn** nếu vẫn còn false positive — thay bằng accent-insensitive exact match (đã đủ cho hầu hết case tiếng Việt).
