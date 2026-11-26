package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.query.SeatCatalog;
import com.jactil.tickethelper.core.station.StationRegistry;
import com.jactil.tickethelper.core.store.Store;
import com.jactil.tickethelper.engine.Timing;
import io.javalin.Javalin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 任务的增删改查与启停。 */
public final class TaskApi {

    private TaskApi() {
    }

    public record TaskRequest(String name, String mode, String from, String to,
                              List<String> dates, List<String> trains, List<String> seatTypes,
                              List<Long> passengerIds, Integer intervalMs, String startAt) {
    }

    public static void register(Javalin app, AppContext ctx) {
        app.get("/api/tasks", c -> c.json(ctx.store.findAllTasks().stream().map(t -> view(ctx, t)).toList()));

        app.get("/api/tasks/{id}", c -> {
            long id = id(c.pathParam("id"));
            Store.TaskRow task = ctx.store.findTask(id)
                    .orElseThrow(() -> ApiException.notFound("任务不存在"));
            c.json(view(ctx, task));
        });

        app.post("/api/tasks", c -> {
            TaskRequest req = c.bodyAsClass(TaskRequest.class);
            Store.TaskRow row = toRow(ctx, 0, req);
            long id = ctx.store.insertTask(row);
            c.status(201).json(view(ctx, ctx.store.findTask(id).orElseThrow()));
        });

        app.put("/api/tasks/{id}", c -> {
            long id = id(c.pathParam("id"));
            Store.TaskRow existing = ctx.store.findTask(id)
                    .orElseThrow(() -> ApiException.notFound("任务不存在"));
            TaskRequest req = c.bodyAsClass(TaskRequest.class);
            Store.TaskRow updated = toRow(ctx, id, req);
            ctx.store.updateTask(updated);
            c.json(view(ctx, ctx.store.findTask(id).orElseThrow()));
        });

        app.delete("/api/tasks/{id}", c -> {
            long id = id(c.pathParam("id"));
            ctx.engine.stop(id);
            ctx.store.deleteTask(id);
            c.json(Map.of("ok", true));
        });

        app.post("/api/tasks/{id}/start", c -> {
            long id = id(c.pathParam("id"));
            Store.TaskRow task = ctx.store.findTask(id)
                    .orElseThrow(() -> ApiException.notFound("任务不存在"));
            if (!ctx.loggedIn()) {
                throw ApiException.conflict("还没登录 12306，请先扫码登录");
            }
            if (!ctx.gate.canSubmit()) {
                throw ApiException.conflict("账号闸门已关闭：" + ctx.gate.status().reason());
            }
            if (!ctx.engine.start(task)) {
                throw ApiException.conflict("任务已完成，不能再启动");
            }
            c.json(Map.of("ok", true, "status", "RUNNING"));
        });

        app.post("/api/tasks/{id}/stop", c -> {
            long id = id(c.pathParam("id"));
            ctx.engine.stop(id);
            c.json(Map.of("ok", true, "status", "PAUSED"));
        });
    }

    static long id(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("任务 id 不合法：" + raw);
        }
    }

    static Map<String, Object> view(AppContext ctx, Store.TaskRow t) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", t.id());
        out.put("name", t.name());
        out.put("status", t.status());
        out.put("mode", t.mode());
        out.put("from", t.from());
        out.put("fromName", ctx.stations.nameOf(t.from()));
        out.put("to", t.to());
        out.put("toName", ctx.stations.nameOf(t.to()));
        out.put("dates", t.dates());
        out.put("trains", t.trains());
        out.put("seatTypes", t.seatTypes());
        out.put("passengerIds", t.passengerIds());
        out.put("intervalMs", t.intervalMs());
        out.put("startAt", t.startAt() == null ? "" : t.startAt().toString());
        out.put("running", ctx.engine.isRunning(t.id()));
        out.put("createdAt", t.createdAt().toString());
        return out;
    }

    static Store.TaskRow toRow(AppContext ctx, long id, TaskRequest req) {
        if (req == null) {
            throw ApiException.badRequest("请求体为空");
        }
        String name = req.name() == null || req.name().isBlank() ? "未命名任务" : req.name().trim();
        String mode = req.mode() == null || req.mode().isBlank() ? "GRAB" : req.mode().toUpperCase();
        if (!mode.equals("GRAB") && !mode.equals("WAITLIST")) {
            throw ApiException.badRequest("mode 只能是 GRAB 或 WAITLIST");
        }
        String from = normalizeStation(ctx, req.from(), "出发站");
        String to = normalizeStation(ctx, req.to(), "到达站");
        if (from.equals(to)) {
            throw ApiException.badRequest("出发站和到达站不能相同");
        }

        List<String> dates = new ArrayList<>(new LinkedHashSet<>(req.dates() == null ? List.of() : req.dates()));
        if (dates.isEmpty()) {
            throw ApiException.badRequest("至少选一个乘车日期");
        }
        dates.sort(String::compareTo);
        for (String d : dates) {
            try {
                LocalDate.parse(d);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("日期格式必须是 yyyy-MM-dd：" + d);
            }
        }

        List<String> seatTypes = req.seatTypes() == null ? List.of() : req.seatTypes();
        if (seatTypes.isEmpty()) {
            throw ApiException.badRequest("至少选一个席别");
        }
        Set<String> known = new LinkedHashSet<>();
        SeatCatalog.COLUMNS.forEach(col -> known.add(col.name()));
        for (String s : seatTypes) {
            if (!known.contains(s)) {
                throw ApiException.badRequest("未知席别：" + s);
            }
        }

        List<Long> passengerIds = req.passengerIds() == null ? List.of() : req.passengerIds();
        List<Store.PassengerRow> known2 = ctx.store.findPassengers(AppContext.ACCOUNT_ID);
        for (Long pid : passengerIds) {
            boolean exists = known2.stream().anyMatch(p -> p.id() == pid);
            if (!exists) {
                throw ApiException.badRequest("乘车人不存在，请先同步：" + pid);
            }
        }

        int interval = req.intervalMs() == null ? ctx.settings().intervalMs : req.intervalMs();
        if (interval < Timing.MIN_INTERVAL_MS || interval > Timing.MAX_INTERVAL_MS) {
            throw ApiException.badRequest("轮询间隔必须在 " + Timing.MIN_INTERVAL_MS
                    + "~" + Timing.MAX_INTERVAL_MS + " 毫秒之间");
        }

        Instant startAt = null;
        if (req.startAt() != null && !req.startAt().isBlank()) {
            try {
                startAt = Instant.parse(req.startAt());
            } catch (RuntimeException e) {
                throw ApiException.badRequest("开抢时刻格式必须是 ISO-8601（如 2026-12-01T08:00:00Z）");
            }
            if (startAt.isBefore(Instant.now())) {
                throw ApiException.badRequest("开抢时刻已过");
            }
        }

        Instant now = Instant.now();
        Instant created = id == 0 ? now : ctx.store.findTask(id).map(Store.TaskRow::createdAt).orElse(now);
        return new Store.TaskRow(id, name, "PENDING", mode, from, to, dates,
                req.trains() == null ? List.of() : req.trains(), seatTypes, passengerIds,
                interval, startAt, created, now);
    }

    private static String normalizeStation(AppContext ctx, String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("请填写" + label);
        }
        String value = raw.trim();
        if (value.length() == 3 && ctx.stations.knows(value.toUpperCase())) {
            return value.toUpperCase();
        }
        return ctx.stations.codeOf(value)
                .orElseThrow(() -> ApiException.badRequest(label + "不是有效车站：" + value));
    }
}
