package me.ancap.p2pmarkets.market;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * In-memory marketplace registry, persisted to JSON on every mutation.
 *
 * Storage location: {@code ~/p2p_markets_listings.json}
 * Format: one listing per line (JSONL), so corruption only loses one entry.
 */
public final class MarketRegistry {

    private static final String FILE_PATH =
            System.getProperty("user.home") + "/p2p_markets_listings.json";

    private static final ConcurrentMap<String, MarketListing> LISTINGS = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private MarketRegistry() {}

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        File f = new File(FILE_PATH);
        if (!f.exists()) {
            log("No listings file yet — starting empty");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(f.toPath());
            int loadedCount = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                MarketListing listing = MarketListing.fromJson(trimmed);
                if (listing != null) {
                    LISTINGS.put(listing.id, listing);
                    loadedCount++;
                }
            }
            log("Loaded " + loadedCount + " listings from " + FILE_PATH);
        } catch (Exception e) {
            log("Load error: " + e.getMessage());
        }
    }

    private static void persist() {
        try {
            StringBuilder sb = new StringBuilder(LISTINGS.size() * 200);
            for (MarketListing l : LISTINGS.values()) {
                sb.append(l.toJson()).append('\n');
            }
            try (FileWriter fw = new FileWriter(FILE_PATH, false)) {
                fw.write(sb.toString());
            }
        } catch (Exception e) {
            log("Persist error: " + e.getMessage());
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public static void create(MarketListing listing) {
        LISTINGS.put(listing.id, listing);
        persist();
        log("Created listing " + listing.shortId() + " by " + listing.sellerName
                + ": " + listing.quantity + "x " + listing.itemId
                + " for " + listing.tokenPrice + " DRK");
    }

    public static MarketListing get(String id) {
        // Allow shortId prefix matching for command convenience
        MarketListing exact = LISTINGS.get(id);
        if (exact != null) return exact;
        for (MarketListing l : LISTINGS.values()) {
            if (l.id.startsWith(id)) return l;
        }
        return null;
    }

    public static List<MarketListing> active() {
        return LISTINGS.values().stream()
                .filter(l -> l.status == MarketListing.Status.ACTIVE)
                .sorted(Comparator.comparingLong((MarketListing l) -> l.createdAt).reversed())
                .collect(Collectors.toList());
    }

    public static List<MarketListing> activeBy(String seller) {
        return active().stream()
                .filter(l -> l.sellerName.equalsIgnoreCase(seller))
                .collect(Collectors.toList());
    }

    public static List<MarketListing> activeByItem(String itemId) {
        return active().stream()
                .filter(l -> l.itemId.equalsIgnoreCase(itemId))
                .collect(Collectors.toList());
    }

    public static void markSold(MarketListing listing, String buyerName) {
        listing.status   = MarketListing.Status.SOLD;
        listing.buyerName = buyerName;
        listing.soldAt   = System.currentTimeMillis();
        persist();
        log("Sold " + listing.shortId() + " to " + buyerName);
    }

    public static void cancel(MarketListing listing) {
        listing.status = MarketListing.Status.CANCELLED;
        persist();
        log("Cancelled " + listing.shortId());
    }

    public static int size() { return LISTINGS.size(); }

    public static Collection<MarketListing> all() {
        return new ArrayList<>(LISTINGS.values());
    }

    private static void log(String msg) {
        System.out.println("[P2PMarkets/Market] " + msg);
    }
}
