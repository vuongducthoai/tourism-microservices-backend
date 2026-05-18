# PLAN: Giao Diện Admin — Quản Lý DEAD Outbox Events

## Mục Tiêu

Thêm 1 trang mới vào Admin Panel để quản lý các **DEAD Outbox Event** (sự kiện coin refund / notification thất bại quá nhiều lần). Admin có thể:
- Xem danh sách DEAD events theo trang (phân trang)
- Lọc theo loại event (Coin Refund / Notification)
- Retry 1 event đơn lẻ
- Retry tất cả (hoặc theo loại)
- Xem chi tiết payload từng event

---

## 1. API Backend (đã có sẵn)

| Method | URL | Mô tả |
|--------|-----|-------|
| `GET` | `/api/bookings/admin/outbox/dead?page=0&size=20` | Danh sách DEAD events (phân trang) |
| `GET` | `/api/bookings/admin/outbox/dead/count` | `{coinRefund: N, notification: M, total: N+M}` |
| `POST` | `/api/bookings/admin/outbox/retry/{id}` | Retry 1 event |
| `POST` | `/api/bookings/admin/outbox/retry-all?routingKey=...` | Retry tất cả (tùy chọn lọc) |

**Response shape của 1 OutboxEvent**:
```json
{
  "id": 42,
  "idempotencyKey": "BOOK-001_coin_refund_1715693000000",
  "exchange": "tourism.events",
  "routingKey": "booking.coin.refund",
  "payload": "{\"bookingCode\":\"BOOK-001\",\"userId\":5,\"coinAmount\":200}",
  "status": "DEAD",
  "retries": 20,
  "maxRetries": 20,
  "maxBackoffSecs": 3600,
  "nextRetryAt": "2026-05-15T10:00:00",
  "createdAt": "2026-05-14T08:00:00",
  "sentAt": null,
  "errorMessage": "Connection refused: iam-service:8081"
}
```

---

## 2. Cấu Trúc File Mới

```
tourism_frontend/client-side/src/
├── components/AdminComponent/
│   ├── AdminComponent.jsx            ← SỬA: thêm route /dead-events
│   ├── AdminLayout/
│   │   └── AdminSidebar/
│   │       └── AdminSidebar.jsx       ← SỬA: thêm menu item
│   └── Pages/
│       └── DeadEventsPage/           ← TẠO MỚI
│           ├── DeadEventsPage.jsx
│           ├── DeadEventsPage.module.scss
│           ├── DeadEventItem.jsx
│           ├── DeadEventItem.module.scss
│           └── DeadEventDetailModal/
│               ├── DeadEventDetailModal.jsx
│               └── DeadEventDetailModal.module.scss
│
├── services/booking/
│   └── booking.ts                    ← SỬA: thêm 4 API functions
│
└── hook/
    └── useDeadEvents.ts              ← TẠO MỚI
```

---

## 3. Chi Tiết Thiết Kế UI

### 3.1 Layout Tổng Thể — `DeadEventsPage.jsx`

```
┌─────────────────────────────────────────────────────────────────┐
│ 💀 Quản lý DEAD Events                                          │
│ ─────────────────────────────────────────────────────────────── │
│  [THỐNG KÊ NHANH]                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │  TỔNG DEAD   │  │ COIN REFUND  │  │ NOTIFICATION │          │
│  │     🔴 3     │  │     🟡 2     │  │     🔵 1     │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│                                                                 │
│  [FILTER + ACTIONS BAR]                                         │
│  Filter: [Tất cả ▼]  [🔄 Làm mới]  [⚡ Retry tất cả ▼]       │
│                                                                 │
│  [BẢNG DANH SÁCH]                                               │
│  ┌──┬──────────────┬──────────────┬───────┬────────┬────────┐  │
│  │ID│ Booking Code │   Loại       │Retries│Lỗi gần │ Hành  │  │
│  │  │              │              │       │ nhất   │ động  │  │
│  ├──┼──────────────┼──────────────┼───────┼────────┼────────┤  │
│  │42│ BOOK-001     │ 🟡 Coin Refund│ 20/20 │ Conn.. │[Retry]│  │
│  │41│ BOOK-002     │ 🔵 Thông báo │ 5/5   │ TimeO..│[Retry]│  │
│  └──┴──────────────┴──────────────┴───────┴────────┴────────┘  │
│                                                                 │
│  [PHÂN TRANG]                                                   │
│  ← 1 2 3 ... →   Hiển thị 1-20 / 42 kết quả                   │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Row Chi Tiết (khi hover/click) — `DeadEventDetailModal.jsx`

```
┌─────────────────────────────────────────────────────────────┐
│ Chi tiết DEAD Event #42                              [×]    │
├─────────────────────────────────────────────────────────────┤
│ Booking Code: BOOK-001         Loại: 🟡 Coin Refund        │
│ Created:  2026-05-14 08:00     Retries: 20/20              │
│ Next Retry: Không còn (DEAD)                               │
├─────────────────────────────────────────────────────────────┤
│ Thông báo lỗi cuối:                                        │
│ ┌───────────────────────────────────────────────────────┐  │
│ │ Connection refused: iam-service:8081                  │  │
│ └───────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│ Payload (JSON):                                            │
│ ┌───────────────────────────────────────────────────────┐  │
│ │ {                                                     │  │
│ │   "bookingCode": "BOOK-001",                         │  │
│ │   "userId": 5,                                       │  │
│ │   "coinAmount": 200                                  │  │
│ │ }                                                     │  │
│ └───────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│              [Hủy]      [⚡ Retry Event này]               │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. Implementation Plan — Chi tiết từng file

