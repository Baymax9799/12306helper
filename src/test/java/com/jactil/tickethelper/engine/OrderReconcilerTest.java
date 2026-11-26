package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.store.Db;
import com.jactil.tickethelper.core.store.Store;
import com.jactil.tickethelper.core.time.ServerClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderReconcilerTest {

    @TempDir
    Path tmp;

    private Db db;
    private Store store;
    private AccountGate gate;
    private EventBus bus;

    @BeforeEach
    void setUp() {
        db = Db.open(tmp.resolve("r.db"));
        store = new Store(db);
        gate = new AccountGate(store, new ServerClock(), com.jactil.tickethelper.core.config.Settings::new);
        bus = new EventBus(store);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void orphanOrderTriggersHold() {
        OrderReconciler reconciler = new OrderReconciler(store, gate, bus,
                () -> List.of("E999999"));

        int orphans = reconciler.runOnce();

        assertEquals(1, orphans);
        assertTrue(store.orderExists("E999999"));
        assertTrue(gate.held(), "发现孤儿单必须立刻 HOLD");
        assertFalse(gate.canSubmit());
    }

    @Test
    void knownOrderIsIgnored() {
        store.insertOrder(1L, "E123", "G1", "O", null);
        int before = store.dailyStat(gate.today()).created();
        OrderReconciler reconciler = new OrderReconciler(store, gate, bus, () -> List.of("E123"));

        int orphans = reconciler.runOnce();

        assertEquals(0, orphans);
        assertFalse(gate.held());
        assertEquals(before, store.dailyStat(gate.today()).created());
    }

    @Test
    void fetcherFailureIsSwallowed() {
        AtomicBoolean called = new AtomicBoolean();
        OrderReconciler reconciler = new OrderReconciler(store, gate, bus, () -> {
            called.set(true);
            throw new java.io.IOException("接口改版了");
        });

        int orphans = reconciler.runOnce();

        assertEquals(0, orphans);
        assertTrue(called.get());
        assertFalse(gate.held());
    }

    @Test
    void gateBlocksAfterDailyLimit() {
        assertTrue(gate.canSubmit());
        gate.onOrderCreated("E1");
        assertFalse(gate.canSubmit());
        gate.unlock("PAID");
        // 额度上限默认 1，今天已经下过一单，即使解锁也不能再提交
        assertFalse(gate.canSubmit());
        assertEquals(1, store.dailyStat(gate.today()).created());
        assertEquals(1, store.dailyStat(gate.today()).paid());
    }

    @Test
    void newDayRestoresQuota() {
        gate.onOrderCreated("E1");
        assertFalse(gate.canSubmit());

        String tomorrow = gate.today().plusDays(1).toString();
        assertEquals(0, store.dailyStat(java.time.LocalDate.parse(tomorrow)).created());
        assertTrue(gate.today().toString().compareTo(tomorrow) < 0);
    }

    @Test
    void eventsArePersistedForTheUi() {
        bus.info(7L, "任务启动");
        List<Store.EventRow> events = store.eventsAfter(0, 10);
        assertEquals(1, events.size());
        assertEquals(Instant.class, events.get(0).createdAt().getClass());
    }
}
