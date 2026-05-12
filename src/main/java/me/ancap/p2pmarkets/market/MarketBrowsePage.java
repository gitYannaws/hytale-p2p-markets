package me.ancap.p2pmarkets.market;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Marketplace browse page — paginated grid of active listings.
 *
 * MVP scope: read-only viewer. Players run {@code /market buy <id>} to purchase.
 * Future: per-row Buy button that wires CustomUIEventBinding with the listing id.
 */
public class MarketBrowsePage extends CustomUIPage {

    private static final String UI_PATH = "Pages/MarketBrowse.ui";

    public MarketBrowsePage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder builder,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {

        builder.append(UI_PATH);

        List<MarketListing> listings = MarketRegistry.active();
        builder.set("#HeaderCount.Text", "Listings: " + listings.size());
        builder.clear("#ListingsList");

        if (listings.isEmpty()) {
            builder.set("#EmptyState.Visible", true);
            return;
        }
        builder.set("#EmptyState.Visible", false);

        long now = System.currentTimeMillis();
        for (MarketListing l : listings) {
            String row = buildRow(l, now);
            builder.appendInline("#ListingsList", row);
        }
    }

    private String buildRow(MarketListing l, long now) {
        String age = humanAge((now - l.createdAt) / 1000);
        String text = String.format("%-9s  %-22s  qty %-4d  %-6d DRK  by %-16s  %s",
                l.shortId(),
                truncate(l.itemId, 22),
                l.quantity,
                l.tokenPrice,
                truncate(l.sellerName, 16),
                age);
        // Hytale UI inline syntax — one Label per row, fixed font for alignment.
        return "Label { Text: \"" + escape(text) + "\"; "
                + "Style: (FontSize: 13, TextColor: #d8e2ee); "
                + "Anchor: (Height: 22); }";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String humanAge(long seconds) {
        if (seconds < 60)      return seconds + "s ago";
        if (seconds < 3600)    return (seconds / 60) + "m ago";
        if (seconds < 86400)   return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