### File 1: `services/booking/booking.ts` — Thêm 4 functions

```typescript
// Thêm 4 functions mới sau các exports hiện có

/** Lấy danh sách DEAD outbox events (phân trang) */
export const getDeadEventsApi = async (page = 0, size = 20): Promise<any> => {
    const response = await api.get('/bookings/admin/outbox/dead', {
        params: { page, size }
    });
    return response.data;  // Page<OutboxEvent>
};

/** Đếm DEAD events theo loại */
export const getDeadEventCountApi = async (): Promise<{
    coinRefund: number;
    notification: number;
    total: number;
}> => {
    const response = await api.get('/bookings/admin/outbox/dead/count');
    return response.data;
};

/** Retry 1 event theo ID */
export const retryDeadEventApi = async (id: number): Promise<void> => {
    await api.post(`/bookings/admin/outbox/retry/${id}`);
};

/** Retry tất cả DEAD events, tùy chọn lọc theo routingKey */
export const retryAllDeadEventsApi = async (
    routingKey?: string
): Promise<{ retried: number }> => {
    const params = routingKey ? { routingKey } : {};
    const response = await api.post('/bookings/admin/outbox/retry-all', null, { params });
    return response.data;  // { retried: N }
};
```

---

### File 2: `hook/useDeadEvents.ts` — Custom Hook

```typescript
// src/hook/useDeadEvents.ts
import { useState, useEffect, useCallback } from 'react';
import {
    getDeadEventsApi,
    getDeadEventCountApi,
    retryDeadEventApi,
    retryAllDeadEventsApi
} from '../services/booking/booking.ts';

interface DeadEventCount {
    coinRefund: number;
    notification: number;
    total: number;
}

interface UseDeadEventsReturn {
    events: any[];
    count: DeadEventCount;
    loading: boolean;
    error: string | null;
    totalPages: number;
    currentPage: number;
    // Actions
    refetch: () => void;
    retryOne: (id: number) => Promise<void>;
    retryAll: (routingKey?: string) => Promise<number>;
    setPage: (page: number) => void;
}

const useDeadEvents = (pageSize = 20): UseDeadEventsReturn => {
    const [events, setEvents]     = useState<any[]>([]);
    const [count, setCount]       = useState<DeadEventCount>({ coinRefund: 0, notification: 0, total: 0 });
    const [loading, setLoading]   = useState(true);
    const [error, setError]       = useState<string | null>(null);
    const [totalPages, setTotalPages] = useState(0);
    const [currentPage, setCurrentPage] = useState(0);
    const [trigger, setTrigger]   = useState(0);

    const refetch = useCallback(() => setTrigger(t => t + 1), []);
    const setPage = useCallback((page: number) => setCurrentPage(page), []);

    useEffect(() => {
        let cancelled = false;
        const load = async () => {
            setLoading(true);
            setError(null);
            try {
                const [pageData, countData] = await Promise.all([
                    getDeadEventsApi(currentPage, pageSize),
                    getDeadEventCountApi()
                ]);
                if (!cancelled) {
                    setEvents(pageData.content ?? []);
                    setTotalPages(pageData.totalPages ?? 0);
                    setCount(countData);
                }
            } catch (e: any) {
                if (!cancelled) setError(e.message ?? 'Lỗi khi tải dữ liệu');
            } finally {
                if (!cancelled) setLoading(false);
            }
        };
        load();
        return () => { cancelled = true; };
    }, [currentPage, pageSize, trigger]);

    const retryOne = useCallback(async (id: number) => {
        await retryDeadEventApi(id);
        refetch();
    }, [refetch]);

    const retryAll = useCallback(async (routingKey?: string) => {
        const result = await retryAllDeadEventsApi(routingKey);
        refetch();
        return result.retried;
    }, [refetch]);

    return { events, count, loading, error, totalPages, currentPage, refetch, retryOne, retryAll, setPage };
};

export default useDeadEvents;
```

