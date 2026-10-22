package dev.tradeplus;
import java.io.*;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Data follows the villager through normal world saves; no global file or stale cache. */
final class PlanStore {
    private final NamespacedKey planKey, lockKey;
    PlanStore(Plugin plugin) {
        planKey = new NamespacedKey(plugin, "plan_v1"); lockKey = new NamespacedKey(plugin, "traded");
    }
    TradePlan read(Villager villager) {
        byte[] bytes = raw(villager);
        if (bytes == null) return null;
        try { return decode(bytes); }
        catch (IOException | RuntimeException error) {
            throw new IllegalStateException("村民交易计划损坏，已保留原始数据：" + villager.getUniqueId(), error);
        }
    }
    byte[] raw(Villager villager) { return villager.getPersistentDataContainer().get(planKey, PersistentDataType.BYTE_ARRAY); }
    void restore(Villager villager, byte[] bytes) {
        if (bytes == null) villager.getPersistentDataContainer().remove(planKey);
        else villager.getPersistentDataContainer().set(planKey, PersistentDataType.BYTE_ARRAY, bytes);
    }
    void write(Villager villager, TradePlan plan) { restore(villager, encode(plan)); }
    boolean locked(Villager villager) {
        return villager.getPersistentDataContainer().has(lockKey, PersistentDataType.BYTE)
                || villager.getVillagerLevel() != 1 || villager.getVillagerExperience() != 0
                || villager.getRecipes().stream().anyMatch(recipe -> recipe.getUses() > 0);
    }
    void lock(Villager villager) { villager.getPersistentDataContainer().set(lockKey, PersistentDataType.BYTE, (byte) 1); }
    static byte[] encode(TradePlan plan) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeUTF(plan.profession()); out.writeUTF(plan.type());
            out.writeInt(plan.levels().size());
            for (var entry : new TreeMap<>(plan.levels()).entrySet()) {
                out.writeInt(entry.getKey()); out.writeInt(plan.cursors().getOrDefault(entry.getKey(), 0));
                out.writeInt(entry.getValue().size());
                for (var recipe : entry.getValue()) {
                    writeItem(out, recipe.getResult()); out.writeInt(recipe.getIngredients().size());
                    for (var ingredient : recipe.getIngredients()) writeItem(out, ingredient);
                    out.writeInt(recipe.getUses()); out.writeInt(recipe.getMaxUses());
                    out.writeBoolean(recipe.hasExperienceReward()); out.writeInt(recipe.getVillagerExperience());
                    out.writeFloat(recipe.getPriceMultiplier()); out.writeInt(recipe.getDemand());
                    out.writeInt(recipe.getSpecialPrice()); out.writeBoolean(recipe.shouldIgnoreDiscounts());
                }
            }
            out.flush();
            if (bytes.size() > 2_097_152) throw new IOException("交易计划超过 2 MiB");
            return bytes.toByteArray();
        } catch (IOException error) { throw new IllegalStateException("无法编码交易计划", error); }
    }
    static TradePlan decode(byte[] bytes) throws IOException {
        if (bytes.length > 2_097_152) throw new IOException("交易计划过大");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 1) throw new IOException("不支持的数据版本");
        String profession = in.readUTF(), type = in.readUTF();
        var levels = new HashMap<Integer, List<MerchantRecipe>>(); var cursors = new HashMap<Integer, Integer>();
        for (int count = bounded(in.readInt(), 0, 5); count > 0; count--) {
            int level = bounded(in.readInt(), 1, 5), cursor = in.readInt(), size = bounded(in.readInt(), 0, 128);
            if (levels.containsKey(level)) throw new IOException("重复等级");
            cursors.put(level, bounded(cursor, 0, size)); var trades = new ArrayList<MerchantRecipe>();
            for (int index = 0; index < size; index++) {
                var result = readItem(in); var ingredients = new ArrayList<ItemStack>();
                for (int n = bounded(in.readInt(), 1, 2); n > 0; n--) ingredients.add(readItem(in));
                var recipe = new MerchantRecipe(result, in.readInt(), in.readInt(), in.readBoolean(),
                        in.readInt(), in.readFloat(), in.readInt(), in.readInt(), in.readBoolean());
                recipe.setIngredients(ingredients); trades.add(recipe);
            }
            levels.put(level, List.copyOf(trades));
        }
        if (in.available() != 0) throw new IOException("多余的数据");
        return new TradePlan(profession, type, levels, cursors);
    }
    private static void writeItem(DataOutputStream out, ItemStack item) throws IOException {
        byte[] bytes = item.serializeAsBytes(); out.writeInt(bytes.length); out.write(bytes);
    }
    private static ItemStack readItem(DataInputStream in) throws IOException {
        int size = bounded(in.readInt(), 1, 1_048_576); byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException(); return ItemStack.deserializeBytes(bytes);
    }
    private static int bounded(int value, int min, int max) throws IOException {
        if (value < min || value > max) throw new IOException("无效的数据范围"); return value;
    }
}
