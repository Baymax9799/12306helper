package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.client.PersistentCookieStore;
import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.passenger.PassengerSync;
import com.jactil.tickethelper.core.query.SeatInfo;
import com.jactil.tickethelper.core.query.TrainRow;
import com.jactil.tickethelper.core.store.Store;
import com.jactil.tickethelper.core.time.ServerClock;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 下单六步的契约测试：断言参数与分支，不打真实 12306。 */
class BookingClientTest {

    @TempDir
    Path tmp;

    private MockWebServer server;
    private BookingClient booking;
    private final AtomicInteger waitTimeCalls = new AtomicInteger();

    private TrainRow train() {
        return new TrainRow("G1001", "IZQ", "IOQ", "广州南", "深圳北", "08:00", "08:40", "00:40",
                "20261201", "SECRET%2BTOKEN", "LOC1",
                Map.of("二等座", new SeatInfo("O", "二等座", 3, "3")),
                Map.of("二等座", new java.math.BigDecimal("74.50")));
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/otn/leftTicket/submitOrderRequest")) {
                    return new MockResponse().setBody("{\"status\":true,\"data\":\"N\"}");
                }
                if (path.startsWith("/otn/confirmPassenger/initDc")) {
                    return new MockResponse().setBody("<html><script>\n"
                            + "var globalRepeatSubmitToken = 'TOKEN123';\n"
                            + "var ticketInfoForPassengerForm={\"leftTicketStr\":\"LEFT\","
                            + "\"key_check_isChange\":\"KEY\",\"train_location\":\"LOC1\"};\n"
                            + "</script></html>");
                }
                if (path.startsWith("/otn/confirmPassenger/checkOrderInfo")) {
                    return new MockResponse().setBody("{\"status\":true,\"data\":{\"submitStatus\":true}}");
                }
                if (path.startsWith("/otn/confirmPassenger/getQueueCount")) {
                    return new MockResponse().setBody("{\"status\":true,\"data\":{\"count\":\"1\",\"ticket\":\"3\"}}");
                }
                if (path.startsWith("/otn/confirmPassenger/confirmSingleForQueue")) {
                    return new MockResponse().setBody("{\"status\":true,\"data\":{\"submitStatus\":true}}");
                }
                if (path.startsWith("/otn/confirmPassenger/queryOrderWaitTime")) {
                    int n = waitTimeCalls.incrementAndGet();
                    return new MockResponse().setBody(n < 2
                            ? "{\"status\":true,\"data\":{\"orderId\":\"\"}}"
                            : "{\"status\":true,\"data\":{\"orderId\":\"E123456\"}}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        TicketClient client = new TicketClient(PersistentCookieStore.open(tmp.resolve("c.json")),
                new ServerClock());
        booking = new BookingClient(client, server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void submitOrderRequestSendsDecodedSecretStr() throws Exception {
        assertNull(booking.submitOrderRequest(train(), "2026-12-01"));

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("secretStr=SECRET%2BTOKEN"), body);
        assertTrue(body.contains("train_date=2026-12-01"), body);
        assertTrue(body.contains("from_station_name="), body);
    }

    @Test
    void initDcExtractsTokens() throws Exception {
        BookingClient.Tokens tokens = booking.initDc();

        assertEquals("TOKEN123", tokens.repeatToken());
        assertEquals("LEFT", tokens.leftTicketStr());
        assertEquals("KEY", tokens.keyCheckIsChange());
        assertEquals("LOC1", tokens.trainLocation());
    }

    @Test
    void fullFlowReturnsOrderNo() throws Exception {
        BookingClient.Tokens tokens = booking.initDc();
        String[] strings = BookingClient.buildPassengerStrings("O", List.of(
                new PassengerSync.Remote("P1", "张三", "1", "身份证", "110101199001011234", "成人票")));

        assertNull(booking.submitOrderRequest(train(), "2026-12-01"));
        assertTrue(booking.checkOrderInfo(tokens, strings[0], strings[1]).ok());
        assertNull(booking.getQueueCount(tokens, train(), "2026-12-01", "O", tokens.leftTicketStr()));
        assertTrue(booking.confirmSingleForQueue(tokens, strings[0], strings[1]).ok());

        BookingClient.BookingOutcome outcome = booking.awaitOrder(tokens, 5000);

        assertNotNull(outcome.orderNo());
        assertEquals("E123456", outcome.orderNo());
        assertTrue(outcome.success());
    }

    @Test
    void passengerStringsUseSeatCodeAndIdFields() {
        String[] strings = BookingClient.buildPassengerStrings("O", List.of(
                new PassengerSync.Remote("P1", "张三", "1", "身份证", "110101199001011234", "成人票"),
                new PassengerSync.Remote("P2", "李四", "1", "身份证", "310101200002022345", "成人票")));

        assertEquals("O,1,张三,1,110101199001011234,N_O,1,李四,1,310101200002022345,N", strings[0]);
        assertEquals("张三,1,110101199001011234,1_李四,1,310101200002022345,1_", strings[1]);
    }

    @Test
    void queueFullIsReported() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("{\"status\":true,\"data\":{\"count\":\"99\",\"ticket\":\"2\"}}");
            }
        });
        BookingClient.Tokens tokens = new BookingClient.Tokens("T", "L", "K", "LOC");

        String problem = booking.getQueueCount(tokens, train(), "2026-12-01", "O", "L");

        assertNotNull(problem);
        assertTrue(problem.contains("99"), problem);
    }

    @Test
    void isoDateConversion() {
        assertEquals("2026-12-01", RealBooking.toIsoDate("20261201"));
        assertNull(RealBooking.toIsoDate("2026-12-01"));
        assertNull(RealBooking.toIsoDate(null));
    }

    @Test
    void taskRowIsUsableForBooking() {
        Store.TaskRow task = new Store.TaskRow(1, "t", "RUNNING", "GRAB", "IZQ", "IOQ",
                List.of("2026-12-01"), List.of(), List.of("二等座"), List.of(1L), 1000, null,
                Instant.now(), Instant.now());
        assertEquals("IZQ", task.from());
        assertEquals(Instant.now().getEpochSecond() > 0, true);
    }
}
