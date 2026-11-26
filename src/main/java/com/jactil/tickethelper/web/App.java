package com.jactil.tickethelper.web;

import io.javalin.Javalin;

import java.util.Map;

/**
 * HTTP 层装配。所有依赖从 {@link AppContext} 注入，app 层负责构造。
 */
public final class App {

    private App() {
    }

    public static Javalin create(AppContext ctx) {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.defaultContentType = "application/json; charset=utf-8";
        });

        app.get("/healthz", c -> c.json(Map.of("status", "ok")));

        app.exception(ApiException.class, (e, c) -> c.status(e.status).json(Map.of("error", e.getMessage())));

        AuthApi.register(app, ctx);

        return app;
    }
}
