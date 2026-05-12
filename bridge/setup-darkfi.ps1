# =============================================================================
# DarkFi Setup Orchestrator (Windows side)
# =============================================================================
# Run from PowerShell in this directory:
#   cd C:\Users\almig\hytale-p2p-markets\bridge
#   .\setup-darkfi.ps1
#
# Re-runnable — each stage detects what's already done and skips it.
# =============================================================================

$ErrorActionPreference = "Stop"
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$BridgePath  = Join-Path $ScriptDir "bridge.py"
$WslScript   = Join-Path $ScriptDir "setup-darkfi-wsl.sh"
$ConfigOut   = Join-Path $ScriptDir "darkfi-generated.json"

function Write-Stage($num, $msg) {
    Write-Host ""
    Write-Host "===============================================================" -ForegroundColor Cyan
    Write-Host "STAGE $num : $msg" -ForegroundColor Cyan
    Write-Host "===============================================================" -ForegroundColor Cyan
}

function Confirm-Continue($prompt) {
    $resp = Read-Host "$prompt [y/N]"
    if ($resp -ne "y" -and $resp -ne "Y") {
        Write-Host "Aborted." -ForegroundColor Yellow
        exit 0
    }
}

# -----------------------------------------------------------------------------
Write-Stage 1 "Check WSL availability"
# -----------------------------------------------------------------------------

$wslInstalled = $false
try {
    $wslOutput = wsl --list --quiet 2>&1 | Out-String
    # WSL is properly installed if `wsl --list` returns distro names (not the install hint)
    if ($wslOutput -notmatch "not installed" -and $wslOutput.Trim().Length -gt 0) {
        $wslInstalled = $true
        Write-Host "WSL is installed. Distros found:" -ForegroundColor Green
        wsl --list --quiet
    }
} catch {
    # wsl.exe exists but errored
}

if (-not $wslInstalled) {
    Write-Host "WSL is not installed. DarkFi requires Linux." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "To install WSL + Ubuntu, open an ADMIN PowerShell and run:" -ForegroundColor White
    Write-Host "    wsl --install -d Ubuntu" -ForegroundColor Green
    Write-Host ""
    Write-Host "This requires a reboot. After reboot, Ubuntu will prompt you to" -ForegroundColor White
    Write-Host "create a Linux username + password. Then re-run this script." -ForegroundColor White
    exit 1
}

# Make sure Ubuntu (or some distro) is actually usable
try {
    $unameOut = wsl -- uname -a 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "WSL is installed but no distro is set up. Run: wsl --install -d Ubuntu" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "WSL kernel: $unameOut" -ForegroundColor Gray
} catch {
    Write-Host "Couldn't query WSL distro. Run: wsl --install -d Ubuntu" -ForegroundColor Yellow
    exit 1
}

# -----------------------------------------------------------------------------
Write-Stage 2 "Copy WSL setup script into the Linux home directory"
# -----------------------------------------------------------------------------

if (-not (Test-Path $WslScript)) {
    Write-Host "ERROR: $WslScript not found. Re-clone the repo." -ForegroundColor Red
    exit 1
}

# Translate the Windows path to the WSL-side mounted path (e.g. /mnt/c/Users/...)
$wslSidePath = (wsl wslpath -a "$WslScript").Trim()
Write-Host "Script accessible inside WSL at: $wslSidePath" -ForegroundColor Gray

# Copy into ~/setup-darkfi.sh inside WSL for convenience
wsl -- bash -c "cp '$wslSidePath' ~/setup-darkfi.sh && chmod +x ~/setup-darkfi.sh && echo Copied to: \$HOME/setup-darkfi.sh"

# -----------------------------------------------------------------------------
Write-Stage 3 "Run DarkFi setup inside WSL (this is the long part)"
# -----------------------------------------------------------------------------

