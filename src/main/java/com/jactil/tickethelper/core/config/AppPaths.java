package com.jactil.tickethelper.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据目录约定。
 *
 * <p>优先级：{@code DATA_DIR} 环境变量 &gt; 容器里的 {@code /data}（存在且可写时）&gt;
 * {@code ~/.tickethelper}。
 *
 * <p>之所以有第二档：镜像里刻意不写 ENV，NAS 图形界面创建容器时环境变量表就是空的。
 * 容器本身有 /data 这个挂载点，自动认它即可，不必让用户在环境变量里再填一遍。
 */
public final class AppPaths {

    private final Path dataDir;

    private AppPaths(Path dataDir) {
        this.dataDir = dataDir;
    }

    public static AppPaths fromEnv() {
        String envDataDir = System.getenv("DATA_DIR");
        String userHome = System.getProperty("user.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        // 只在类 Unix 环境认 /data；Windows 上 "/data" 会变成当前盘符下的 \data，容易误伤
        Path containerData = windows ? null : Path.of("/data");
        return fromEnv(envDataDir, userHome, containerData);
    }

    public static AppPaths fromEnv(String envDataDir, String userHome, Path containerDataDir) {
        if (envDataDir != null && !envDataDir.isBlank()) {
            return resolve(envDataDir, userHome);
        }
        if (containerDataDir != null && Files.isDirectory(containerDataDir)) {
            return new AppPaths(containerDataDir).ensure();
        }
        return resolve(null, userHome);
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
