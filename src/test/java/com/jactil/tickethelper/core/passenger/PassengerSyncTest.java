package com.jactil.tickethelper.core.passenger;

import com.jactil.tickethelper.core.client.PersistentCookieStore;
import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.time.ServerClock;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassengerSyncTest {

    @TempDir
    Path tmp;

    private MockWebServer server;
    private PassengerSync sync;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        TicketClient client = new TicketClient(
                PersistentCookieStore.open(tmp.resolve("c.json")), new ServerClock());
        sync = new PassengerSync(client, server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void mapsPassengerFields() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"data":{"normal_passengers":[
                  {"code":"P1","passenger_name":"张三","passenger_id_type_code":"1",
                   "passenger_id_type_name":"中国居民身份证","passenger_id_no":"110101199001011234",
                   "passenger_type":"1","passenger_type_name":"成人票"},
                  {"code":"P2","passenger_name":"李四","passenger_id_type_code":"1",
                   "passenger_id_type_name":"中国居民身份证","passenger_id_no":"310101200002022345",
                   "passenger_type":"3","passenger_type_name":"学生票"}
                ]}}"""));

        List<PassengerSync.Remote> rows = sync.fetch();

        assertEquals(2, rows.size());
        PassengerSync.Remote first = rows.get(0);
        assertEquals("P1", first.pid());
        assertEquals("张三", first.name());
        assertEquals("110101199001011234", first.idNo());
        assertEquals("成人票", first.ticketType());
        assertEquals("学生票", rows.get(1).ticketType());

        RecordedRequest request = server.takeRequest();
        assertTrue(request.getPath().contains("getPassengerDTOs"), request.getPath());
    }

    @Test
    void masksNameAndIdNo() {
        assertEquals("张**", PassengerSync.maskName("张三丰"));
        assertEquals("张*", PassengerSync.maskName("张三"));
        assertEquals("张", PassengerSync.maskName("张"));
        assertEquals("****1234", PassengerSync.maskIdNo("110101199001011234"));
        assertEquals("****", PassengerSync.maskIdNo("12"));
    }
}
