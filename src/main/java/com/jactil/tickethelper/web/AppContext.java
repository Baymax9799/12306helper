package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.config.AppPaths;
import com.jactil.tickethelper.core.config.Settings;
import com.jactil.tickethelper.core.config.SettingsStore;
import com.jactil.tickethelper.core.security.AesGcm;
import com.jactil.tickethelper.core.security.Passwords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 装配上下文。所有可变状态都从这里取，避免静态单例。
 */
public final class AppContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    public final AppPaths paths;
    public final SettingsStore settingsStore;
    public final AdminSessions sessions = new AdminSessions();

    private volatile Settings settings;

    private AppContext(AppPaths paths, SettingsStore store, Settings settings) {
        this.paths = paths;
        this.settingsStore = store;
        this.settings = settings;
    }

    public static AppContext boot(AppPaths paths) {
        AesGcm cipher = AesGcm.load(paths.secretKey());
        SettingsStore store = new SettingsStore(paths.settings(), cipher);
        Settings settings = store.load();

        boolean needsPassword = settings.adminPasswordHash == null || settings.adminPasswordHash.isBlank();
        if (needsPassword) {
            String fromEnv = System.getenv("ADMIN_PASSWORD");
            if (fromEnv != null && !fromEnv.isBlank()) {
                settings.adminPasswordHash = Passwords.hash(fromEnv);
                store.save(settings);
                log.info("已使用环境变量 ADMIN_PASSWORD 初始化管理员口令");
            } else {
                String generated = Passwords.random(16);
                settings.adminPasswordHash = Passwords.hash(generated);
                store.save(settings);
                log.warn("=====================================================");
                log.warn("首次启动，已生成管理员口令：{}", generated);
                log.warn("请立即登录 http://<NAS_IP>:8080 并在设置里改掉它");
                log.warn("=====================================================");
            }
        }

        return new AppContext(paths, store, settings);
    }

    public Settings settings() {
        return settings;
    }

    public synchronized void updateSettings(Settings updated) {
        this.settings = updated;
        this.settingsStore.save(updated);
    }

    @Override
    public void close() {
        // 资源在后续任务（DB、线程池）里逐步挂上
    }
}
