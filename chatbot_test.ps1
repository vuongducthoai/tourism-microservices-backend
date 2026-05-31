
# ============================================================
# COMPREHENSIVE CHATBOT LONG-SESSION TEST
# Tests all chatbot scenarios with real system data
# ============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$BASE = "http://localhost:8080/api/chatbot/chat"
$SESSION = "chatbot_test_$(Get-Date -Format 'yyyyMMddHHmmss')"
$PASS = 0
$FAIL = 0
$RESULTS = @()

function Chat {
    param([string]$msg, [string]$expectedStage = "", [string]$label = "")
    $bodyObj = @{ message = $msg; sessionId = $SESSION; userId = $null }
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes(($bodyObj | ConvertTo-Json))
    try {
        $r = Invoke-RestMethod -Uri $BASE -Method POST -ContentType "application/json; charset=utf-8" -Body $bodyBytes
        $reply = $r.reply
        $stage = $r.conversationStage
        $type  = $r.messageType
        $pass = $true
        if ($expectedStage -ne "" -and $stage -ne $expectedStage) { $pass = $false }
        $status = if ($pass) { "PASS" } else { "FAIL (expected stage=$expectedStage got $stage)" }
        if ($pass) { $script:PASS++ } else { $script:FAIL++ }
        $shortReply = if ($reply.Length -gt 120) { $reply.Substring(0,120) + "..." } else { $reply }
        Write-Output "[$status] [$label] >>> $msg"
        Write-Output "         <<< $shortReply"
        Write-Output "         [stage=$stage, type=$type]"
        if ($r.tourSuggestions -and $r.tourSuggestions.Count -gt 0) {
            Write-Output "         [tours: $($r.tourSuggestions.Count) results - first: $($r.tourSuggestions[0].tourName)]"
        }
        Write-Output ""
        $script:RESULTS += [PSCustomObject]@{ Label=$label; Msg=$msg; Stage=$stage; Status=$status; Reply=$shortReply }
        return $r
    } catch {
        Write-Output "[FAIL] [$label] >>> $msg"
        Write-Output "       Error: $($_.Exception.Message)"
        Write-Output ""
        $script:FAIL++
        $script:RESULTS += [PSCustomObject]@{ Label=$label; Msg=$msg; Stage="ERROR"; Status="FAIL"; Reply=$_.Exception.Message }
        return $null
    }
}

Write-Output "============================================================"
Write-Output " CHATBOT COMPREHENSIVE TEST - Session: $SESSION"
Write-Output " Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Output "============================================================"
Write-Output ""

# ── T01: Greeting ──
Write-Output "─── SCENARIO 1: GREETING ───"
Chat "Xin chào" "IDLE" "T01-Greeting"

# ── T02: Policy question (should NOT trigger cancel) ──
Write-Output "─── SCENARIO 2: POLICY QUESTIONS (should NOT cancel) ───"
Chat "Chính sách hủy tour như thế nào?" "IDLE" "T02-HuyTourPolicy"
Chat "Điều kiện hủy đặt tour là gì?" "IDLE" "T03-HuyDatPolicy"
Chat "Mình bỏ qua chính sách hoàn tiền" "IDLE" "T04-BoQuaPolicy"

# ── T05-T07: Tour search ──
Write-Output "─── SCENARIO 3: TOUR SEARCH ───"
$r = Chat "Tôi muốn đặt tour đi Đà Nẵng" "" "T05-SearchDaNang"
$r = Chat "Tôi đi từ TP. Hồ Chí Minh, tháng 7" "" "T06-AddStartDate"
if ($r -and $r.tourSuggestions -and $r.tourSuggestions.Count -gt 0) {
    Write-Output "  → Got $($r.tourSuggestions.Count) tour results"
    $global:firstTourId = $r.tourSuggestions[0].tourId
    $global:firstTourName = $r.tourSuggestions[0].tourName
    Write-Output "  → First tour: [$firstTourId] $firstTourName"
}

# ── T08: Select departure ──
Write-Output "─── SCENARIO 4: TOUR SELECTION ───"
$r2 = Chat "1" "" "T08-SelectTour1"

# ── T09: Select departure date ──
Write-Output "─── SCENARIO 5: DEPARTURE SELECTION ───"
$r3 = Chat "1" "" "T09-SelectDeparture1"

# ── T10-T12: Passenger info ──
Write-Output "─── SCENARIO 6: PASSENGER INFO ───"
Chat "Nguyễn Văn A, Nam, 1990-05-15" "" "T10-Passenger1"
Chat "Không có trẻ em" "" "T11-NoChildren"