---

### File 3: `DeadEventsPage.jsx` — Component Chính

```jsx
// src/components/AdminComponent/Pages/DeadEventsPage/DeadEventsPage.jsx
import React, { useState, useRef, useEffect } from 'react';
import styles from './DeadEventsPage.module.scss';
import { FaSkullCrossbones, FaRedo, FaChevronDown, FaSyncAlt } from 'react-icons/fa';
import useDeadEvents from '../../../../hook/useDeadEvents.ts';
import DeadEventItem from './DeadEventItem';
import DeadEventDetailModal from './DeadEventDetailModal/DeadEventDetailModal';

const ROUTING_KEY_OPTIONS = [
    { key: undefined,                     label: 'Tất cả loại' },
    { key: 'booking.coin.refund',          label: '🟡 Coin Refund' },
    { key: 'booking.notification.event',   label: '🔵 Thông báo' },
];

const DeadEventsPage = () => {
    const { events, count, loading, error, totalPages, currentPage,
            refetch, retryOne, retryAll, setPage } = useDeadEvents(20);

    const [selectedEvent, setSelectedEvent] = useState(null);   // cho modal
    const [retryAllKey, setRetryAllKey]     = useState(undefined);
    const [retryAllOpen, setRetryAllOpen]   = useState(false);
    const [retryAllMsg, setRetryAllMsg]     = useState('');
    const retryAllRef = useRef(null);

    // Đóng dropdown khi click ngoài
    useEffect(() => {
        const handler = (e) => {
            if (retryAllRef.current && !retryAllRef.current.contains(e.target))
                setRetryAllOpen(false);
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, []);

    const handleRetryAll = async (routingKey) => {
        setRetryAllOpen(false);
        try {
            const n = await retryAll(routingKey);
            setRetryAllMsg(`✅ Đã reset ${n} event(s) về NEW.`);
            setTimeout(() => setRetryAllMsg(''), 4000);
        } catch {
            setRetryAllMsg('❌ Retry thất bại, thử lại sau.');
            setTimeout(() => setRetryAllMsg(''), 4000);
        }
    };

    return (
        <div className={styles.pageContainer}>
            {/* HEADER */}
            <h1 className={styles.pageTitle}>
                <FaSkullCrossbones className={styles.icon} />
                Quản lý DEAD Events
            </h1>

            {/* STAT CARDS */}
            <div className={styles.statsRow}>
                <div className={`${styles.statCard} ${styles.statTotal}`}>
                    <span className={styles.statValue}>{count.total}</span>
                    <span className={styles.statLabel}>Tổng DEAD</span>
                </div>
                <div className={`${styles.statCard} ${styles.statCoin}`}>
                    <span className={styles.statValue}>{count.coinRefund}</span>
                    <span className={styles.statLabel}>🟡 Coin Refund</span>
                </div>
                <div className={`${styles.statCard} ${styles.statNotif}`}>
                    <span className={styles.statValue}>{count.notification}</span>
                    <span className={styles.statLabel}>🔵 Thông báo</span>
                </div>
            </div>

            {/* ACTION BAR */}
            <div className={styles.actionBar}>
                <button className={styles.btnRefresh} onClick={refetch} disabled={loading}>
                    <FaSyncAlt className={loading ? styles.spinning : ''} />
                    Làm mới
                </button>

                {/* Retry All Dropdown */}
                <div className={styles.retryAllWrapper} ref={retryAllRef}>
                    <button
                        className={styles.btnRetryAll}
                        onClick={() => setRetryAllOpen(o => !o)}
                    >
                        <FaRedo /> Retry tất cả <FaChevronDown />
                    </button>
                    {retryAllOpen && (
                        <div className={styles.retryDropdown}>
                            {ROUTING_KEY_OPTIONS.map(opt => (
                                <button
                                    key={String(opt.key)}
                                    className={styles.retryDropdownItem}
                                    onClick={() => handleRetryAll(opt.key)}
                                >
                                    {opt.label}
                                </button>
                            ))}
                        </div>
                    )}
                </div>

                {retryAllMsg && (
                    <span className={styles.retryMsg}>{retryAllMsg}</span>
                )}
            </div>

            {/* ERROR / EMPTY */}
            {error && <div className={styles.errorMsg}>⚠️ {error}</div>}

            {/* TABLE HEADER */}
            {!error && (
                <div className={styles.tableWrapper}>
                    <table className={styles.table}>
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>Booking Code</th>
                                <th>Loại</th>
                                <th>Retries</th>
                                <th>Tạo lúc</th>
                                <th>Lỗi cuối</th>
                                <th>Hành động</th>
                            </tr>
                        </thead>
                        <tbody>
                            {loading
                                ? Array.from({ length: 5 }).map((_, i) => (
                                    <tr key={i} className={styles.skeletonRow}>
                                        {Array.from({ length: 7 }).map((_, j) => (
                                            <td key={j}><div className={styles.skeleton} /></td>
                                        ))}
                                    </tr>
                                  ))
                                : events.length === 0
                                    ? (
                                        <tr>
                                            <td colSpan={7} className={styles.emptyMsg}>
                                                🎉 Không có DEAD events nào!
                                            </td>
                                        </tr>
                                      )
                                    : events.map(event => (
                                        <DeadEventItem
                                            key={event.id}
                                            event={event}
                                            onViewDetail={() => setSelectedEvent(event)}
                                            onRetry={async () => {
                                                await retryOne(event.id);
                                            }}
                                        />
                                      ))
                            }
                        </tbody>
                    </table>
                </div>
            )}

            {/* PHÂN TRANG */}
            {totalPages > 1 && (
                <div className={styles.pagination}>
                    <button
                        disabled={currentPage === 0}
                        onClick={() => setPage(currentPage - 1)}
                    >←</button>
                    {Array.from({ length: totalPages }, (_, i) => (
                        <button
                            key={i}
                            className={currentPage === i ? styles.activePage : ''}
                            onClick={() => setPage(i)}
                        >{i + 1}</button>
                    ))}
                    <button
                        disabled={currentPage === totalPages - 1}
                        onClick={() => setPage(currentPage + 1)}
                    >→</button>
                </div>
            )}

            {/* MODAL CHI TIẾT */}
            {selectedEvent && (
                <DeadEventDetailModal
                    event={selectedEvent}
                    onClose={() => setSelectedEvent(null)}
                    onRetry={async () => {
                        await retryOne(selectedEvent.id);
                        setSelectedEvent(null);
                    }}
                />
            )}
        </div>
    );
};

export default DeadEventsPage;
```

