package dev.villagertradeplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies the previous config once, without overwriting the destination or removing the original. */
final class ConfigMigration {
    static void copyLegacy(Path dataFolder) throws IOException {
        Path target = dataFolder.resolve("config.yml");
        Path legacy = dataFolder.resolveSibling("TradePlus").resolve("config.yml");
        if (Files.exists(target) || !Files.isRegularFile(legacy)) return;
        Files.createDirectories(dataFolder);
        Files.copy(legacy, target);
    }
}
