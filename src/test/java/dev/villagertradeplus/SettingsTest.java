package dev.villagertradeplus;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class SettingsTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    @Test void legacyConfigCopiedOnceWithoutOverwritingOrDeletingOriginal() throws Exception {
        var legacy = temporary.resolve("TradePlus/config.yml");
        var target = temporary.resolve("VillagerTradePlus");
        java.nio.file.Files.createDirectories(legacy.getParent());
        java.nio.file.Files.writeString(legacy, "cycle-cooldown-millis: 700");
        ConfigMigration.copyLegacy(target);
        assertEquals(java.nio.file.Files.readString(legacy), java.nio.file.Files.readString(target.resolve("config.yml")));
        java.nio.file.Files.writeString(target.resolve("config.yml"), "cycle-cooldown-millis: 400");
        ConfigMigration.copyLegacy(target);
        assertEquals("cycle-cooldown-millis: 400", java.nio.file.Files.readString(target.resolve("config.yml")));
        assertTrue(java.nio.file.Files.exists(legacy));
    }
    @Test void defaultsUseSinglePressAnd400Millis() throws Exception {
        var config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(config.getBoolean("double-press-enabled"));
        assertEquals(350, config.getLong("double-press-millis"));
        assertEquals(400, config.getLong("cycle-cooldown-millis"));
        assertFalse(config.contains("language"));
    }
    @Test void legacyLanguageIsRemovedWithoutChangingExplicitSettings() throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("language:\n  console-chinese: true\ncycle-cooldown-millis: 500\n");
        VillagerTradePlus.removeLegacyLanguage(config);
        assertFalse(config.contains("language"));
        assertEquals(500, config.getLong("cycle-cooldown-millis"));
        assertFalse(config.getBoolean("double-press-enabled", false));
        VillagerTradePlus.removeLegacyLanguage(config);
        assertFalse(config.saveToString().contains("console-chinese"));
    }
}
