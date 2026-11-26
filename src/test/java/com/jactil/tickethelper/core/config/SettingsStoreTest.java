package com.jactil.tickethelper.core.config;

import com.jactil.tickethelper.core.security.AesGcm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsStoreTest {

    @TempDir
    Path tmp;

    @Test
    void sensitiveFieldsAreEncryptedOnDiskAndDecryptedInMemory() throws Exception {
        Path key = tmp.resolve("secret.key");
        AesGcm cipher = AesGcm.load(key);
        SettingsStore store = new SettingsStore(tmp.resolve("settings.json"), cipher);

        Settings settings = new Settings();
        settings.smtp.password = "smtp-token-123";
        settings.wechat.key = "SCT123456";
        store.save(settings);

        String raw = Files.readString(tmp.resolve("settings.json"));
        assertTrue(raw.contains("enc:"), "落盘应加密");
        assertFalse(raw.contains("smtp-token-123"), "落盘不得出现明文密码");
        assertFalse(raw.contains("SCT123456"), "落盘不得出现明文 SendKey");

        Settings loaded = store.load();
        assertEquals("smtp-token-123", loaded.smtp.password);
        assertEquals("SCT123456", loaded.wechat.key);
        assertEquals(1000, loaded.intervalMs);
        assertEquals(1, loaded.dailyOrderLimit);
    }

    @Test
    void missingFileYieldsDefaults() {
        AesGcm cipher = AesGcm.load(tmp.resolve("secret.key"));
        SettingsStore store = new SettingsStore(tmp.resolve("absent.json"), cipher);

        Settings loaded = store.load();

        assertEquals("", loaded.smtp.host);
        assertEquals(465, loaded.smtp.port);
        assertEquals(1, loaded.dailyOrderLimit);
    }

    @Test
    void sameKeyCanReadBackTwice() {
        Path key = tmp.resolve("secret.key");
        SettingsStore first = new SettingsStore(tmp.resolve("s.json"), AesGcm.load(key));
        Settings s = new Settings();
        s.smtp.password = "p";
        first.save(s);

        SettingsStore second = new SettingsStore(tmp.resolve("s.json"), AesGcm.load(key));
        assertEquals("p", second.load().smtp.password);
    }
}
