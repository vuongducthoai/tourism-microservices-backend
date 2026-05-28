# Chatbot Diagnostic Test Script
# Usage: .\chatbot_diag.ps1
$B = "http://localhost:8080/api/chatbot/chat"
$pass = 0; $fail = 0; $bugs = @()

function Chat([string]$msg, [string]$sess) {
    $body = '{"message":"' + $msg + '","sessionId":"' + $sess + '","userId":null}'
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    return Invoke-RestMethod -Uri $B -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes
}

function ChatJ([hashtable]$ht, [string]$sess) {
    $ht["sessionId"] = $sess; $ht["userId"] = $null
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($ht | ConvertTo-Json))
    return Invoke-RestMethod -Uri $B -Method POST -ContentType "application/json; charset=utf-8" -Body $bytes
}

function Check([string]$label, $r, [string]$expectStage, [string]$mustNotContain, [string]$mustContain) {
    $ok = $true; $reasons = @()
    if ($expectStage -ne "" -and $r.conversationStage -ne $expectStage) {
        $ok = $false; $reasons += "stage=$($r.conversationStage) want=$expectStage"
    }
    if ($mustNotContain -ne "" -and $r.reply -match $mustNotContain) {
        $ok = $false; $reasons += "reply contains FORBIDDEN: $mustNotContain"
    }
    if ($mustContain -ne "" -and $r.reply -notmatch $mustContain) {
        $ok = $false; $reasons += "reply MISSING: $mustContain"
    }
    if ($ok) {
        $script:pass++
        Write-Host "[PASS] $label"
    } else {
        $script:fail++
        $bug = "$label : $($reasons -join '; ')"
        $script:bugs += $bug
        Write-Host "[FAIL] $label"
        foreach ($rr in $reasons) { Write-Host "       -> $rr" }
    }
    $rr = $r.reply; if ($rr.Length -gt 90) { $rr = $rr.Substring(0,90) + "..." }
    Write-Host "       reply: $rr"
    Write-Host ""
}

Write-Host "===== CHATBOT DIAGNOSTIC =====" 
Write-Host ""

# ---- BLOCK 1: GREETING TYPO DETECTION ----
Write-Host "=== BLOCK 1: GREETING TYPOS ==="
# Greeting should give clean welcome msg, NOT tour deals
$forbidTours = "Ti.t ki.m|coupon|m.s? gi.m|SUMMER|WELCOME100"

$r = Chat "helllo" "g1"
Check "B1-T1 helllo (triple-l)" $r "IDLE" $forbidTours ""

$r = Chat "hellooo" "g2"
Check "B1-T2 hellooo (o*3)" $r "IDLE" $forbidTours ""

$r = Chat "helloooo" "g3"
Check "B1-T3 helloooo (o*4)" $r "IDLE" $forbidTours ""

$r = Chat "hiii" "g4"
Check "B1-T4 hiii (i*3)" $r "IDLE" $forbidTours ""

$r = Chat "heyy" "g5"
Check "B1-T5 heyy (y*2)" $r "IDLE" $forbidTours ""

$r = Chat "heyyy" "g6"
Check "B1-T6 heyyy (y*3)" $r "IDLE" $forbidTours ""

$r = Chat "xin chao" "g7"
Check "B1-T7 xin chao (no diacritic)" $r "IDLE" $forbidTours ""

$r = Chat "hello" "g8"
Check "B1-T8 hello (exact, correct)" $r "IDLE" $forbidTours ""

$r = Chat "hi" "g9"
Check "B1-T9 hi (exact, correct)" $r "IDLE" $forbidTours ""

$r = Chat "hey" "g10"
Check "B1-T10 hey (exact, correct)" $r "IDLE" $forbidTours ""


# ---- BLOCK 2: POLICY QUESTIONS IN IDLE (should answer policy, NOT show tour deals) ----
Write-Host "=== BLOCK 2: POLICY QUESTIONS (IDLE) ==="
# Policy questions at IDLE stage: should NOT spam tour deals
# Acceptable: Answer the policy + maybe ask if they want to search

$r = ChatJ @{message="chinh sach huy tour nhu the nao"} "p1"
Check "B2-T1 cancel policy (no diacritic)" $r "IDLE" "Ti.t ki.m|SUMMER|WELCOME100" "h.y"

$r = ChatJ @{message="dieu kien hoan tien"} "p2"
Check "B2-T2 refund policy" $r "IDLE" "Ti.t ki.m|SUMMER|WELCOME100" "ho.n"

$r = ChatJ @{message="tour co bao hiem khong"} "p3"
Check "B2-T3 insurance question" $r "IDLE" "" "b.o hi.m|b.o v."

