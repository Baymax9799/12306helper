package com.jactil.tickethelper.core.time;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用 12306 响应头 Date 校准的时钟。容器里没有 NTP，抢票对秒敏感，所以以服务器为准。
 */
public final class ServerClock {

    private final AtomicLong offsetMillis = new AtomicLong(0);
    private volatile Instant lastSyncedAt;

    /** 服务器时间与本机时间的差（毫秒）。 */
    public long offsetMillis() {
        return offsetMillis.get();
    }

    public Instant now() {
        return Instant.ofEpochMilli(System.currentTimeMillis() + offsetMillis.get());
    }

    public Instant lastSyncedAt() {
        return lastSyncedAt;
    }

    public void sync(long serverEpochMillis) {
        offsetMillis.set(serverEpochMillis - System.currentTimeMillis());
        lastSyncedAt = Instant.now();
    }

    /** 解析 RFC 1123 的 Date 头；解析失败静默忽略，保留上一次偏移。 */
    public boolean syncFromDateHeader(String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String cleaned = header.trim().replaceFirst("^[A-Za-z]{3},\\s*", "");
        try {
            ZonedDateTime parsed = ZonedDateTime.parse(cleaned,
                    DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss zzz", Locale.US));
            sync(parsed.toInstant().toEpochMilli());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
