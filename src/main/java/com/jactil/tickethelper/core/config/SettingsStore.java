package com.jactil.tickethelper.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jactil.tickethelper.core.security.AesGcm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * settings.json 的读写。内存里始终是明文，落盘前把敏感字段换成 enc: 密文。
 */
public final class SettingsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final AesGcm cipher;

    public SettingsStore(Path file, AesGcm cipher) {
        this.file = file;
        this.cipher = cipher;
    }

    public synchronized Settings load() {
        if (!Files.exists(file)) {
            return new Settings();
        }
        try {
            Settings settings = MAPPER.readValue(file.toFile(), Settings.class);
            decryptInPlace(settings);
            return settings;
        } catch (IOException e) {
            throw new UncheckedIOException("读取 settings.json 失败：" + file, e);
        }
    }

    public synchronized void save(Settings settings) {
        try {
            Settings copy = MAPPER.convertValue(settings, Settings.class);
            encryptInPlace(copy);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), copy);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 settings.json 失败：" + file, e);
        }
    }

    private void encryptInPlace(Settings s) {
        s.smtp.password = cipher.encrypt(s.smtp.password);
        s.wechat.key = cipher.encrypt(s.wechat.key);
    }

    private void decryptInPlace(Settings s) {
        s.smtp.password = cipher.decrypt(s.smtp.password);
        s.wechat.key = cipher.decrypt(s.wechat.key);
    }
}
