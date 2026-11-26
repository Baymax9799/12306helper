package com.jactil.tickethelper.core.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    @TempDir
    Path tmp;

    private Db db;
    private Store store;

    @BeforeEach
    void setUp() {
        db = Db.open(tmp.resolve("test.db"));
        store = new Store(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Store.TaskRow sample(String status, Instant startAt) {
        Instant now = Instant.parse("2026-11-26T08:00:00Z");
        return new Store.TaskRow(0, "回长沙", status, "GRAB", "SZQ", "CSQ",
                List.of("2026-12-01", "2026-12-02"), List.of("G1", "G2"),
                List.of("O", "M"), List.of(1L, 2L), 1000, startAt, now, now);
    }

    @Test
    void migrateIsIdempotent() {
        db.migrate();
        db.migrate();
        assertTrue(store.findAllTasks().isEmpty());
    }

    @Test
    void taskRoundTripKeepsAllFields() {
        long id = store.insertTask(sample("PENDING", Instant.parse("2026-12-01T00:00:00Z")));

        Store.TaskRow loaded = store.findTask(id).orElseThrow();

        assertEquals("回长沙", loaded.name());
        assertEquals("PENDING", loaded.status());
        assertEquals("GRAB", loaded.mode());
        assertEquals("SZQ", loaded.from());
        assertEquals(List.of("2026-12-01", "2026-12-02"), loaded.dates());
        assertEquals(List.of("G1", "G2"), loaded.trains());
        assertEquals(List.of("O", "M"), loaded.seatTypes());
        assertEquals(List.of(1L, 2L), loaded.passengerIds());
        assertEquals(1000, loaded.intervalMs());
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), loaded.startAt());
    }

    @Test
    void taskWithNullStartAtRoundTrips() {
        long id = store.insertTask(sample("RUNNING", null));

        Store.TaskRow loaded = store.findTask(id).orElseThrow();

        assertNull(loaded.startAt());
    }

    @Test
    void updateStatusAndDelete() {
        long id = store.insertTask(sample("PENDING", null));

        store.updateTaskStatus(id, "HELD");
        assertEquals("HELD", store.findTask(id).orElseThrow().status());

        store.deleteTask(id);
        assertTrue(store.findTask(id).isEmpty());
    }

    @Test
    void gateDefaultsToOpenAndCanHold() {
        assertEquals("OPEN", store.gate().state());

        store.setGate("HOLD", "已抢到订单 TB12345");

        assertEquals("HOLD", store.gate().state());
        assertEquals("已抢到订单 TB12345", store.gate().reason());
    }

    @Test
    void dailyStatStartsAtZeroAndAccumulates() {
        LocalDate day = LocalDate.of(2026, 12, 1);
        assertEquals(0, store.dailyStat(day).created());

        store.bumpDailyStat(day, "orders_created", 1);
        store.bumpDailyStat(day, "orders_created", 1);
        store.bumpDailyStat(day, "orders_paid", 1);

        Store.DailyStat stat = store.dailyStat(day);
        assertEquals(2, stat.created());
        assertEquals(1, stat.paid());
        assertEquals(0, stat.cancelled());
        assertEquals(0, store.dailyStat(day.plusDays(1)).created());
    }

    @Test
    void eventsAreOrderedAndFiltered() {
        store.appendEvent(1L, "INFO", "第一条");
        store.appendEvent(2L, "WARN", "第二条");

        List<Store.EventRow> all = store.eventsAfter(0, 10);
        assertEquals(2, all.size());
        assertEquals("第一条", all.get(0).message());

        List<Store.EventRow> tail = store.eventsAfter(all.get(0).id(), 10);
        assertEquals(1, tail.size());
        assertEquals("第二条", tail.get(0).message());
        assertEquals(2L, tail.get(0).taskId());
        assertEquals(all.get(1).id(), store.lastEventId());
    }

    @Test
    void passengersAreReplacedByPid() {
        store.replacePassengers(1L, List.of(
                new Store.PassengerRow(0, "p1", "张*", "1", "****1234", "ADULT", Instant.now()),
                new Store.PassengerRow(0, "p2", "李*", "1", "****5678", "ADULT", Instant.now())));
        assertEquals(2, store.findPassengers(1L).size());

        store.replacePassengers(1L, List.of(
                new Store.PassengerRow(0, "p1", "张*", "1", "****1234", "ADULT", Instant.now())));

        assertEquals(1, store.findPassengers(1L).size());
        assertEquals("p1", store.findPassengers(1L).get(0).pid());
    }

    @Test
    void ordersAreUniqueByOrderNo() {
        store.insertOrder(7L, "E123456789", "G1", "O", "174.50");
        assertTrue(store.orderExists("E123456789"));
        assertEquals(1, store.findAllOrders().size());
        assertEquals("PENDING_PAY", store.findAllOrders().get(0).status());
    }
}
