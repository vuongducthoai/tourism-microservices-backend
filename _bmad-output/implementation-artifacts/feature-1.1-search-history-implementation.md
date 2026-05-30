# Implementation Guide — Feature 1.1: Lịch sử tìm kiếm & Tìm kiếm gần đây

> **Mức độ**: Quick Win — Frontend only — ước tính 1 ngày
>
> **Tham chiếu**: `feature-roadmap-and-enhancement-ideas.md` — mục 1.1

---

## Mục tiêu

1. Tự động lưu **10 từ khóa tìm kiếm gần nhất** vào `localStorage`
2. Khi user **focus** vào search box (cả trang `/tours` và Banner home) → hiện dropdown các từ khóa đã tìm
3. Click vào item → tự động submit search với từ khóa đó
4. Mỗi item có nút **X** để xóa khỏi history
5. Có nút **"Xóa tất cả"** ở cuối dropdown
6. Nếu user search **lặp lại** từ khóa cũ → kéo lên đầu (không duplicate)

---

## Trải nghiệm người dùng

```
┌─────────────────────────────────────────┐
│ 🔍 Nơi bạn muốn đến...        [Tìm]   │  ← Focus vào input
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│ 🕐 TÌM KIẾM GẦN ĐÂY          Xóa tất cả│
│ ─────────────────────────────────────── │
│ 🔍 Đà Nẵng                          ✕  │  ← Click để search lại
│ 🔍 Vịnh Hạ Long                     ✕  │
│ 🔍 Phú Quốc 3 ngày                  ✕  │
│ 🔍 Tour Đà Lạt giá rẻ               ✕  │
└─────────────────────────────────────────┘
```

---

## Kiến trúc giải pháp

```
┌─────────────────────────────────────────┐
│   utils/searchHistory.js                │  ← shared logic (load/save/clear)
│   - getHistory()                        │
│   - addToHistory(keyword)               │
│   - removeFromHistory(keyword)          │
│   - clearHistory()                      │
└──────────────┬──────────────────────────┘
               │ import
       ┌───────┴───────┐
       │               │
┌──────▼──────┐ ┌─────▼──────┐
│ Tours page  │ │ Home banner│
│ Search input│ │ Search input│
└─────────────┘ └────────────┘
       │               │
       └───────┬───────┘
               ▼
   localStorage key: 'tour_search_history'
   format: JSON array string
   ["Đà Nẵng", "Hạ Long", "Phú Quốc"]
```

---

## Bước 1 — Tạo utility module quản lý history

### File mới: `src/utils/searchHistory.js`

```javascript
/**
 * Quản lý lịch sử tìm kiếm tour trong localStorage.
 * Dùng chung cho mọi search input trong app.
 */

const STORAGE_KEY = 'tour_search_history';
const MAX_ITEMS = 10;

/**
 * Lấy danh sách lịch sử search (mới nhất ở đầu).
 * @returns {string[]}
 */
export const getHistory = () => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
};

/**
 * Thêm 1 từ khóa vào đầu danh sách.
 * - Nếu đã tồn tại: kéo lên đầu (không duplicate)
 * - Trim whitespace, bỏ qua chuỗi rỗng
 * - Giới hạn tối đa MAX_ITEMS
 * @param {string} keyword
 */
export const addToHistory = (keyword) => {
  if (!keyword || typeof keyword !== 'string') return;
  const clean = keyword.trim();
  if (!clean) return;

  const current = getHistory();
  // Bỏ trùng (case-insensitive)
  const filtered = current.filter(
    (item) => item.toLowerCase() !== clean.toLowerCase()
  );
  // Thêm vào đầu, cắt giới hạn
  const next = [clean, ...filtered].slice(0, MAX_ITEMS);

  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    // Phát event để các component khác cùng lắng nghe có thể refresh
    window.dispatchEvent(new CustomEvent('search-history-changed'));
  } catch (e) {
    console.warn('Could not save search history:', e);
  }
};

/**
 * Xóa 1 từ khóa cụ thể.
 * @param {string} keyword
 */
export const removeFromHistory = (keyword) => {
  const current = getHistory();
  const next = current.filter(
    (item) => item.toLowerCase() !== keyword.toLowerCase()
  );
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    window.dispatchEvent(new CustomEvent('search-history-changed'));
  } catch {}
};

/**
 * Xóa toàn bộ lịch sử.
 */
export const clearHistory = () => {
  try {
    localStorage.removeItem(STORAGE_KEY);
    window.dispatchEvent(new CustomEvent('search-history-changed'));
  } catch {}
};
```

---

## Bước 2 — Tạo custom hook để component sử dụng

### File mới: `src/hook/useSearchHistory.js`