---

### File 4: `DeadEventItem.jsx` — Hàng trong bảng

```jsx
// src/components/AdminComponent/Pages/DeadEventsPage/DeadEventItem.jsx
import React, { useState } from 'react';
import { FaRedo, FaEye } from 'react-icons/fa';
import styles from './DeadEventItem.module.scss';

const ROUTING_LABELS = {
    'booking.coin.refund':         { text: 'Coin Refund', cls: 'coinTag' },
    'booking.notification.event':  { text: 'Thông báo',  cls: 'notifTag' },
};

const formatDate = (str) =>
    str ? new Date(str).toLocaleString('vi-VN') : '—';

const truncate = (str, n = 60) =>
    str && str.length > n ? str.slice(0, n) + '…' : str || '—';

const DeadEventItem = ({ event, onViewDetail, onRetry }) => {
    const [retrying, setRetrying] = useState(false);
    const [done, setDone]         = useState(false);
    const tag = ROUTING_LABELS[event.routingKey] ?? { text: event.routingKey, cls: 'unknownTag' };

    const handleRetry = async () => {
        setRetrying(true);
        try {
            await onRetry();
            setDone(true);
        } finally {
            setRetrying(false);
        }
    };

    // Parse bookingCode from idempotencyKey or payload
    const bookingCode = (() => {
        try {
            const p = JSON.parse(event.payload);
            return p.bookingCode ?? event.idempotencyKey?.split('_')[0] ?? '—';
        } catch { return event.idempotencyKey?.split('_')[0] ?? '—'; }
    })();

    return (
        <tr className={`${styles.row} ${done ? styles.rowDone : ''}`}>
            <td className={styles.idCell}>{event.id}</td>
            <td className={styles.bookingCell}>{bookingCode}</td>
            <td>
                <span className={`${styles.tag} ${styles[tag.cls]}`}>
                    {tag.text}
                </span>
            </td>
            <td className={styles.retriesCell}>
                <span className={styles.retriesBadge}>
                    {event.retries}/{event.maxRetries}
                </span>
            </td>
            <td className={styles.dateCell}>{formatDate(event.createdAt)}</td>
            <td className={styles.errorCell} title={event.errorMessage}>
                {truncate(event.errorMessage)}
            </td>
            <td className={styles.actionsCell}>
                <button
                    className={styles.btnView}
                    onClick={onViewDetail}
                    title="Xem chi tiết"
                >
                    <FaEye />
                </button>
                <button
                    className={styles.btnRetry}
                    onClick={handleRetry}
                    disabled={retrying || done}
                    title="Retry event này"
                >
                    {retrying ? '...' : done ? '✓' : <FaRedo />}
                </button>
            </td>
        </tr>
    );
};

export default DeadEventItem;
```

