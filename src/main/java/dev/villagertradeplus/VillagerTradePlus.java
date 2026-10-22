package dev.villagertradeplus;

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

public final class VillagerTradePlus extends JavaPlugin implements Listener {
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> pendingResync = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<VillagerAcquireTradeEvent, byte[]> pendingAcquisitions = new IdentityHashMap<>();
    private final PlanGenerator generator = new PlanGenerator();
    private PlanStore store;
    private Paper263Adapter adapter;
    private long doubleMillis, cooldownMillis;
    private double distanceSquared;
    private boolean rerollEnabled, previewEnabled, doublePressEnabled;

    @Override public void onEnable() {
        try { ConfigMigration.copyLegacy(getDataFolder().toPath()); }
        catch (java.io.IOException error) {
            getLogger().log(Level.SEVERE, "Could not migrate legacy config; plugin disabled", error);
            getServer().getPluginManager().disablePlugin(this); return;
        }
        saveDefaultConfig(); getConfig().options().copyDefaults(true); readSettings();
        for (String conflict : List.of("TradePlus", "TradeCycle", "VillagerVision")) {
            if (getServer().getPluginManager().getPlugin(conflict) != null) {
                getLogger().severe("Remove conflicting plugin " + conflict + "; its features are included in VillagerTradePlus. Plugin disabled.");
                getServer().getPluginManager().disablePlugin(this); return;
            }
        }
        if (!Bukkit.getMinecraftVersion().equals("26.3")) {
            getLogger().severe("This build requires Paper 26.3; current version: " + Bukkit.getMinecraftVersion());
            getServer().getPluginManager().disablePlugin(this); return;
        }
        store = new PlanStore(this);
        try {
            adapter = new Paper263Adapter(sessions, this::queueResync, id -> Bukkit.getScheduler().runTask(this, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null) { player.closeInventory(); message(player, Messages.ADAPTER_FAILED, false); }
                getLogger().severe("Paper 26.3 packet adapter failed for " + id);
            }));
            for (Player player : Bukkit.getOnlinePlayers()) adapter.attach(player);
        } catch (ReflectiveOperationException | RuntimeException error) {
            getLogger().log(Level.SEVERE, "Could not enable Paper 26.3 adapter", error);
            getServer().getPluginManager().disablePlugin(this); return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::validateSessions, 10, 10);
        getLogger().info("VillagerTradePlus enabled: Paper 26.3 / Java 25; reroll=" + rerollEnabled + "; preview=" + previewEnabled);
        new LaunchCounter().recordIfEnabled(getConfig().getBoolean("launch-counter.enabled", true),
                task -> Thread.ofVirtual().name("VillagerTradePlus-launch-counter").start(task), getLogger()::fine);
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
        cooldownMillis = Math.clamp(getConfig().getLong("cycle-cooldown-millis", 400), 100, 60000);
        double distance = getConfig().getDouble("interaction-distance", 6);
        if (!Double.isFinite(distance)) distance = 6;
        distance = Math.clamp(distance, 1, 16); distanceSquared = distance * distance;
        doublePressEnabled = getConfig().getBoolean("double-press-enabled", false);
        removeLegacyLanguage(getConfig());
        saveConfig();
    }
    private static boolean hasPermission(CommandSender sender, String suffix) {
        String current = "villagertradeplus." + suffix;
        String legacy = "tradeplus." + suffix;
        boolean explicitCurrent = sender.getEffectivePermissions().stream()
                .anyMatch(info -> info.getPermission().equals(current) && info.getAttachment() != null);
        return sender.isPermissionSet(legacy) && !explicitCurrent
                ? sender.hasPermission(legacy) : sender.hasPermission(current);
    }
    static void removeLegacyLanguage(org.bukkit.configuration.file.FileConfiguration config) {
        config.set("language.console-chinese", null);
        var language = config.getConfigurationSection("language");
        if (language != null && language.getKeys(false).isEmpty()) config.set("language", null);
    }
    static boolean coolingDown(long now, long last, long millis) {
        return now - last < millis * 1_000_000L;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean chinese = Messages.isChinese(sender);
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!hasPermission(sender, "admin")) { sender.sendMessage(Component.text(Messages.NO_ADMIN_PERMISSION.text(chinese), NamedTextColor.RED)); return true; }
            reloadConfig(); readSettings();
            // Old windows may contain virtual offers; discard their sessions and queued actions.
            for (TradeSession session : List.copyOf(sessions.values())) {
                Player player = Bukkit.getPlayer(session.playerId);
                if (player != null && player.getOpenInventory() == session.view) player.closeInventory();
                else end(session.playerId);
            }
            sender.sendMessage(Component.text(Messages.CONFIG_RELOADED.text(chinese), NamedTextColor.GREEN)); return true;
        }
        sender.sendMessage(Component.text(Messages.STATUS.text(chinese, Messages.onOff(chinese, rerollEnabled),
                Messages.onOff(chinese, previewEnabled)), NamedTextColor.GOLD));
        if (rerollEnabled) sender.sendMessage(Component.text((doublePressEnabled ? Messages.DOUBLE_PRESS_HINT : Messages.SINGLE_PRESS_HINT).text(chinese), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(Messages.RELOAD_HINT.text(chinese), NamedTextColor.GRAY)); return true;
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        try { adapter.attach(event.getPlayer()); }
        catch (RuntimeException error) { getLogger().log(Level.SEVERE, "Could not attach player trade adapter", error); }
    }
    @EventHandler public void localeChanged(PlayerLocaleChangeEvent event) {
        Player player = event.getPlayer();
        TradeSession expected = sessions.get(player.getUniqueId());
        if (expected == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (sessions.get(player.getUniqueId()) != expected || player.getOpenInventory() != expected.view) return;
            Villager villager = villager(expected);
            if (!valid(player, villager, expected)) { player.closeInventory(); return; }
            refreshPreview(player, villager, expected);
        });
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
    private List<Object> displayOffers(TradePlan plan, int level, boolean chinese) {
        if (!previewEnabled) return List.of();
        var result = new ArrayList<Object>();
        for (var entry : new TreeMap<>(plan.levels()).entrySet()) {
            if (entry.getKey() <= level) continue;
            for (MerchantRecipe original : entry.getValue()) {
                var item = original.getResult().clone();
                var meta = item.getItemMeta();
                var lore = meta.lore() == null ? new ArrayList<Component>() : new ArrayList<>(meta.lore());
                lore.add(Component.text(Messages.UNLOCK_LORE.text(chinese, Messages.levelName(chinese, entry.getKey()),
                        String.valueOf(entry.getKey())), NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
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
                || !eligible(villager) || !hasPermission(player, "use") || (!rerollEnabled && !previewEnabled)) return;
        try {
            adapter.attach(player);
            var plan = sessionPlan(villager);
            if (store.locked(villager)) store.lock(villager);
            var session = new TradeSession(player.getUniqueId(), villager.getUniqueId(), event.getView(),
                    adapter.windowId(event.getView()), plan, villager.getRecipeCount(),
                    displayOffers(plan, villager.getVillagerLevel(), Messages.isChinese(player)));
            sessions.put(player.getUniqueId(), session);
        } catch (RuntimeException error) {
            getLogger().log(Level.WARNING, "Could not prepare villager trade preview " + villager.getUniqueId(), error);
            message(player, Messages.PREVIEW_UNAVAILABLE, false);
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
            if (session.presses.press(System.nanoTime(), doubleMillis, doublePressEnabled)) {
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
                && hasPermission(player, "use") && player.getWorld().equals(villager.getWorld())
                && player.getLocation().distanceSquared(villager.getLocation()) <= distanceSquared
                && session.profession.equals(villager.getProfession().getKey().toString())
                && session.type.equals(villager.getVillagerType().getKey().toString())
                && (villager.getTrader() == null || villager.getTrader().getUniqueId().equals(player.getUniqueId()));
    }
    private void reroll(Player player, TradeSession session) {
        if (!rerollEnabled) { message(player, Messages.REROLL_DISABLED, false); return; }
        var villager = villager(session);
        if (!valid(player, villager, session)) { player.closeInventory(); return; }
        if (!hasPermission(player, "cycle")) { message(player, Messages.NO_REROLL_PERMISSION, false); return; }
        if (store.locked(villager)) { store.lock(villager); message(player, Messages.VILLAGER_LOCKED, false); return; }
        long now = System.nanoTime();
        Long last = cooldowns.get(villager.getUniqueId());
        if (last != null && coolingDown(now, last, cooldownMillis)) { message(player, Messages.REROLL_COOLDOWN, false); return; }
        try {
            var generated = generator.generate(villager, true);
            var bytes = PlanStore.encode(generated.plan());
            var display = displayOffers(generated.plan(), villager.getVillagerLevel(), Messages.isChinese(player));
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
            cooldowns.put(villager.getUniqueId(), now); message(player, Messages.REROLL_DONE, true);
        } catch (RuntimeException error) {
            getLogger().log(Level.WARNING, "Villager trade reroll failed " + session.villagerId, error);
            player.closeInventory(); message(player, Messages.REROLL_FAILED, false);
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
            event.setCancelled(true); getLogger().log(Level.SEVERE, "Could not fulfill trade plan; new trade blocked", error);
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
                refreshPreview(player, villager, session);
            }
        }
    }
    private void refreshPreview(Player player, Villager villager, TradeSession session) {
        try {
            session.futureOffers = previewEnabled
                    ? displayOffers(plan(villager), villager.getVillagerLevel(), Messages.isChinese(player)) : List.of();
            adapter.refresh(player, villager, session);
        } catch (RuntimeException error) {
            player.closeInventory(); getLogger().log(Level.WARNING, "Could not refresh trades", error);
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
    private static void message(Player player, Messages text, boolean success) {
        player.sendActionBar(Component.text(text.text(Messages.isChinese(player)), success ? NamedTextColor.GREEN : NamedTextColor.RED));
        player.playSound(player.getLocation(), success ? Sound.UI_BUTTON_CLICK : Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }
}
