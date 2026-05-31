[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$BASE = "http://localhost:8080/api/coin-withdrawals"
$USER_ID = 1
$PASS = 0
$FAIL = 0

function Check {
    param([string]$label, [scriptblock]$fn)
    try {
        & $fn
        Write-Output "[PASS] $label"
        $script:PASS++
    } catch {
        Write-Output "[FAIL] $label -> $($_.Exception.Message)"
        $script:FAIL++
    }
}

Write-Output "============================================================"
Write-Output " COIN WITHDRAWAL API TEST"
Write-Output " Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Output "============================================================"

Check "GET my-history" {
    $res = Invoke-RestMethod -Uri "$BASE/my-history?userId=$USER_ID" -Method GET
    if ($null -eq $res) { throw "Empty response" }
}

$createdId = $null

Check "POST create withdrawal" {
    $body = @{
        userId = $USER_ID
        coinAmount = 5
        bank = "VCB"
        accountNumber = "1234567890"
        accountName = "TEST USER"
    }
    $json = $body | ConvertTo-Json
    $res = Invoke-RestMethod -Uri $BASE -Method POST -ContentType "application/json" -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
    if (-not $res.id) { throw "Missing id in response" }
    if (-not $res.referenceCode) { throw "Missing referenceCode" }
    $script:createdId = $res.id
}

Check "GET admin search" {
    $res = Invoke-RestMethod -Uri "$BASE/admin/search?page=0&size=5&sortBy=createdAt&sortDir=DESC" -Method GET
    if ($null -eq $res.content) { throw "Missing content field" }
}

if ($createdId) {
    Check "GET admin detail" {
        $res = Invoke-RestMethod -Uri "$BASE/admin/$createdId" -Method GET
        if ($res.id -ne $createdId) { throw "Detail id mismatch" }
    }
}

Write-Output "============================================================"
Write-Output " RESULT: PASS=$PASS | FAIL=$FAIL | TOTAL=$($PASS + $FAIL)"
Write-Output "============================================================"