```javascript
import { useState, useEffect, useCallback } from 'react';
import {
  getHistory,
  addToHistory,
  removeFromHistory,
  clearHistory
} from '../utils/searchHistory';

/**
 * Hook quản lý state lịch sử search, tự sync giữa các tab/component.
 * @returns {{
 *   history: string[],
 *   add: (kw: string) => void,
 *   remove: (kw: string) => void,
 *   clear: () => void
 * }}
 */
export const useSearchHistory = () => {
  const [history, setHistory] = useState(() => getHistory());

  // Lắng nghe event để cập nhật khi component khác sửa history
  useEffect(() => {
    const refresh = () => setHistory(getHistory());

    window.addEventListener('search-history-changed', refresh);
    // Sync giữa các tab browser
    window.addEventListener('storage', refresh);

    return () => {
      window.removeEventListener('search-history-changed', refresh);
      window.removeEventListener('storage', refresh);
    };
  }, []);

  const add = useCallback((kw) => addToHistory(kw), []);
  const remove = useCallback((kw) => removeFromHistory(kw), []);
  const clear = useCallback(() => clearHistory(), []);

  return { history, add, remove, clear };
};
```

---

## Bước 3 — Tạo component Dropdown tái sử dụng

### File mới: `src/components/Commons/SearchHistoryDropdown/SearchHistoryDropdown.jsx`

```jsx
import React from 'react';
import { Clock, Search, X, Trash2 } from 'lucide-react';
import styles from './SearchHistoryDropdown.module.scss';

/**
 * Dropdown hiển thị lịch sử tìm kiếm.
 *
 * @param {Object}   props
 * @param {string[]} props.items       — danh sách từ khóa
 * @param {boolean}  props.visible     — show/hide
 * @param {Function} props.onSelect    — (keyword) => void
 * @param {Function} props.onRemove    — (keyword) => void
 * @param {Function} props.onClearAll  — () => void
 */
const SearchHistoryDropdown = ({
  items = [],
  visible,
  onSelect,
  onRemove,
  onClearAll,
}) => {
  if (!visible || items.length === 0) return null;

  return (
    <div className={styles.dropdown}>
      <div className={styles.header}>
        <span className={styles.title}>
          <Clock size={12} /> Tìm kiếm gần đây
        </span>
        <button
          type="button"
          className={styles.clearAllBtn}
          onClick={(e) => {
            e.stopPropagation();
            onClearAll?.();
          }}
        >
          <Trash2 size={11} /> Xóa tất cả
        </button>
      </div>

      <ul className={styles.list}>
        {items.map((item, idx) => (
          <li key={`${item}-${idx}`} className={styles.item}>
            <button
              type="button"
              className={styles.selectBtn}
              onMouseDown={(e) => {
                // dùng onMouseDown thay vì onClick để fire trước onBlur
                e.preventDefault();
                onSelect?.(item);
              }}
            >
              <Search size={13} className={styles.searchIcon} />
              <span className={styles.text}>{item}</span>
            </button>
            <button
              type="button"
              className={styles.removeBtn}
              onMouseDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onRemove?.(item);
              }}
              aria-label={`Xóa ${item}`}
            >
              <X size={12} />
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
};

export default SearchHistoryDropdown;
```

### File mới: `src/components/Commons/SearchHistoryDropdown/SearchHistoryDropdown.module.scss`

```scss
@keyframes slideDown {
  from { opacity: 0; transform: translateY(-6px); }
  to   { opacity: 1; transform: translateY(0); }
}

.dropdown {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.14);
  z-index: 50;
  overflow: hidden;
  animation: slideDown 0.18s ease;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: linear-gradient(135deg, #f8fafc, #f1f5f9);
  border-bottom: 1px solid #e2e8f0;
}

.title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 700;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.6px;

  svg { color: #94a3b8; }
}

.clearAllBtn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: none;
  color: #dc2626;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.15s ease;

  &:hover {
    background: #fee2e2;
  }
}

.list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
  max-height: 320px;
  overflow-y: auto;
}

.item {
  display: flex;
  align-items: stretch;
  transition: background 0.15s ease;

  &:hover {
    background: #f1f5f9;
  }
}

.selectBtn {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
  min-width: 0;
}

.searchIcon {
  color: #94a3b8;
  flex-shrink: 0;
}

.text {
  font-size: 13px;
  color: #0f172a;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.removeBtn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  background: transparent;
  border: none;
  color: #cbd5e1;
  cursor: pointer;
  transition: all 0.15s ease;
  margin-right: 8px;
  border-radius: 6px;

  &:hover {
    color: #dc2626;
    background: #fee2e2;
  }
}
```

---

## Bước 4 — Tích hợp vào trang `/tours` (FilterAndSearchInput.jsx)

