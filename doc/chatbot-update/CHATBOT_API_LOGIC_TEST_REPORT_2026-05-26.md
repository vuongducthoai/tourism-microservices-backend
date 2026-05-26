# CHATBOT API & BUSINESS LOGIC TEST REPORT

**Date:** 2026-05-26  
**Scope:** API + business logic verification only. No code changes were made during this test pass.  
**Endpoint tested:** `POST http://localhost:8080/api/chatbot/chat` via API Gateway  
**Health endpoints:** `http://localhost:8080/api/chatbot/health`, `http://localhost:8087/api/chatbot/health`

---

## 1. Environment Check

| Check | Result | Notes |
|---|---:|---|
| Gateway chatbot health `8080` | PASS | Returned `status=UP`, `service=chatbot` |
| Analytics chatbot health `8087` | PASS | Returned `status=UP`, `service=chatbot` |
| Frontend `3000` | PASS | Returned React HTML |
| Frontend `5173` | NOT RUNNING | Connection failed; not required because app is running on `3000` |
| `analytics-service` compile | PASS | `mvn -pl analytics-service -DskipTests compile` succeeded after running outside sandbox dependency restrictions |

Note: PowerShell console displayed Vietnamese mojibake, but API behavior was still readable through `messageType`, `conversationStage`, suggestions, and reply content.

---

## 2. API Test Summary

### 2.1 Passed / Improved

| Case | Input | Result |
|---|---|---|
| Health check | `GET /api/chatbot/health` | PASS |
| No-result destination, known missing data | `tôi muốn đi đà lạt` | PASS/PARTIAL: no longer returned random Đà Nẵng/Sa Pa/Vũng Tàu tours; response correctly said no matching Đà Lạt tour |
| Exact BK lookup at active session | `BK3f7a9c12` | PASS: returned "không tìm thấy đơn hàng BK3F7A9C12", stage `IDLE`, no date-stage trap |
| Stateful search with canonical spacing | `tôi muốn đi sa pa` | PASS: returned `TOUR_SUGGESTIONS`, stage `SHOWING_SEARCH_RESULTS`, 1 Sa Pa tour |
| Deterministic slot after stateful search | `còn mấy slot` after `tôi muốn đi sa pa` | PASS: returned actual available slots from state for both Sa Pa departures; did not call generic RAG-style tour list |
| Selecting tour | `1` after Sa Pa results | PASS: moved to `SELECTING_DEPARTURE`, returned departure dates and slots |
| Cancel active flow | `hủy` | PASS: stage returned to `IDLE`, response confirmed cancellation |

### 2.2 Failed / Still Not Pro Enough

| Case | Input | Actual | Expected |
|---|---|---|---|
| Alias mismatch | `tôi muốn đi sapa` | No matching tour found | Should normalize `sapa` to `sa pa` and find Sa Pa |
| Natural detail search not stateful | `xem chi tiết tour sapa đi` | RAG response, stage `IDLE`, suggestions included unrelated tours | Should identify Sa Pa, return detail or stateful result, and preserve context |
| Follow-up after non-stateful search | `còn mấy slot` after `xem chi tiết tour sapa đi` | RAG listed Đà Nẵng + Sa Pa mixed results | Should answer slots for the previously shown Sa Pa tour or ask clarification |
| Price follow-up drift | `giá tour 1 bao nhiêu` after Sa Pa RAG | Answered Vũng Tàu instead of Sa Pa | Should resolve `tour 1` from current visible results |
| Date/detail follow-up drift | `ngày khởi hành tour đó khi nào`, `xem chi tiết tour đó` | Drifted to Vũng Tàu / Cần Thơ | Should resolve `tour đó` from state, not RAG memory |
| Natural Hạ Long search not stateful | `tour nào đi hạ long ko` | Answered Hạ Long but stage stayed `IDLE`; follow-ups drifted | Should enter stateful result mode or save visible results for reference |
| Start location filter | `có tour khởi hành hcm ko` | Included `Đà Nẵng - TP.HCM`, likely destination HCM not start HCM | Should strictly filter `startLocationName` = HCM/Sài Gòn/TP.HCM |
| Impossible destination | `có tour đi alaska không` | Said no Alaska but still listed domestic tours and returned suggestions | Should not attach unrelated tour suggestions unless explicitly framed as alternatives |
| Off-topic booking help while selecting date | `tôi muốn xem 1 booking thì sao` during `SELECTING_DEPARTURE` | Stage preserved and booking guidance appeared, but response also included coupons/tour suggestions | Should answer concise booking help and ask for BK code or continue/cancel; no promotional tour list |

