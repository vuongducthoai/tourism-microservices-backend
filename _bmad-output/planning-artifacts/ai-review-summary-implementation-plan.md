# Plan: AI Review Summary — Tóm tắt review tour bằng AI

> **Trạng thái**: 📋 Đang lên kế hoạch
> **Effort dự kiến**: 2–3 ngày
> **Phạm vi**: `tour-catalog-service` (backend) + `client-side/src/components/TourDetail` (frontend)
> **Mục tiêu**: Tour có 200+ review, user không đọc hết → AI tóm tắt thành 3 mục (Ưu điểm / Nhược điểm / Lời khuyên), hiển thị ngay đầu trang tour detail. Cache lại, chỉ regen khi có review mới.

---

## 1. Vấn đề & Giải pháp

### 1.1. Vấn đề
- Tour phổ biến có 200+ review, user phải scroll hàng giờ.
- Review hiện chỉ sort theo thời gian/rating — không có cái nhìn tổng quan.
- Đọc review là **rào cản đặt tour** — user phân vân nhưng lười đọc → rớt conversion.

### 1.2. Giải pháp
AI Summary 3 mục hiển thị đầu trang tour:

| Mục | Ví dụ output |
|---|---|
| **Ưu điểm chính** | "Hướng dẫn viên thân thiện, am hiểu địa phương — 23/50 review đề cập. Khách sạn sạch sẽ, view đẹp — 19/50." |
| **Nhược điểm thường gặp** | "Lịch trình hơi gấp ngày 2 — 12/50 review. Bữa trưa chưa hài lòng — 8/50." |
| **Lời khuyên từ khách trước** | "Mang theo áo ấm khi lên Sapa buổi tối. Đặt phòng hướng biển khi check-in." |

### 1.3. Tech stack
- **AI**: Groq Llama 3.3 70B Versatile (đã dùng cho forum moderation, đã có WebClient + JdkClientHttpConnector bypass Netty DNS trong Docker).
- **Cache**: PostgreSQL bảng `tour_review_summaries` (1 row mỗi tour), invalidate bằng cờ `is_stale`.
- **Trigger regen**: cron daily 02:00 + flag stale ngay khi có review mới.

---

## 2. Thiết kế Backend (tour-catalog-service)

### 2.1. Entity mới — `TourReviewSummary`

File: `tour-catalog-service/src/main/java/com/tourism/tourcatalog/entity/TourReviewSummary.java`

```java
@Entity
@Table(name = "tour_review_summaries", indexes = {
    @Index(name = "idx_summary_tour", columnList = "tour_id", unique = true)
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TourReviewSummary {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tour_id", nullable = false, unique = true)
    private Integer tourId;

    @Column(name = "pros",       columnDefinition = "TEXT") private String pros;
    @Column(name = "cons",       columnDefinition = "TEXT") private String cons;
    @Column(name = "tips",       columnDefinition = "TEXT") private String tips;

    @Column(name = "review_count_at_gen") private Integer reviewCountAtGen;
    @Column(name = "avg_rating_at_gen")   private Double  avgRatingAtGen;
    @Column(name = "model")               private String  model;     // "llama-3.3-70b-versatile"

    @Column(name = "is_stale") @Builder.Default
    private Boolean isStale = false;   // true → cần regen

    @Column(name = "generated_at")  private LocalDateTime generatedAt;
    @Column(name = "last_used_at")  private LocalDateTime lastUsedAt;
}
```

**Vì sao 3 cột TEXT riêng thay vì 1 JSON?**
- Truy vấn / hiển thị từng phần dễ hơn (không phải parse JSON ở FE).
- Migrate cấu trúc dễ hơn nếu sau này thêm mục thứ 4 (vd "Best for").

**Vì sao có `reviewCountAtGen` + `avgRatingAtGen`?**
- Theo dõi summary cũ bao nhiêu — quyết định khi nào trigger regen (vd chênh > 10 review mới so với lúc gen).

### 2.2. Repository

```java
public interface TourReviewSummaryRepository extends JpaRepository<TourReviewSummary, Long> {
    Optional<TourReviewSummary> findByTourId(Integer tourId);
    List<TourReviewSummary> findByIsStaleTrueOrGeneratedAtBefore(LocalDateTime threshold);
}
```

### 2.3. Service mới — `ReviewSummaryService`

