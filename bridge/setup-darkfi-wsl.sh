#!/usr/bin/env bash
# =============================================================================
# DarkFi Setup (WSL/Ubuntu side)
# =============================================================================
# Invoked by setup-darkfi.ps1 from the Windows side.
# Writes a JSON config file with generated addresses/IDs to the path in
# $DARKFI_CONFIG_OUT (mounted Windows path) for the orchestrator to pick up.
# =============================================================================

set -e
set -o pipefail

# Sanity check
if [[ -z "$DARKFI_CONFIG_OUT" ]]; then
    echo "ERROR: DARKFI_CONFIG_OUT not set. Run via setup-darkfi.ps1, not directly."
    exit 1
fi

RUNTIME_DIR="$HOME/darkfi-runtime"
DARKFI_SRC="$RUNTIME_DIR/darkfi"
SEED_BACKUP="$RUNTIME_DIR/SEED_PHRASE_BACKUP.txt"
DARKFID_LOG="$RUNTIME_DIR/darkfid.log"
DARKFID_PID="$RUNTIME_DIR/darkfid.pid"

mkdir -p "$RUNTIME_DIR"

stage() {
    echo ""
    echo "---------------------------------------------------------------"
    echo ">>> $1"
    echo "---------------------------------------------------------------"
}

confirm() {
    read -r -p "$1 [y/N] " resp
    if [[ "$resp" != "y" && "$resp" != "Y" ]]; then
        echo "Aborted."
        exit 0
    fi
}

# -----------------------------------------------------------------------------
stage "1. Install system dependencies (apt)"
# -----------------------------------------------------------------------------

if ! dpkg -l | grep -q build-essential; then
    echo "Installing build-essential, git, pkg-config, libssl-dev..."
    sudo apt-get update
    sudo apt-get install -y build-essential git pkg-config libssl-dev curl clang
else
    echo "Build deps already installed."
fi

# -----------------------------------------------------------------------------
stage "2. Install Rust toolchain"
# -----------------------------------------------------------------------------

if ! command -v cargo >/dev/null 2>&1; then
    echo "Installing Rust via rustup..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
    source "$HOME/.cargo/env"
else
    echo "Rust already installed: $(rustc --version)"
fi

# Make sure rustc meets DarkFi minimum (1.87)
RUST_VER=$(rustc --version | awk '{print $2}')
echo "Rust version: $RUST_VER"
if ! printf '%s\n%s\n' "1.87.0" "$RUST_VER" | sort -V -C; then
    echo "Updating Rust to latest stable..."
    rustup update stable
fi

# Ensure cargo on PATH for the rest of this script
[[ -f "$HOME/.cargo/env" ]] && source "$HOME/.cargo/env"

# -----------------------------------------------------------------------------
stage "3. Clone darkrenaissance/darkfi"
# -----------------------------------------------------------------------------

if [[ ! -d "$DARKFI_SRC/.git" ]]; then
    git clone https://github.com/darkrenaissance/darkfi.git "$DARKFI_SRC"
else
    echo "Repo already cloned at $DARKFI_SRC"
    cd "$DARKFI_SRC"
    git fetch --tags
fi

cd "$DARKFI_SRC"

# -----------------------------------------------------------------------------
stage "4. Build darkfid + drk (this takes 15-30 minutes)"
# -----------------------------------------------------------------------------

if [[ -x "$DARKFI_SRC/darkfid" && -x "$DARKFI_SRC/drk" ]]; then
    echo "darkfid and drk binaries already exist. Skipping build."
    echo "(Delete $DARKFI_SRC/darkfid + $DARKFI_SRC/drk to force rebuild.)"
else
    echo "Building darkfid (fullnode)..."
    make BINS="darkfid drk"
fi

# Add to PATH for this script + future shells
if ! grep -q "darkfi-runtime/darkfi" "$HOME/.bashrc" 2>/dev/null; then
    echo "export PATH=\"$DARKFI_SRC:\$PATH\"" >> "$HOME/.bashrc"
fi
export PATH="$DARKFI_SRC:$PATH"

# Sanity check
if ! command -v darkfid >/dev/null 2>&1; then
    echo "ERROR: darkfid not found after build. Check $DARKFI_SRC for build errors."
    exit 1
fi
echo "darkfid: $(darkfid --version 2>&1 | head -1)"
echo "drk:     $(drk --version 2>&1 | head -1)"

# -----------------------------------------------------------------------------
stage "5. Wallet initialization (SECURITY CRITICAL)"
# -----------------------------------------------------------------------------

if [[ -f "$SEED_BACKUP" ]]; then
    echo "Wallet already initialized (seed backed up at $SEED_BACKUP)."
