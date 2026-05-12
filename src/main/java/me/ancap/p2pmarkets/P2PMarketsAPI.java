package me.ancap.p2pmarkets;

import java.io.FileWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Public API surface for other Hytale mods to integrate with the P2P Markets
 * (DarkFi-backed) economy. This is the ONLY class other mods should reference.
 *
 * Other mods should declare this as a compileOnly dependency and wrap calls
 * in a try/catch on NoClassDefFoundError so they keep working if P2P Markets
 * isn't installed at runtime. See the MarketsHelper pattern.
 *
 * Three things you can do:
 *   1. emitPayout(...)       — pay a player tokens for an in-game event
 *   2. registerHandler(...)  — handle a custom action type from the bridge
 *   3. isAvailable()         — probe whether the integration is active
 */
public final class P2PMarketsAPI {

    private P2PMarketsAPI() {}

    // Set by P2PMarketsIntegration.start() / .stop()
    private static volatile boolean active = false;

    // Pluggable handlers — built-ins (CMD/CFG/ANN) live in P2PMarketsIntegration.
    // Other mods can add their own action types here.
    private static final ConcurrentMap<String, ActionHandler> HANDLERS = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Queue a token payout for a player. The Python bridge will pick this up
     * and execute the on-chain transfer.
     *
     * Safe to call regardless of state — silently no-ops when the integration
     * is disabled.
     *
     * @param playerName Hytale player name (must match an entry in PLAYER_DEPOSIT_MAP)
     * @param amount     DRK token amount (positive integer)
     * @param reason     short audit tag, e.g. "survived_sandworm", "found_prisma_ore"
     */
    public static void emitPayout(String playerName, int amount, String reason) {
        if (!active) return;
        try {
            synchronized (P2PMarketsAPI.class) {
                try (FileWriter fw = new FileWriter(P2PMarketsConfig.PAYOUTS_FILE, true)) {
                    fw.write(playerName + "|" + amount + "|" + reason + "\n");
                }
            }
            log("Payout queued: " + playerName + " +" + amount + " (" + reason + ")");
        } catch (Exception e) {
            log("Payout emit error: " + e.getMessage());
        }
    }

    /**
     * Register a custom action handler that the integration's poll loop will
     * invoke when it sees a matching line type in drk_queue.txt.
     *
     * Built-in handlers (you don't need to register these):
     *   CMD — dispatch to DevConsole
     *   CFG — patch disaster_config.json
     *   ANN — announce via DevConsole
     *
     * Custom example: another mod could register "TRADE" to process atomic-swap
     * notifications from the bridge.
     */
    public static void registerHandler(String actionType, ActionHandler handler) {
        if (actionType == null || handler == null) return;
        HANDLERS.put(actionType.toUpperCase(), handler);
        log("Handler registered: " + actionType.toUpperCase());
    }

    /** True iff the integration is loaded AND its master switch is enabled. */
    public static boolean isAvailable() {
        return active;
    }

    // ── Internal (called by P2PMarketsIntegration) ─────────────────────────

    static void setActive(boolean v) { active = v; }
    static ActionHandler getHandler(String type) { return HANDLERS.get(type.toUpperCase()); }

    private static void log(String msg) {
        System.out.println("[P2PMarkets] " + msg);
    }
}