Write-Host ""
Write-Host "The WSL script will now:" -ForegroundColor White
Write-Host "  - Install Rust toolchain (~5 min, if not already installed)" -ForegroundColor White
Write-Host "  - Clone darkrenaissance/darkfi (~30 seconds)" -ForegroundColor White
Write-Host "  - Build darkfid + drk from source (~15-30 min, lots of CPU)" -ForegroundColor White
Write-Host "  - Initialize a wallet (you MUST record the seed phrase)" -ForegroundColor Yellow
Write-Host "  - Start darkfid in the background" -ForegroundColor White
Write-Host "  - Generate treasury + per-player addresses + HYTALE token ID" -ForegroundColor White
Write-Host ""
Confirm-Continue "Ready to start the long build?"

# Pass the Windows-side config output path so the WSL script can write JSON there
$configOutWsl = (wsl wslpath -a "$ConfigOut").Trim()

wsl -- bash -c "DARKFI_CONFIG_OUT='$configOutWsl' ~/setup-darkfi.sh"
if ($LASTEXITCODE -ne 0) {
    Write-Host "WSL setup script failed (exit $LASTEXITCODE). Fix the error above and re-run." -ForegroundColor Red
    exit 1
}

# -----------------------------------------------------------------------------
Write-Stage 4 "Patch bridge.py with the generated DarkFi values"
# -----------------------------------------------------------------------------

if (-not (Test-Path $ConfigOut)) {
    Write-Host "ERROR: $ConfigOut not generated. Check WSL script output above." -ForegroundColor Red
    exit 1
}

$cfg = Get-Content $ConfigOut | ConvertFrom-Json
Write-Host "Read generated config:" -ForegroundColor Green
Write-Host "  Treasury:  $($cfg.treasury_address)" -ForegroundColor Gray
Write-Host "  Token ID:  $($cfg.hytale_token_id)" -ForegroundColor Gray
Write-Host "  Players:   $($cfg.player_addresses.PSObject.Properties.Count)" -ForegroundColor Gray

# Build the new PLAYER_DEPOSIT_MAP block from the JSON
$mapLines = @()
foreach ($p in $cfg.player_addresses.PSObject.Properties) {
    $playerName = $p.Name
    foreach ($entry in $p.Value.PSObject.Properties) {
        $action  = $entry.Name
        $address = $entry.Value
        $mapLines += "    `"$address`": (`"$playerName`", `"$action`"),"
    }
}
$newMapBlock = "PLAYER_DEPOSIT_MAP = {`n" + ($mapLines -join "`n") + "`n}"

# Patch bridge.py
$bridge = Get-Content $BridgePath -Raw

$bridge = $bridge -replace `
    'TREASURY_ADDRESS\s*=\s*"[^"]*"', `
    "TREASURY_ADDRESS = `"$($cfg.treasury_address)`""

$bridge = $bridge -replace `
    'HYTALE_TOKEN_ID\s*=\s*"[^"]*"', `
    "HYTALE_TOKEN_ID  = `"$($cfg.hytale_token_id)`""

# Replace the entire PLAYER_DEPOSIT_MAP = { ... } block
$bridge = [regex]::Replace(
    $bridge,
    'PLAYER_DEPOSIT_MAP\s*=\s*\{[^}]*\}',
    [regex]::Escape($newMapBlock) -replace '\\(.)', '$1',
    [System.Text.RegularExpressions.RegexOptions]::Singleline
)

Set-Content -Path $BridgePath -Value $bridge -Encoding UTF8
Write-Host "Patched: $BridgePath" -ForegroundColor Green

# -----------------------------------------------------------------------------
Write-Stage 5 "Next steps"
# -----------------------------------------------------------------------------

Write-Host ""
Write-Host "DarkFi is set up. To use the bridge:" -ForegroundColor White
Write-Host ""
Write-Host "  1. Make sure darkfid is running in WSL:" -ForegroundColor White
Write-Host "       wsl -- ~/darkfi-runtime/start-darkfid.sh" -ForegroundColor Green
Write-Host ""
Write-Host "  2. Start the Python bridge (in this PowerShell window):" -ForegroundColor White
Write-Host "       python bridge.py" -ForegroundColor Green
Write-Host ""
Write-Host "  3. Fund a player wallet to test:" -ForegroundColor White
Write-Host "       wsl -- drk token mint 500 <player_address>" -ForegroundColor Green
Write-Host ""
Write-Host "Wallet seed phrase was shown during setup. SAVE IT NOW if you haven't." -ForegroundColor Yellow