Trách nhiệm:
1. `getSummary(tourId)` — trả summary hiện có (kèm trạng thái cache HIT/STALE/MISS); cập nhật `last_used_at`.
2. `generateSummary(tourId)` — gọi Groq, lưu DB. Throw nếu < 10 review (chưa đủ dữ liệu).
3. `markStale(tourId)` — gọi từ `ReviewService.create()` khi có review mới.
4. `regenStaleSummaries()` — cron daily, regen tour có `is_stale = true` HOẶC `generated_at < 7 ngày trước`.

```java
@Service @RequiredArgsConstructor @Slf4j
public class ReviewSummaryServiceImpl implements ReviewSummaryService {
    private final TourReviewSummaryRepository summaryRepository;
    private final ReviewRepository reviewRepository;
    private final TourRepository tourRepository;
    private final GroqReviewSummaryClient groqClient;

    private static final int MIN_REVIEWS_TO_SUMMARIZE = 10;
    private static final int MAX_REVIEWS_TO_FEED = 50;   // sample, không feed hết 200

    @Transactional
    public TourReviewSummaryResponse getSummary(Integer tourId) {
        return summaryRepository.findByTourId(tourId)
            .map(s -> {
                s.setLastUsedAt(LocalDateTime.now());
                return mapToResponse(s, s.getIsStale() ? "STALE" : "HIT");
            })
            .orElseGet(() -> mapToResponse(null, "MISS"));
    }

    @Transactional
    public TourReviewSummaryResponse generateSummary(Integer tourId) {
        Tour tour = tourRepository.findById(tourId)
            .orElseThrow(() -> new RuntimeException("Tour không tồn tại"));

        // Lấy 50 review mới nhất (chỉ visible) để feed
        List<Review> reviews = reviewRepository.findTop50ByTourTourIDAndIsVisibleTrueOrderByCreatedAtDesc(tourId);
        if (reviews.size() < MIN_REVIEWS_TO_SUMMARIZE) {
            throw new RuntimeException("Cần ít nhất " + MIN_REVIEWS_TO_SUMMARIZE + " review để tóm tắt");
        }

        GroqSummaryResult result = groqClient.summarize(tour.getName(), reviews);

        TourReviewSummary entity = summaryRepository.findByTourId(tourId)
            .orElseGet(() -> TourReviewSummary.builder().tourId(tourId).build());
        entity.setPros(result.pros());
        entity.setCons(result.cons());
        entity.setTips(result.tips());
        entity.setReviewCountAtGen(reviews.size());
        entity.setAvgRatingAtGen(reviews.stream().mapToInt(Review::getRating).average().orElse(0));
        entity.setModel(result.model());
        entity.setGeneratedAt(LocalDateTime.now());
        entity.setIsStale(false);
        summaryRepository.save(entity);

        return mapToResponse(entity, "GENERATED");
    }

    public void markStale(Integer tourId) {
        summaryRepository.findByTourId(tourId).ifPresent(s -> {
            s.setIsStale(true);
            summaryRepository.save(s);
        });
    }
}
```

### 2.4. Groq client — `GroqReviewSummaryClient`

Tách riêng khỏi forum-service vì khác prompt + khác output format. Tái sử dụng **pattern** (WebClient + JdkClientHttpConnector + retry) chứ không tái sử dụng code:

```java
@Service @RequiredArgsConstructor @Slf4j
public class GroqReviewSummaryClient {
    private final WebClient webClient;   // Bean dùng JdkClientHttpConnector

    @Value("${groq.api.key}") private String apiKey;
    @Value("${groq.api.model:llama-3.3-70b-versatile}") private String model;

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    public GroqSummaryResult summarize(String tourName, List<Review> reviews) {
        String prompt = buildPrompt(tourName, reviews);
        // ... call Groq, parse JSON response
    }

    private String buildPrompt(String tourName, List<Review> reviews) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bạn là trợ lý phân tích review du lịch. ")
          .append("Tóm tắt ").append(reviews.size())
          .append(" review sau cho tour \"").append(tourName).append("\" theo 3 mục:\n")
          .append("1. Ưu điểm chính (3-5 ý ngắn, kèm số review đề cập nếu rõ)\n")
          .append("2. Nhược điểm thường gặp (3-5 ý ngắn)\n")
          .append("3. Lời khuyên từ khách trước (2-4 lời khuyên thực tế)\n\n")
          .append("Trả về JSON: {\"pros\":\"...\",\"cons\":\"...\",\"tips\":\"...\"}.\n")
          .append("Mỗi mục dùng bullet \"- \" xuống dòng.\n\n")
          .append("=== REVIEWS ===\n");
        for (int i = 0; i < reviews.size(); i++) {
            Review r = reviews.get(i);
            sb.append("[").append(i + 1).append("] ⭐")
              .append(r.getRating()).append("/5: ")
              .append(truncate(r.getComment(), 300)).append("\n");
        }
        return sb.toString();
    }
}
```

