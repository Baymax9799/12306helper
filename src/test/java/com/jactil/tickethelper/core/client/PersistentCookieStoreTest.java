package com.jactil.tickethelper.core.client;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentCookieStoreTest {

    private static final HttpUrl URL = HttpUrl.get("https://kyfw.12306.cn/otn/leftTicket/init");

    @TempDir
    Path tmp;

    private Cookie cookie(String name, String value) {
        return new Cookie.Builder()
                .name(name)
                .value(value)
                .domain("kyfw.12306.cn")
                .path("/")
                .build();
    }

    @Test
    void survivesRestart() {
        Path file = tmp.resolve("cookies.json");
        PersistentCookieStore store = PersistentCookieStore.open(file);
        store.saveFromResponse(URL, List.of(
                cookie("RAIL_DEVICEID", "abc123"),
                cookie("_passport_session", "sess-1")));
        assertEquals(2, store.size());
        assertTrue(Files.exists(file));

        PersistentCookieStore reopened = PersistentCookieStore.open(file);

        assertEquals(2, reopened.size());
        assertEquals("abc123", reopened.railDeviceId());
        assertTrue(reopened.dumpHeader().contains("_passport_session=sess-1"));
    }

    @Test
    void importsPastedBrowserCookieHeader() {
        PersistentCookieStore store = PersistentCookieStore.open(tmp.resolve("c.json"));

        int count = store.loadFromHeader("RAIL_DEVICEID=xyz; _passport_session=abc;  bad ;name=with=equals");

        assertEquals(3, count);
        assertEquals("xyz", store.railDeviceId());
        assertTrue(store.dumpHeader().contains("name=with=equals"));
    }

    @Test
    void concurrentWritesDoNotCorruptFile() throws Exception {
        Path file = tmp.resolve("cookies.json");
        PersistentCookieStore store = PersistentCookieStore.open(file);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    store.saveFromResponse(URL, List.of(cookie("k" + index, "v" + index)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        PersistentCookieStore reopened = PersistentCookieStore.open(file);
        assertEquals(threads, reopened.size());
        assertNotNull(reopened.dumpHeader());
        assertNull(reopened.railDeviceId());
    }

    @Test
    void expiredCookiesAreDroppedOnLoad() throws Exception {
        Path file = tmp.resolve("expired.json");
        Files.writeString(file, "[{\"name\":\"a\",\"value\":\"1\",\"domain\":\"kyfw.12306.cn\","
                + "\"path\":\"/\",\"expiresAt\":1,\"secure\":false,\"httpOnly\":false}]");

        PersistentCookieStore store = PersistentCookieStore.open(file);

        assertEquals(0, store.size());
    }
}
