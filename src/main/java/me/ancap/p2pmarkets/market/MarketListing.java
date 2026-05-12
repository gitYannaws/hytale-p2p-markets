package me.ancap.p2pmarkets.market;

import java.util.UUID;

/**
 * A single market listing. Sellers create one of these when listing an item;
 * buyers pay the {@code tokenPrice} in DRK tokens and the item is delivered.
 *
 * Persisted as JSON in {@code ~/p2p_markets_listings.json}.
 */
public class MarketListing {

    public enum Status { ACTIVE, SOLD, CANCELLED }

    public final String  id;             // UUID
    public final String  sellerName;     // Hytale player name
    public final String  itemId;         // e.g. "Ore_Prisma"
    public final int     quantity;
    public final int     tokenPrice;     // DRK tokens
    public final long    createdAt;      // epoch millis
    public Status        status;
    public String        buyerName;      // null until sold
    public long          soldAt;         // 0 until sold

    public MarketListing(String sellerName, String itemId, int quantity, int tokenPrice) {
        this.id         = UUID.randomUUID().toString();
        this.sellerName = sellerName;
        this.itemId     = itemId;
        this.quantity   = quantity;
        this.tokenPrice = tokenPrice;
        this.createdAt  = System.currentTimeMillis();
        this.status     = Status.ACTIVE;
    }

    // For JSON deserialization
    public MarketListing(String id, String sellerName, String itemId, int quantity,
                         int tokenPrice, long createdAt, Status status,
                         String buyerName, long soldAt) {
        this.id         = id;
        this.sellerName = sellerName;
        this.itemId     = itemId;
        this.quantity   = quantity;
        this.tokenPrice = tokenPrice;
        this.createdAt  = createdAt;
        this.status     = status;
        this.buyerName  = buyerName;
        this.soldAt     = soldAt;
    }

    public String shortId() { return id.substring(0, 8); }

    /** Single-line JSON encoding. No external library — fields are simple types. */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
          .append("\"id\":\"").append(id).append('"').append(',')
          .append("\"seller\":\"").append(escape(sellerName)).append('"').append(',')
          .append("\"itemId\":\"").append(escape(itemId)).append('"').append(',')
          .append("\"quantity\":").append(quantity).append(',')
          .append("\"tokenPrice\":").append(tokenPrice).append(',')
          .append("\"createdAt\":").append(createdAt).append(',')
          .append("\"status\":\"").append(status.name()).append('"').append(',')
          .append("\"buyer\":\"").append(buyerName == null ? "" : escape(buyerName)).append('"').append(',')
          .append("\"soldAt\":").append(soldAt)
          .append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Parse a one-line JSON record. Tolerant — assumes the writer-side format,
     * does not handle nested objects or escaped slashes.
     */
    public static MarketListing fromJson(String json) {
        try {
            String id          = grab(json, "id");
            String seller      = grab(json, "seller");
            String itemId      = grab(json, "itemId");
            int    quantity    = Integer.parseInt(grab(json, "quantity"));
            int    tokenPrice  = Integer.parseInt(grab(json, "tokenPrice"));
            long   createdAt   = Long.parseLong(grab(json, "createdAt"));
            Status status      = Status.valueOf(grab(json, "status"));
            String buyer       = grab(json, "buyer");
            long   soldAt      = Long.parseLong(grab(json, "soldAt"));
            return new MarketListing(id, seller, itemId, quantity, tokenPrice,
                    createdAt, status, buyer.isEmpty() ? null : buyer, soldAt);
        } catch (Exception e) {
            return null;
        }
    }

    private static String grab(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        i += needle.length();
        if (i >= json.length()) return "";
        // string value (in quotes)
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            return end < 0 ? "" : json.substring(i + 1, end);
        }
        // numeric value
        int end = i;
        while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
        return json.substring(i, end);
    }
}