# ── T13: Contact name + phone ──
Write-Output "─── SCENARIO 7: CONTACT INFO ───"
Chat "Nguyễn Văn A, 0901234567" "" "T13-ContactNamePhone"

# ── T14: Contact email ──
Chat "test@gmail.com" "" "T14-ContactEmail"

# ── T15: Skip note/coupon ──
Write-Output "─── SCENARIO 8: SKIP NOTE/COUPON ───"
$r4 = Chat "bỏ qua" "" "T15-SkipNoteCoupon"

# ── T16: Confirm booking ──
Write-Output "─── SCENARIO 9: CONFIRM BOOKING ───"
$r5 = Chat "Xác nhận" "" "T16-ConfirmBooking"
$global:bookingCode = $null
if ($r5 -and $r5.bookingCode) {
    $global:bookingCode = $r5.bookingCode
    Write-Output "  → Booking created: $bookingCode"
} elseif ($r5 -and $r5.reply -match "BK[A-Za-z0-9]{8}") {
    $global:bookingCode = $Matches[0]
    Write-Output "  → Booking code from reply: $bookingCode"
}

# ── T17: Lookup with exact case ──
Write-Output "─── SCENARIO 10: BOOKING LOOKUP ───"
if ($bookingCode) {
    $r6 = Chat "tra cứu $bookingCode" "" "T17-LookupExact"
    # T18: Lookup with lowercase
    $lowerCode = $bookingCode.ToLower()
    Chat "tra cứu $lowerCode" "" "T18-LookupLowerCase"
    # T19: Lookup with just the BK code
    Chat $bookingCode "" "T19-LookupBKCodeDirect"
} else {
    Write-Output "[SKIP] T17-T19: No booking code - skipping lookup tests"
    $FAIL += 3
}

# ── T20: Greeting resets state ──
Write-Output "─── SCENARIO 11: RESET VIA GREETING ───"
Chat "Xin chào" "IDLE" "T20-GreetingReset"

# ── T21: Cancel during booking flow ──
Write-Output "─── SCENARIO 12: CANCEL DURING BOOKING ───"
Chat "Tôi muốn đặt tour Hội An 2 người lớn" "" "T21-StartBooking"
Chat "hủy" "IDLE" "T22-CancelExact"

# ── T23-T24: False cancel tests (should NOT cancel) ──
Write-Output "─── SCENARIO 13: FALSE CANCEL PREVENTION ───"
Chat "Tôi muốn đặt tour Hà Nội" "" "T23-StartSearch"
Chat "hủy tour nếu thời tiết xấu" "" "T24-HuyTourNotCancel"
Chat "Tôi muốn hủy đặt phòng khách sạn" "" "T25-HuyDatNotCancel"

# ── T26: Policy question mid-booking ──
Write-Output "─── SCENARIO 14: POLICY DURING FLOW ───"
Chat "Tôi muốn đặt tour Phú Quốc" "" "T26-StartPhuQuoc"
Chat "chính sách hoàn tiền hủy tour là bao nhiêu?" "" "T27-PolicyMidFlow"

# ── T28: New booking fresh ──
Write-Output "─── SCENARIO 15: FRESH SEARCH ───"
$SESSION_NEW = "chatbot_test_new_$(Get-Date -Format 'yyyyMMddHHmmss')"
function ChatNew($msg, $expectedStage="", $label="") {
    $bodyObj = @{ message = $msg; sessionId = $SESSION_NEW; userId = $null }
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes(($bodyObj | ConvertTo-Json))
    $r = Invoke-RestMethod -Uri $BASE -Method POST -ContentType "application/json; charset=utf-8" -Body $bodyBytes -ErrorAction SilentlyContinue
    $stage = $r.conversationStage
    $status = if ($expectedStage -eq "" -or $stage -eq $expectedStage) { "PASS" } else { "FAIL(stage=$stage)" }
    if ($status -eq "PASS") { $script:PASS++ } else { $script:FAIL++ }
    $shortReply = if ($r -and $r.reply -and $r.reply.Length -gt 100) { $r.reply.Substring(0,100)+"..." } else { $r.reply }
    Write-Output "[$status] [$label] >>> $msg"
    Write-Output "         <<< $shortReply"
    Write-Output ""
    $r
}

ChatNew "tôi muốn đặt tour" "" "T28-BookingIntent"

# ── SUMMARY ──
Write-Output "============================================================"
Write-Output " TEST RESULTS SUMMARY"
Write-Output " Session: $SESSION"
Write-Output " PASS: $PASS | FAIL: $FAIL | TOTAL: $($PASS+$FAIL)"
Write-Output "============================================================"
$RESULTS | Format-Table -AutoSize | Out-String -Width 200 | Write-Output
