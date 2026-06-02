# Plan: Share tour qua social (Facebook / Zalo / Copy link) + Open Graph

> **Nguồn**: mục 1.7 Quick Wins trong `feature-roadmap-and-enhancement-ideas.md`
> **Mục tiêu**: nút Share trên trang chi tiết tour (Facebook / Zalo / Copy link) + Open Graph meta tags để link share đẹp (ảnh + tiêu đề + giá).
> **Effort ước tính**: 1 ngày (FE share button 0.5d + OG meta 0.5d)
> **Phương pháp**: tận dụng pattern share menu đã làm cho forum PostCard.

---

## 1. Hiện trạng (đã khảo sát code)

| Hạng mục | Trạng thái |
|---|---|
| Share trên tour detail | ❌ Chưa có |
| Share menu pattern | ✅ Đã có ở forum (`PostCard.jsx` — Copy/FB/Twitter/Telegram dropdown) → clone được |
| ContactWidget (Messenger/FB/Zalo) | ✅ Đã có nhưng là widget liên hệ chung, không phải share tour |
| OG meta tags trong `index.html` | ❌ Chưa có |
| `react-helmet` | ❌ Chưa cài |

→ Phần share button: clone nhanh từ forum. Phần OG: cần cân nhắc kỹ vì project là **CRA (Client-Side Rendering)** — đây là điểm khó nhất (xem mục 4).

---

## 2. Phần A — Nút Share trên trang tour detail (FE, ~0.5 ngày)

### 2.1. Vị trí đặt nút

Trên `TourDetail.jsx`, đặt nút Share cạnh tiêu đề tour hoặc trong khối `compactPrice` (gần nút "Chọn ngày"). Đề xuất: 1 nút icon `Share2` → click bung dropdown 3 lựa chọn.

### 2.2. Component

Tạo `TourDetailComponent/ShareTourButton/ShareTourButton.jsx` (tái dùng logic từ PostCard share menu):

```jsx
import { FaShareAlt, FaFacebookF, FaRegCopy } from 'react-icons/fa';

const ShareTourButton = ({ tourCode, tourName, price, imageUrl }) => {
    const [open, setOpen] = useState(false);
    const shareUrl = `${window.location.origin}/tour-detail?tourCode=${tourCode}`;
    // hoặc URL thật của trang hiện tại: window.location.href

    const handleShare = async (channel) => {
        const title = `${tourName} — chỉ từ ${formatCurrency(price)}`;
        if (channel === 'copy') {
            await navigator.clipboard.writeText(shareUrl);
            toast.success('Đã copy link tour');
        } else if (channel === 'facebook') {
            window.open(
                `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(shareUrl)}&quote=${encodeURIComponent(title)}`,
                '_blank', 'width=620,height=520'
            );
        } else if (channel === 'zalo') {
            // Zalo share: dùng Zalo Share SDK hoặc link share
            window.open(
                `https://zalo.me/share?u=${encodeURIComponent(shareUrl)}&t=${encodeURIComponent(title)}`,
                '_blank', 'width=620,height=520'
            );
        }
        setOpen(false);
    };

    return (/* nút + dropdown 3 channel */);
};
```

### 2.3. Wire vào TourDetail

```jsx
<ShareTourButton
    tourCode={tourData.tourCode}
    tourName={tourData.tourName}
    price={priceData.salePrice || priceData.finalPrice}
    imageUrl={tourData.images?.[0]}
