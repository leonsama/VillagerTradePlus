package dev.tradeplus;
import java.util.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;

record TradePlan(String profession, String type, Map<Integer, List<MerchantRecipe>> levels,
                 Map<Integer, Integer> cursors) {
    TradePlan { levels = Map.copyOf(levels); cursors = Map.copyOf(cursors); }
    boolean matches(Villager villager) {
        return profession.equals(villager.getProfession().getKey().toString())
                && type.equals(villager.getVillagerType().getKey().toString());
    }
    MerchantRecipe next(int level) {
        var trades = levels.getOrDefault(level, List.of());
        int index = cursors.getOrDefault(level, 0);
        return index < trades.size() ? trades.get(index) : null;
    }
    TradePlan advance(int level) {
        var next = new HashMap<>(cursors);
        next.merge(level, 1, Integer::sum);
        return new TradePlan(profession, type, levels, next);
    }
}