**Token budget**: 50 review × ~300 ký tự ≈ 15k token input. Llama 3.3 70B context 128k → thoải mái. Output ~500 token.

**JSON parsing**: dùng `response_format: { type: "json_object" }` của Groq để force JSON; fallback regex nếu Groq bug.

### 2.5. DTO response

```java
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class TourReviewSummaryResponse {
    private String pros;
    private String cons;
    private String tips;
    private Integer reviewCountAtGen;
    private Double avgRatingAtGen;
    private LocalDateTime generatedAt;
    private String cacheStatus;   // HIT | STALE | MISS | GENERATED
    private Boolean isStale;
}
```

### 2.6. Endpoints

Thêm vào `ReviewController` (hoặc `TourController` — tùy convention sẵn):

```java
@GetMapping("/tours/{tourId}/review-summary")
public ResponseEntity<?> getSummary(@PathVariable Integer tourId) {
    return ResponseEntity.ok(reviewSummaryService.getSummary(tourId));
}

@PostMapping("/admin/tours/{tourId}/review-summary/regenerate")
public ResponseEntity<?> regenerate(@PathVariable Integer tourId) {
    return ResponseEntity.ok(reviewSummaryService.generateSummary(tourId));
}
```

- **GET** public — FE gọi khi load tour detail.
- **POST regenerate** chỉ ADMIN — debug + force refresh khi prompt được tinh chỉnh.

### 2.7. Trigger stale khi có review mới

Sửa `ReviewServiceImpl.createReview()`:

```java
public Review createReview(...) {
    Review saved = reviewRepository.save(...);
    // Đánh dấu summary stale — async để không block user
    try {
        reviewSummaryService.markStale(saved.getTour().getTourID());
    } catch (Exception e) {
        log.warn("Cannot mark summary stale: {}", e.getMessage());
    }
    return saved;
}
```

### 2.8. Cron job regen daily

```java
@Component @RequiredArgsConstructor @Slf4j
public class ReviewSummaryRegenJob {
    private final TourReviewSummaryRepository repo;
    private final ReviewSummaryService service;

    /** Mỗi 02:00 đêm: regen mọi summary đang stale hoặc gen > 7 ngày trước. */
    @Scheduled(cron = "0 0 2 * * *")
    public void regenStale() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        List<TourReviewSummary> targets = repo.findByIsStaleTrueOrGeneratedAtBefore(threshold);
        log.info("Regenerating {} stale summaries", targets.size());
        for (TourReviewSummary s : targets) {
            try {
                service.generateSummary(s.getTourId());
                Thread.sleep(2000);   // rate-limit Groq API
            } catch (Exception e) {
                log.warn("Regen failed tour {}: {}", s.getTourId(), e.getMessage());
            }
        }
    }
}
```

**Lý do sleep 2s**: Groq free tier ~30 req/min. Với 50 tour, mất ~2 phút — chấp nhận được vì chạy 02:00 đêm.

### 2.9. Config bổ sung

`application.yml`:
```yaml
groq:
  api:
    key: ${GROQ_API_KEY}      # đã có cho forum, dùng chung
    model: llama-3.3-70b-versatile
review-summary:
  min-reviews: 10
  max-reviews-feed: 50
  regen-after-days: 7
  cron-enabled: true
```

---

## 3. Thiết kế Frontend (client-side)

### 3.1. API service

`client-side/src/services/tour/reviewSummaryApi.js`:
```javascript
import axios from '../../utils/axiosCustomize';
export const reviewSummaryApi = {
    get: (tourId) => axios.get(`/tours/${tourId}/review-summary`).then(r => r.data?.data ?? r.data),
};
```

### 3.2. Component `<ReviewSummaryCard />`

Vị trí: ngay sau hero/banner tour detail, trước list review chi tiết.

