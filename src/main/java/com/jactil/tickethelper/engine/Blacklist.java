package com.jactil.tickethelper.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 小黑屋：某个「车次+席别」提交失败后冷却一段时间，避免反复撞缓存票、污染整趟查询的配额。
 * 首次 30 秒；同一目标连续 3 次失败升到 5 分钟。
 */
public final class Blacklist {

    public static final Duration FIRST_COOLDOWN = Duration.ofSeconds(30);
    public static final Duration ESCALATED_COOLDOWN = Duration.ofMinutes(5);
    public static final int STRIKE_THRESHOLD = 3;

    private record Entry(Instant until, int strikes) {
    }

    private final Map<String, Entry> entries = new HashMap<>();

    private static String key(String train, String seat) {
        return train + "|" + seat;
    }

    public synchronized boolean blocked(String train, String seat, Instant now) {
        Entry e = entries.get(key(train, seat));
        return e != null && now.isBefore(e.until());
    }

    /** 记一次失败，返回累计次数。 */
    public synchronized int hit(String train, String seat, Instant now) {
        String k = key(train, seat);
        Entry old = entries.get(k);
        int strikes = old == null ? 1 : old.strikes() + 1;
        Duration cooldown = strikes >= STRIKE_THRESHOLD ? ESCALATED_COOLDOWN : FIRST_COOLDOWN;
        entries.put(k, new Entry(now.plus(cooldown), strikes));
        return strikes;
    }

    public synchronized void forget(String train, String seat) {
        entries.remove(key(train, seat));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
