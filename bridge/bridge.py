"""
DarkFi <-> Hytale Mod Bridge
===========================
Polls darkfid JSON-RPC for incoming transactions, maps them to in-game
actions, and writes commands to drk_queue.txt for the mod to execute.
Also reads drk_payouts.txt and sends DRK tokens to players who earned them.

Run:  python bridge.py
Stop: Ctrl+C

Requirements: Python 3.8+, darkfid running locally, drk CLI in PATH
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path

# -- Configuration ------------------------------------------------------------

DARKFID_RPC    = "http://127.0.0.1:8340"   # darkfid JSON-RPC endpoint
POLL_INTERVAL  = 2.0                         # seconds between polls
HOME           = Path.home()

QUEUE_FILE     = HOME / "drk_queue.txt"      # bridge -> mod
PAYOUTS_FILE   = HOME / "drk_payouts.txt"    # mod -> bridge
STATE_FILE     = HOME / "drk_bridge_state.json"  # persists last-seen tx

# WARNING:  PLACEHOLDER VALUES -- replace with real ones once darkfid is running.
#     Real treasury: drk wallet address
#     Real token:    drk token generate-id
TREASURY_ADDRESS = "DRKtreasury11111111111111111111111111111111111"

# Custom HYTALE governance/currency token ID
HYTALE_TOKEN_ID  = "HYTLtoken1111111111111111111111111111111111111"

# Per-player deposit address -> (player_name, action)
# Real version: each player gets a unique address via drk wallet address.
# These are placeholders to test the bridge plumbing.
PLAYER_DEPOSIT_MAP = {
    "DRKsteve_tornado1111111111111111111111111111111": ("Steve", "disaster_tornado_1.0"),
    "DRKsteve_quake11111111111111111111111111111111":  ("Steve", "disaster_earthquake_1.5"),
    "DRKsteve_dao11111111111111111111111111111111111": ("Steve", "dao_vote"),
    "DRKalice_meteor1111111111111111111111111111111":  ("Alice", "disaster_meteorite_2.0"),
    "DRKalice_blizz1111111111111111111111111111111":   ("Alice", "disaster_blizzard_1.0"),
    "DRKalice_dao11111111111111111111111111111111111": ("Alice", "dao_vote"),
    "DRKbob_volcano1111111111111111111111111111111":   ("Bob",   "disaster_volcano_1.5"),
    "DRKbob_flood11111111111111111111111111111111":    ("Bob",   "disaster_flood_1.0"),
}

# -- Disaster price table (must match DarkFiConfig.java) ----------------------

DISASTER_PRICES = {
    "tornado":     100,
    "earthquake":   80,
    "meteorite":    60,
    "tsunami":     120,
    "blizzard":     50,
    "sandstorm":    40,
    "flood":        30,
    "volcano":     150,
    "blackhole":   200,
    "hivemind":     70,
    "hailstorm":    25,
    "eclipse":      20,
}

# -- State persistence ---------------------------------------------------------

def load_state():
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text())
        except Exception:
            pass
    return {"seen_tx_ids": [], "last_block": 0}

def save_state(state):
    STATE_FILE.write_text(json.dumps(state, indent=2))

# -- darkfid JSON-RPC ----------------------------------------------------------

def rpc_call(method, params=None):
    """Send a JSON-RPC request to darkfid."""
    import urllib.request
    payload = json.dumps({
        "jsonrpc": "2.0",
        "id":      1,
        "method":  method,
        "params":  params or []
    }).encode()
    req = urllib.request.Request(
        DARKFID_RPC,
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read())
    except Exception as e:
        print(f"[Bridge] RPC error ({method}): {e}")
        return None

def get_new_transactions(state):
    """
    Fetch coins received since last check.
    Uses drk CLI as fallback since darkfid RPC wallet methods vary by version.
    """
    try:
        result = subprocess.run(
            ["drk", "wallet", "coins", "--json"],
            capture_output=True, text=True, timeout=10
        )
        if result.returncode != 0:
            print(f"[Bridge] drk coins error: {result.stderr.strip()}")
            return []
        coins = json.loads(result.stdout)
        new_txs = []
        for coin in coins:
            tx_id = coin.get("nullifier") or coin.get("coin_id") or str(coin)
            if tx_id not in state["seen_tx_ids"]:
                state["seen_tx_ids"].append(tx_id)
                new_txs.append(coin)
        # Cap seen list to last 1000 to avoid unbounded growth
        state["seen_tx_ids"] = state["seen_tx_ids"][-1000:]
        return new_txs
    except FileNotFoundError:
        print("[Bridge] 'drk' CLI not found in PATH -- install darkfid and add drk to PATH")
        return []
    except Exception as e:
        print(f"[Bridge] get_new_transactions error: {e}")
        return []

# -- Incoming transaction -> mod action ----------------------------------------

def process_incoming(coin, state):
    """
    Map an incoming coin to a mod queue action.
    Checks the receiving address against PLAYER_DEPOSIT_MAP.
    """
    recv_addr = coin.get("recipient_address") or coin.get("address", "")
    amount    = int(coin.get("value", 0))
    token_id  = coin.get("token_id", "")

    # Only process our custom token
    if HYTALE_TOKEN_ID != "REPLACE_WITH_YOUR_TOKEN_ID" and token_id != HYTALE_TOKEN_ID:
        return

    mapping = PLAYER_DEPOSIT_MAP.get(recv_addr)
    if not mapping:
        print(f"[Bridge] Received {amount} tokens at unknown address {recv_addr[:12]}...")
        return

    player, action = mapping
    print(f"[Bridge] {player} paid {amount} tokens -> {action}")

    # Parse action: "disaster_tornado_1.5" or "dao_vote" or "dao_propose_<key>_<val>"
    parts = action.split("_", 2)
    action_type = parts[0]

    if action_type == "disaster" and len(parts) >= 2:
        disaster  = parts[1]
        intensity = float(parts[2]) if len(parts) > 2 else 1.0
        required  = DISASTER_PRICES.get(disaster, 9999)
        if amount < required:
            print(f"[Bridge] Insufficient tokens: {amount} < {required} for {disaster}")
            append_queue(f"ANN|{player} tried to trigger {disaster} but only paid {amount}/{required} tokens")
            return
        cmd = f"disaster {disaster} {intensity}"
        append_queue(f"CMD|{cmd}")
        append_queue(f"ANN|{player} triggered {disaster} (intensity {intensity}) via DarkFi!")

    elif action_type == "dao":
        # DAO votes are tallied off-chain; this is just a token-weighted signal
        # For actual on-chain DAO, use darkfid DAO contract directly
        print(f"[Bridge] DAO action for {player}: {action}")
        append_queue(f"ANN|{player} cast a DAO governance vote")

    else:
        print(f"[Bridge] Unknown action type: {action_type}")

# -- Outgoing payouts: mod -> bridge -> drk CLI ---------------------------------

def process_payouts():
    """
    Read drk_payouts.txt, send DRK tokens to each player's linked address,
    then clear the file.
    """
    if not PAYOUTS_FILE.exists() or PAYOUTS_FILE.stat().st_size == 0:
        return

    lines = PAYOUTS_FILE.read_text().strip().splitlines()
    PAYOUTS_FILE.write_text("")  # clear atomically

    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|", 2)
        if len(parts) < 3:
            continue
        player, amount_str, reason = parts
        amount = int(amount_str)

        # Look up the player's receiving address
        recv_addr = find_player_address(player)
        if not recv_addr:
            print(f"[Bridge] No address for player '{player}' -- payout skipped ({reason})")
            continue

        send_tokens(recv_addr, amount, player, reason)

def find_player_address(player_name):
    """Reverse-lookup: find a player's address from PLAYER_DEPOSIT_MAP."""
    for addr, (name, _) in PLAYER_DEPOSIT_MAP.items():
        if name == player_name:
            return addr
    return None