```jsx
const ReviewSummaryCard = ({ tourId }) => {
    const [summary, setSummary] = useState(null);
    const [loading, setLoading] = useState(true);
    const [expanded, setExpanded] = useState({ pros: false, cons: false, tips: false });

    useEffect(() => {
        reviewSummaryApi.get(tourId)
            .then(setSummary)
            .catch(() => setSummary(null))
            .finally(() => setLoading(false));
    }, [tourId]);

    if (loading) return <SummarySkeleton />;
    if (!summary || summary.cacheStatus === 'MISS')
        return null;   // tour < 10 review → ẩn widget

    return (
        <div className={styles.summaryCard}>
            <div className={styles.summaryHeader}>
                <Sparkles size={18} /> Tóm tắt AI từ {summary.reviewCountAtGen} review
                <span className={styles.avgRating}>⭐ {summary.avgRatingAtGen?.toFixed(1)}</span>
                {summary.isStale && <span className={styles.staleTag}>Đang cập nhật</span>}
            </div>
            <div className={styles.summaryGrid}>
                <SummarySection title="Ưu điểm chính" icon={ThumbsUp} color="#059669"
                                content={summary.pros} />
                <SummarySection title="Nhược điểm" icon={ThumbsDown} color="#dc2626"
                                content={summary.cons} />
                <SummarySection title="Lời khuyên" icon={Lightbulb} color="#d97706"
                                content={summary.tips} />
            </div>
            <div className={styles.summaryFooter}>
                <Clock size={12} /> Cập nhật {formatRelative(summary.generatedAt)}
                <span className={styles.disclaimer}>· Tóm tắt tự động bằng AI, có thể chưa hoàn toàn chính xác</span>
            </div>
        </div>
    );
};

const SummarySection = ({ title, icon: Icon, color, content }) => (
    <div className={styles.section} style={{ borderLeftColor: color }}>
        <h4 style={{ color }}><Icon size={14} /> {title}</h4>
        <div className={styles.sectionContent}>
            {/* AI trả về plain text với bullet "- " mỗi dòng — render thẳng giữ format */}
            <pre>{content}</pre>
        </div>
    </div>
);
```

### 3.3. Style

- Card có gradient nhẹ + shadow nổi để phân biệt với content thường.
- Grid 3 cột desktop, 1 cột mobile.
- Section bên trái có border màu (xanh/đỏ/vàng) phân biệt.
- Skeleton 3 cột mờ trong lúc load (~500ms-1s).

### 3.4. Tích hợp vào TourDetail

Trang tour detail hiện tại có structure: `Hero → Info → Itinerary → Reviews`. Thêm:
```jsx
<HeroSection />
<TourInfo />
<ReviewSummaryCard tourId={tourId} />   {/* ← NEW */}
<Itinerary />
<ReviewList />
```

### 3.5. Trang admin xem & regen

Trang admin tour management thêm cột "AI Summary" với:
- Trạng thái: ✅ Fresh / ⚠ Stale / ❌ Chưa có
- Nút "Regen" (gọi POST endpoint)

---

## 4. Phase rollout

| Phase | Task | Effort |
|---|---|---|
| **Phase 1 — Backend foundation** | Entity + Repository + Migration | 0.5 ngày |
| **Phase 2 — Groq integration** | Client + prompt + JSON parser + test với 1 tour | 0.5 ngày |
| **Phase 3 — Service layer** | Generate / getSummary / markStale + tích hợp vào createReview | 0.5 ngày |
| **Phase 4 — Endpoints + cron** | GET public + POST admin + @Scheduled regen | 0.5 ngày |
| **Phase 5 — Frontend card** | Component + API + skeleton + style | 0.5 ngày |
| **Phase 6 — Admin UI** | Trang admin xem status + nút regen | 0.5 ngày |
| **Tổng** | | **3 ngày** |

---

## 5. Edge case & rủi ro

### 5.1. Edge cases
| Case | Xử lý |
|---|---|
| Tour < 10 review | Không hiển thị widget — `cacheStatus = MISS` |
| Tour có 200 review nhưng chỉ feed 50 | Lấy 50 review **mới nhất** + ưu tiên review có comment dài (loại review 1 dòng) |
| Review chứa ngôn từ không phù hợp lọt vào summary | Lọc trước khi feed: chỉ review `isVisible = true` |
| Groq trả về JSON malformed | Fallback regex parser; nếu vẫn fail → giữ summary cũ + log error |
| Groq API down lúc cron | Skip lần đó, lần cron sau (24h) thử lại; nếu stale > 14 ngày → alert admin |
| Tour bị xóa nhưng summary còn | Cron + FK CASCADE: thêm `@OnDelete` hoặc query check tồn tại |

### 5.2. Rủi ro

