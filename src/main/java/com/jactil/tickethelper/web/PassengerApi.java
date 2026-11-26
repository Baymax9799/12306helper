package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.passenger.PassengerSync;
import com.jactil.tickethelper.core.station.StationRegistry;
import com.jactil.tickethelper.core.store.Store;
import io.javalin.Javalin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 乘车人同步（只存掩码）与站点搜索。 */
public final class PassengerApi {

    private PassengerApi() {
    }

    public static void registerPassengers(Javalin app, AppContext ctx) {
        app.get("/api/passengers", c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Store.PassengerRow p : ctx.store.findPassengers(AppContext.ACCOUNT_ID)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", p.id());
                row.put("pid", p.pid());
                row.put("nameMasked", p.nameMasked());
                row.put("idType", p.idType());
                row.put("idNoMasked", p.idNoMasked());
                row.put("ticketType", p.ticketType());
                out.add(row);
            }
            c.json(out);
        });

        app.post("/api/passengers/sync", c -> {
            if (!ctx.loggedIn()) {
                throw ApiException.conflict("还没登录 12306，请先扫码登录");
            }
            List<PassengerSync.Remote> remote;
            try {
                remote = ctx.passengerSync.fetch();
            } catch (IOException e) {
                throw ApiException.serverError("同步乘车人失败：" + e.getMessage());
            }
            List<Store.PassengerRow> rows = new ArrayList<>(remote.size());
            for (PassengerSync.Remote r : remote) {
                rows.add(new Store.PassengerRow(0, r.pid(), PassengerSync.maskName(r.name()),
                        r.idTypeName(), PassengerSync.maskIdNo(r.idNo()), r.ticketType(),
                        java.time.Instant.now()));
            }
            ctx.store.replacePassengers(AppContext.ACCOUNT_ID, rows);
            ctx.bus.info(null, "已同步 " + rows.size() + " 位乘车人");
            c.json(Map.of("synced", rows.size()));
        });
    }

    public static void registerStations(Javalin app, AppContext ctx) {
        app.get("/api/stations/search", c -> {
            String q = c.queryParam("q");
            int limit = Math.min(20, Math.max(1, parseInt(c.queryParam("limit"), 10)));
            List<Map<String, Object>> out = new ArrayList<>();
            for (StationRegistry.Station s : ctx.stations.search(q == null ? "" : q, limit)) {
                out.add(Map.of("code", s.code(), "name", s.name()));
            }
            c.json(out);
        });

        app.post("/api/stations/refresh", c -> {
            ctx.loadStations();
            c.json(Map.of("count", ctx.stations.size()));
        });
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
