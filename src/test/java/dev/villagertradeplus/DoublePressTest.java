package dev.villagertradeplus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoublePressTest {
    @Test void singleModeTriggersImmediatelyAndClearsArmedPair() {
        var detector = new DoublePress();
        assertFalse(detector.press(0, 350, true));
        assertTrue(detector.press(10, 350, false));
        assertTrue(detector.press(20, 350, false));
        assertFalse(detector.press(30, 350, true));
        assertTrue(detector.press(40, 350, true));
    }
    @Test void cooldownBoundaryIs400Millis() {
        assertTrue(VillagerTradePlus.coolingDown(399_999_999, 0, 400));
        assertFalse(VillagerTradePlus.coolingDown(400_000_000, 0, 400));
        assertFalse(VillagerTradePlus.coolingDown(400_000_001, 0, 400));
    }
    @Test void singlePressDoesNothing() { assertFalse(new DoublePress().press(0, 350)); }
    @Test void pairWithinWindowTriggersOnce() {
        var detector = new DoublePress();
        assertFalse(detector.press(0, 350)); assertTrue(detector.press(200_000_000, 350));
        assertFalse(detector.press(210_000_000, 350));
    }
    @Test void exactBoundaryAccepted() {
        var detector = new DoublePress(); detector.press(0, 350); assertTrue(detector.press(350_000_000, 350));
    }
    @Test void expiredPressStartsNewPair() {
        var detector = new DoublePress(); detector.press(0, 350);
        assertFalse(detector.press(400_000_000, 350)); assertTrue(detector.press(500_000_000, 350));
    }
    @Test void closingOrReloadResetsPair() {
        var detector = new DoublePress(); detector.press(0, 350); detector.reset(); assertFalse(detector.press(1, 350));
    }
    @Test void separateWindowsNeverSharePresses() {
        new DoublePress().press(0, 350); assertFalse(new DoublePress().press(10, 350));
    }
    @Test void backwardClockDoesNotTrigger() {
        var detector = new DoublePress(); detector.press(100, 350); assertFalse(detector.press(50, 350));
    }
}
