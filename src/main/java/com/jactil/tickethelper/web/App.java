package com.jactil.tickethelper.web;

import io.javalin.Javalin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        app.exception(Exception.class, (e, c) -> {
            c.status(500).json(Map.of("error", "服务端异常：" + e.getMessage()));
        });

        AuthApi.register(app, ctx);
        TaskApi.register(app, ctx);
        QueryApi.register(app, ctx);
        PassengerApi.registerPassengers(app, ctx);
        PassengerApi.registerStations(app, ctx);
        OpsApi.register(app, ctx);
        BookingApi.register(app, ctx);

        registerStatic(app);

        return app;
    }

    /** 静态页只有三个文件，直接读 classpath，省掉 Javalin 静态配置的版本差异。 */
    private static void registerStatic(Javalin app) {
        app.get("/", c -> c.contentType("text/html; charset=utf-8").result(load("/static/index.html")));
        app.get("/index.html", c -> c.contentType("text/html; charset=utf-8").result(load("/static/index.html")));
        app.get("/app.js", c -> c.contentType("application/javascript; charset=utf-8").result(load("/static/app.js")));
        app.get("/style.css", c -> c.contentType("text/css; charset=utf-8").result(load("/static/style.css")));
    }

    static String load(String resource) {
        try (InputStream in = App.class.getResourceAsStream(resource)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
