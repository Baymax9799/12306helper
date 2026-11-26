package com.jactil.tickethelper.core.client;

import com.jactil.tickethelper.core.time.ServerClock;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketClientTest {

    @TempDir
    Path tmp;

    private MockWebServer server;
    private TicketClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new TicketClient(PersistentCookieStore.open(tmp.resolve("c.json")), new ServerClock());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void getSendsBrowserLikeHeaders() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));

        client.get(server.url("/otn/leftTicket/queryI").toString());

        RecordedRequest request = server.takeRequest();
        assertTrue(request.getHeader("User-Agent").contains("Chrome"), "UA=" + request.getHeader("User-Agent"));
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"));
        assertTrue(request.getHeader("Referer").startsWith("https://kyfw.12306.cn/"));
    }

    @Test
    void postSendsFormBody() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));

        client.post(server.url("/passport/web/login").toString(), Map.of("username", "u1", "appid", "otn"));

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getBody().readUtf8().contains("username=u1"));
        assertTrue(request.getHeader("Content-Type").startsWith("application/x-www-form-urlencoded"));
    }

    @Test
    void clockSyncsFromDateHeader() throws Exception {
        server.enqueue(new MockResponse().setBody("{}")
                .setHeader("Date", "Wed, 26 Nov 2026 01:00:00 GMT"));
        long before = System.currentTimeMillis();

        client.get(server.url("/otn/").toString());

        long expected = java.time.Instant.parse("2026-11-26T01:00:00Z").toEpochMilli()
                - (before + System.currentTimeMillis()) / 2;
        assertTrue(Math.abs(client.clock().offsetMillis() - expected) < 5_000,
                "offset=" + client.clock().offsetMillis() + " expected≈" + expected);
    }

    @Test
    void redirectIsSurfacedAsRiskSignal() {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "https://kyfw.12306.cn/otn/resources/login.html"));

        assertThrows(TicketClient.RedirectedException.class,
                () -> client.get(server.url("/otn/confirmPassenger/initDc").toString()));
    }

    @Test
    void nonSuccessStatusThrows() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThrows(java.io.IOException.class, () -> client.get(server.url("/otn/").toString()));
    }
}
