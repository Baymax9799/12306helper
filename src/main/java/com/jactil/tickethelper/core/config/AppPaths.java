package com.jactil.tickethelper.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据目录约定。容器内是 /data，开发环境回退到 ~/.tickethelper。
 */
public final class AppPaths {

    private final Path dataDir;

    private AppPaths(Path dataDir) {
        this.dataDir = dataDir;
    }

    public static AppPaths fromEnv() {
        return resolve(System.getenv("DATA_DIR"), System.getProperty("user.home"));
    }

    public static AppPaths resolve(String envDataDir, String userHome) {
        Path dir = (envDataDir == null || envDataDir.isBlank())
                ? Path.of(userHome, ".tickethelper")
                : Path.of(envDataDir).toAbsolutePath();
        return new AppPaths(dir).ensure();
    }

    private AppPaths ensure() {
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(logs());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录：" + dataDir, e);
        }
        return this;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path db() {
        return dataDir.resolve("tickethelper.db");
    }

    public Path settings() {
        return dataDir.resolve("settings.json");
    }

    public Path secretKey() {
        return dataDir.resolve("secret.key");
    }

    public Path cookieStore() {
        return dataDir.resolve("cookies.json");
    }

    public Path logs() {
        return dataDir.resolve("logs");
    }
}
