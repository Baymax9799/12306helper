package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.config.AppPaths;
import com.jactil.tickethelper.core.config.Settings;
import com.jactil.tickethelper.core.config.SettingsStore;
import com.jactil.tickethelper.core.client.PersistentCookieStore;
import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.auth.QrLoginFlow;
import com.jactil.tickethelper.core.passenger.PassengerSync;
import com.jactil.tickethelper.core.query.LeftTicketQuery;
import com.jactil.tickethelper.core.security.AesGcm;
import com.jactil.tickethelper.core.security.Passwords;
import com.jactil.tickethelper.core.station.StationRegistry;
import com.jactil.tickethelper.core.store.Db;
import com.jactil.tickethelper.core.store.Store;
import com.jactil.tickethelper.core.time.ServerClock;
import com.jactil.tickethelper.engine.AccountGate;
import com.jactil.tickethelper.engine.BookingAttempt;
import com.jactil.tickethelper.engine.DisabledBooking;
import com.jactil.tickethelper.engine.EventBus;
import com.jactil.tickethelper.engine.RateLimiter;
import com.jactil.tickethelper.engine.Blacklist;
import com.jactil.tickethelper.engine.TaskEngine;
import com.jactil.tickethelper.engine.TaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * 装配上下文。所有可变状态都从这里取，避免静态单例。
 */
