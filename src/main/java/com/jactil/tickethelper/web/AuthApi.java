package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.auth.QrLoginFlow;
import com.jactil.tickethelper.core.security.Passwords;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 管理员登录 + 12306 登录（扫码 / Cookie 导入）。 */
public final class AuthApi {

    private AuthApi() {
    }

    public record AdminLogin(String password) {
    }

    public record CookieImport(String cookieHeader) {
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

        app.get("/api/auth/me", c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("authenticated", true);
            out.put("loggedIn12306", ctx.loggedIn());
            Instant expiresAt = ctx.sessionExpiresAt();
            out.put("sessionExpiresAt", expiresAt == null ? null : expiresAt.toString());
            c.json(out);
        });

        app.post("/api/auth/logout", c -> {
            ctx.sessions.revoke(c.cookie(AdminSessions.COOKIE));
            c.header("Set-Cookie", AdminSessions.COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
            c.json(Map.of("ok", true));
        });

        // ---------- 12306 登录 ----------

        app.post("/api/auth/qr", c -> {
            try {
                QrLoginFlow.QrCode qr = ctx.qrLogin.create();
                c.json(Map.of("uuid", qr.uuid(), "imageBase64", qr.imageBase64()));
            } catch (java.io.IOException e) {
                throw ApiException.serverError("生成二维码失败：" + e.getMessage());
            }
        });

        app.get("/api/auth/qr/{uuid}", c -> {
            String uuid = c.pathParam("uuid");
            try {
                QrLoginFlow.PollResult r = ctx.qrLogin.poll(uuid);
                if (r.state() == QrLoginFlow.State.LOGGED_IN) {
                    ctx.markLoggedIn(r.username());
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("state", r.state().name());
                out.put("message", r.message());
                out.put("username", r.username());
                out.put("loggedIn12306", ctx.loggedIn());
                c.json(out);
            } catch (java.io.IOException e) {
                throw ApiException.serverError("查询扫码状态失败：" + e.getMessage());
            }
        });

        app.post("/api/auth/cookie", c -> {
            CookieImport body = c.bodyAsClass(CookieImport.class);
            if (body == null || body.cookieHeader() == null || body.cookieHeader().isBlank()) {
                throw ApiException.badRequest("请粘贴 Cookie 字符串");
            }
            int n = ctx.client.store().loadFromHeader(body.cookieHeader());
            if (n == 0) {
                throw ApiException.badRequest("没解析出任何 Cookie，格式应为 a=1; b=2");
            }
            ctx.markLoggedIn("");
            c.json(Map.of("imported", n));
        });

        app.post("/api/auth/12306/logout", c -> {
            ctx.markLoggedOut();
            c.json(Map.of("ok", true));
        });

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
