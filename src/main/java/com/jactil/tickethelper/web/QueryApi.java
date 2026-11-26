package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.query.SeatInfo;
import com.jactil.tickethelper.core.query.TrainRow;
import io.javalin.Javalin;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 立即查询余票（不建任务），用于页面上的「先看看有没有票」。 */
public final class QueryApi {

    private static final int MAX_TRAINS = 200;

    private QueryApi() {
    }

    public record QueryRequest(String date, String from, String to) {
    }

    public static void register(Javalin app, AppContext ctx) {
        app.post("/api/query", c -> {
            QueryRequest req = c.bodyAsClass(QueryRequest.class);
            if (req == null || req.date() == null || req.from() == null || req.to() == null) {
                throw ApiException.badRequest("需要 date / from / to");
            }
            LocalDate date;
            try {
                date = LocalDate.parse(req.date());
            } catch (RuntimeException e) {
                throw ApiException.badRequest("日期格式必须是 yyyy-MM-dd：" + req.date());
            }
            String from = resolve(ctx, req.from());
            String to = resolve(ctx, req.to());
            if (from.equals(to)) {
                throw ApiException.badRequest("出发站和到达站不能相同");
            }
            if (!ctx.loggedIn()) {
                throw ApiException.conflict("还没登录 12306，请先扫码登录");
            }
            List<TrainRow> trains;
            try {
                trains = ctx.query.query(date, from, to, "ADULT", ctx.stations::nameOf);
            } catch (IOException e) {
                throw ApiException.serverError("查询失败：" + e.getMessage());
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (TrainRow t : trains) {
                if (out.size() >= MAX_TRAINS) {
                    break;
                }
                out.add(view(t));
            }
            c.json(Map.of("count", out.size(), "total", trains.size(), "trains", out));
        });
    }

    private static String resolve(AppContext ctx, String raw) {
        String v = raw.trim();
        if (v.length() == 3 && ctx.stations.knows(v.toUpperCase())) {
            return v.toUpperCase();
        }
        return ctx.stations.codeOf(v)
                .orElseThrow(() -> ApiException.badRequest("不是有效车站：" + v));
    }

    static Map<String, Object> view(TrainRow t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trainNo", t.trainNo());
        out.put("from", t.fromCode());
        out.put("fromName", t.fromName());
        out.put("to", t.toCode());
        out.put("toName", t.toName());
        out.put("startTime", t.startTime());
        out.put("arriveTime", t.arriveTime());
        out.put("duration", t.duration());
        Map<String, Object> seats = new LinkedHashMap<>();
        for (Map.Entry<String, SeatInfo> e : t.seats().entrySet()) {
            SeatInfo s = e.getValue();
            if (!s.exists()) {
                continue;
            }
            Map<String, Object> seat = new LinkedHashMap<>();
            seat.put("count", s.count());
            seat.put("raw", s.raw());
            seat.put("available", s.available());
            java.math.BigDecimal price = t.price(e.getKey());
            seat.put("price", price == null ? null : price.toPlainString());
            seats.put(e.getKey(), seat);
        }
        out.put("seats", seats);
        return out;
    }
}
