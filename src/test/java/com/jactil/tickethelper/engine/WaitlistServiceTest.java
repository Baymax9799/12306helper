package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.client.PersistentCookieStore;
import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.passenger.PassengerSync;
import com.jactil.tickethelper.core.query.SeatInfo;
import com.jactil.tickethelper.core.query.TrainRow;
import com.jactil.tickethelper.core.store.Store;
import com.jactil.tickethelper.core.time.ServerClock;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitlistServiceTest {

    @TempDir
    Path tmp;

    private MockWebServer server;
    private TicketClient client;

    private Store.TaskRow task() {
        return new Store.TaskRow(1, "候补", "RUNNING", "WAITLIST", "IZQ", "IOQ",
                List.of("2026-12-01"), List.of(), List.of("二等座"), List.of(1L), 1000, null,
                Instant.now(), Instant.now());
    }

    private TrainRow train() {
        return new TrainRow("G1001", "IZQ", "IOQ", "广州南", "深圳北", "08:00", "08:40", "00:40",
                "20261201", "S", "L", Map.of("二等座", new SeatInfo("O", "二等座", 0, "候补")),
                Map.of("二等座", new java.math.BigDecimal("74.50")));
    }

    private List<PassengerSync.Remote> pax() {
        return List.of(new PassengerSync.Remote("P1", "张三", "1", "身份证", "110101199001011234", "成人票"));
    }

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
    void disabledByDefaultDoesNotCallServer() {
        WaitlistService service = new WaitlistService(client, server.url("/").toString(), false);

        WaitlistService.Outcome outcome = service.submit(task(), train(), "二等座", pax());

        assertEquals(WaitlistService.Result.DISABLED, outcome.result());
        assertTrue(outcome.message().contains("退化"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void enabledSubmitsAndReportsCreated() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":true,\"data\":{\"flag\":true,\"orderId\":\"W123\"}}"));
        WaitlistService service = new WaitlistService(client, server.url("/").toString(), true);

        WaitlistService.Outcome outcome = service.submit(task(), train(), "二等座", pax());

        assertEquals(WaitlistService.Result.CREATED, outcome.result());
        assertEquals("W123", outcome.orderNo());
        assertTrue(server.takeRequest().getBody().readUtf8().contains("passengerInfo"));
    }

    @Test
    void rejectionIsUnavailableNotError() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":false,\"messages\":[\"当前无法候补\"]}"));
        WaitlistService service = new WaitlistService(client, server.url("/").toString(), true);

        WaitlistService.Outcome outcome = service.submit(task(), train(), "二等座", pax());

        assertEquals(WaitlistService.Result.UNAVAILABLE, outcome.result());
        assertTrue(outcome.message().contains("当前无法候补"));
    }

    @Test
    void formCarriesPassengerAndJourney() {
        Map<String, String> form = WaitlistService.buildForm(task(), train(), "二等座", pax());

        assertTrue(form.get("passengerInfo").contains("张三"), form.toString());
        assertEquals("IZQ,IOQ,2026-12-01", form.get("journey"));
        assertEquals("二等座", form.get("seatType"));
        assertTrue(form.get("train").startsWith("G1001"));
    }
}
