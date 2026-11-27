package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.config.Settings;
import com.jactil.tickethelper.core.config.SettingsStore;
import com.jactil.tickethelper.core.security.AesGcm;
import com.jactil.tickethelper.core.security.Passwords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理员口令的取值优先级：环境变量 > 旧哈希 > 随机生成。 */
class AdminPasswordBootTest {

    @TempDir
    Path tmp;

    private SettingsStore store;

    @BeforeEach
    void setUp() {
        store = new SettingsStore(tmp.resolve("settings.json"), AesGcm.load(tmp.resolve("secret.key")));
    }

    @Test
    void envPasswordOverridesExistingHash() {
        Settings settings = new Settings();
        settings.adminPasswordHash = Passwords.hash("旧口令");
        store.save(settings);

        AppContext.applyAdminPassword(settings, store, Map.of("ADMIN_PASSWORD", "新口令"));

        assertTrue(Passwords.verify("新口令", settings.adminPasswordHash), "应改成环境变量里的口令");
        assertFalse(Passwords.verify("旧口令", settings.adminPasswordHash), "旧口令必须失效");
        assertTrue(Passwords.verify("新口令", store.load().adminPasswordHash), "新口令要落盘");
    }

    @Test
    void samePasswordDoesNotRewriteFile() throws Exception {
        Settings settings = new Settings();
        settings.adminPasswordHash = Passwords.hash("同一个");
        store.save(settings);
        String before = Files.readString(tmp.resolve("settings.json"));

        AppContext.applyAdminPassword(settings, store, Map.of("ADMIN_PASSWORD", "同一个"));

        String after = Files.readString(tmp.resolve("settings.json"));
        assertEquals(before, after, "口令一致时不应重写文件（bcrypt 每次盐不同）");
    }

    @Test
    void quotedAndPaddedEnvIsNormalized() {
        Settings settings = new Settings();

        AppContext.applyAdminPassword(settings, store, Map.of("ADMIN_PASSWORD", "  \"带引号的口令\"  "));

        assertTrue(Passwords.verify("带引号的口令", settings.adminPasswordHash));
        assertFalse(Passwords.verify("\"带引号的口令\"", settings.adminPasswordHash));
    }

    @Test
    void blankEnvKeepsExistingHash() {
        Settings settings = new Settings();
        settings.adminPasswordHash = Passwords.hash("原有口令");
        store.save(settings);

        AppContext.applyAdminPassword(settings, store, Map.of("ADMIN_PASSWORD", "   "));

        assertTrue(Passwords.verify("原有口令", settings.adminPasswordHash), "空环境变量不能把口令清掉");
    }

    @Test
    void generatesRandomWhenNothingProvided() {
        Settings settings = new Settings();

        AppContext.applyAdminPassword(settings, store, Map.of());

        assertNotNull(settings.adminPasswordHash);
        assertFalse(settings.adminPasswordHash.isBlank());
        assertFalse(Passwords.verify("", settings.adminPasswordHash));
        assertFalse(Passwords.verify("admin", settings.adminPasswordHash));
    }

    @Test
    void normalizeHandlesEdgeCases() {
        assertNull(AppContext.normalizePassword(null));
        assertNull(AppContext.normalizePassword(""));
        assertNull(AppContext.normalizePassword("   "));
        assertEquals("abc", AppContext.normalizePassword(" abc "));
        assertEquals("abc", AppContext.normalizePassword("\"abc\""));
        assertEquals("abc", AppContext.normalizePassword("'abc'"));
        assertEquals("\"abc", AppContext.normalizePassword("\"abc"), "只去成对引号");
    }
}
