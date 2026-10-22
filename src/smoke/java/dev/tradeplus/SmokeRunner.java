package dev.tradeplus;
import java.lang.reflect.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-server only. Never included in the release JAR. */
public final class SmokeRunner extends JavaPlugin {
    private Class<?> checks;
    private JavaPlugin subject;
    @Override public void onEnable() {
        subject = (JavaPlugin) Bukkit.getPluginManager().getPlugin("TradePlus");
        try { checks = subject.getClass().getClassLoader().loadClass("dev.tradeplus.SmokeChecks"); }
        catch (ClassNotFoundException error) { throw new IllegalStateException("Use the smoke JAR on this test server", error); }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { checks.getMethod("run", JavaPlugin.class).invoke(null, subject); }
            catch (ReflectiveOperationException error) {
                getLogger().log(java.util.logging.Level.SEVERE, "SMOKE FAILED", error);
                Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 2);
            }
        }, 5);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 2) {
                Player player = Bukkit.getPlayerExact(args[1]);
                if (player == null) throw new IllegalArgumentException("Player offline");
                checks.getMethod("fixture", JavaPlugin.class, Player.class, String.class).invoke(null, subject, player, args[0]);
                sender.sendMessage("SMOKE fixture " + args[0] + " ready"); return true;
            }
        } catch (ReflectiveOperationException | RuntimeException error) { getLogger().log(java.util.logging.Level.SEVERE, "Fixture failed", error); }
        return true;
    }
}
