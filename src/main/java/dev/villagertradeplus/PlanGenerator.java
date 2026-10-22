package dev.villagertradeplus;
import java.util.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;

final class PlanGenerator {
    private final Set<UUID> simulations = new HashSet<>();
    boolean isSimulation(Villager villager) { return simulations.contains(villager.getUniqueId()); }
    record Generated(List<MerchantRecipe> current, TradePlan plan) {}
    Generated generate(Villager source, boolean reroll) {
        // VillagerVision's level simulation, on a detached entity instead of the real villager.
        Villager temporary = source.getWorld().createEntity(source.getLocation(), Villager.class);
        simulations.add(temporary.getUniqueId());
        try {
            temporary.setAdult();
            temporary.setVillagerType(source.getVillagerType());
            temporary.setProfession(source.getProfession());
            temporary.setVillagerLevel(source.getVillagerLevel());
            if (!reroll) temporary.setRecipes(Recipes.copyAll(source.getRecipes()));
            var current = Recipes.copyAll(temporary.getRecipes());
            if (current.isEmpty()) throw new IllegalStateException("No valid current trades generated");
            var future = new TreeMap<Integer, List<MerchantRecipe>>();
            int known = current.size();
            for (int level = source.getVillagerLevel() + 1; level <= 5; level++) {
                if (!temporary.increaseLevel(1)) throw new IllegalStateException("Could not simulate level " + level);
                var generated = temporary.getRecipes();
                if (generated.size() < known) throw new IllegalStateException("Simulated trade count unexpectedly decreased");
                future.put(level, Recipes.copyAll(generated.subList(known, generated.size())));
                known = generated.size();
            }
            return new Generated(current, new TradePlan(source.getProfession().getKey().toString(),
                    source.getVillagerType().getKey().toString(), future, Map.of()));
        } finally {
            simulations.remove(temporary.getUniqueId());
            // Never spawned: don't call CraftVillager.remove(), which releases world POIs.
        }
    }
}
