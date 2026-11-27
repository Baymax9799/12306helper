package com.jactil.tickethelper.notify;

import com.jactil.tickethelper.core.config.Settings;
import com.jactil.tickethelper.engine.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 通知分发：按当前设置组装渠道、去重、重试。
 * 所有发送都在独立线程里做，绝不阻塞抓票线程。
 */
public final class NotifyDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotifyDispatcher.class);
    private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(5);

    private final Supplier<Settings> settings;
    private final Supplier<List<Notifier>> channelsOverride;
    private final Map<String, Instant> recent = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notify");
        t.setDaemon(true);
        return t;
    });

    public NotifyDispatcher(Supplier<Settings> settings) {
        this(settings, null);
    }

    /** 测试用：直接指定渠道，绕开配置组装。 */
    public NotifyDispatcher(Supplier<Settings> settings, Supplier<List<Notifier>> channelsOverride) {
        this.settings = settings;
        this.channelsOverride = channelsOverride;
    }

    public List<Notifier> channels() {
        if (channelsOverride != null) {
            return channelsOverride.get();
        }
        Settings s = settings.get();
        List<Notifier> list = new ArrayList<>();
        if (s.emailEnabled) {
            list.add(new EmailNotifier(s.smtp));
        }
        if (s.wechatEnabled) {
            list.add(new WeChatNotifier(s.wechat));
        }
        if (s.webhookEnabled) {
            list.add(new WebhookNotifier(s.webhook));
        }
        return list;
    }

    /** 异步派发；同样的内容 5 分钟内只发一次。 */
    public void dispatch(String title, String body) {
        String key = digest(title + "\n" + body);
        Instant last = recent.get(key);
        if (last != null && last.plus(DEDUPE_WINDOW).isAfter(Instant.now())) {
            return;
        }
        recent.put(key, Instant.now());
        pool.submit(() -> sendAll(title, body));
    }

    /** 同步测试，返回每个渠道的结果，供设置页显示。 */
    public List<String> test() {
        List<String> results = new ArrayList<>();
        List<Notifier> list = channels();
        if (list.isEmpty()) {
            results.add("没有启用任何通知渠道");
            return results;
        }
        for (Notifier notifier : list) {
            if (!notifier.enabled()) {
                results.add(notifier.name() + "：未配置完整，跳过");
                continue;
            }
            try {
                notifier.send("12306Helper 测试通知", "如果你收到这条，说明通知配置可用。");
                results.add(notifier.name() + "：成功");
            } catch (Exception e) {
                results.add(notifier.name() + "：失败 " + e.getMessage());
            }
        }
        return results;
    }

    private void sendAll(String title, String body) {
        for (Notifier notifier : channels()) {
            if (!notifier.enabled()) {
                continue;
            }
            boolean ok = false;
            String lastError = "";
            for (int attempt = 0; attempt < 3 && !ok; attempt++) {
                try {
                    notifier.send(title, body);
                    ok = true;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    try {
                        Thread.sleep(1500L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (ok) {
                log.info("通知已发送：{} - {}", notifier.name(), title);
            } else {
                log.warn("通知发送失败：{} - {}：{}", notifier.name(), title, lastError);
            }
        }
    }

    /** 把事件翻译成人类能看懂的通知。 */
    public void onEvent(EventBus.Event event) {
        switch (event.type()) {
            case "ORDER_SUCCESS" -> dispatch("抢票成功", event.message() + "\n请立即登录 12306 付款（30 分钟内）。");
            case "MATCH" -> dispatch("发现有票", event.message());
            default -> {
                if ("WARN".equals(event.level())) {
                    dispatch("任务提醒", event.message());
                }
            }
        }
    }

    private static String digest(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