---

### File 5: `DeadEventDetailModal.jsx` — Modal Chi Tiết

```jsx
// src/components/AdminComponent/Pages/DeadEventsPage/DeadEventDetailModal/DeadEventDetailModal.jsx
import React, { useState } from 'react';
import { FaTimes, FaRedo } from 'react-icons/fa';
import styles from './DeadEventDetailModal.module.scss';

const formatDate = (str) =>
    str ? new Date(str).toLocaleString('vi-VN') : '—';

const prettyJson = (str) => {
    try { return JSON.stringify(JSON.parse(str), null, 2); }
    catch { return str; }
};

const DeadEventDetailModal = ({ event, onClose, onRetry }) => {
    const [retrying, setRetrying] = useState(false);
    const [done, setDone]         = useState(false);

    const isCoin = event.routingKey === 'booking.coin.refund';

    const handleRetry = async () => {
        setRetrying(true);
        try {
            await onRetry();
            setDone(true);
        } finally {
            setRetrying(false);
        }
    };

    return (
        <div className={styles.overlay} onClick={onClose}>
            <div className={styles.modal} onClick={e => e.stopPropagation()}>
                {/* HEADER */}
                <div className={styles.header}>
                    <h3>Chi tiết DEAD Event #{event.id}</h3>
                    <button className={styles.closeBtn} onClick={onClose}>
                        <FaTimes />
                    </button>
                </div>

                {/* METADATA */}
                <div className={styles.metaGrid}>
                    <div className={styles.metaItem}>
                        <span className={styles.metaLabel}>Loại</span>
                        <span className={`${styles.tag} ${isCoin ? styles.coinTag : styles.notifTag}`}>
                            {isCoin ? '🟡 Coin Refund' : '🔵 Thông báo'}
                        </span>
                    </div>
                    <div className={styles.metaItem}>
                        <span className={styles.metaLabel}>Idempotency Key</span>
                        <span className={styles.metaVal}>{event.idempotencyKey}</span>
                    </div>
                    <div className={styles.metaItem}>
                        <span className={styles.metaLabel}>Số lần retry</span>
                        <span className={styles.retriesBadge}>
                            {event.retries}/{event.maxRetries}
                        </span>
                    </div>
                    <div className={styles.metaItem}>
                        <span className={styles.metaLabel}>Tạo lúc</span>
                        <span className={styles.metaVal}>{formatDate(event.createdAt)}</span>
                    </div>
                    <div className={styles.metaItem}>
                        <span className={styles.metaLabel}>Next retry</span>
                        <span className={styles.metaVal}>{formatDate(event.nextRetryAt)}</span>
                    </div>
                    <div className={styles.metaItem}>
                        <span className={styles.metaLabel}>Max backoff</span>
                        <span className={styles.metaVal}>{event.maxBackoffSecs}s ({Math.round(event.maxBackoffSecs / 60)} phút)</span>
                    </div>
                </div>

                {/* LỖI */}
                <div className={styles.section}>
                    <h4 className={styles.sectionTitle}>Thông báo lỗi cuối</h4>
                    <pre className={styles.errorBox}>{event.errorMessage || '(không có)'}</pre>
                </div>

                {/* PAYLOAD */}
                <div className={styles.section}>
                    <h4 className={styles.sectionTitle}>Payload</h4>
                    <pre className={styles.payloadBox}>{prettyJson(event.payload)}</pre>
                </div>

                {/* FOOTER */}
                <div className={styles.footer}>
                    <button className={styles.btnCancel} onClick={onClose}>
                        Đóng
                    </button>
                    <button
                        className={styles.btnRetry}
                        onClick={handleRetry}
                        disabled={retrying || done}
                    >
                        <FaRedo />
                        {retrying ? ' Đang retry...' : done ? ' Đã reset ✓' : ' Retry Event này'}
                    </button>
                </div>
            </div>
        </div>
    );
};

export default DeadEventDetailModal;
```