### File cần sửa: `src/components/toursPageComponent/FilterAndSearchInput.jsx`

#### 4.1. Imports

Thêm vào đầu file:

```javascript
import { useSearchHistory } from '../../hook/useSearchHistory';
import SearchHistoryDropdown from '../Commons/SearchHistoryDropdown/SearchHistoryDropdown';
```

#### 4.2. Thêm state + hook trong component

```javascript
const FilterAndSearchInput = (...) => {
  // ... state hiện tại

  // ── Search history ──
  const { history, add: addHistory, remove: removeHistory, clear: clearHistory } = useSearchHistory();
  const [showHistory, setShowHistory] = useState(false);

  // ...
};
```

#### 4.3. Khi submit search → lưu vào history

Sửa hàm `handleApplyFilter` (~ line 109):

```javascript
const handleApplyFilter = (e) => {
  e?.preventDefault();

  // ⬇ THÊM dòng này: lưu vào history nếu có nhập text
  if (searchNameTour && searchNameTour.trim()) {
    addHistory(searchNameTour.trim());
  }

  // ... code cũ (setSearchParams...)
  const params = {};
  if (searchNameTour) params.searchNameTour = searchNameTour;
  // ... v.v.
  setSearchParams(params);
  setShowHistory(false); // đóng dropdown sau khi search
};
```

#### 4.4. Khi click history item → fill input + search

Thêm hàm:

```javascript
const handleSelectHistory = (keyword) => {
  setSearchNameTour(keyword);
  setShowHistory(false);
  // Trigger search ngay
  setTimeout(() => {
    const params = { searchNameTour: keyword };
    if (selectedStartLocationId) params.startLocationID = selectedStartLocationId;
    if (selectedEndLocationId) params.endLocationID = selectedEndLocationId;
    // ... copy logic từ handleApplyFilter
    setSearchParams(params);
    addHistory(keyword); // bảo đảm kéo lên đầu
  }, 0);
};
```

#### 4.5. Sửa input — thêm onFocus/onBlur + render dropdown

```jsx
<form onSubmit={handleApplyFilter} className={styles.searchBar} style={{ position: 'relative' }}>
  <input
    type="text"
    placeholder="Nơi bạn muốn đến..."
    value={searchNameTour}
    onChange={(e) => setSearchNameTour(e.target.value)}
    onFocus={() => setShowHistory(true)}
    onBlur={() => {
      // Delay để onMouseDown trên dropdown item fire trước
      setTimeout(() => setShowHistory(false), 150);
    }}
  />

  <SearchHistoryDropdown
    items={history}
    visible={showHistory}
    onSelect={handleSelectHistory}
    onRemove={removeHistory}
    onClearAll={clearHistory}
  />
</form>
```

> ⚠️ **Lưu ý**: `form` phải có `position: relative` để dropdown absolute đặt đúng. Đã set inline style hoặc thêm vào SCSS.

---

## Bước 5 — Tích hợp vào Banner home (DestinationAutocomplete)

### Lưu ý phân biệt

- **DestinationAutocomplete** đang dùng Mapbox để gợi ý địa danh (city, country)
- **History dropdown** lưu **từ khóa đã search**, có thể khác

→ Đề xuất: cho hiển thị **cả 2** trong cùng dropdown:
- Section 1: **"Tìm kiếm gần đây"** (từ history)
- Section 2: **"Gợi ý địa điểm"** (từ Mapbox API như hiện tại)

### File cần sửa: `src/components/DestinationSearchComponent/DestinationAutocomplete.jsx`

#### Pattern (tham khảo, tùy implement hiện tại):

```jsx
import { useSearchHistory } from '../../hook/useSearchHistory';
import { Clock, Search } from 'lucide-react';

const DestinationAutocomplete = (...) => {
  // ... state cũ
  const { history, add: addHistory, remove: removeHistory } = useSearchHistory();
  const [isFocused, setIsFocused] = useState(false);

  const handleSelectFromHistory = (kw) => {
    setQuery(kw);
    addHistory(kw);
    setIsFocused(false);
    onSelectDestination?.({ name: kw, type: 'history' });
  };

  // Hiện history khi input rỗng + đang focus
  const shouldShowHistory = isFocused && !query && history.length > 0;
  const shouldShowSuggestions = isFocused && query.length >= 2;

  return (
    <div className={styles.wrapper}>
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={() => setIsFocused(true)}
        onBlur={() => setTimeout(() => setIsFocused(false), 150)}
        placeholder="Bạn muốn đi đâu?"
      />

      {/* History section */}
      {shouldShowHistory && (
        <div className={styles.dropdown}>
          <div className={styles.sectionLabel}>
            <Clock size={11} /> Tìm kiếm gần đây
          </div>
          {history.slice(0, 5).map((item) => (
            <div
              key={item}
              className={styles.item}
              onMouseDown={(e) => { e.preventDefault(); handleSelectFromHistory(item); }}
            >
              <Search size={12} /> {item}
            </div>
          ))}
        </div>
      )}

      {/* Mapbox suggestions section (giữ nguyên logic cũ) */}
      {shouldShowSuggestions && (
        // ... existing JSX
      )}
    </div>
  );
};
```

