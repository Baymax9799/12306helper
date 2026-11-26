package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.config.Settings;
import com.jactil.tickethelper.core.store.Store;
import io.javalin.Javalin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运维面：闸门、订单、事件流、设置。 */
public final class OpsApi {

    private static final String MASK = "******";

    private OpsApi() {
    }

    public record UnlockRequest(String action) {
    }

    public static void register(Javalin app, AppContext ctx) {
        app.get("/api/gate", c -> {
            var s = ctx.gate.status();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", s.state());
            out.put("reason", s.reason() == null ? "" : s.reason());
            out.put("created", s.created());
            out.put("paid", s.paid());
            out.put("cancelled", s.cancelled());
            out.put("limit", s.limit());
            out.put("day", s.day());
            out.put("canSubmit", ctx.gate.canSubmit());
            c.json(out);
        });

        app.post("/api/gate/unlock", c -> {
            UnlockRequest req = c.bodyAsClass(UnlockRequest.class);
            String action = req == null || req.action() == null ? "" : req.action().toUpperCase();
            if (!action.equals("PAID") && !action.equals("CANCELLED")) {
                throw ApiException.badRequest("action 只能是 PAID 或 CANCELLED");
            }
            ctx.gate.unlock(action);
            ctx.bus.info(null, "闸门已解锁（" + ("PAID".equals(action) ? "已付款" : "已取消") + "）");
            c.json(Map.of("ok", true, "state", ctx.store.gate().state()));
        });

        app.get("/api/orders", c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Store.OrderRow o : ctx.store.findAllOrders()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", o.id());
                row.put("taskId", o.taskId());
                row.put("orderNo", o.orderNo());
                row.put("trainNo", o.trainNo());
                row.put("seatType", o.seatType());
                row.put("amount", o.amount());
                row.put("status", o.status());
                row.put("createdAt", o.createdAt().toString());
                out.add(row);
            }
            c.json(out);
        });

        app.post("/api/orders/reconcile", c -> {
            int found = ctx.reconciler.runOnce();
            c.json(Map.of("found", found, "gate", ctx.store.gate().state()));
        });

        app.get("/api/events", c -> {
            long after = parseLong(c.queryParam("after"), 0);
            int limit = Math.min(500, Math.max(1, (int) parseLong(c.queryParam("limit"), 200)));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Store.EventRow e : ctx.store.eventsAfter(after, limit)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.id());
                row.put("taskId", e.taskId());
                row.put("level", e.level());
                row.put("message", e.message());
                row.put("createdAt", e.createdAt().toString());
                out.add(row);
            }
            c.json(Map.of("events", out, "lastId", ctx.store.lastEventId()));
        });

        app.get("/api/settings", c -> c.json(mask(ctx.settings())));

        app.get("/api/meta", c -> c.json(Map.of(
                "seats", com.jactil.tickethelper.core.query.SeatCatalog.COLUMNS.stream()
                        .map(com.jactil.tickethelper.core.query.SeatCatalog.Column::name).toList(),
                "minIntervalMs", com.jactil.tickethelper.engine.Timing.MIN_INTERVAL_MS,
                "maxIntervalMs", com.jactil.tickethelper.engine.Timing.MAX_INTERVAL_MS,
                "dailyOrderLimitMax", 3,
                "stationCount", ctx.stations.size())));

        app.post("/api/settings/test-notify", c -> {
            java.util.List<String> results = ctx.notifier.test();
            c.json(Map.of("message", String.join("；", results), "results", results));
        });

        app.put("/api/settings", c -> {
            Settings incoming = c.bodyAsClass(Settings.class);
            if (incoming == null) {
                throw ApiException.badRequest("请求体为空");
            }
            Settings current = ctx.settings();
            if (MASK.equals(incoming.smtp.password)) {
                incoming.smtp.password = current.smtp.password;
            }
            if (MASK.equals(incoming.wechat.key)) {
                incoming.wechat.key = current.wechat.key;
            }
            incoming.adminPasswordHash = current.adminPasswordHash;
            if (incoming.intervalMs < 500 || incoming.intervalMs > 5000) {
                throw ApiException.badRequest("轮询间隔必须在 500~5000 毫秒");
            }
            if (incoming.dailyOrderLimit < 1 || incoming.dailyOrderLimit > 3) {
                throw ApiException.badRequest("每日单量上限必须在 1~3（12306 规则上限 3）");
            }
            ctx.updateSettings(incoming);
            c.json(Map.of("ok", true));
        });

        app.post("/api/settings/admin-password", c -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = c.bodyAsClass(Map.class);
            String oldPwd = String.valueOf(body.get("oldPassword"));
            String newPwd = String.valueOf(body.get("newPassword"));
            if (!com.jactil.tickethelper.core.security.Passwords.verify(oldPwd, ctx.settings().adminPasswordHash)) {
                throw ApiException.unauthorized("原口令不正确");
            }
            if (newPwd.length() < 6) {
                throw ApiException.badRequest("新口令至少 6 位");
            }
            Settings s = ctx.settings();
            s.adminPasswordHash = com.jactil.tickethelper.core.security.Passwords.hash(newPwd);
            ctx.updateSettings(s);
            c.json(Map.of("ok", true));
        });
    }

    static Settings mask(Settings s) {
        Settings copy = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .convertValue(s, Settings.class);
        if (copy.smtp.password != null && !copy.smtp.password.isEmpty()) {
            copy.smtp.password = MASK;
        }
        if (copy.wechat.key != null && !copy.wechat.key.isEmpty()) {
            copy.wechat.key = MASK;
        }
        copy.adminPasswordHash = "";
        return copy;
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
