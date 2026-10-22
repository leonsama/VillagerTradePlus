package dev.villagertradeplus;

import io.netty.channel.embedded.EmbeddedChannel;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Runs inside the tested plugin's classloader, against the real Paper server and NMS classes. */
public final class SmokeChecks {
    private static int passed;
    private static final List<String> report = new ArrayList<>();
    private static final Map<UUID, Villager> fixtures = new HashMap<>();
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        passed++; report.add("PASS " + description);
    }
    public static void run(JavaPlugin plugin) throws Exception {
        passed = 0; report.clear();
        var store = new PlanStore(plugin); var generator = new PlanGenerator();
        World world = Bukkit.getWorlds().getFirst();
        Location location = new Location(world, 0.5, 70, 0.5);
        world.getChunkAt(location).load();
        var professions = List.of(Villager.Profession.ARMORER, Villager.Profession.BUTCHER,
                Villager.Profession.CARTOGRAPHER, Villager.Profession.CLERIC, Villager.Profession.FARMER,
                Villager.Profession.FISHERMAN, Villager.Profession.FLETCHER, Villager.Profession.LEATHERWORKER,
                Villager.Profession.LIBRARIAN, Villager.Profession.MASON, Villager.Profession.SHEPHERD,
                Villager.Profession.TOOLSMITH, Villager.Profession.WEAPONSMITH);
        for (var profession : professions) {
            Villager villager = world.spawn(location, Villager.class, entity -> {
                entity.setAdult(); entity.setAI(false); entity.setInvulnerable(true); entity.setProfession(profession);
            });
            try {
                var original = Recipes.copyAll(villager.getRecipes());
                var generated = generator.generate(villager, false);
                check(villager.getVillagerLevel() == 1 && villager.getVillagerExperience() == 0
                        && same(original, villager.getRecipes()), profession + " detached simulation preserves actual state");
                store.write(villager, generated.plan());
                var reloaded = store.read(villager);
                check(reloaded.matches(villager) && reloaded.levels().size() == 4, profession + " PDC metadata round trip");
                for (int level = 2; level <= 5; level++) {
                    int count = villager.getRecipeCount();
                    villager.increaseLevel(1);
                    check(same(reloaded.levels().get(level), villager.getRecipes().subList(count, villager.getRecipeCount())),
                            profession + " actual upgrade fulfills planned level " + level);
                }
            } finally { villager.remove(); }
        }
        Villager source = world.spawn(location, Villager.class, entity -> {
            entity.setAdult(); entity.setAI(false); entity.setProfession(Villager.Profession.LIBRARIAN);
        });
        try {
            var generated = generator.generate(source, true);
            check(!generated.current().isEmpty() && generated.plan().levels().size() == 4, "full reroll candidate covers current and future levels");
            source.setRecipes(generated.current()); store.write(source, generated.plan());
            var legacyPlan = java.util.Objects.requireNonNull(NamespacedKey.fromString("tradeplus:plan_v1"));
            var legacyLock = java.util.Objects.requireNonNull(NamespacedKey.fromString("tradeplus:traded"));
            var currentPlan = new NamespacedKey(plugin, "plan_v1");
            var currentLock = new NamespacedKey(plugin, "traded");
            var data = source.getPersistentDataContainer();
            byte[] saved = store.raw(source);
            data.remove(currentPlan);
            data.set(legacyPlan, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, saved);
            data.set(legacyLock, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            check(Arrays.equals(saved, store.raw(source)) && store.locked(source), "legacy namespace preserves trade plan and permanent lock");
            store.restore(source, saved); store.lock(source);
            check(data.has(currentPlan) && data.has(currentLock) && !data.has(legacyPlan) && !data.has(legacyLock),
                    "successful writes migrate old villager keys to villagertradeplus namespace");
            data.remove(currentLock);

            var cancellation = new Listener() {
                @EventHandler(priority = EventPriority.HIGHEST)
                public void cancel(VillagerAcquireTradeEvent event) {
                    if (event.getEntity().getUniqueId().equals(source.getUniqueId())) event.setCancelled(true);
                }
            };
            Bukkit.getPluginManager().registerEvents(cancellation, plugin);
            byte[] before = store.raw(source);
            source.increaseLevel(1);
            check(Arrays.equals(before, store.raw(source)), "another plugin cancelling acquisition does not consume planned cursor");
            HandlerList.unregisterAll(cancellation);
            source.setVillagerLevel(1);
            check(!store.locked(source), "fresh villager can reroll");
            store.lock(source); source.setVillagerExperience(0);
            source.getRecipes().forEach(recipe -> recipe.setUses(0));
            check(store.locked(source), "persistent trade lock survives zero experience and restock");
            source.setProfession(Villager.Profession.FARMER);
            check(!store.read(source).matches(source), "profession change invalidates old preview plan");
            source.setProfession(Villager.Profession.LIBRARIAN); source.setVillagerType(Villager.Type.DESERT);
            check(!store.read(source).matches(source), "villager type change invalidates old preview plan");
            source.setVillagerType(Villager.Type.PLAINS);
            packetChecks(source, generated.plan());
        } finally { source.remove(); }
        Location distant = new Location(world, 1600.5, 70, 1600.5);
        Villager chunkVillager = world.spawn(distant, Villager.class, entity -> {
            entity.setAdult(); entity.setAI(false); entity.setGravity(false); entity.setPersistent(true);
            entity.setProfession(Villager.Profession.LIBRARIAN);
        });
        UUID chunkVillagerId = chunkVillager.getUniqueId();
        store.write(chunkVillager, generator.generate(chunkVillager, false).plan()); store.lock(chunkVillager);
        byte[] chunkBytes = store.raw(chunkVillager);
        store.restore(chunkVillager, new byte[]{0, 0, 0, 99});
        boolean rejected = false;
        try { store.read(chunkVillager); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && Arrays.equals(store.raw(chunkVillager), new byte[]{0, 0, 0, 99}), "corrupted PDC plan rejected without replacing original data");
        store.restore(chunkVillager, chunkBytes);
        world.save();
        check(world.unloadChunk(100, 100, true), "isolated villager chunk unloads successfully");
        var reloadedChunk = world.getChunkAt(100, 100);
        Villager chunkReloaded = Arrays.stream(reloadedChunk.getEntities()).filter(entity -> entity.getUniqueId().equals(chunkVillagerId))
                .map(entity -> (Villager) entity).findFirst().orElseThrow();
        check(Arrays.equals(chunkBytes, store.raw(chunkReloaded)) && store.locked(chunkReloaded), "chunk unload and reload preserve plan and permanent trade lock");
        chunkReloaded.remove();
        Path persisted = plugin.getDataFolder().toPath().resolve("smoke-persist.txt");
        if (Files.exists(persisted)) {
            var lines = Files.readAllLines(persisted);
            UUID id = UUID.fromString(lines.get(0));
            Villager existing = world.getEntitiesByClass(Villager.class).stream().filter(v -> v.getUniqueId().equals(id)).findFirst().orElseThrow();
            check(Base64.getEncoder().encodeToString(store.raw(existing)).equals(lines.get(1)) && store.locked(existing),
                    "world save and full server restart preserve plan bytes and trade lock");
        } else {
            Villager existing = world.spawn(location, Villager.class, entity -> {
                entity.setAdult(); entity.setAI(false); entity.setInvulnerable(true); entity.setPersistent(true);
                entity.setProfession(Villager.Profession.LIBRARIAN);
            });
            store.write(existing, generator.generate(existing, false).plan()); store.lock(existing);
            Files.write(persisted, List.of(existing.getUniqueId().toString(), Base64.getEncoder().encodeToString(store.raw(existing))));
            world.save();
        }
        FakePlayerChecks.run(plugin, results -> {
            report.addAll(results); passed += results.size();
            report.add("TOTAL " + passed + " passed");
            try { Files.write(Path.of("smoke-results.txt"), report); }
            catch (IOException error) { throw new UncheckedIOException(error); }
            plugin.getLogger().info("SMOKE SUCCESS: " + passed + " checks passed");
            Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 5);
        });
    }
    private static boolean same(List<MerchantRecipe> a, List<MerchantRecipe> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).getResult().equals(b.get(i).getResult()) || !a.get(i).getIngredients().equals(b.get(i).getIngredients())
                    || a.get(i).getMaxUses() != b.get(i).getMaxUses() || a.get(i).getPriceMultiplier() != b.get(i).getPriceMultiplier()) return false;
        return true;
    }
    private static void packetChecks(Villager source, TradePlan plan) throws Exception {
        UUID id = UUID.randomUUID(); var sessions = new ConcurrentHashMap<UUID, TradeSession>();
        var resync = new AtomicInteger(); var failures = new AtomicInteger();
        var adapter = new Paper263Adapter(sessions, ignored -> resync.incrementAndGet(), ignored -> failures.incrementAndGet());
        var locked = Recipes.copy(plan.levels().get(2).getFirst()); locked.setUses(1); locked.setMaxUses(1);
        var session = new TradeSession(id, source.getUniqueId(), null, 42, plan, source.getRecipeCount(), List.of(adapter.nativeOffer(locked)));
        sessions.put(id, session);
        var channel = new EmbeddedChannel(adapter.handler(id));
        try {
            Class<?> offersClass = Class.forName("net.minecraft.world.item.trading.MerchantOffers");
            @SuppressWarnings("unchecked") var real = (List<Object>) offersClass.getConstructor().newInstance();
            source.getRecipes().forEach(recipe -> real.add(adapter.nativeOffer(recipe)));
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket");
            Object packet = packetClass.getConstructor(int.class, offersClass, int.class, int.class, boolean.class, boolean.class)
                    .newInstance(42, real, 1, 0, true, true);
            channel.writeOutbound(packet); Object outgoing = channel.readOutbound();
            var displayed = (List<?>) packetClass.getMethod("getOffers").invoke(outgoing);
            check(displayed.size() == real.size() + 1 && real.size() == source.getRecipeCount(), "network preview appends without mutating actual offers");
            Object select = Class.forName("net.minecraft.network.protocol.game.ServerboundSelectTradePacket").getConstructor(int.class).newInstance(real.size());
            channel.writeInbound(select);
            check(channel.readInbound() == null && session.futureSelected && resync.get() == 1, "future trade selection never reaches native merchant");
            var click = new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(42, 1, (short) 2,
                    (byte) 0, net.minecraft.world.inventory.ContainerInput.PICKUP,
                    new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY);
            channel.writeInbound(click);
            check(channel.readInbound() == null, "forged result click while future selected is rejected before native processing");
            var offhand = new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(42, 1, (short) 0,
                    (byte) 40, net.minecraft.world.inventory.ContainerInput.SWAP,
                    new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY);
            channel.writeInbound(offhand);
            check(channel.readInbound() == offhand, "double-F detector remains reachable while browsing a future trade");
            Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack"); Object empty = stack.getField("EMPTY").get(null);
            Class<?> slot = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket");
            Object realResult = real.getFirst().getClass().getMethod("getResult").invoke(real.getFirst());
            channel.writeOutbound(slot.getConstructor(int.class, int.class, int.class, stack).newInstance(42, 1, 2, realResult));
            Object hiddenResult = channel.readOutbound();
            check(slot.getMethod("getItem").invoke(hiddenResult) == empty, "locked selection masks native result slot");
            Class<?> selectClass = select.getClass();
            channel.writeInbound(selectClass.getConstructor(int.class).newInstance(0));
            check(channel.readInbound() != null && !session.futureSelected, "unlocked trade selection remains vanilla");
            channel.writeInbound(selectClass.getConstructor(int.class).newInstance(-1));
            check(channel.readInbound() == null && session.futureSelected, "negative forged trade index rejected");
            session.active = false; channel.writeOutbound(packet);
            check(channel.readOutbound() == packet, "closed session stops rewriting packets");
            check(failures.get() == 0, "Paper 26.3 packet signatures verified at runtime");
        } finally { channel.finishAndReleaseAll(); adapter.close(); }
    }
    public static void fixture(JavaPlugin plugin, Player player, String action) throws Exception {
        if (action.equals("open")) {
            player.closeInventory(); player.getInventory().clear();
            player.teleport(new Location(player.getWorld(), 2.5, 70, 0.5));
            Villager old = fixtures.remove(player.getUniqueId()); if (old != null) old.remove();
            Villager villager = player.getWorld().spawn(player.getLocation().add(1, 0, 0), Villager.class, entity -> {
                entity.setAdult(); entity.setAI(false); entity.setGravity(false); entity.setProfession(Villager.Profession.LIBRARIAN);
            });
            fixtures.put(player.getUniqueId(), villager);
            player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 64));
            player.getInventory().setItem(1, new ItemStack(Material.BOOK, 64));
            player.getInventory().setItem(2, new ItemStack(Material.PAPER, 64));
            player.openMerchant(villager, false);
        } else {
            Villager villager = fixtures.get(player.getUniqueId());
            var store = new PlanStore(plugin);
            plugin.getLogger().info("FIXTURE " + action + " villager=" + villager.getUniqueId() + " level=" + villager.getVillagerLevel()
                    + " xp=" + villager.getVillagerExperience() + " offers=" + villager.getRecipeCount() + " lock=" + store.locked(villager));
            if (action.equals("upgrade")) villager.increaseLevel(1);
        }
    }
}