---

### File 6: `DeadEventsPage.module.scss` — Styles Trang Chính

```scss
// src/components/AdminComponent/Pages/DeadEventsPage/DeadEventsPage.module.scss
$dead-red:     #e53e3e;
$coin-yellow:  #d69e2e;
$notif-blue:   #3182ce;
$border:       #e2e8f0;
$light-bg:     #f7fafc;
$white:        #ffffff;
$text:         #2d3748;
$shadow-sm:    0 1px 3px rgba(0,0,0,.08);
$shadow-md:    0 4px 12px rgba(0,0,0,.08);

.pageContainer {
    padding: 24px;
    background: $white;
    border-radius: 8px;
    box-shadow: $shadow-md;
    min-height: 600px;
    display: flex;
    flex-direction: column;
    gap: 20px;
}

.pageTitle {
    font-size: 1.8rem;
    font-weight: 700;
    color: $text;
    display: flex;
    align-items: center;
    border-bottom: 2px solid $border;
    padding-bottom: 14px;
    margin: 0;
}

.icon { margin-right: 10px; color: $dead-red; }

/* --- STAT CARDS --- */
.statsRow {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 16px;
}

.statCard {
    border-radius: 10px;
    padding: 18px 24px;
    display: flex;
    flex-direction: column;
    align-items: center;
    box-shadow: $shadow-sm;
    color: $white;
}
.statTotal  { background: linear-gradient(135deg, #e53e3e, #c53030); }
.statCoin   { background: linear-gradient(135deg, #d69e2e, #b7791f); }
.statNotif  { background: linear-gradient(135deg, #3182ce, #2b6cb0); }

.statValue  { font-size: 2.4rem; font-weight: 800; line-height: 1; }
.statLabel  { font-size: .85rem; opacity: .9; margin-top: 4px; }

/* --- ACTION BAR --- */
.actionBar {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
}

.btnRefresh {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 16px;
    background: $light-bg;
    border: 1px solid $border;
    border-radius: 6px;
    cursor: pointer;
    font-size: .9rem;
    &:hover { background: darken($light-bg, 4%); }
}

.spinning { animation: spin .7s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.retryAllWrapper { position: relative; }

.btnRetryAll {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 16px;
    background: #e53e3e;
    color: $white;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    font-size: .9rem;
    &:hover { background: #c53030; }
}

.retryDropdown {
    position: absolute;
    top: calc(100% + 4px);
    left: 0;
    background: $white;
    border: 1px solid $border;
    border-radius: 6px;
    box-shadow: $shadow-md;
    min-width: 180px;
    z-index: 100;
}

.retryDropdownItem {
    display: block;
    width: 100%;
    padding: 10px 16px;
    background: none;
    border: none;
    text-align: left;
    cursor: pointer;
    font-size: .9rem;
    &:hover { background: $light-bg; }
}

.retryMsg { font-size: .9rem; color: $text; }

/* --- TABLE --- */
.tableWrapper { overflow-x: auto; flex: 1; }

.table {
    width: 100%;
    border-collapse: collapse;
    font-size: .9rem;

    th {
        background: $light-bg;
        padding: 12px 14px;
        text-align: left;
        font-weight: 600;
        color: $text;
        border-bottom: 2px solid $border;
        white-space: nowrap;
    }
    td {
        padding: 12px 14px;
        border-bottom: 1px solid $border;
        vertical-align: middle;
        color: $text;
    }
}

.skeletonRow td { padding: 14px; }
.skeleton {
    height: 16px;
    background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
    background-size: 200% 100%;
    border-radius: 4px;
    animation: shimmer 1.2s infinite;
}
@keyframes shimmer { to { background-position: -200% 0; } }

.emptyMsg { text-align: center; color: #718096; padding: 32px !important; }
.errorMsg  { color: $dead-red; background: #fff5f5; border: 1px solid #feb2b2;
             padding: 12px 16px; border-radius: 6px; }

/* --- PAGINATION --- */
.pagination {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    flex-shrink: 0;

    button {
        padding: 6px 12px;
        border: 1px solid $border;
        border-radius: 4px;
        background: $white;
        cursor: pointer;
        font-size: .85rem;
        &:hover:not(:disabled) { background: $light-bg; }
        &:disabled { opacity: .4; cursor: not-allowed; }
    }

    .activePage {
        background: #3182ce;
        color: $white;
        border-color: #3182ce;
    }
}
```

