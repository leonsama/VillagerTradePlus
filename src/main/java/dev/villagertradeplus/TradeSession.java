package dev.villagertradeplus;
import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.InventoryView;

final class TradeSession {
    final UUID playerId, villagerId;
    final InventoryView view;
    final int windowId;
    final String profession, type;
    final DoublePress presses = new DoublePress();
    // Only immutable display offers cross into the Netty event loop.
    volatile List<Object> futureOffers;
    volatile int realCount;
    volatile boolean futureSelected;
    volatile boolean active = true;
    boolean rerollQueued;
    TradeSession(UUID playerId, UUID villagerId, InventoryView view, int windowId,
                 TradePlan plan, int realCount, List<Object> futureOffers) {
        this.playerId = playerId; this.villagerId = villagerId; this.view = view; this.windowId = windowId;
        profession = plan.profession(); type = plan.type(); this.realCount = realCount; this.futureOffers = futureOffers;
    }
}
