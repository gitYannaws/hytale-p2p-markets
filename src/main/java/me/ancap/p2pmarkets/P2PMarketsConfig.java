package me.ancap.p2pmarkets;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal configuration. Bundled inside the P2P Markets jar — not part of
 * the public API. Other mods should not reference this directly.
 */
public class P2PMarketsConfig {

    /** Master switch — flip to false to disable without uninstalling the jar. */
    public static final boolean ENABLED = true;

    // ── File paths ───────────────────────────────────────────────────────────
    static final String HOME = System.getProperty("user.home");

    public static final String QUEUE_FILE   = HOME + "/drk_queue.txt";   // bridge -> mod
    public static final String PAYOUTS_FILE = HOME + "/drk_payouts.txt"; // mod -> bridge
    public static final String DEV_CMD_FILE = HOME + "/dev-cmd.txt";     // existing DevConsole inbox

    public static final String DISASTER_CONFIG = HOME
            + "/AppData/Roaming/Hytale/UserData/Saves/New World/disaster_config.json";

    // ── Disaster price table (must match bridge.py DISASTER_PRICES) ─────────
    public static final Map<String, Integer> DISASTER_PRICES = new HashMap<>();
    static {
        DISASTER_PRICES.put("tornado",     100);
        DISASTER_PRICES.put("earthquake",   80);
        DISASTER_PRICES.put("meteorite",    60);
        DISASTER_PRICES.put("tsunami",     120);
        DISASTER_PRICES.put("blizzard",     50);
        DISASTER_PRICES.put("sandstorm",    40);
        DISASTER_PRICES.put("flood",        30);
        DISASTER_PRICES.put("volcano",     150);
        DISASTER_PRICES.put("blackhole",   200);
        DISASTER_PRICES.put("hivemind",     70);
        DISASTER_PRICES.put("hailstorm",    25);
        DISASTER_PRICES.put("eclipse",      20);
    }

    // ── Suggested payout amounts (other mods reference these as constants) ──
    public static final int PAYOUT_SURVIVED_DISASTER = 15;
    public static final int PAYOUT_KILLED_SHARK      = 25;
    public static final int PAYOUT_FOUND_PRISMA      = 100;
    public static final int PAYOUT_FOUND_ONYXIUM     = 50;
    public static final int PAYOUT_FLOOD_ORE         = 10;
    public static final int PAYOUT_SURVIVED_SANDWORM = 40;

    public static final long POLL_INTERVAL_MS = 1_000L;
}
