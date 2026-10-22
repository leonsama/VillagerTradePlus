package dev.villagertradeplus;

import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Player-facing messages follow client language; non-player senders always use English. */
public enum Messages {
    NO_ADMIN_PERMISSION("无管理权限", "No admin permission"),
    CONFIG_RELOADED("配置已重载", "Config reloaded"),
    STATUS("重刷：{0} · 预览：{1}", "Reroll: {0} · Preview: {1}"),
    SINGLE_PRESS_HINT("交易界面物品格上按 F 重刷交易", "Press F over an item slot to reroll"),
    DOUBLE_PRESS_HINT(" | 交易界面物品格上双按 F 重刷交易", " | Double-tap F over an item slot to reroll"),
    RELOAD_HINT("/villagertradeplus reload · 重载配置", "/villagertradeplus reload · Reload config"),
    ADAPTER_FAILED("交易界面异常，请联系管理员", "Trade screen error. Contact an admin"),
    PREVIEW_UNAVAILABLE("交易预览暂不可用", "Trade preview unavailable"),
    REROLL_DISABLED("重刷已关闭", "Reroll disabled"),
    NO_REROLL_PERMISSION("无重刷权限", "No reroll permission"),
    VILLAGER_LOCKED("已交易或升级，无法重刷", "Already traded or leveled up"),
    REROLL_COOLDOWN("请稍后再试", "Please wait"),
    REROLL_DONE("已重刷交易", "Trades rerolled"),
    REROLL_FAILED("重刷失败，请联系管理员", "Reroll failed. Contact an admin"),
    UNLOCK_LORE("{0}（Lv.{1}）解锁", "Unlocks at {0} (Lv.{1})");

    private static final String[] LEVEL_NAMES_ZH = {"", "新手", "学徒", "老手", "专家", "大师"};
    private static final String[] LEVEL_NAMES_EN = {"", "Novice", "Apprentice", "Journeyman", "Expert", "Master"};

    private final String zh;
    private final String en;

    Messages(String zh, String en) { this.zh = zh; this.en = en; }

    public static boolean isChinese(String locale) {
        if (locale == null) return false;
        String normalized = locale.toLowerCase(Locale.ROOT);
        return normalized.equals("zh") || normalized.startsWith("zh_") || normalized.startsWith("zh-");
    }

    public static boolean isChinese(Player player) {
        Locale locale = player.locale();
        return locale != null && isChinese(locale.toString());
    }

    public static boolean isChinese(CommandSender sender) {
        return sender instanceof Player player && isChinese(player);
    }

    public String text(boolean chinese) { return chinese ? zh : en; }

    public String text(boolean chinese, String... args) {
        String text = chinese ? zh : en;
        for (int index = 0; index < args.length; index++) text = text.replace("{" + index + "}", args[index]);
        return text;
    }

    public static String levelName(boolean chinese, int level) {
        String[] names = chinese ? LEVEL_NAMES_ZH : LEVEL_NAMES_EN;
        return level >= 0 && level < names.length ? names[level] : String.valueOf(level);
    }

    public static String onOff(boolean chinese, boolean value) {
        return value ? chinese ? "开启" : "on" : chinese ? "关闭" : "off";
    }
}