---

### File 7: `DeadEventItem.module.scss`

```scss
// src/components/AdminComponent/Pages/DeadEventsPage/DeadEventItem.module.scss
.row {
    transition: background .15s;
    &:hover { background: #f7fafc; }
}
.rowDone { opacity: .5; }

.idCell     { color: #718096; font-size: .82rem; }
.bookingCell { font-weight: 600; }
.retriesCell { text-align: center; }
.dateCell   { white-space: nowrap; font-size: .83rem; color: #4a5568; }
.errorCell  { font-size: .82rem; color: #c53030; max-width: 240px;
              overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.actionsCell {
    display: flex;
    gap: 8px;
    align-items: center;
}

.btnView, .btnRetry {
    padding: 6px 10px;
    border-radius: 5px;
    border: none;
    cursor: pointer;
    font-size: .85rem;
    display: flex;
    align-items: center;
    gap: 4px;
    transition: background .15s;
}
.btnView   { background: #ebf8ff; color: #2b6cb0; &:hover { background: #bee3f8; } }
.btnRetry  { background: #fff5f5; color: #c53030; &:hover { background: #fed7d7; }
             &:disabled { opacity: .4; cursor: not-allowed; } }

.tag {
    padding: 3px 10px;
    border-radius: 12px;
    font-size: .8rem;
    font-weight: 600;
}
.coinTag   { background: #fef3c7; color: #92400e; }
.notifTag  { background: #ebf8ff; color: #1e4e8c; }
.unknownTag { background: #f0f0f0; color: #555; }

.retriesBadge {
    background: #fff5f5;
    color: #c53030;
    padding: 3px 10px;
    border-radius: 12px;
    font-size: .82rem;
    font-weight: 700;
}
```

---

### File 8: `DeadEventDetailModal.module.scss`

```scss
// .../DeadEventDetailModal/DeadEventDetailModal.module.scss
.overlay {
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
}

.modal {
    background: #fff;
    border-radius: 12px;
    width: 600px;
    max-width: 96vw;
    max-height: 90vh;
    overflow-y: auto;
    box-shadow: 0 20px 60px rgba(0,0,0,.2);
    display: flex;
    flex-direction: column;
}

.header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 18px 24px;
    border-bottom: 1px solid #e2e8f0;
    h3 { margin: 0; font-size: 1.1rem; font-weight: 700; }
}

.closeBtn {
    background: none;
    border: none;
    cursor: pointer;
    font-size: 1.1rem;
    color: #718096;
    &:hover { color: #2d3748; }
}

.metaGrid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px 24px;
    padding: 18px 24px;
    border-bottom: 1px solid #e2e8f0;
}

.metaItem { display: flex; flex-direction: column; gap: 4px; }
.metaLabel { font-size: .78rem; text-transform: uppercase; color: #718096; font-weight: 600; }
.metaVal   { font-size: .9rem; color: #2d3748; }

.section { padding: 14px 24px; border-bottom: 1px solid #e2e8f0; }
.sectionTitle { font-size: .85rem; text-transform: uppercase; color: #718096;
                font-weight: 600; margin: 0 0 8px; }

.errorBox {
    background: #fff5f5;
    border: 1px solid #feb2b2;
    border-radius: 6px;
    padding: 10px 14px;
    font-size: .85rem;
    color: #c53030;
    white-space: pre-wrap;
    word-break: break-all;
    margin: 0;
    max-height: 100px;
    overflow-y: auto;
}

.payloadBox {
    background: #f7fafc;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 10px 14px;
    font-size: .82rem;
    color: #2d3748;
    font-family: 'Courier New', monospace;
    white-space: pre;
    overflow-x: auto;
    margin: 0;
    max-height: 180px;
}

.tag {
    padding: 3px 10px;
    border-radius: 12px;
    font-size: .8rem;
    font-weight: 600;
    display: inline-block;
}
.coinTag   { background: #fef3c7; color: #92400e; }
.notifTag  { background: #ebf8ff; color: #1e4e8c; }

.retriesBadge {
    background: #fff5f5;
    color: #c53030;
    padding: 3px 10px;
    border-radius: 12px;
    font-size: .82rem;
    font-weight: 700;
    display: inline-block;
}

.footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    padding: 16px 24px;
}

.btnCancel {
    padding: 9px 20px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    background: #fff;
    cursor: pointer;
    &:hover { background: #f7fafc; }
}

.btnRetry {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 9px 20px;
    background: #e53e3e;
    color: #fff;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    font-weight: 600;
    &:hover:not(:disabled) { background: #c53030; }
    &:disabled { opacity: .5; cursor: not-allowed; }
}
```

