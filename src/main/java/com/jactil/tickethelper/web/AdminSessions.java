package com.jactil.tickethelper.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 管理员登录态。内存表，进程重启后需要重新登录（可接受）。 */
public final class AdminSessions {

    public static final String COOKIE = "sid";

    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;

    public AdminSessions() {
        this(Duration.ofDays(7));
    }

    public AdminSessions(Duration ttl) {
        this.ttl = ttl;
    }

    public String create() {
        String sid = UUID.randomUUID().toString().replace("-", "");
        sessions.put(sid, Instant.now().plus(ttl));
        return sid;
    }

    public boolean valid(String sid) {
        if (sid == null || sid.isBlank()) {
            return false;
        }
        Instant expiry = sessions.get(sid);
        if (expiry == null) {
            return false;
        }
        if (expiry.isBefore(Instant.now())) {
            sessions.remove(sid);
            return false;
        }
        return true;
    }

    public void revoke(String sid) {
        if (sid != null) {
            sessions.remove(sid);
        }
    }

    public int size() {
        return sessions.size();
    }
}
