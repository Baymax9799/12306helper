package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.security.Passwords;
import io.javalin.Javalin;

import java.util.Map;

/** 管理员口令登录与全局 /api 鉴权。 */
public final class AuthApi {

    private AuthApi() {
    }

    public record AdminLogin(String password) {
    }

    public static void register(Javalin app, AppContext ctx) {
        app.post("/api/auth/admin", c -> {
            AdminLogin body = c.bodyAsClass(AdminLogin.class);
            if (body == null || !Passwords.verify(body.password(), ctx.settings().adminPasswordHash)) {
                throw ApiException.unauthorized("口令不正确");
            }
            String sid = ctx.sessions.create();
            c.header("Set-Cookie", AdminSessions.COOKIE + "=" + sid
                    + "; Path=/; Max-Age=" + (7 * 24 * 3600) + "; HttpOnly; SameSite=Lax");
            c.json(Map.of("ok", true));
        });

        app.post("/api/auth/logout", c -> {
            ctx.sessions.revoke(c.cookie(AdminSessions.COOKIE));
            c.header("Set-Cookie", AdminSessions.COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
            c.json(Map.of("ok", true));
        });

        app.get("/api/auth/me", c -> c.json(Map.of("authenticated", true)));

        app.before("/api/*", c -> {
            String path = c.path();
            if (path.equals("/api/auth/admin")) {
                return;
            }
            if (!ctx.sessions.valid(c.cookie(AdminSessions.COOKIE))) {
                throw ApiException.unauthorized("未登录");
            }
        });
    }
}