def send_tokens(address, amount, player, reason):
    """Send DRK tokens to a player address using the drk CLI."""
    if HYTALE_TOKEN_ID == "REPLACE_WITH_YOUR_TOKEN_ID":
        print(f"[Bridge] PAYOUT (dry run -- token ID not set): {player} +{amount} ({reason})")
        return
    try:
        result = subprocess.run(
            ["drk", "transfer", str(amount), HYTALE_TOKEN_ID, address],
            capture_output=True, text=True, timeout=30
        )
        if result.returncode == 0:
            print(f"[Bridge] PAYOUT sent: {player} +{amount} DRK ({reason})")
        else:
            print(f"[Bridge] PAYOUT failed for {player}: {result.stderr.strip()}")
    except Exception as e:
        print(f"[Bridge] send_tokens error: {e}")

# -- Queue file helpers --------------------------------------------------------

def append_queue(line):
    with open(QUEUE_FILE, "a") as f:
        f.write(line + "\n")
    print(f"[Bridge] Queued: {line}")

# -- Main loop -----------------------------------------------------------------

def main():
    print("[Bridge] DarkFi <-> Hytale bridge starting...")
    print(f"[Bridge] Queue file:   {QUEUE_FILE}")
    print(f"[Bridge] Payouts file: {PAYOUTS_FILE}")

    if TREASURY_ADDRESS == "REPLACE_WITH_YOUR_TREASURY_ADDRESS":
        print("[Bridge] WARNING: TREASURY_ADDRESS not configured")
    if HYTALE_TOKEN_ID == "REPLACE_WITH_YOUR_TOKEN_ID":
        print("[Bridge] WARNING: HYTALE_TOKEN_ID not configured -- payouts will dry-run")

    state = load_state()
    print(f"[Bridge] Loaded state: {len(state['seen_tx_ids'])} known transactions")

    try:
        while True:
            # 1. Check for incoming player payments
            new_coins = get_new_transactions(state)
            for coin in new_coins:
                process_incoming(coin, state)
            if new_coins:
                save_state(state)

            # 2. Send earned token payouts
            process_payouts()

            time.sleep(POLL_INTERVAL)

    except KeyboardInterrupt:
        print("\n[Bridge] Stopped.")
        save_state(state)

if __name__ == "__main__":
    main()