public final class AppContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    /** v1 只有单账号，固定 accountId=1。 */
    public static final long ACCOUNT_ID = 1L;

    public final AppPaths paths;
    public final SettingsStore settingsStore;
    public final AdminSessions sessions = new AdminSessions();
    public final Store store;
    public final Db db;
    public final ServerClock clock;
    public final TicketClient client;
    public final QrLoginFlow qrLogin;
    public final com.jactil.tickethelper.core.auth.SmsLoginFlow smsLogin;
    public final LeftTicketQuery query;
    public final PassengerSync passengerSync;
    public final StationRegistry stations = new StationRegistry();
    public final EventBus bus;
    public final AccountGate gate;
    public final RateLimiter limiter;
    public final Blacklist blacklist = new Blacklist();
    public final TaskEngine engine;
    public final com.jactil.tickethelper.notify.NotifyDispatcher notifier;
    public final com.jactil.tickethelper.engine.OrderReconciler reconciler;
    public final java.util.concurrent.ScheduledExecutorService scheduler;

    private volatile Settings settings;
    private volatile BookingAttempt booking = new DisabledBooking();

    private AppContext(AppPaths paths, SettingsStore store, Settings settings, Db db,
                       TicketClient client, ServerClock clock) {
        this.paths = paths;
        this.settingsStore = store;
        this.settings = settings;
        this.db = db;
        this.store = new Store(db);
        this.clock = clock;
        this.client = client;
        this.qrLogin = new QrLoginFlow(client);
        this.smsLogin = new com.jactil.tickethelper.core.auth.SmsLoginFlow(client);
        this.query = new LeftTicketQuery(client);
        this.passengerSync = new PassengerSync(client);
        this.bus = new EventBus(this.store);
        this.gate = new AccountGate(this.store, clock, this::settings);
        this.limiter = new RateLimiter(settings.intervalMs);
        this.engine = new TaskEngine(new TaskRunner.Deps(this.store, this.query, this.limiter,
                this.blacklist, this.bus, this.clock, this.gate, () -> this.booking,
                this.stations::nameOf));
        this.notifier = new com.jactil.tickethelper.notify.NotifyDispatcher(this::settings);
        this.bus.subscribe(this.notifier::onEvent);
        this.reconciler = new com.jactil.tickethelper.engine.OrderReconciler(this.store, this.gate,
                this.bus, new com.jactil.tickethelper.engine.OrderFetcher(client));
        this.scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reconciler");
            t.setDaemon(true);
            return t;
        });
    }

    public static AppContext boot(AppPaths paths) {
        return boot(paths, System.getenv());
    }

    /** 允许测试注入环境变量，避免读进程级 env。 */
    public static AppContext boot(AppPaths paths, java.util.Map<String, String> env) {
        AesGcm cipher = AesGcm.load(paths.secretKey());
        SettingsStore store = new SettingsStore(paths.settings(), cipher);
        Settings settings = store.load();

        applyAdminPassword(settings, store, env);

        Db db = Db.open(paths.db());
        PersistentCookieStore cookieStore = PersistentCookieStore.open(paths.cookieStore());
        ServerClock clock = new ServerClock();
        TicketClient client = new TicketClient(cookieStore, clock);
        log.info("12306 会话 Cookie：{} 个", cookieStore.size());

        AppContext ctx = new AppContext(paths, store, settings, db, client, clock);
        ctx.engine.markStaleTasksPaused();
        ctx.loadStations();
        ctx.scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (ctx.loggedIn()) {
                    ctx.reconciler.runOnce();
                }
            } catch (RuntimeException e) {
                log.warn("周期对账失败：{}", e.getMessage());
            }
        }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        return ctx;
    }

    /** 站点库：启动时拉一次，失败就等用户点刷新。 */
    public void loadStations() {
        try {
            stations.refresh(client);
        } catch (RuntimeException e) {
            log.warn("站点库初始化失败：{}", e.getMessage());
        }
    }

    /**
     * 管理员口令来源，优先级：环境变量 ADMIN_PASSWORD &gt; settings.json 里的旧哈希 &gt; 随机生成。
     *
     * <p>只要容器里给了 ADMIN_PASSWORD 就以它为准。早先的实现只在首次启动读环境变量，
     * 于是「重建容器、改了环境变量，却始终登录不上」——旧哈希还在，新口令被忽略。
     * 现在每次启动都会比对，不一致就覆盖。
     */
    static void applyAdminPassword(Settings settings, SettingsStore store,
                                   java.util.Map<String, String> env) {
        String fromEnv = normalizePassword(env == null ? null : env.get("ADMIN_PASSWORD"));
        boolean hasHash = settings.adminPasswordHash != null && !settings.adminPasswordHash.isBlank();

        if (fromEnv != null) {
            if (!hasHash || !Passwords.verify(fromEnv, settings.adminPasswordHash)) {
                settings.adminPasswordHash = Passwords.hash(fromEnv);
                store.save(settings);
                log.info("已按环境变量 ADMIN_PASSWORD 设置管理员口令");
            } else {
                log.info("管理员口令与环境变量一致，无需改动");
            }
            return;
        }

        if (hasHash) {
            return;
        }

        String generated = Passwords.random(16);
        settings.adminPasswordHash = Passwords.hash(generated);
        store.save(settings);
        log.warn("=====================================================");
        log.warn("首次启动，已生成管理员口令：{}", generated);
        log.warn("请登录后到设置里改掉，或用 ADMIN_PASSWORD 环境变量指定");
        log.warn("=====================================================");
    }

    /** 去掉首尾空格与成对引号；空值视作未设置。 */
    static String normalizePassword(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                value = value.substring(1, value.length() - 1).trim();
            }
        }
        return value.isEmpty() ? null : value;
    }

    public Settings settings() {
        return settings;
    }

    public BookingAttempt booking() {
        return booking;
    }

    public boolean autoBooking() {
        return booking instanceof com.jactil.tickethelper.engine.RealBooking;
    }

    /** 每次开启自动下单都新建一个实例，避免共享可变状态。 */
    public BookingAttempt createRealBooking() {
        return new com.jactil.tickethelper.engine.RealBooking(store, passengerSync,
                new com.jactil.tickethelper.engine.BookingClient(client));
    }

    public void setBooking(BookingAttempt booking) {
        this.booking = booking;
    }

    public synchronized void updateSettings(Settings updated) {
        this.settings = updated;
        this.settingsStore.save(updated);
        this.limiter.setIntervalMs(updated.intervalMs);
    }

    /** 是否已登录 12306：有落库的会话，且 Cookie 不为空。 */
    public boolean loggedIn() {
        return store.loadSession(ACCOUNT_ID).isPresent() && client.store().size() > 0;
    }

    public Instant sessionExpiresAt() {
        return store.loadSession(ACCOUNT_ID).map(Store.SessionRow::expiresAt).orElse(null);
    }

    public void markLoggedIn(String username) {
        store.saveSession(ACCOUNT_ID, client.store().dumpHeader(), client.store().railDeviceId(),
                Instant.now().plusSeconds(14L * 24 * 3600));
        store.appendEvent(null, "INFO", "12306 登录成功："
                + (username == null || username.isBlank() ? "未知用户" : username));
    }

    public void markLoggedOut() {
        client.store().clear();
        store.clearSession(ACCOUNT_ID);
        store.appendEvent(null, "INFO", "已退出 12306");
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        engine.close();
        notifier.shutdown();
        db.close();
    }
}
