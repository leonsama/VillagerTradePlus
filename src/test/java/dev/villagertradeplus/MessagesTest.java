package dev.villagertradeplus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Locale;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class MessagesTest {
    @Test void localeDetection() {
        for (String locale : new String[]{"zh", "zh_cn", "zh_tw", "ZH_HANS", "zh-Hant", "zh-hk"})
            assertTrue(Messages.isChinese(locale), locale);
        for (String locale : new String[]{"en_us", "ja_jp", "de_de", "", "zhfake"})
            assertFalse(Messages.isChinese(locale), locale);
        assertFalse(Messages.isChinese((String) null));
    }
    @Test void senderUsesClientLanguageAndConsoleAlwaysEnglish() {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.SIMPLIFIED_CHINESE, Locale.US, null);
        assertTrue(Messages.isChinese(player));
        assertFalse(Messages.isChinese(player));
        assertFalse(Messages.isChinese(player));
        assertFalse(Messages.isChinese(mock(ConsoleCommandSender.class)));
    }
    @Test void templatesRenderBothLanguages() {
        assertEquals("重刷：开启 · 预览：关闭", Messages.STATUS.text(true, "开启", "关闭"));
        assertEquals("Reroll: on · Preview: off", Messages.STATUS.text(false, "on", "off"));
        assertEquals("大师（Lv.5）解锁", Messages.UNLOCK_LORE.text(true, "大师", "5"));
        assertEquals("Unlocks at Master (Lv.5)", Messages.UNLOCK_LORE.text(false, "Master", "5"));
        assertEquals("Press F over an item slot to reroll", Messages.SINGLE_PRESS_HINT.text(false));
        assertEquals(" | Double-tap F over an item slot to reroll", Messages.DOUBLE_PRESS_HINT.text(false));
        for (Messages message : Messages.values()) {
            assertFalse(message.text(false).matches(".*[\\p{IsHan}].*"));
            assertFalse(message.text(false).contains("Future trade ·"));
        }
    }
    @Test void levelNamesAndToggles() {
        assertEquals("新手", Messages.levelName(true, 1));
        assertEquals("Master", Messages.levelName(false, 5));
        assertEquals("42", Messages.levelName(false, 42));
        assertEquals("开启", Messages.onOff(true, true));
        assertEquals("off", Messages.onOff(false, false));
    }
}
