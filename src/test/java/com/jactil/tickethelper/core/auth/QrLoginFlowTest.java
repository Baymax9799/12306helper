package com.jactil.tickethelper.core.auth;

import com.jactil.tickethelper.core.client.PersistentCookieStore;
import com.jactil.tickethelper.core.client.TicketClient;
import com.jactil.tickethelper.core.time.ServerClock;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrLoginFlowTest {

    @TempDir
    Path tmp;

    private MockWebServer server;
    private QrLoginFlow flow;
    private final AtomicReference<String> checkQrBody = new AtomicReference<>("{\"result_code\":0}");
    private final AtomicReference<String> uamauthCalled = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/otn/resources/login.html")) {
                    return ok("<html></html>");
                }
                if (path.startsWith("/otn/login/conf")) {
                    return ok("{\"is_login\":false}");
                }
                if (path.startsWith("/passport/web/create-qr64")) {
                    return ok("{\"result_code\":0,\"uuid\":\"uuid-1\",\"image\":\"BASE64IMAGE\"}");
                }
                if (path.startsWith("/passport/web/checkqr")) {
                    return ok(checkQrBody.get());
                }
                if (path.startsWith("/passport/web/auth/uamtk")) {
                    return ok("{\"result_code\":0,\"newapptk\":\"APP_TK\"}");
                }
                if (path.startsWith("/otn/uamauthclient")) {
                    uamauthCalled.set(request.getBody().readUtf8());
                    return ok("{\"result_code\":0}");
                }
                if (path.startsWith("/otn/index/initMy12306Api")) {
                    return ok("{\"data\":{\"user_name\":\"张三\",\"login_name\":\"zhangsan\"}}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();

        TicketClient client = new TicketClient(
                PersistentCookieStore.open(tmp.resolve("cookies.json")), new ServerClock());
        flow = new QrLoginFlow(client, server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private MockResponse ok(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body);
    }

    @Test
    void createReturnsUuidAndImage() throws IOException {
        QrLoginFlow.QrCode qr = flow.create();

        assertEquals("uuid-1", qr.uuid());
        assertEquals("BASE64IMAGE", qr.imageBase64());
    }

    @Test
    void createFailsWhenServerRejects() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/passport/web/create-qr64")) {
                    return ok("{\"result_code\":1,\"result_message\":\"系统忙\"}");
                }
                return ok("{}");
            }
        });

        IOException e = assertThrows(IOException.class, () -> flow.create());
        assertTrue(e.getMessage().contains("系统忙"), e.getMessage());
    }

    @Test
    void pollReportsWaitingThenScanned() throws IOException {
        checkQrBody.set("{\"result_code\":0}");
        assertEquals(QrLoginFlow.State.WAITING, flow.poll("uuid-1").state());

        checkQrBody.set("{\"result_code\":1}");
        assertEquals(QrLoginFlow.State.SCANNED, flow.poll("uuid-1").state());

        checkQrBody.set("{\"result_code\":3}");
        assertEquals(QrLoginFlow.State.EXPIRED, flow.poll("uuid-1").state());
    }

    @Test
    void successfulScanCompletesAuthChain() throws IOException {
        checkQrBody.set("{\"result_code\":2,\"uamtk\":\"LOGIN_TK\"}");

        QrLoginFlow.PollResult result = flow.poll("uuid-1");

        assertEquals(QrLoginFlow.State.LOGGED_IN, result.state());
        assertEquals("张三", result.username());
        assertTrue(uamauthCalled.get().contains("tk=APP_TK"), "uamauthclient body=" + uamauthCalled.get());
    }
}
