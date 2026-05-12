package me.ancap.p2pmarkets.market;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Helpers for finding, removing, and adding items in a player's inventory.
 * Searches hotbar + backpack in order, stops as soon as quantity is satisfied.
 * Doesn't handle storage chests, equipment slots, or partial-stack merging on add.
 */
public final class InventoryOps {

    private InventoryOps() {}

    public static int removeItem(Store<EntityStore> store, Ref<EntityStore> playerRef,
                                  String itemId, int quantity) {
        int remaining = quantity;
        InventoryComponent.Hotbar hot = store.getComponent(playerRef, InventoryComponent.Hotbar.getComponentType());
        if (hot != null) remaining -= removeFromContainer(hot.getInventory(), itemId, remaining);
        if (remaining <= 0) return quantity;
        InventoryComponent.Backpack bp = store.getComponent(playerRef, InventoryComponent.Backpack.getComponentType());
        if (bp != null) remaining -= removeFromContainer(bp.getInventory(), itemId, remaining);
        return quantity - Math.max(remaining, 0);
    }

    public static boolean addItem(Store<EntityStore> store, Ref<EntityStore> playerRef,
                                   String itemId, int quantity) {
        int remaining = quantity;
        InventoryComponent.Hotbar hot = store.getComponent(playerRef, InventoryComponent.Hotbar.getComponentType());
        if (hot != null) remaining = addToContainer(hot.getInventory(), itemId, remaining);
        if (remaining <= 0) return true;
        InventoryComponent.Backpack bp = store.getComponent(playerRef, InventoryComponent.Backpack.getComponentType());
        if (bp != null) remaining = addToContainer(bp.getInventory(), itemId, remaining);
        return remaining <= 0;
    }

    public static int count(Store<EntityStore> store, Ref<EntityStore> playerRef, String itemId) {
        int total = 0;
        InventoryComponent.Hotbar hot = store.getComponent(playerRef, InventoryComponent.Hotbar.getComponentType());
        if (hot != null) total += countInContainer(hot.getInventory(), itemId);
        InventoryComponent.Backpack bp = store.getComponent(playerRef, InventoryComponent.Backpack.getComponentType());
        if (bp != null) total += countInContainer(bp.getInventory(), itemId);
        return total;
    }

    // ── Container helpers ────────────────────────────────────────────────────

    private static int removeFromContainer(ItemContainer c, String itemId, int needed) {
        if (c == null || needed <= 0) return 0;
        int removed = 0;
        short cap = c.getCapacity();
        for (short s = 0; s < cap && removed < needed; s++) {
            try {
                ItemStack stack = c.getItemStack(s);
                if (stack == null || stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (item == null || !itemId.equalsIgnoreCase(item.getId())) continue;
                int take = Math.min(stack.getQuantity(), needed - removed);
                int newQ = stack.getQuantity() - take;
                c.setItemStackForSlot(s, newQ <= 0 ? ItemStack.EMPTY : stack.withQuantity(newQ));
                removed += take;
            } catch (Exception ignored) {}
        }
        return removed;
    }

    private static int countInContainer(ItemContainer c, String itemId) {
        if (c == null) return 0;
        int total = 0;
        short cap = c.getCapacity();
        for (short s = 0; s < cap; s++) {
            try {
                ItemStack stack = c.getItemStack(s);
                if (stack == null || stack.isEmpty()) continue;
                Item item = stack.getItem();
                if (item != null && itemId.equalsIgnoreCase(item.getId())) {
                    total += stack.getQuantity();
                }
            } catch (Exception ignored) {}
        }
        return total;
    }

    /**
     * Place into first empty slot. Doesn't merge onto partial stacks (MVP).
     * Returns remaining unplaced quantity.
     */
    private static int addToContainer(ItemContainer c, String itemId, int quantity) {
        if (c == null || quantity <= 0) return quantity;
        short cap = c.getCapacity();
        for (short s = 0; s < cap && quantity > 0; s++) {
            try {
                ItemStack existing = c.getItemStack(s);
                if (existing != null && !existing.isEmpty()) continue;
                // ItemStack constructor accepts itemId string directly
                int placeQty = Math.min(quantity, 64); // conservative stack cap; refined post-MVP
                c.setItemStackForSlot(s, new ItemStack(itemId, placeQty));
                quantity -= placeQty;
            } catch (Exception ignored) {
                return quantity;
            }
        }
        return quantity;
    }
}
