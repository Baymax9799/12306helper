package com.jactil.tickethelper.web;

import com.jactil.tickethelper.engine.DisabledBooking;
import io.javalin.Javalin;

import java.util.Map;

/** 是否开启「抢到就下单」。默认关闭，必须由用户显式打开。 */
public final class BookingApi {

    private BookingApi() {
    }

    public record Toggle(boolean enabled) {
    }

    public static void register(Javalin app, AppContext ctx) {
        app.get("/api/booking", c -> c.json(Map.of(
                "enabled", ctx.autoBooking(),
                "note", ctx.autoBooking()
                        ? "已开启：命中余票会自动提交订单，成功后停在待支付"
                        : "监控模式：命中余票只通知，不提交订单")));

        app.post("/api/booking", c -> {
            Toggle toggle = c.bodyAsClass(Toggle.class);
            boolean enabled = toggle != null && toggle.enabled();
            if (enabled && !ctx.loggedIn()) {
                throw ApiException.conflict("还没登录 12306，请先扫码登录");
            }
            ctx.setBooking(enabled ? ctx.createRealBooking() : new DisabledBooking());
            ctx.bus.info(null, enabled ? "已开启自动下单" : "已切换为监控模式");
            c.json(Map.of("enabled", enabled));
        });
    }
}
