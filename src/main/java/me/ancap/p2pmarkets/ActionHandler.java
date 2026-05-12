package me.ancap.p2pmarkets;

/**
 * Implemented by other mods that want to handle custom action types
 * arriving from the bridge.
 *
 * Action lines in drk_queue.txt have the format:  TYPE|param1|param2|...
 *
 * Example registration:
 * <pre>
 *   P2PMarketsAPI.registerHandler("TRADE", (params) -> {
 *       // params[0] = seller, params[1] = buyer, params[2] = itemId, params[3] = price
 *       myMod.completeTrade(params[0], params[1], params[2], params[3]);
 *   });
 * </pre>
 */
@FunctionalInterface
public interface ActionHandler {
    /**
     * @param params the pipe-separated fields after the action type
     */
    void handle(String[] params);
}
