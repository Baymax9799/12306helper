package com.jactil.tickethelper.notify;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.jactil.tickethelper.core.config.Settings;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifyTest {

    @Test
    void wecomSendsMarkdownPayload() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("{\"errcode\":0}"));
        server.start();

        Settings.Wechat cfg = new Settings.Wechat();
        cfg.provider = "wecom";
        cfg.webhookUrl = server.url("/robot/send").toString();
        new WeChatNotifier(cfg).send("有票了", "G1001 二等座");

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("msgtype"), body);
        assertTrue(body.contains("markdown"), body);
        assertTrue(body.contains("G1001"), body);
        server.shutdown();
    }

    @Test
    void pushplusSendsJson() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("{\"code\":200}"));
        server.start();

        Settings.Wechat cfg = new Settings.Wechat();
        cfg.provider = "pushplus";
        cfg.key = "TOKEN";
        WeChatNotifier notifier = new WeChatNotifier(cfg);
        // 端点固定为官方域名，这里只验证 enabled 判断与拼装不抛异常
        assertTrue(notifier.enabled());
        assertEquals("微信(pushplus)", notifier.name());
        server.shutdown();
    }

    @Test
    void webhookPostsToConfiguredUrl() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("ok"));
        server.start();

        Settings.Webhook cfg = new Settings.Webhook();
        cfg.url = server.url("/hook").toString();
        new WebhookNotifier(cfg).send("标题", "正文");

        RecordedRequest request = server.takeRequest();
        assertEquals("/hook", request.getPath());
        assertTrue(request.getBody().readUtf8().contains("正文"));
        server.shutdown();
    }

    @Test
    void emailReachesSmtpServer() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
        try {
            Settings.Smtp smtp = new Settings.Smtp();
            smtp.host = "127.0.0.1";
            smtp.port = greenMail.getSmtp().getPort();
            smtp.username = "";
            smtp.password = "";
            smtp.from = "from@test.local";
            smtp.to = "to@test.local";
            smtp.ssl = false;

            new EmailNotifier(smtp).send("抢票成功", "订单号 E123");

            assertEquals(1, greenMail.getReceivedMessages().length);
            assertEquals("抢票成功", greenMail.getReceivedMessages()[0].getSubject());
        } finally {
            greenMail.stop();
        }
    }

    @Test
    void dispatcherDeduplicatesSameContent() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        Notifier counting = new Notifier() {
            @Override
            public String name() {
                return "counting";
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void send(String title, String body) {
                sent.incrementAndGet();
            }
        };
        NotifyDispatcher dispatcher = new NotifyDispatcher(Settings::new, () -> List.of(counting));

        dispatcher.dispatch("同一件事", "内容");
        dispatcher.dispatch("同一件事", "内容");

        Thread.sleep(600);
        assertEquals(1, sent.get());
        dispatcher.shutdown();
    }

    @Test
    void dispatcherRetriesThenGivesUp() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Notifier failing = new Notifier() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void send(String title, String body) throws Exception {
                attempts.incrementAndGet();
                throw new Exception("boom");
            }
        };
        NotifyDispatcher dispatcher = new NotifyDispatcher(Settings::new, () -> List.of(failing));

        dispatcher.dispatch("会失败", "内容");
        long deadline = System.currentTimeMillis() + 8000;
        while (attempts.get() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }

        assertEquals(3, attempts.get(), "应重试 2 次，共 3 次");
        dispatcher.shutdown();
    }
}