else
    echo ""
    echo "About to initialize a DarkFi wallet."
    echo "The seed phrase will be displayed. WRITE IT DOWN ON PAPER NOW."
    echo "If you lose the seed, all funds (and player payouts) are gone forever."
    echo ""
    confirm "Ready to create the wallet?"

    # `drk wallet --initialize` typically prints a mnemonic. Capture to backup.
    drk wallet --initialize 2>&1 | tee "$SEED_BACKUP"
    chmod 600 "$SEED_BACKUP"
    echo ""
    echo "Seed phrase saved to: $SEED_BACKUP"
    echo "Copy this file somewhere safe (offline, e.g. encrypted USB)."
    echo ""
    confirm "Have you backed up the seed phrase?"
fi

# -----------------------------------------------------------------------------
stage "6. Start darkfid daemon"
# -----------------------------------------------------------------------------

# Kill stale daemon
if [[ -f "$DARKFID_PID" ]] && kill -0 "$(cat "$DARKFID_PID")" 2>/dev/null; then
    echo "darkfid already running (pid $(cat "$DARKFID_PID"))"
else
    echo "Starting darkfid in the background..."
    nohup darkfid > "$DARKFID_LOG" 2>&1 &
    echo $! > "$DARKFID_PID"
    sleep 5
    if ! kill -0 "$(cat "$DARKFID_PID")" 2>/dev/null; then
        echo "ERROR: darkfid failed to start. Log:"
        tail -30 "$DARKFID_LOG"
        exit 1
    fi
    echo "darkfid running (pid $(cat "$DARKFID_PID")), log at $DARKFID_LOG"
fi

# Wait a bit for RPC to come up
sleep 5

# Convenience helper to restart darkfid later
cat > "$RUNTIME_DIR/start-darkfid.sh" <<EOF
#!/usr/bin/env bash
export PATH="$DARKFI_SRC:\$PATH"
nohup darkfid > "$DARKFID_LOG" 2>&1 &
echo \$! > "$DARKFID_PID"
echo "darkfid started (pid \$(cat "$DARKFID_PID"))"
EOF
chmod +x "$RUNTIME_DIR/start-darkfid.sh"

# -----------------------------------------------------------------------------
stage "7. Generate treasury + per-player addresses + HYTALE token"
# -----------------------------------------------------------------------------

# Helper: capture first address-like string from `drk wallet address` output
gen_address() {
    drk wallet address 2>/dev/null | grep -oE '[A-Za-z0-9]{32,}' | head -1
}

echo "Generating treasury address..."
TREASURY=$(gen_address)
[[ -z "$TREASURY" ]] && { echo "ERROR: treasury address generation failed"; exit 1; }
echo "Treasury: $TREASURY"

echo "Generating HYTALE token ID..."
TOKEN_ID=$(drk token generate-id 2>/dev/null | grep -oE '[A-Za-z0-9]{32,}' | head -1)
[[ -z "$TOKEN_ID" ]] && { echo "ERROR: token ID generation failed"; exit 1; }
echo "Token ID: $TOKEN_ID"

# Per-player addresses — sample set, expand as needed
declare -A PLAYERS=(
    [Steve]="disaster_tornado_1.0 disaster_earthquake_1.5 dao_vote"
    [Alice]="disaster_meteorite_2.0 disaster_blizzard_1.0 dao_vote"
    [Bob]="disaster_volcano_1.5 disaster_flood_1.0 dao_vote"
)

echo ""
echo "Generating per-player deposit addresses..."

PLAYER_JSON=""
for player in "${!PLAYERS[@]}"; do
    actions="${PLAYERS[$player]}"
    PLAYER_JSON+="\"$player\": {"
    first=1
    for action in $actions; do
        addr=$(gen_address)
        [[ -z "$addr" ]] && { echo "ERROR: address generation failed for $player/$action"; exit 1; }
        if [[ $first -eq 1 ]]; then
            first=0
        else
            PLAYER_JSON+=","
        fi
        PLAYER_JSON+="\"$action\":\"$addr\""
        echo "  $player / $action -> $addr"
    done
    PLAYER_JSON+="},"
done
# strip trailing comma
PLAYER_JSON="${PLAYER_JSON%,}"

# -----------------------------------------------------------------------------
stage "8. Write generated config JSON for the Windows side"
# -----------------------------------------------------------------------------

cat > "$DARKFI_CONFIG_OUT" <<EOF
{
  "treasury_address": "$TREASURY",
  "hytale_token_id": "$TOKEN_ID",
  "player_addresses": { $PLAYER_JSON }
}
EOF

echo "Config written: $DARKFI_CONFIG_OUT"
echo ""
echo "DarkFi setup complete. Returning to Windows side for bridge.py patching..."
