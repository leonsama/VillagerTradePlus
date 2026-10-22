package dev.villagertradeplus;

import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Villager;
import org.bukkit.event.*;
import org.bukkit.inventory.*;
import org.bukkit.potion.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Native server player + merchant menu + real packet listener, with an in-memory network transport. */
final class FakePlayerChecks {
    private static void expect(List<String> report, boolean condition, String text) {
        if (!condition) throw new AssertionError(text); report.add("PASS " + text);
    }
    private static Object field(Object instance, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    static void run(JavaPlugin plugin, Consumer<List<String>> complete) throws Exception {
        var report = new ArrayList<String>();
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        var world = ((CraftWorld) Bukkit.getWorlds().getFirst()).getHandle();
        UUID id = UUID.randomUUID();
        var nativePlayer = new ServerPlayer(server, world, new GameProfile(id, "VillagerTradePlusTest"), ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(); channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
        connection.channel = channel; connection.address = new InetSocketAddress("127.0.0.1", 25578); connection.preparing = false;
        var listener = new ServerGamePacketListenerImpl(server, connection, nativePlayer, CommonListenerCookie.createInitial(nativePlayer.getGameProfile(), false));
        nativePlayer.connection = listener;
        nativePlayer.valid = true;
        server.getPlayerList().getPlayersByUUID().put(id, nativePlayer);
        var player = nativePlayer.getBukkitEntity(); nativePlayer.setPos(0.5, 70, 0.5);
        var store = new PlanStore(plugin);
        Villager villager = player.getWorld().spawn(player.getLocation().add(1, 0, 0), Villager.class, entity -> {
            entity.setAdult(); entity.setAI(false); entity.setGravity(false); entity.setInvulnerable(true);
            entity.setProfession(Villager.Profession.LIBRARIAN);
        });
        Runnable cleanup = () -> {
            player.closeInventory(); villager.remove(); server.getPlayerList().getPlayersByUUID().remove(id);
            channel.finishAndReleaseAll();
        };
        try {
            displayIsolationChecks(plugin, report);
            configurationChecks(plugin, report, nativePlayer, listener, store);
            expect(report, !(boolean) field(plugin, VillagerTradePlus.class, "doublePressEnabled"), "default mode is single F");
            plugin.getConfig().set("double-press-enabled", true);
            features(plugin, true, true);
            player.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 1200, 0));
            player.openMerchant(villager, false);
            @SuppressWarnings("unchecked") var sessions = (Map<UUID, TradeSession>) field(plugin, VillagerTradePlus.class, "sessions");
            var session = sessions.get(id);
            expect(report, session != null && session.view == player.getOpenInventory(), "native merchant open attaches correct session and window");
            Object outgoing; ClientboundMerchantOffersPacket offers = null;
            while ((outgoing = channel.readOutbound()) != null) if (outgoing instanceof ClientboundMerchantOffersPacket packet) offers = packet;
            expect(report, offers != null && offers.getOffers().size() > villager.getRecipeCount(), "real merchant opening sends all future offers");
            var merchant = (MerchantMenu) nativePlayer.containerMenu;
            expect(report, !nativePlayer.isImmobile(), "native test player is mobile");
            expect(report, merchant.stillValid(nativePlayer), "native merchant is reachable");
            var validity = VillagerTradePlus.class.getDeclaredMethod("valid", org.bukkit.entity.Player.class, Villager.class, TradeSession.class);
            validity.setAccessible(true);
            nativePlayer.setPos(100, 70, 100);
            expect(report, !(boolean) validity.invoke(plugin, player, villager, session), "distant player cannot operate villager session");
            nativePlayer.setPos(0.5, 70, 0.5);
            var denied = player.addAttachment(plugin, "tradeplus.use", false);
            expect(report, !(boolean) validity.invoke(plugin, player, villager, session), "revoked use permission invalidates session");
            player.removeAttachment(denied);
            var other = new ServerPlayer(server, world, new GameProfile(UUID.randomUUID(), "OtherTrader"), ClientInformation.createDefault());
            var nativeVillager = ((org.bukkit.craftbukkit.entity.CraftVillager) villager).getHandle();
            nativeVillager.setTradingPlayer(other);
            expect(report, !(boolean) validity.invoke(plugin, player, villager, session), "another trader owns villager and cannot be displaced by reroll");
            nativeVillager.setTradingPlayer(nativePlayer);
            expect(report, previewLore(session).contains("Unlocks at"), "English client receives English unlock lore only");
            setLocale(nativePlayer, "zh_cn");
            var before = store.raw(villager);
            var top = player.getOpenInventory().getTopInventory();
            top.setItem(0, new ItemStack(Material.EMERALD, 13)); top.setItem(1, new ItemStack(Material.BOOK, 7));
            player.getInventory().setItemInOffHand(new ItemStack(Material.DIAMOND, 3));
            press(listener, merchant);
            expect(report, Arrays.equals(before, store.raw(villager)), "one native F packet does not reroll");
            expect(report, top.getItem(0).getAmount() == 13 && player.getInventory().getItemInOffHand().getType() == Material.DIAMOND,
                    "native F action is cancelled without swapping input and offhand");
            press(listener, merchant);
            expect(report, session.rerollQueued, "second native F packet queues exactly one reroll");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    expect(report, previewLore(session).contains("解锁") && !previewLore(session).contains("未来交易"),
                            "Chinese client receives concise Chinese unlock lore in open window");
                    setLocale(nativePlayer, "en_us");
                    expect(report, !Arrays.equals(before, store.raw(villager)), "two native F packets reroll current and future plans");
                    expect(report, top.getItem(0) == null && top.getItem(1) == null
                                    && player.getInventory().contains(Material.EMERALD, 13) && player.getInventory().contains(Material.BOOK, 7)
                                    && player.getInventory().getItemInOffHand().getAmount() == 3,
                            "reroll returns both inputs once and preserves offhand");
                    expect(report, nativePlayer.containerMenu == merchant && player.getOpenInventory() == session.view,
                            "reroll refreshes the same native merchant window");
                    expect(report, villager.getRecipes().stream().anyMatch(recipe -> recipe.getSpecialPrice() < 0),
                            "rerolled current offers reapply actual player Hero discount");
                    byte[] rerolled = store.raw(villager);
                    press(listener, merchant); press(listener, merchant);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            expect(report, previewLore(session).contains("Unlocks at"), "changing client language refreshes existing preview next tick");
                            expect(report, Arrays.equals(rerolled, store.raw(villager)), "native repeated double F respects villager cooldown");
                            // Reset the armed detector by closing; a press from a previous view cannot carry over.
                            player.closeInventory(); player.openMerchant(villager, false);
                            var reopened = (MerchantMenu) nativePlayer.containerMenu;
                            press(listener, reopened);
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                try {
                                    expect(report, Arrays.equals(rerolled, store.raw(villager)), "native close/reopen clears double-F sequence");
                                    plugin.getConfig().set("double-press-enabled", false);
                                    features(plugin, true, true);
                                    player.openMerchant(villager, false);
                                    @SuppressWarnings("unchecked") var cooldowns = (Map<UUID, Long>) field(plugin, VillagerTradePlus.class, "cooldowns");
                                    cooldowns.put(villager.getUniqueId(), System.nanoTime() - 400_000_001L);
                                    press(listener, (MerchantMenu) nativePlayer.containerMenu);
                                    expect(report, sessions.get(id).rerollQueued, "single F queues reroll immediately after mode reload");
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        try {
                                            expect(report, !Arrays.equals(rerolled, store.raw(villager)), "single native F rerolls after 400ms cooldown");
                                            nativeTradeChecks(plugin, report, nativePlayer, villager, store);
                                            villager.remove();
                                            expect(report, !(boolean) validity.invoke(plugin, player, villager, sessions.get(id)),
                                                    "removed villager invalidates existing session");
                                            cleanup.run(); complete.accept(report);
                                        } catch (Throwable error) { fail(plugin, cleanup, error); }
                                    }, 1);
                                } catch (Throwable error) { fail(plugin, cleanup, error); }
                            }, 1);
                        } catch (Throwable error) { fail(plugin, cleanup, error); }
                    }, 1);
                } catch (Throwable error) { fail(plugin, cleanup, error); }
            }, 1);
        } catch (Throwable error) { cleanup.run(); throw error; }
    }
    private static void displayIsolationChecks(JavaPlugin plugin, List<String> report) throws Exception {
        var item = new ItemStack(Material.DIAMOND_SWORD);
        var meta = item.getItemMeta();
        var originalLore = net.kyori.adventure.text.Component.text("Original lore");
        meta.lore(List.of(originalLore));
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
        item.setItemMeta(meta);
        var recipe = new MerchantRecipe(item, 10);
        recipe.setIngredients(List.of(new ItemStack(Material.EMERALD, 5)));
        var plan = new TradePlan("minecraft:librarian", "minecraft:plains", Map.of(2, List.of(recipe)), Map.of());
        var display = VillagerTradePlus.class.getDeclaredMethod("displayOffers", TradePlan.class, int.class, boolean.class);
        display.setAccessible(true);
        @SuppressWarnings("unchecked") var english = (List<Object>) display.invoke(plugin, plan, 1, false);
        @SuppressWarnings("unchecked") var chinese = (List<Object>) display.invoke(plugin, plan, 1, true);
        var en = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(
                ((net.minecraft.world.item.trading.MerchantOffer) english.getFirst()).getResult());
        var zh = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(
                ((net.minecraft.world.item.trading.MerchantOffer) chinese.getFirst()).getResult());
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        expect(report, en.getItemMeta().lore().size() == 2 && zh.getItemMeta().lore().size() == 2
                        && plain.serialize(en.getItemMeta().lore().get(1)).equals("Unlocks at Apprentice (Lv.2)")
                        && plain.serialize(zh.getItemMeta().lore().get(1)).equals("学徒（Lv.2）解锁"),
                "language-specific display copies remain isolated and append exactly one unlock line");
        expect(report, plain.serialize(en.getItemMeta().lore().getFirst()).equals("Original lore")
                        && en.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING) == 3
                        && zh.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING) == 3,
                "both display languages preserve original lore and enchantments");
        expect(report, recipe.getResult().equals(item) && recipe.getResult().getItemMeta().lore().size() == 1,
                "display generation never mutates persisted or actual trade results");
    }
    private static String previewLore(TradeSession session) {
        var offer = (net.minecraft.world.item.trading.MerchantOffer) session.futureOffers.getFirst();
        var item = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(offer.getResult());
        var lore = item.getItemMeta().lore();
        if (lore == null || lore.size() != 1) throw new AssertionError("Expected exactly one unlock lore line");
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(lore.getFirst());
    }
    private static void setLocale(ServerPlayer player, String locale) {
        var current = player.clientInformation();
        player.updateOptions(new ClientInformation(locale, current.viewDistance(), current.chatVisibility(),
                current.chatColors(), current.modelCustomisation(), current.mainHand(),
                current.textFilteringEnabled(), current.allowsListing(), current.particleStatus()));
    }
    private static void press(ServerGamePacketListenerImpl listener, MerchantMenu menu) {
        listener.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) 0,
                (byte) 40, ContainerInput.SWAP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
    }
    private static void features(JavaPlugin plugin, boolean reroll, boolean preview) {
        plugin.getConfig().set("features.reroll.enabled", reroll);
        plugin.getConfig().set("features.preview.enabled", preview);
        plugin.saveConfig();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "villagertradeplus reload");
    }
    private static void configurationChecks(JavaPlugin plugin, List<String> report, ServerPlayer nativePlayer,
                                            ServerGamePacketListenerImpl listener, PlanStore store) throws Exception {
        var player = nativePlayer.getBukkitEntity();
        Villager villager = player.getWorld().spawn(player.getLocation().add(1, 0, 1), Villager.class, entity -> {
            entity.setAdult(); entity.setAI(false); entity.setGravity(false); entity.setInvulnerable(true);
            entity.setProfession(Villager.Profession.LIBRARIAN);
        });
        @SuppressWarnings("unchecked") var sessions = (Map<UUID, TradeSession>) field(plugin, VillagerTradePlus.class, "sessions");
        try {
            for (boolean[] mode : List.of(new boolean[]{false, false}, new boolean[]{true, false},
                    new boolean[]{false, true}, new boolean[]{true, true})) {
                String name = "reroll=" + mode[0] + ",preview=" + mode[1];
                features(plugin, mode[0], mode[1]);
                expect(report, !sessions.containsKey(player.getUniqueId()), name + " reload discards old session and queued input");
                player.closeInventory(); // Both-disabled windows belong to vanilla, not TradePlus.
                player.openMerchant(villager, false);
                var session = sessions.get(player.getUniqueId());
                expect(report, (session != null) == (mode[0] || mode[1]), name + " manages only enabled feature sessions");
                if (session != null) expect(report, !session.futureOffers.isEmpty() == mode[1], name + " future offer visibility follows configuration");
                if (!mode[1]) expect(report, store.raw(villager) == null, name + " opening does not generate hidden future plans");
                var menu = (MerchantMenu) nativePlayer.containerMenu;
                var top = player.getOpenInventory().getTopInventory();
                top.setItem(0, new ItemStack(Material.EMERALD, 1));
                player.getInventory().setItemInOffHand(new ItemStack(Material.DIAMOND, 1));
                press(listener, menu);
                expect(report, player.getInventory().getItemInOffHand().getType() == (mode[0] ? Material.DIAMOND : Material.EMERALD),
                        name + " disabled reroll restores vanilla offhand action");
                press(listener, menu);
                expect(report, session == null || session.rerollQueued == mode[0], name + " double F queues reroll only when enabled");
            }
            var existing = store.read(villager);
            features(plugin, false, false);
            expect(report, player.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.MERCHANT,
                    "disabling both features closes existing virtual-offer window");
            int count = villager.getRecipeCount(); villager.increaseLevel(1);
            var added = villager.getRecipes().subList(count, villager.getRecipeCount());
            expect(report, added.size() == existing.levels().get(2).size()
                            && added.getFirst().getResult().equals(existing.levels().get(2).getFirst().getResult()),
                    "disabling preview still fulfills previously promised future trades");
        } finally {
            player.closeInventory(); villager.remove(); player.getInventory().clear();
            features(plugin, true, true);
        }
    }
    private static void nativeTradeChecks(JavaPlugin plugin, List<String> report, ServerPlayer nativePlayer, Villager villager, PlanStore store) {
        var player = nativePlayer.getBukkitEntity(); var menu = (MerchantMenu) nativePlayer.containerMenu;
        var inventory = player.getOpenInventory().getTopInventory();
        var recipe = villager.getRecipe(0); var costs = recipe.getIngredients();
        inventory.setItem(0, costs.getFirst().clone()); inventory.setItem(1, costs.size() > 1 ? costs.get(1).clone() : null);
        menu.setSelectionHint(0);
        expect(report, inventory.getItem(2) != null, "native unlocked recipe produces result normally");
        int xp = villager.getVillagerExperience();
        var cancellation = new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void cancel(io.papermc.paper.event.player.PlayerTradeEvent event) {
                if (event.getVillager().getUniqueId().equals(villager.getUniqueId())) event.setCancelled(true);
            }
        };
        Bukkit.getPluginManager().registerEvents(cancellation, plugin);
        menu.clicked(2, 0, ContainerInput.PICKUP, nativePlayer);
        expect(report, villager.getVillagerExperience() == xp && villager.getRecipe(0).getUses() == 0 && !store.locked(villager),
                "another plugin cancelling actual purchase prevents XP, uses, and permanent lock");
        HandlerList.unregisterAll(cancellation);
        inventory.setItem(0, costs.getFirst().clone()); inventory.setItem(1, costs.size() > 1 ? costs.get(1).clone() : null);
        menu.setSelectionHint(0);
        menu.clicked(2, 0, ContainerInput.PICKUP, nativePlayer);
        expect(report, villager.getVillagerExperience() > xp && villager.getRecipe(0).getUses() == 1,
                "native trade awards villager experience and consumes one use");
        expect(report, store.locked(villager), "actual PlayerTradeEvent permanently locks reroll");
        nativePlayer.containerMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
        for (int index = 0; index < costs.size(); index++) {
            var ingredient = costs.get(index).clone(); ingredient.setAmount(ingredient.getMaxStackSize()); inventory.setItem(index, ingredient);
        }
        menu.setSelectionHint(0); menu.clicked(2, 0, ContainerInput.QUICK_MOVE, nativePlayer);
        expect(report, villager.getRecipe(0).getUses() > 1 && player.getInventory().contains(recipe.getResult().getType()),
                "native Shift bulk purchase still works and increments actual recipe uses");
        byte[] plan = store.raw(villager);
        // Call the action directly here to verify the guard independently of the current cooldown.
        try {
            @SuppressWarnings("unchecked") var sessions = (Map<UUID, TradeSession>) field(plugin, VillagerTradePlus.class, "sessions");
            var method = VillagerTradePlus.class.getDeclaredMethod("reroll", org.bukkit.entity.Player.class, TradeSession.class);
            method.setAccessible(true); method.invoke(plugin, player, sessions.get(player.getUniqueId()));
        } catch (Exception error) { throw new IllegalStateException(error); }
        expect(report, Arrays.equals(plan, store.raw(villager)), "traded villager rejects reroll without altering plan");
    }
    private static void fail(JavaPlugin plugin, Runnable cleanup, Throwable error) {
        cleanup.run(); plugin.getLogger().log(java.util.logging.Level.SEVERE, "NATIVE PLAYER SMOKE FAILED", error);
        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 2);
    }
}