---

## 3. Business Logic Findings

### Finding 1 — Stateful flow only works for some search phrasings

`tôi muốn đi sa pa` enters `SHOWING_SEARCH_RESULTS`, but `xem chi tiết tour sapa đi` and `tour nào đi hạ long ko` stay in `IDLE` and rely on RAG. This means the bot can answer the first message but cannot reliably remember what it just showed.

Impact: follow-up questions like `còn mấy slot`, `giá tour 1`, `tour đó khi nào` drift to unrelated tours.

Required fix direction:
- Route natural search/detail phrases through `IntentRouter` into stateful search/detail handling.
- Normalize aliases: `sapa` == `sa pa`, `ha long` == `hạ long`, etc.
- Save visible RAG tour results into `lastSearchResults` if the response includes tour suggestions, or avoid using RAG for tour result cards.

### Finding 2 — Deterministic slot logic is good when state exists

When state exists (`tôi muốn đi sa pa` -> `SHOWING_SEARCH_RESULTS`), `còn mấy slot` returns real slot values from state. This is the right architecture: no Gemini for slot.

Remaining gap:
- The deterministic handler depends on `lastSearchResults`; many natural queries still do not populate it.

### Finding 3 — Search result filtering improved, but not complete

The Đà Lạt test no longer showed unrelated tours, which confirms fallback-tour-rác behavior is at least partly fixed. However:
- `sapa` without space fails.
- HCM start-location query still includes a likely wrong route.
- Alaska still returns unrelated suggestions and long domestic fallback content.

Required fix direction:
- Exact/normalized destination filter must handle aliases.
- Start location must filter `startLocationName`, not semantic text only.
- For unknown destinations, response should be short: "no exact match" + optional suggestion prompt, not automatic list of random tours.

### Finding 4 — Booking lookup by explicit code works

Explicit `BK3f7a9c12` no longer gets trapped as a departure date. It returns a proper not-found booking response and resets stage to `IDLE`.

Remaining gap:
- General question `tôi muốn xem 1 booking thì sao` should not trigger coupon/tour promotion. It should ask for a booking code or explain lookup path.

### Finding 5 — RAG remains too promotional and too eager

Several off-topic or no-result cases attach coupons/tour suggestions. This makes the chatbot feel like it is ignoring the user.

Required fix direction:
- Add prompt/policy: when user asks support/booking/error/no-result, do not append tour promotions unless user asks for alternatives.
- Suppress `tourSuggestions` in API response for support-style answers.

---

## 4. Test Sessions Used

| Session | Purpose |
|---|---|
| `codex_api_test_20260526_134550` | Đà Lạt no-result, slot/price after no state, BK fake lookup, cancel |
| `codex_flow_sapa_134710` | Natural Sa Pa detail query and follow-up drift |
| `codex_start_hcm_134739` | Start-location HCM search |
| `codex_no_result_134744` | Impossible destination Alaska |
| `codex_stateful_134811` | `sapa` alias failure |
| `codex_stateful_sapa_space_134835` | Successful stateful Sa Pa flow, slot, select, off-topic booking |
| `codex_price_date_134919` | Hạ Long natural search and follow-up drift |

---

## 5. Verdict

Current chatbot is **partially improved**, but **not yet stable/pro-level**.

What is stable:
- API is up.
- Backend compiles.
- Explicit booking-code lookup works.
- Stateful Sa Pa flow works if the user phrase matches expected destination format.
- Slot answer is deterministic when state is correctly populated.
- Cancel active flow works.

What is not stable:
- Natural phrasing still often falls into RAG and does not populate state.
- Follow-up memory is unreliable outside strict stateful search flow.
- Start-location filtering is not strict enough.
- No-result handling still exposes unrelated suggestions in some paths.
- Booking-help/off-topic support answers are too verbose and promotional.

Recommended next priority:
1. Make `IntentRouter` catch natural tour/detail/search phrases and force stateful handling.
2. Add alias normalization for destinations (`sapa` -> `sa pa`, etc.).
3. Ensure RAG tour suggestions also update `lastSearchResults`, or stop returning tour cards from RAG for tour-search intents.
4. Add deterministic `ASK_DETAIL/ASK_ITINERARY` for "xem chi tiết tour đó/tour sapa".
5. Tighten start-location filter using `startLocationName`.
6. Suppress promotional suggestions for booking/help/no-result answers.

