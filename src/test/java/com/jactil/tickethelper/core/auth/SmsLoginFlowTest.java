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

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsLoginFlowTest {

    @TempDir
    Path tmp;

    private MockWebServer server;
    private SmsLoginFlow flow;
    private final AtomicReference<String> loginBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/passport/web/getMessageCode")) {
                    return new MockResponse().setBody("{\"result_code\":0,\"result_message\":\"ok\"}");
                }
                if (path.startsWith("/passport/web/login")) {
                    loginBody.set(request.getBody().readUtf8());
                    return new MockResponse().setBody("{\"result_code\":0,\"uamtk\":\"TK\"}");
                }
                if (path.startsWith("/passport/web/auth/uamtk")) {
                    return new MockResponse().setBody("{\"result_code\":0,\"newapptk\":\"NEW\"}");
                }
                if (path.startsWith("/otn/uamauthclient")) {
                    return new MockResponse().setBody("{\"result_code\":0}");
                }
                if (path.startsWith("/otn/index/initMy12306Api")) {
                    return new MockResponse().setBody("{\"data\":{\"user_name\":\"王五\"}}");
                }
                return new MockResponse().setBody("{}");
            }
        });
        server.start();
        TicketClient client = new TicketClient(PersistentCookieStore.open(tmp.resolve("c.json")),
                new ServerClock());
        flow = new SmsLoginFlow(client, server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsSmsCode() throws Exception {
        SmsLoginFlow.SendResult result = flow.sendCode("13800000000");

        assertTrue(result.ok());
    }

    @Test
    void passwordIsEncryptedNeverPlaintext() throws Exception {
        SmsLoginFlow.LoginResult result = flow.login("13800000000", "MySecret123", "123456");

        assertTrue(result.ok(), result.message());
        assertEquals("王五", result.username());
        String body = loginBody.get();
        assertFalse(body.contains("MySecret123"), "请求体里不能出现明文密码：" + body);
        assertTrue(body.contains("password=%40"), "密码应经过 SM4 且带 @ 前缀：" + body);
        assertTrue(body.contains("randCode=123456"), body);
    }

    @Test
    void cipherProducesStablePrefix() {
        String encrypted = PasswordCipher.encrypt("abc123");

        assertTrue(PasswordCipher.looksEncrypted(encrypted), encrypted);
        assertEquals(encrypted, PasswordCipher.encrypt("abc123"), "同输入应得到同密文");
    }

    @Test
    void rejectedLoginIsReported() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("{\"result_code\":1,\"result_message\":\"验证码错误\"}");
            }
        });

        SmsLoginFlow.LoginResult result = flow.login("u", "p", "000000");

        assertFalse(result.ok());
        assertTrue(result.message().contains("验证码错误"), result.message());
    }
}