---

## Bước 6 — (Optional) Track popular search analytics

Sau này có thể thêm: mỗi lần `addToHistory` cũng gửi event analytics lên backend để biết top từ khóa user search nhiều nhất → đề xuất tour theo trend.

```javascript
// Trong addToHistory:
try {
  // fire-and-forget, không await
  axios.post('/analytics/search-events', { keyword: clean }).catch(() => {});
} catch {}
```

→ Backend `analytics-service` ghi vào bảng `search_events(keyword, user_id, created_at)` → dashboard admin xem top keyword tháng.

**Không bắt buộc ở phase này.**

---

## Bước 7 — Testing

### Test cases thủ công

| Test | Bước | Expected |
|---|---|---|
| TC1 — Lưu mới | Search "Đà Nẵng" lần đầu | localStorage có `["Đà Nẵng"]` |
| TC2 — Không duplicate | Search "Đà Nẵng" lại | Vẫn có 1 item, không 2 |
| TC3 — Kéo lên đầu | Search "Hạ Long" → search "Đà Nẵng" lại | Thứ tự: `["Đà Nẵng", "Hạ Long"]` |
| TC4 — Giới hạn 10 | Search 12 keyword khác nhau | Chỉ giữ 10 cuối cùng |
| TC5 — Focus hiện dropdown | Click vào input có history | Dropdown hiện |
| TC6 — Click item search lại | Click "Đà Nẵng" trong dropdown | Input fill "Đà Nẵng" + URL update + dropdown đóng |
| TC7 — Nút X xóa | Click X trên item | Item biến mất khỏi list |
| TC8 — Xóa tất cả | Click "Xóa tất cả" | List rỗng, dropdown ẩn |
| TC9 — Sync tab | Mở 2 tab, search ở tab 1 | Tab 2 cũng thấy history mới (storage event) |
| TC10 — Input rỗng | Focus vào input không có history | Dropdown KHÔNG hiện |

### Debug nhanh trong DevTools

```javascript
// Console:
localStorage.getItem('tour_search_history')
// → '["Đà Nẵng","Hạ Long","Phú Quốc"]'

// Xóa manual để test:
localStorage.removeItem('tour_search_history')
```

---

## Bước 8 — Checklist hoàn thành

- [ ] Tạo `src/utils/searchHistory.js`
- [ ] Tạo `src/hook/useSearchHistory.js`
- [ ] Tạo `src/components/Commons/SearchHistoryDropdown/SearchHistoryDropdown.jsx`
- [ ] Tạo `src/components/Commons/SearchHistoryDropdown/SearchHistoryDropdown.module.scss`
- [ ] Sửa `FilterAndSearchInput.jsx` — import + state + integrate
- [ ] Sửa `DestinationAutocomplete.jsx` — show history section
- [ ] Test 10 cases ở Bước 7
- [ ] Commit: `feat: add search history with recent searches dropdown`

---

## Edge cases cần xử lý

1. **User chưa từng search** → không hiện section history (đã xử lý trong dropdown: `if (items.length === 0) return null`)
2. **localStorage bị disable** (incognito mode strict) → try/catch đã bao quanh, không crash
3. **Keyword quá dài** (> 100 ký tự) → có thể truncate trước khi lưu:
   ```javascript
   const clean = keyword.trim().slice(0, 100);
   ```
4. **Click X gây submit form** — đã dùng `onMouseDown` + `e.preventDefault()` + `e.stopPropagation()`
5. **Mobile keyboard cover dropdown** → CSS cần `position: fixed` thay vì `absolute` trên mobile (test sau)

---

## Cải tiến phase 2

Sau khi feature ổn định, có thể nâng cấp:

- **Bookmark search**: ngoài history, user pin từ khóa quan trọng → luôn hiện ở top
- **Search suggestion từ AI**: kết hợp với `feature-roadmap` 3.6 (Smart NLP search) → khi user gõ "tour biển 3 ngày", AI parse + suggest
- **Server-side history**: lưu vào DB thay vì localStorage để sync giữa devices (sau khi user login)
- **Trending searches**: section "Mọi người đang tìm" lấy từ analytics

---

**Tác giả**: AI implementation guide — copy-paste ready
**Ngày tạo**: 2026-05-26
**Reference**: `feature-roadmap-and-enhancement-ideas.md` mục 1.1
