package dev.tradeplus;
import java.io.IOException;
import java.util.*;
import org.bukkit.inventory.MerchantRecipe;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradePlanTest {
    @Test void cursorAdvancesInOrderWithoutMutatingOldPlan() {
        var a = mock(MerchantRecipe.class); var b = mock(MerchantRecipe.class);
        var plan = new TradePlan("minecraft:librarian", "minecraft:plains", Map.of(2, List.of(a, b)), Map.of());
        assertSame(a, plan.next(2)); var advanced = plan.advance(2);
        assertSame(a, plan.next(2)); assertSame(b, advanced.next(2)); assertNull(advanced.advance(2).next(2));
    }
    @Test void missingOrEmptyLevelsHaveNoTrade() {
        var plan = new TradePlan("p", "t", Map.of(2, List.of()), Map.of());
        assertNull(plan.next(1)); assertNull(plan.next(2)); assertNull(plan.next(5));
    }
    @Test void cursorsAreLevelSpecific() {
        var recipe = mock(MerchantRecipe.class);
        var plan = new TradePlan("p", "t", Map.of(2, List.of(recipe), 3, List.of(recipe)), Map.of()).advance(2);
        assertNull(plan.next(2)); assertSame(recipe, plan.next(3));
    }
    @Test void metadataRoundTrip() throws IOException {
        var original = new TradePlan("minecraft:librarian", "minecraft:desert", Map.of(2, List.of()), Map.of(2, 0));
        assertEquals(original, PlanStore.decode(PlanStore.encode(original)));
    }
    @Test void rejectsTruncationUnknownVersionAndTrailingData() {
        assertThrows(IOException.class, () -> PlanStore.decode(new byte[]{0, 0}));
        assertThrows(IOException.class, () -> PlanStore.decode(new byte[]{0, 0, 0, 2}));
        byte[] valid = PlanStore.encode(new TradePlan("p", "t", Map.of(), Map.of()));
        assertThrows(IOException.class, () -> PlanStore.decode(Arrays.copyOf(valid, valid.length + 1)));
    }
    @Test void rejectsOversizedData() {
        assertThrows(IOException.class, () -> PlanStore.decode(new byte[2_097_153]));
    }
}
