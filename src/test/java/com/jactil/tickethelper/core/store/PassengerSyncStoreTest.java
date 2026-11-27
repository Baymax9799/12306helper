package com.jactil.tickethelper.core.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 乘车人同步的真实脏数据：pid 重复、pid 为空。 */
class PassengerSyncStoreTest {

    @TempDir
    Path tmp;

    private Db db;
    private Store store;

    @BeforeEach
    void setUp() {
        db = Db.open(tmp.resolve("p.db"));
        store = new Store(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Store.PassengerRow row(String pid, String name) {
        return new Store.PassengerRow(0, pid, name, "身份证", "****1234", "成人票", Instant.now());
    }

    @Test
    void duplicatePidDoesNotBlowUpTheWholeBatch() {
        List<Store.PassengerRow> rows = List.of(
                row("P1", "张三"),
                row("P1", "张三"),
                row("P2", "李四"));

        store.replacePassengers(1L, rows);

        assertEquals(2, store.findPassengers(1L).size(), "重复 pid 应被折叠成一条");
    }

    @Test
    void blankPidIsSkippedInsteadOfFailing() {
        List<Store.PassengerRow> rows = List.of(
                row("", "张三"),
                row("P2", "李四"));

        store.replacePassengers(1L, rows);

        List<Store.PassengerRow> saved = store.findPassengers(1L);
        assertEquals(1, saved.size(), "空 pid 无法用于下单，应跳过");
        assertEquals("P2", saved.get(0).pid());
        assertTrue(store.findPassengers(1L).get(0).nameMasked().contains("李"));
    }

    @Test
    void emptyListStillClearsOldRows() {
        store.replacePassengers(1L, List.of(row("P1", "张三")));

        store.replacePassengers(1L, List.of());

        assertEquals(0, store.findPassengers(1L).size());
    }
}
