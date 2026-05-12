# Hytale P2P Markets

Anonymous peer-to-peer token economy and DAO governance for Hytale servers, powered by [DarkFi](https://github.com/darkrenaissance/darkfi).

A Hytale mod plus a sidecar bridge that lets server operators (and other mods) issue tokens to players, accept anonymous payments to trigger in-game events, and run on-chain DAO votes that mutate server config in real time.

## What It Does

- **Token payouts** — any other mod can call `P2PMarketsAPI.emitPayout(player, amount, reason)` to mint custom tokens to a player as a reward for in-game actions (surviving a disaster, finding rare ore, winning PvP, etc.).
- **Anonymous in-game purchases** — players send tokens to deposit addresses to trigger server events (e.g. spawn a tornado). The bridge maps the incoming payment to a queued command.
- **DAO governance** — on-chain proposals can update server config files (`disaster_config.json` etc.) when they pass. Token-weighted voting via DarkFi's native DAO contract.
- **Pluggable action handlers** — other mods can register their own action types via `P2PMarketsAPI.registerHandler(...)` (e.g. marketplaces, auctions, atomic swaps).

## Architecture

```
Player wallet  ──pays──>  darkfid (DarkFi node)
                              │
                              ▼
                          bridge.py  ──writes──>  ~/drk_queue.txt
                                                       │
                                                       ▼
                                       P2PMarketsIntegration (in-game)
                                                       │
                                                       ▼
                                       Dispatches to DevConsole / config / custom handler
```

Payouts flow the other way: mods write to `~/drk_payouts.txt`, the bridge picks them up and runs `drk transfer` to send tokens.

## Layout

```
src/main/java/me/ancap/p2pmarkets/
  P2PMarketsPlugin.java      JavaPlugin entry point
  P2PMarketsAPI.java         ★ public surface for other mods
  P2PMarketsConfig.java      internal config
  P2PMarketsIntegration.java internal poller + built-in handlers
  ActionHandler.java         interface for custom action types

bridge/
  bridge.py                  Python sidecar (polls darkfid, sends payouts)
  setup-darkfi.ps1           Windows setup orchestrator
  setup-darkfi-wsl.sh        WSL build + wallet bootstrap
```

## Quick Start

### Build the mod jar

```powershell
.\gradlew.bat deploy
```

This compiles, packages, and copies `P2PMarkets-<version>.jar` into your Hytale mods folder.

### Set up DarkFi (one-time, interactive)

```powershell
cd bridge
.\setup-darkfi.ps1
```

The script:

1. Verifies WSL is available (Ubuntu recommended)
2. Installs Rust + system deps inside WSL
3. Clones and builds `darkfid` + `drk` from source (~15-30 min)
4. **Pauses** for you to record your wallet seed phrase
5. Starts `darkfid` in the background
6. Generates treasury address, custom token ID, per-player deposit addresses
7. Auto-patches `bridge.py` with the generated values

### Run the bridge

```powershell
python bridge/bridge.py
```

The bridge polls `darkfid` for incoming payments and processes them. Stop with `Ctrl+C`.

## Public API (For Other Mods)

Declare a `compileOnly` dependency on `P2PMarkets-<version>.jar` and wrap calls in a try/catch on `NoClassDefFoundError` (or copy the `MarketsHelper` pattern from the example consumer).

```java
// Reward a player
P2PMarketsAPI.emitPayout("Steve", 50, "survived_sandworm");

// Register a custom action type (handled when the bridge writes "TRADE|seller|buyer|item|price")
P2PMarketsAPI.registerHandler("TRADE", params -> {
    String seller = params[0];
    String buyer  = params[1];
    // ... your handler ...
});

// Check if integration is loaded + active
if (P2PMarketsAPI.isAvailable()) {
    // ...
}
```

## Action Format

The bridge writes one line per action to `~/drk_queue.txt`:

```
TYPE|param1|param2|...
```

Built-in types:

| Type  | Format                          | Effect                                            |
|-------|---------------------------------|---------------------------------------------------|
| `CMD` | `CMD\|<dev-console command>`    | Forwarded to `~/dev-cmd.txt` for DevConsole       |
| `CFG` | `CFG\|<key>\|<numeric value>`   | Patches a key in `disaster_config.json`           |
| `ANN` | `ANN\|<message>`                | In-game announcement via DevConsole               |
| _Any_ | `TYPE\|...`                     | Custom — dispatched to registered `ActionHandler` |

Lines beginning with `#` are comments.

## Security Notes

- **Never commit your wallet seed phrase.** `setup-darkfi.ps1` writes it to `~/darkfi-runtime/SEED_PHRASE_BACKUP.txt` inside WSL with `chmod 600`. Back it up offline.
- **`bridge/darkfi-generated.json` is gitignored** — it contains your real treasury + token IDs.
- DarkFi is **pre-mainnet**. Tokens on testnet have no economic value, which is fine for testing. Re-run setup to regenerate addresses when mainnet launches.

## Status

Alpha. DarkFi itself is pre-mainnet (currently in Phase 2 / RandomX testnet, mainnet TBD). This mod runs against testnet for now.

## License

MIT — see [LICENSE](LICENSE).
