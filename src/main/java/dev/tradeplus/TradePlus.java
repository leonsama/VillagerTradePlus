package dev.tradeplus;

import io.papermc.paper.event.player.PlayerTradeEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class TradePlus extends JavaPlugin implements Listener {
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> pendingResync = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<VillagerAcquireTradeEvent, byte[]> pendingAcquisitions = new IdentityHashMap<>();
    private final PlanGenerator generator = new PlanGenerator();
    private PlanStore store;
    private Paper263Adapter adapter;
    private long doubleMillis, cooldownMillis;
    private double distanceSquared;
    private boolean rerollEnabled, previewEnabled;

    @Override public void onEnable() {
        saveDefaultConfig(); getConfig().options().copyDefaults(true); saveConfig(); readSettings();
        for (String conflict : List.of("TradeCycle", "VillagerVision")) {
            if (getServer().getPluginManager().getPlugin(conflict) != null) {
                getLogger().severe("请移除原插件 " + conflict + "，TradePlus 已包含其功能。已停止启用。");
                getServer().getPluginManager().disablePlugin(this); return;
            }
        }
        if (!Bukkit.getMinecraftVersion().equals("26.3")) {
            getLogger().severe("此构建仅支持 Paper 26.3；当前版本：" + Bukkit.getMinecraftVersion());
            getServer().getPluginManager().disablePlugin(this); return;
        }
        store = new PlanStore(this);
        try {
            adapter = new Paper263Adapter(sessions, this::queueResync, id -> Bukkit.getScheduler().runTask(this, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null) { player.closeInventory(); message(player, "交易界面适配失败，请查看服务器日志。", false); }
                getLogger().severe("Paper 26.3 packet adapter failed for " + id);
            }));
            for (Player player : Bukkit.getOnlinePlayers()) adapter.attach(player);
        } catch (ReflectiveOperationException | RuntimeException error) {
            getLogger().log(Level.SEVERE, "无法启用 Paper 26.3 适配器", error);
            getServer().getPluginManager().disablePlugin(this); return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::validateSessions, 10, 10);
        getLogger().info("TradePlus enabled: Paper 26.3 / Java 25; reroll=" + rerollEnabled + "; preview=" + previewEnabled);
    }
    @Override public void onDisable() {
        for (TradeSession session : List.copyOf(sessions.values())) {
            session.active = false;
            Player player = Bukkit.getPlayer(session.playerId);
            if (player != null && player.getOpenInventory() == session.view) player.closeInventory();
        }
        sessions.clear();
        if (adapter != null) adapter.close();
    }
    private void readSettings() {
        rerollEnabled = getConfig().getBoolean("features.reroll.enabled", true);
        previewEnabled = getConfig().getBoolean("features.preview.enabled", true);
        doubleMillis = Math.clamp(getConfig().getLong("double-press-millis", 350), 100, 1000);
        cooldownMillis = Math.clamp(getConfig().getLong("cycle-cooldown-millis", 500), 100, 60000);
        double distance = getConfig().getDouble("interaction-distance", 6);
        if (!Double.isFinite(distance)) distance = 6;
        distance = Math.clamp(distance, 1, 16); distanceSquared = distance * distance;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("tradeplus.admin")) { sender.sendMessage(Component.text("没有管理权限。", NamedTextColor.RED)); return true; }
            reloadConfig(); readSettings();
            // Old windows may contain virtual offers; discard their sessions and queued actions.
            for (TradeSession session : List.copyOf(sessions.values())) {
                Player player = Bukkit.getPlayer(session.playerId);
                if (player != null && player.getOpenInventory() == session.view) player.closeInventory();
                else end(session.playerId);
            }
            sender.sendMessage(Component.text("TradePlus 配置已重载。", NamedTextColor.GREEN)); return true;
        }
        sender.sendMessage(Component.text("TradePlus · 交易重刷：" + (rerollEnabled ? "开启" : "关闭")
                + " · 未来交易预览：" + (previewEnabled ? "开启" : "关闭"), NamedTextColor.GOLD));
        if (rerollEnabled) sender.sendMessage(Component.text("在交易界面的物品格上快速按两次 F 重刷。", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/tradeplus reload · 重载配置（需要管理权限）", NamedTextColor.GRAY)); return true;
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        try { adapter.attach(event.getPlayer()); }
        catch (RuntimeException error) { getLogger().log(Level.SEVERE, "无法连接玩家交易适配器", error); }
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        end(event.getPlayer().getUniqueId()); adapter.detach(event.getPlayer().getUniqueId());
    }
    private static boolean eligible(Villager villager) {
        return villager.isAdult() && villager.getProfession() != Villager.Profession.NONE
                && villager.getProfession() != Villager.Profession.NITWIT;
    }
    private TradePlan plan(Villager villager) {
        TradePlan plan = store.read(villager);
        if (plan == null || !plan.matches(villager)) {
            plan = generator.generate(villager, false).plan(); store.write(villager, plan);
        }
        return plan;
    }
    private TradePlan sessionPlan(Villager villager) {
        if (previewEnabled) return plan(villager);
        TradePlan existing = store.read(villager);
        if (existing != null && existing.matches(villager)) return existing;
        // Reroll-only mode need not pre-generate a future plan when opening the window.
        return new TradePlan(villager.getProfession().getKey().toString(), villager.getVillagerType().getKey().toString(), Map.of(), Map.of());
    }
    private List<Object> displayOffers(TradePlan plan, int level) {
        if (!previewEnabled) return List.of();
        var result = new ArrayList<Object>();
        String[] names = {"", "新手", "学徒", "老手", "专家", "大师"};
        for (var entry : new TreeMap<>(plan.levels()).entrySet()) {
            if (entry.getKey() <= level) continue;
            for (MerchantRecipe original : entry.getValue()) {
                var item = original.getResult().clone();
                var meta = item.getItemMeta();
                var lore = meta.lore() == null ? new ArrayList<Component>() : new ArrayList<>(meta.lore());
                lore.add(Component.text("解锁：" + names[entry.getKey()] + "（Lv." + entry.getKey() + "）", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("未来交易 · 仅预览 · 显示基础价格", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore); item.setItemMeta(meta);
                var locked = new MerchantRecipe(item, 1, 1, false, 0, original.getPriceMultiplier(), 0, 0, true);
                locked.setIngredients(original.getIngredients().stream().map(ItemStack::clone).toList());
                result.add(adapter.nativeOffer(locked));
            }
        }
        return List.copyOf(result);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        end(player.getUniqueId());
        if (!(event.getInventory() instanceof MerchantInventory inventory)
                || !(inventory.getHolder() instanceof Villager villager)
                || !eligible(villager) || !player.hasPermission("tradeplus.use") || (!rerollEnabled && !previewEnabled)) return;
        try {
            adapter.attach(player);
            var plan = sessionPlan(villager);
            if (store.locked(villager)) store.lock(villager);
            var session = new TradeSession(player.getUniqueId(), villager.getUniqueId(), event.getView(),
                    adapter.windowId(event.getView()), plan, villager.getRecipeCount(), displayOffers(plan, villager.getVillagerLevel()));
            sessions.put(player.getUniqueId(), session);
        } catch (RuntimeException error) {
            getLogger().log(Level.WARNING, "无法准备村民交易预览 " + villager.getUniqueId(), error);
            message(player, "未来交易预览暂不可用，当前交易保持原样。", false);
        }
    }
    @EventHandler public void close(InventoryCloseEvent event) { end(event.getPlayer().getUniqueId()); }
    private void end(UUID id) {
        var session = sessions.remove(id);
        if (session != null) session.active = false;
        pendingResync.remove(id);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        var session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session == null || !session.active || event.getView() != session.view) return;
        if (rerollEnabled && event.getClick() == ClickType.SWAP_OFFHAND && event.getClickedInventory() != null) {
            boolean wasCancelled = event.isCancelled(); event.setCancelled(true);
            if (wasCancelled || session.rerollQueued) return;
            if (session.presses.press(System.nanoTime(), doubleMillis)) {
                session.rerollQueued = true;
                Bukkit.getScheduler().runTask(this, () -> {
                    session.rerollQueued = false;
                    Player player = Bukkit.getPlayer(session.playerId);
                    if (player != null && sessions.get(session.playerId) == session && player.getOpenInventory() == session.view)
                        reroll(player, session);
                });
            }
        } else if (session.futureSelected) event.setCancelled(true);
    }
    private Villager villager(TradeSession session) {
        Entity entity = Bukkit.getEntity(session.villagerId);
        return entity instanceof Villager villager ? villager : null;
    }
    private boolean valid(Player player, Villager villager, TradeSession session) {
        return session != null && session.active && villager != null && villager.isValid() && !villager.isDead() && eligible(villager)
                && player.hasPermission("tradeplus.use") && player.getWorld().equals(villager.getWorld())
                && player.getLocation().distanceSquared(villager.getLocation()) <= distanceSquared
                && session.profession.equals(villager.getProfession().getKey().toString())
                && session.type.equals(villager.getVillagerType().getKey().toString())
                && (villager.getTrader() == null || villager.getTrader().getUniqueId().equals(player.getUniqueId()));
    }
    private void reroll(Player player, TradeSession session) {
        if (!rerollEnabled) { message(player, "服务器已关闭交易重刷功能。", false); return; }
        var villager = villager(session);
        if (!valid(player, villager, session)) { player.closeInventory(); return; }
        if (!player.hasPermission("tradeplus.cycle")) { message(player, "没有重刷权限。", false); return; }
        if (store.locked(villager)) { store.lock(villager); message(player, "这个村民已经交易或升级，不能重刷。", false); return; }
        long now = System.nanoTime();
        Long last = cooldowns.get(villager.getUniqueId());
        if (last != null && now - last < cooldownMillis * 1_000_000L) { message(player, "重刷冷却中，请稍后再试。", false); return; }
        try {
            var generated = generator.generate(villager, true);
            var bytes = PlanStore.encode(generated.plan());
            var display = displayOffers(generated.plan(), villager.getVillagerLevel());
            var previous = Recipes.copyAll(villager.getRecipes()); var oldData = store.raw(villager);
            try { villager.setRecipes(generated.current()); adapter.applyPlayerPrices(player, villager); store.restore(villager, bytes); }
            catch (RuntimeException error) { villager.setRecipes(previous); store.restore(villager, oldData); throw error; }
            session.futureSelected = false; session.futureOffers = display; session.realCount = generated.current().size();
            // Return actual inputs once; the result slot is a derived preview and must never be returned.
            Inventory inventory = session.view.getTopInventory();
            for (int slot = 0; slot < 2; slot++) {
                ItemStack item = inventory.getItem(slot); inventory.setItem(slot, null);
                if (item != null && !item.isEmpty())
                    player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
            inventory.setItem(2, null); adapter.resetSelection(session); adapter.refresh(player, villager, session); player.updateInventory();
            cooldowns.put(villager.getUniqueId(), now); message(player, "已重刷当前及所有未来等级的交易。", true);
        } catch (RuntimeException error) {
            getLogger().log(Level.WARNING, "村民交易重刷失败 " + session.villagerId, error);
            player.closeInventory(); message(player, "重刷未能完成，请查看服务器日志。", false);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void acquire(VillagerAcquireTradeEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || generator.isSimulation(villager)) return;
        try {
            var plan = store.read(villager);
            if (plan == null || !plan.matches(villager)) return;
            var next = plan.next(villager.getVillagerLevel()); if (next == null) return;
            var recipe = Recipes.copy(next);
            byte[] old = store.raw(villager);
            store.write(villager, plan.advance(villager.getVillagerLevel()));
            pendingAcquisitions.put(event, old); event.setRecipe(recipe);
        } catch (RuntimeException error) {
            event.setCancelled(true); getLogger().log(Level.SEVERE, "无法兑现村民交易计划，已阻止新增交易", error);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void acquired(VillagerAcquireTradeEvent event) {
        byte[] old = pendingAcquisitions.remove(event);
        if (old == null || !(event.getEntity() instanceof Villager villager)) return;
        if (event.isCancelled()) store.restore(villager, old);
        else Bukkit.getScheduler().runTask(this, () -> refreshVillager(villager));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void traded(PlayerTradeEvent event) {
        if (event.getVillager() instanceof Villager villager) {
            store.lock(villager);
            Bukkit.getScheduler().runTask(this, () -> refreshVillager(villager));
        }
    }
    private void refreshVillager(Villager villager) {
        for (var session : sessions.values()) if (session.villagerId.equals(villager.getUniqueId())) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player != null && valid(player, villager, session)) {
                try {
                    session.futureOffers = previewEnabled ? displayOffers(plan(villager), villager.getVillagerLevel()) : List.of();
                    adapter.refresh(player, villager, session);
                } catch (RuntimeException error) { player.closeInventory(); getLogger().log(Level.WARNING, "无法刷新交易", error); }
            }
        }
    }
    private void queueResync(UUID id) {
        if (!isEnabled() || !pendingResync.add(id)) return;
        Bukkit.getScheduler().runTask(this, () -> {
            pendingResync.remove(id); var session = sessions.get(id); Player player = Bukkit.getPlayer(id);
            if (session != null && session.active && player != null && player.getOpenInventory() == session.view) player.updateInventory();
        });
    }
    private void validateSessions() {
        for (var session : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null || player.getOpenInventory() != session.view) end(session.playerId);
            else if (!valid(player, villager(session), session)) player.closeInventory();
        }
        long expiry = System.nanoTime() - Math.max(cooldownMillis * 1_000_000L, 60_000_000_000L);
        cooldowns.values().removeIf(time -> time < expiry);
    }
    private static void message(Player player, String text, boolean success) {
        player.sendActionBar(Component.text(text, success ? NamedTextColor.GREEN : NamedTextColor.RED));
        player.playSound(player.getLocation(), success ? Sound.UI_BUTTON_CLICK : Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }
}