---

## 5. Sửa File Hiện Có

### Sửa `AdminSidebar.jsx` — Thêm menu item

```jsx
// Thêm vào mảng navItems (sau 'Quản lý Bookings'):
import { FaSkullCrossbones } from 'react-icons/fa';

// Trong navItems:
{ name: 'DEAD Events', path: '/admin/dead-events', icon: FaSkullCrossbones },
```

### Sửa `AdminComponent.jsx` — Thêm route

```jsx
import DeadEventsPage from './Pages/DeadEventsPage/DeadEventsPage';

// Trong <Route path="/" element={<AdminLayout />}>:
<Route path="dead-events" element={<DeadEventsPage />} />
```

---

## 6. Luồng Dữ Liệu (Data Flow)

```
Admin mở /admin/dead-events
        │
        ▼
useDeadEvents() khởi tạo
        │
        ├─► getDeadEventsApi(page=0, size=20)   → GET /api/bookings/admin/outbox/dead
        └─► getDeadEventCountApi()              → GET /api/bookings/admin/outbox/dead/count
                │
                ▼
        Render bảng + stat cards
                │
        [Admin click "Retry" trên 1 row]
                │
                ▼
        retryDeadEventApi(id)               → POST /api/bookings/admin/outbox/retry/{id}
                │
                ▼
        refetch() → reload danh sách + count
                │
        [Admin click "Retry tất cả > Coin Refund"]
                │
                ▼
        retryAllDeadEventsApi('booking.coin.refund')
                            → POST /api/bookings/admin/outbox/retry-all?routingKey=...
                │
                ▼
        Toast: "Đã reset N events về NEW"
        refetch()
```

---

## 7. Danh Sách File Cần Tạo/Sửa

| File | Thao tác | Ghi chú |
|------|---------|---------|
| `services/booking/booking.ts` | **Sửa** | Thêm 4 API functions |
| `hook/useDeadEvents.ts` | **Tạo mới** | Custom hook |
| `Pages/DeadEventsPage/DeadEventsPage.jsx` | **Tạo mới** | Component chính |
| `Pages/DeadEventsPage/DeadEventsPage.module.scss` | **Tạo mới** | Styles |
| `Pages/DeadEventsPage/DeadEventItem.jsx` | **Tạo mới** | Row trong bảng |
| `Pages/DeadEventsPage/DeadEventItem.module.scss` | **Tạo mới** | Styles |
| `Pages/DeadEventsPage/DeadEventDetailModal/DeadEventDetailModal.jsx` | **Tạo mới** | Modal chi tiết |
| `Pages/DeadEventsPage/DeadEventDetailModal/DeadEventDetailModal.module.scss` | **Tạo mới** | Styles |
| `AdminLayout/AdminSidebar/AdminSidebar.jsx` | **Sửa** | Thêm 1 menu item |
| `AdminComponent.jsx` | **Sửa** | Thêm 1 route |

**Tổng**: 8 file mới + 2 file sửa = **10 file**

---

## 8. Thứ Tự Implementation

1. Sửa `booking.ts` → thêm 4 API functions (không phá hiện tại)
2. Tạo `useDeadEvents.ts`
3. Tạo thư mục `DeadEventsPage/` và các component + styles
4. Sửa `AdminSidebar.jsx` (thêm 1 dòng vào `navItems`)
5. Sửa `AdminComponent.jsx` (thêm 1 `<Route>`)
6. Test: `npm start` → vào `/admin/dead-events`
