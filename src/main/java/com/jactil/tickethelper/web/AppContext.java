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
        AesGcm cipher = AesGcm.load(paths.secretKey());
        SettingsStore store = new SettingsStore(paths.settings(), cipher);
        Settings settings = store.load();

        if (settings.adminPasswordHash == null || settings.adminPasswordHash.isBlank()) {
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