| Rủi ro | Xác suất | Tác động | Giảm thiểu |
|---|---|---|---|
| AI tóm tắt sai nội dung review | Trung bình | Cao (mất uy tín) | Disclaimer "Tóm tắt tự động bằng AI"; admin có nút regen + xem nguồn |
| Chi phí Groq vượt budget | Thấp | Trung bình | Free tier 30 req/min đủ; max ~50 tour × 1 lần/tuần ≈ 200 req/tháng |
| Latency lần đầu load (Groq mất 5-10s) | Cao | Trung bình | Lazy load card riêng (không block render trang); chỉ gen async qua cron, GET luôn trả cache |
| Review độc hại vẫn ảnh hưởng AI summary | Trung bình | Trung bình | Chỉ feed review `isVisible=true`; sau Sprint 4 dùng cờ moderation tương tự forum |

### 5.3. Quyết định kiến trúc đáng lưu ý

- **Vì sao đặt trong `tour-catalog-service` chứ không service riêng?** Review entity nằm ở đây; tách thành microservice mới chi phí cao mà không giá trị thêm.
- **Vì sao không dùng Redis cache thay PostgreSQL?** Summary là **dữ liệu** cần persist (regenerate cost cao, mất khi Redis restart không chấp nhận được). DB phù hợp hơn.
- **Vì sao không stream Groq response?** UX khác biệt không đáng — gen async ở backend, FE chỉ đọc cache nên không cần streaming.
- **Vì sao không gen ngay khi user request summary lần đầu?** Latency 5-10s không acceptable cho GET. Phải có review đầu tiên → cron đêm sau gen → user xem được hôm sau. Trade-off chấp nhận.

---

## 6. Verification

### 6.1. Test thủ công
1. Pick 1 tour có ≥ 10 review → gọi POST regenerate → verify summary trong DB có pros/cons/tips.
2. GET endpoint → verify cacheStatus = HIT.
3. Thêm 1 review mới → DB summary có `is_stale = true`.
4. Trigger cron manually → summary refresh + `is_stale = false`.
5. Tour < 10 review → GET trả MISS → FE ẩn widget.

### 6.2. Verify với prompt thực
- So sánh output AI vs đọc thủ công 50 review → đánh giá độ chính xác.
- Tinh chỉnh prompt nếu AI quá generic hoặc bỏ sót pattern phổ biến.

---

## 7. Tác động & Sprint sau

- **Tour catalog admin**: thêm tab "AI Summary" trong tour detail admin (xem/regen/edit thủ công nếu AI sai).
- **Booking conversion**: theo dõi tỉ lệ user đặt tour có summary vs không — A/B test 2 tuần.
- **Sprint sau**: có thể mở rộng cho **summary review hotel** (nếu thêm domain), **summary destination** (gom review từ nhiều tour cùng địa điểm).

---

## 8. Files cần tạo/sửa

**Backend (tour-catalog-service):**
- ✨ `entity/TourReviewSummary.java`
- ✨ `repository/TourReviewSummaryRepository.java`
- ✨ `service/ReviewSummaryService.java` + `impl/ReviewSummaryServiceImpl.java`
- ✨ `service/GroqReviewSummaryClient.java`
- ✨ `dto/response/TourReviewSummaryResponse.java`
- ✨ `dto/internal/GroqSummaryResult.java` (record)
- ✨ `job/ReviewSummaryRegenJob.java`
- ✏ `service/impl/ReviewServiceImpl.java` — gọi `markStale` trong `createReview`
- ✏ `controller/ReviewController.java` (hoặc TourController) — 2 endpoints
- ✏ `repository/ReviewRepository.java` — query `findTop50ByTourTourIDAndIsVisibleTrueOrderByCreatedAtDesc`
- ✏ `application.yml` — config Groq + review-summary
- ✏ `config/WebClientConfig.java` (nếu chưa có) — bean WebClient với JdkClientHttpConnector
- ✏ Main app class — `@EnableScheduling` (nếu chưa bật)

**Frontend (client-side):**
- ✨ `services/tour/reviewSummaryApi.js`
- ✨ `components/TourDetail/ReviewSummary/ReviewSummaryCard.jsx`
- ✨ `components/TourDetail/ReviewSummary/ReviewSummaryCard.module.scss`
- ✨ `components/TourDetail/ReviewSummary/SummarySkeleton.jsx`
- ✏ `components/TourDetail/TourDetailPage.jsx` — gắn `<ReviewSummaryCard tourId={...} />`
- ✏ `components/AdminComponent/Pages/ToursPage/*` — thêm cột AI Summary status + nút Regen