$r = ChatJ @{message="phuong thuc thanh toan"} "p4"
Check "B2-T4 payment methods" $r "IDLE" "" "thanh to.n|chuy.n kho.n|th. t.n"


# ---- BLOCK 3: CANCEL/RESUME EDGE CASES ----
Write-Host "=== BLOCK 3: CANCEL & RESUME EDGE CASES ==="

# "huy" standalone should cancel
$r = Chat "huy" "c1"
Check "B3-T1 huy standalone" $r "IDLE" "" "h.y|kh.ng c. lu.ng"

# "bo qua" should NOT cancel (was removed from cancel patterns)
$r = Chat "bo qua" "bq1"
Check "B3-T2 bo qua (should NOT cancel, should be RAG)" $r "" "" ""
# Just check it doesn't crash

# "huy tour" should NOT cancel (was removed)
$r = Chat "huy tour" "ht1"
Check "B3-T3 huy tour should NOT cancel flow" $r "" "" ""

# Resume without active session
$r = Chat "tiep tuc dat tour" "re1"
Check "B3-T4 resume with no active session" $r "IDLE" "" "kh.ng c. lu.ng|Kh.ng c."


# ---- BLOCK 4: BOOKING LOOKUP ----
Write-Host "=== BLOCK 4: BOOKING LOOKUP ==="

# Valid BK code (lowercase)
$r = Chat "xem booking bkf3845364" "bl1"
Check "B4-T1 lookup BK lowercase" $r "" "" ""

# Valid BK code uppercase
$r = Chat "xem booking BKF3845364" "bl2"
Check "B4-T2 lookup BK uppercase" $r "" "" ""

# Non-existent BK code
$r = Chat "tra cuu BK9999999" "bl3"
Check "B4-T3 lookup non-existent BK" $r "IDLE" "" "kh.ng t.m|kh.ng c."


# ---- BLOCK 5: SEARCH FLOW ----
Write-Host "=== BLOCK 5: TOUR SEARCH ==="

$r = Chat "tour da nang 3 ngay" "s1"
Check "B5-T1 search Da Nang" $r "" "Ti.t ki.m" "tour|Tour"

$r = Chat "tour re nhat" "s2"
Check "B5-T2 search cheapest" $r "" "" ""

$r = Chat "di dau dep thang 6" "s3"
Check "B5-T3 open recommendation" $r "" "" ""


# ---- BLOCK 6: SEARCH → BOOKING FLOW ----
Write-Host "=== BLOCK 6: FULL BOOKING FLOW ==="
$flowS = "flow_$(Get-Random)"

$r = Chat "tour da nang 3 ngay 2 dem" $flowS
$stage1 = $r.conversationStage
Write-Host "[INFO] B6-T1 search -> stage=$stage1, tours=$($r.tourSuggestions.Count)"

if ($r.tourSuggestions.Count -gt 0) {
    $r2 = Chat "dat tour 1" $flowS
    Write-Host "[INFO] B6-T2 chon tour 1 -> stage=$($r2.conversationStage)"
    
    $r3 = Chat "2 nguoi lon" $flowS
    Write-Host "[INFO] B6-T3 so nguoi -> stage=$($r3.conversationStage)"
    
    $r4 = Chat "BK test" $flowS
    Write-Host "[INFO] B6-T4 note field -> stage=$($r4.conversationStage)"
} else {
    Write-Host "[SKIP] B6 - no tours returned in search"
}
Write-Host ""


# ---- BLOCK 7: FALSE POSITIVE CANCEL CHECK ----
Write-Host "=== BLOCK 7: FALSE POSITIVE CANCEL ==="
# Messages with "huy" in non-cancel context should NOT trigger cancel
$msgs = @("huy hieu","huy hoang","huy dong","bi huy mat roi","tour da bi huy boi ai")
foreach ($msg in $msgs) {
    $r = Chat $msg "fp_$(Get-Random)"
    # This should NOT say "Da huy luong"
    $wasCanceled = $r.reply -match ".a h.y lu.ng|lu.ng .ang ho.t .ong .a b. h.y"
    $tag = if($wasCanceled){"[BUG-FALSE-CANCEL]"}else{"[OK-NO-CANCEL]"}
    Write-Host "$tag `"$msg`""
}
Write-Host ""


# ---- SUMMARY ----
Write-Host "================================"
Write-Host "PASSED: $pass"
Write-Host "FAILED: $fail"
Write-Host ""
if ($bugs.Count -gt 0) {
    Write-Host "BUGS FOUND:"
    foreach ($b in $bugs) { Write-Host "  - $b" }
}
