package dev.tradeplus;

import io.netty.channel.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MerchantRecipe;

/** The only NMS boundary. Names and constructor signatures verified against Paper 26.3 build 211. */
final class Paper263Adapter implements AutoCloseable {
    private static final String HANDLER = "tradeplus";
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final Map<UUID, TradeSession> sessions;
    private final Consumer<UUID> resync, failure;
    private final Class<?> offersClass, offersPacket, selectPacket, clickPacket, slotPacket, contentPacket;
    private final Constructor<?> offersConstructor, slotConstructor, contentConstructor;
    private final Method fromBukkit, toMinecraft;
    private final Object emptyStack;

    Paper263Adapter(Map<UUID, TradeSession> sessions, Consumer<UUID> resync, Consumer<UUID> failure) throws ReflectiveOperationException {
        this.sessions = sessions; this.resync = resync; this.failure = failure;
        offersClass = Class.forName("net.minecraft.world.item.trading.MerchantOffers");
        offersPacket = packet("ClientboundMerchantOffersPacket"); selectPacket = packet("ServerboundSelectTradePacket");
        clickPacket = packet("ServerboundContainerClickPacket"); slotPacket = packet("ClientboundContainerSetSlotPacket");
        contentPacket = packet("ClientboundContainerSetContentPacket");
        var stackClass = Class.forName("net.minecraft.world.item.ItemStack");
        emptyStack = stackClass.getField("EMPTY").get(null);
        offersConstructor = offersPacket.getConstructor(int.class, offersClass, int.class, int.class, boolean.class, boolean.class);
        slotConstructor = slotPacket.getConstructor(int.class, int.class, int.class, stackClass);
        contentConstructor = contentPacket.getConstructor(int.class, int.class, List.class, stackClass);
        var recipeClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftMerchantRecipe");
        fromBukkit = recipeClass.getMethod("fromBukkit", MerchantRecipe.class);
        toMinecraft = recipeClass.getMethod("toMinecraft");
        // Fail at enable, not in the middle of a player's transaction.
        offersPacket.getMethod("getContainerId"); offersPacket.getMethod("getOffers");
        selectPacket.getMethod("getItem"); clickPacket.getMethod("containerId");
        Class.forName("org.bukkit.craftbukkit.inventory.CraftInventoryView").getMethod("getHandle");
    }
    private static Class<?> packet(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft.network.protocol.game." + name);
    }
    private static Object call(Object target, String method, Object... args) throws ReflectiveOperationException {
        if (args.length != 0) throw new IllegalArgumentException("No-argument method expected");
        return target.getClass().getMethod(method).invoke(target);
    }
    int windowId(InventoryView view) {
        try { return (int) call(view, "getHandle").getClass().getField("containerId").get(call(view, "getHandle")); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    Object nativeOffer(MerchantRecipe recipe) {
        try { return toMinecraft.invoke(fromBukkit.invoke(null, recipe)); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private Object nativeOffers(Collection<?> source) throws ReflectiveOperationException {
        Object result = offersClass.getConstructor().newInstance();
        @SuppressWarnings("unchecked") var list = (List<Object>) result;
        list.addAll(source); return result;
    }
    void attach(Player player) {
        try {
            Object handle = call(player, "getHandle");
            Object listener = handle.getClass().getField("connection").get(handle);
            Object connection = listener.getClass().getField("connection").get(listener);
            Channel channel = (Channel) connection.getClass().getField("channel").get(connection);
            if (channels.putIfAbsent(player.getUniqueId(), channel) != null) return;
            channel.pipeline().addBefore("packet_handler", HANDLER, handler(player.getUniqueId()));
        } catch (ReflectiveOperationException | RuntimeException error) {
            channels.remove(player.getUniqueId()); throw new IllegalStateException("无法安装 Paper 26.3 适配器", error);
        }
    }
    ChannelDuplexHandler handler(UUID playerId) {
        return new ChannelDuplexHandler() {
            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                TradeSession session = sessions.get(playerId);
                try {
                    if (session != null && session.active) message = outgoing(session, message);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    fail(session, playerId);
                }
                super.write(context, message, promise);
            }
            @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                TradeSession session = sessions.get(playerId);
                try {
                    if (session != null && session.active) {
                        if (selectPacket.isInstance(message)) {
                            int index = (int) call(message, "getItem");
                            session.futureSelected = index < 0 || index >= session.realCount;
                            if (session.futureSelected) { resync.accept(playerId); return; }
                        }
                        if (clickPacket.isInstance(message) && (int) call(message, "containerId") == session.windowId
                                && session.futureSelected) {
                            boolean offhand = call(message, "containerInput").toString().equals("SWAP")
                                    && (byte) call(message, "buttonNum") == 40 && (short) call(message, "slotNum") >= 0;
                            if (!offhand) { resync.accept(playerId); return; }
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    fail(session, playerId); return; // fail closed for an unparsed transaction
                }
                super.channelRead(context, message);
            }
        };
    }
    private void fail(TradeSession session, UUID playerId) {
        if (session != null && session.active) { session.active = false; failure.accept(playerId); }
    }
    private Object outgoing(TradeSession session, Object packet) throws ReflectiveOperationException {
        if (offersPacket.isInstance(packet) && (int) call(packet, "getContainerId") == session.windowId) {
            var real = (List<?>) call(packet, "getOffers");
            session.realCount = real.size();
            var all = new ArrayList<Object>(real); all.addAll(session.futureOffers);
            return offersConstructor.newInstance(session.windowId, nativeOffers(all), call(packet, "getVillagerLevel"),
                    call(packet, "getVillagerXp"), call(packet, "showProgress"), call(packet, "canRestock"));
        }
        if (session.futureSelected && slotPacket.isInstance(packet)
                && (int) call(packet, "getContainerId") == session.windowId && (int) call(packet, "getSlot") == 2)
            return slotConstructor.newInstance(session.windowId, call(packet, "getStateId"), 2, emptyStack);
        if (session.futureSelected && contentPacket.isInstance(packet) && (int) call(packet, "containerId") == session.windowId) {
            var items = new ArrayList<Object>((List<?>) call(packet, "items"));
            if (items.size() > 2) items.set(2, emptyStack);
            return contentConstructor.newInstance(session.windowId, call(packet, "stateId"), items, call(packet, "carriedItem"));
        }
        return packet;
    }
    void refresh(Player player, Villager villager, TradeSession session) {
        try {
            List<Object> real = villager.getRecipes().stream().map(this::nativeOffer).toList();
            Object packet = offersConstructor.newInstance(session.windowId, nativeOffers(real), villager.getVillagerLevel(),
                    villager.getVillagerExperience(), true, true);
            Object handle = call(player, "getHandle"), listener = handle.getClass().getField("connection").get(handle);
            listener.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet")).invoke(listener, packet);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    void resetSelection(TradeSession session) {
        try { Object menu = call(session.view, "getHandle"); menu.getClass().getMethod("setSelectionHint", int.class).invoke(menu, 0); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    void applyPlayerPrices(Player player, Villager villager) {
        try {
            Object handle = call(villager, "getHandle");
            handle.getClass().getMethod("updateSpecialPrices", Class.forName("net.minecraft.world.entity.player.Player"))
                    .invoke(handle, call(player, "getHandle"));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    void detach(UUID playerId) {
        Channel channel = channels.remove(playerId);
        if (channel != null) channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
        });
    }
    @Override public void close() { for (UUID id : List.copyOf(channels.keySet())) detach(id); }
}