/>
```

### 2.4. Lưu ý Zalo

Zalo không có sharer URL chính thức đơn giản như Facebook. 2 cách:
- **Cách nhẹ (khuyến nghị MVP)**: dùng `https://zalo.me/share?...` (hoạt động hạn chế) hoặc **chỉ copy link + hướng dẫn dán vào Zalo**
- **Cách đầy đủ**: tích hợp [Zalo Share SDK](https://developers.zalo.me/docs/social-api/tham-khao/chia-se-su-dung-zalo-sharer) — cần đăng ký Zalo App ID, nhúng SDK JS. Tốn thêm ~0.5 ngày.

→ MVP: Copy link + Facebook là chính, Zalo dùng link share cơ bản.

**Effort phần A**: 0.5 ngày (component + wire + SCSS, clone từ PostCard).

---

## 3. Phần B — Open Graph meta tags (phần KHÓ — đọc kỹ)

### 3.1. Vấn đề cốt lõi: CRA không SSR

Project frontend là **Create React App (client-side rendering)**. Khi Facebook/Zalo crawler fetch URL `/tour-detail?tourCode=X`:
- Crawler **KHÔNG chạy JavaScript** → chỉ đọc HTML tĩnh `index.html`
- `react-helmet` set meta tag bằng JS **sau khi** React render → crawler không thấy
- → Link share sẽ hiện meta **mặc định** (logo công ty, tiêu đề chung), KHÔNG hiện ảnh+tên+giá tour cụ thể

**Đây là giới hạn kỹ thuật, không phải bug.** Có 4 hướng giải quyết, từ đơn giản → đầy đủ:

### 3.2. Bốn phương án OG

#### Phương án 1 — Static OG mặc định (đơn giản nhất, 30 phút) ⭐ MVP

Thêm OG tag tĩnh vào `public/index.html`:
```html
<meta property="og:title" content="Future Travel — Đặt tour du lịch" />
<meta property="og:description" content="Khám phá hàng nghìn tour du lịch chất lượng" />
<meta property="og:image" content="https://yourdomain.com/og-default.jpg" />
<meta property="og:type" content="website" />
```

→ Mọi link share đều hiện ảnh + tiêu đề **chung của công ty** (không riêng từng tour). Tốt hơn hiện trạng (không có gì), nhưng không "đẹp riêng từng tour".

#### Phương án 2 — react-helmet (chỉ đẹp cho user, KHÔNG cho crawler)

Cài `react-helmet-async`, set OG động trong TourDetail:
```jsx
<Helmet>
  <meta property="og:title" content={tourData.tourName} />
  <meta property="og:image" content={tourData.images[0]} />
  <meta property="og:description" content={`Chỉ từ ${formatCurrency(price)}`} />
</Helmet>
```

→ **Cảnh báo**: chỉ hoạt động khi browser render (tab title, share API native trên mobile). Facebook crawler **vẫn không thấy** vì không chạy JS. → ít giá trị cho mục tiêu "share đẹp".

#### Phương án 3 — Prerender service (đầy đủ, ~1-2 ngày) ⭐⭐ Khuyến nghị nếu cần SEO thật

Dùng **Prerender.io** hoặc tự host **Rendertron** / **Puppeteer**:
- Nginx/gateway detect User-Agent là bot (facebookexternalhit, Zalobot) → route sang prerender service
- Prerender chạy headless Chrome render trang đầy đủ → trả HTML có OG tag động cho crawler
- User thường vẫn nhận SPA bình thường

→ Crawler thấy đúng ảnh+tên+giá từng tour. Setup phức tạp hơn.

#### Phương án 4 — Backend route OG (cân bằng nhất, ~1 ngày) ⭐⭐⭐ Khuyến nghị

Tạo 1 endpoint tour-catalog-service trả HTML tối giản có OG tag động cho **crawler**:
```
GET /share/tour/{tourCode}  (HTML, không phải JSON)
→ <html><head>
    <meta property="og:title" content="{tourName}">
    <meta property="og:image" content="{thumbnail}">
    <meta property="og:description" content="Chỉ từ {price} — {duration}">
    <meta http-equiv="refresh" content="0;url=/tour-detail?tourCode={code}">  (redirect user thật về SPA)
  </head></html>
```
- Nút share dùng URL `/share/tour/{code}` thay vì `/tour-detail?...`
- Crawler fetch → thấy OG đầy đủ
- User click → meta refresh redirect về trang SPA thật

→ Không cần headless Chrome, không cần prerender service. Đơn giản hơn phương án 3.

### 3.3. Khuyến nghị

| Giai đoạn | Phương án | Lý do |
|---|---|---|
| **MVP (1 ngày)** | Phần A (share button) + Phương án 1 (OG tĩnh) | Ship nhanh, link share có ảnh chung công ty |
| **Phase 2** | Phương án 4 (backend OG route) | Link share đẹp riêng từng tour, không cần infra nặng |
| **Khi scale SEO** | Phương án 3 (prerender) | Nếu cần index Google tốt cho mọi trang SPA |

---

## 4. Sprint plan

### MVP (1 ngày) — đúng scope roadmap

| Task | Effort |
|---|---|
| A. `ShareTourButton` component (clone PostCard share) | 2h |
| A. SCSS dropdown + wire vào TourDetail | 1h |
| A. Logic Copy / Facebook / Zalo link | 1h |
| A. Ping share counter BE (optional, nếu muốn đếm) | 0.5h |
| B. OG tĩnh trong `index.html` (Phương án 1) | 0.5h |
| Test trên Facebook Sharing Debugger | 1h |

### Phase 2 (1 ngày, nếu muốn OG động đẹp từng tour)

| Task | Effort |
|---|---|
| BE: endpoint `/share/tour/{tourCode}` trả HTML + OG động | 3h |
| Gateway route `/share/**` → tour-catalog | 0.5h |
| Đổi share URL trong ShareTourButton sang `/share/tour/...` | 0.5h |
| Test crawler (curl User-Agent facebookexternalhit) | 1h |

---

## 5. Files sẽ chạm

**MVP:**
- `client-side/src/components/TourDetailComponent/ShareTourButton/ShareTourButton.jsx` — **NEW**
- `client-side/src/components/TourDetailComponent/ShareTourButton/ShareTourButton.module.scss` — **NEW**
- `client-side/src/components/TourDetailComponent/TourDetail.jsx` — thêm `<ShareTourButton>`
- `client-side/public/index.html` — OG tĩnh

**Phase 2:**
- `tour-catalog-service/.../controller/ShareController.java` — **NEW** (trả HTML)
- `api-gateway` GatewayRoutesConfig — route `/share/**`

---

## 6. Verify

1. **Share button**: click → dropdown hiện 3 nút. Copy → toast "Đã copy". Facebook → mở popup sharer. Zalo → mở share.
2. **OG (MVP)**: paste link vào [Facebook Sharing Debugger](https://developers.facebook.com/tools/debug/) → thấy ảnh + tiêu đề công ty.
3. **OG (Phase 2)**: `curl -A "facebookexternalhit/1.1" http://domain/share/tour/NDNHA7861` → HTML có đúng `og:title/og:image` của tour đó.

---

## 7. Lưu ý

- **Localhost không test được OG**: Facebook crawler cần URL public. Chỉ test OG sau khi deploy domain thật, hoặc dùng ngrok.
- **Share counter**: forum đã có endpoint `POST /forum/posts/{id}/share`. Nếu muốn đếm share tour, thêm cột `shareCount` + endpoint tương tự ở tour-catalog (optional, không bắt buộc cho MVP).
- **Zalo share đầy đủ** cần Zalo App ID — chỉ làm nếu Zalo là kênh chính của khách hàng.
- **SEO**: OG tag chỉ giúp share đẹp, không trực tiếp giúp SEO Google. SEO thật cần SSR/prerender (Phương án 3) hoặc chuyển sang Next.js (ngoài scope).

---

## 8. Câu hỏi cần quyết trước khi code

1. **Scope**: chỉ MVP (share button + OG tĩnh) hay làm cả Phase 2 (OG động đẹp từng tour)?
2. **Zalo**: link share cơ bản (đủ MVP) hay tích hợp Zalo SDK đầy đủ (cần App ID)?
3. **Share counter**: có cần đếm số lượt share tour không (để admin xem analytics)?
4. **URL share**: dùng `/tour-detail?tourCode=X` (query param) hay có route đẹp `/tour/{code}`?
