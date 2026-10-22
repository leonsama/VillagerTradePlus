package dev.villagertradeplus;
import java.util.List;
import org.bukkit.inventory.MerchantRecipe;

final class Recipes {
    private Recipes() {}
    // Adapted from VillagerVision; also preserve Paper's ignoreDiscounts property.
    static MerchantRecipe copy(MerchantRecipe source) {
        var copy = new MerchantRecipe(source.getResult().clone(), source.getUses(), source.getMaxUses(),
                source.hasExperienceReward(), source.getVillagerExperience(), source.getPriceMultiplier(),
                source.getDemand(), source.getSpecialPrice(), source.shouldIgnoreDiscounts());
        copy.setIngredients(source.getIngredients().stream().map(item -> item.clone()).toList());
        return copy;
    }
    static List<MerchantRecipe> copyAll(List<MerchantRecipe> source) {
        return source.stream().map(Recipes::copy).toList();
    }
}
