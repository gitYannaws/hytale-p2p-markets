package me.ancap.p2pmarkets.market;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ancap.p2pmarkets.P2PMarketsAPI;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Slash command interface for the marketplace.
 *
 * <pre>
 *   /market browse                        — open the GUI listings browser
 *   /market list [seller]                 — text list of active listings (yours if no arg)
 *   /market sell &lt;itemId&gt; &lt;qty&gt; &lt;price&gt;   — create a new listing, items removed from inv
 *   /market buy &lt;listingId&gt;               — purchase a listing (token payout queued to seller)
 *   /market cancel &lt;listingId&gt;            — cancel your own listing, items refunded
 * </pre>
 */
public class MarketCommand extends AbstractPlayerCommand {

    private final RequiredArg<String> argsArg;

    public MarketCommand() {
        super("market", "P2P marketplace. Usage: /market <browse|list|sell|buy|cancel> ...");
        this.setPermissionGroup(GameMode.Adventure);
        argsArg = withRequiredArg("args", "Subcommand and parameters", ArgTypes.GREEDY_STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> playerRef,
                           @Nonnull PlayerRef pRef,
                           @Nonnull World world) {

        String raw = argsArg.get(ctx);
        String[] parts = (raw == null) ? new String[0] : raw.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) { showHelp(ctx); return; }

        String sub = parts[0].toLowerCase();
        String playerName = pRef.getUsername();

        switch (sub) {
            case "help":   showHelp(ctx); break;
            case "browse": cmdBrowse(ctx, store, playerRef, pRef); break;
            case "list":   cmdList(ctx, parts.length > 1 ? parts[1] : playerName); break;
            case "sell":   cmdSell(ctx, store, playerRef, playerName, parts); break;
            case "buy":    cmdBuy(ctx, store, playerRef, playerName, parts); break;
            case "cancel": cmdCancel(ctx, store, playerRef, playerName, parts); break;
            default:       reply(ctx, "Unknown subcommand: " + sub); showHelp(ctx);
        }
    }

    // ── Subcommand handlers ──────────────────────────────────────────────────

    private void showHelp(CommandContext ctx) {
        reply(ctx, "===== P2P Market =====");
        reply(ctx, "/market browse                       open GUI browser");
        reply(ctx, "/market list [seller]                text list (yours if no arg)");
        reply(ctx, "/market sell <itemId> <qty> <price>  create a listing");
        reply(ctx, "/market buy <listingId>              purchase a listing");
        reply(ctx, "/market cancel <listingId>           cancel your listing");
    }

    private void cmdBrowse(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> playerRef, PlayerRef pRef) {
        Player player = store.getComponent(playerRef, Player.getComponentType());
        if (player == null) { reply(ctx, "Player component missing"); return; }
        try {
            MarketBrowsePage page = new MarketBrowsePage(pRef);
            player.getPageManager().openCustomPage(playerRef, store, page);
            reply(ctx, "Opening marketplace...");
        } catch (Exception e) {
            reply(ctx, "Could not open browser: " + e.getMessage());
        }
    }

    private void cmdList(CommandContext ctx, String seller) {
        List<MarketListing> listings = MarketRegistry.activeBy(seller);
        if (listings.isEmpty()) {
            reply(ctx, seller + " has no active listings.");
            return;
        }
        reply(ctx, "===== Listings by " + seller + " (" + listings.size() + ") =====");
        for (MarketListing l : listings) {
            reply(ctx, l.shortId() + "  " + l.quantity + "x " + l.itemId
                    + "  -  " + l.tokenPrice + " DRK");
        }
    }

    private void cmdSell(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> playerRef,
                          String seller, String[] parts) {
        if (parts.length < 4) {
            reply(ctx, "Usage: /market sell <itemId> <qty> <price>");
            return;
        }
        String itemId;
        int qty, price;
        try {
            itemId = parts[1];
            qty    = Integer.parseInt(parts[2]);
            price  = Integer.parseInt(parts[3]);
        } catch (NumberFormatException nfe) {
            reply(ctx, "Quantity and price must be integers");
            return;
        }
        if (qty <= 0 || price <= 0) { reply(ctx, "Quantity and price must be positive"); return; }

        int have = InventoryOps.count(store, playerRef, itemId);
        if (have < qty) {
            reply(ctx, "You only have " + have + " " + itemId + " (need " + qty + ")");
            return;
        }
        int removed = InventoryOps.removeItem(store, playerRef, itemId, qty);
        if (removed < qty) {
            // Race condition or partial removal — refund what we got and abort
            InventoryOps.addItem(store, playerRef, itemId, removed);
            reply(ctx, "Failed to remove items cleanly — listing aborted");
            return;
        }
        MarketListing listing = new MarketListing(seller, itemId, qty, price);
        MarketRegistry.create(listing);
        reply(ctx, "Listed " + qty + "x " + itemId + " for " + price + " DRK  (id: " + listing.shortId() + ")");
    }

    private void cmdBuy(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> playerRef,
                         String buyer, String[] parts) {
        if (parts.length < 2) { reply(ctx, "Usage: /market buy <listingId>"); return; }
        MarketListing l = MarketRegistry.get(parts[1]);
        if (l == null) { reply(ctx, "No listing matches: " + parts[1]); return; }
        if (l.status != MarketListing.Status.ACTIVE) {
            reply(ctx, "Listing is " + l.status.name().toLowerCase());
            return;
        }
        if (l.sellerName.equalsIgnoreCase(buyer)) {
            reply(ctx, "You can't buy your own listing — use /market cancel to take it down");
            return;
        }

        // Deliver item to buyer first; if their inventory is full, abort.
        boolean delivered = InventoryOps.addItem(store, playerRef, l.itemId, l.quantity);
        if (!delivered) {
            reply(ctx, "Your inventory is too full to receive " + l.quantity + "x " + l.itemId);
            return;
        }
        MarketRegistry.markSold(l, buyer);

        // Queue token payout to seller via P2P Markets API (bridge handles mint).
        // MVP: buyer's side payment is symbolic — needs wallet integration to be real.
        P2PMarketsAPI.emitPayout(l.sellerName, l.tokenPrice, "market_sale_" + l.shortId());

        reply(ctx, "Bought " + l.quantity + "x " + l.itemId
                + " from " + l.sellerName + " for " + l.tokenPrice + " DRK");
    }

    private void cmdCancel(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> playerRef,
                            String player, String[] parts) {
        if (parts.length < 2) { reply(ctx, "Usage: /market cancel <listingId>"); return; }
        MarketListing l = MarketRegistry.get(parts[1]);
        if (l == null) { reply(ctx, "No listing matches: " + parts[1]); return; }
        if (!l.sellerName.equalsIgnoreCase(player)) {
            reply(ctx, "That's not your listing");
            return;
        }
        if (l.status != MarketListing.Status.ACTIVE) {
            reply(ctx, "Listing already " + l.status.name().toLowerCase());
            return;
        }
        boolean refunded = InventoryOps.addItem(store, playerRef, l.itemId, l.quantity);
        if (!refunded) {
            reply(ctx, "Couldn't refund items (inv full?) — listing kept active");
            return;
        }
        MarketRegistry.cancel(l);
        reply(ctx, "Cancelled listing " + l.shortId() + " — " + l.quantity + "x " + l.itemId + " refunded");
    }

    private void reply(CommandContext ctx, String msg) {
        ctx.sendMessage(Message.raw(msg));
    }
}
