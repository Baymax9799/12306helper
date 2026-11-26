package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.config.AppPaths;
import com.jactil.tickethelper.core.config.Settings;
import com.jactil.tickethelper.core.security.Passwords;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthTest {

    @TempDir
    Path tmp;

    private Javalin app;
    private AppContext ctx;
    private HttpClient http;
    private String base;

    @BeforeEach
    void setUp() {
        ctx = AppContext.boot(AppPaths.resolve(tmp.resolve("data").toString(), "unused"));
        Settings settings = ctx.settings();
        settings.adminPasswordHash = Passwords.hash("s3cret");
        ctx.updateSettings(settings);

        app = App.create(ctx).start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        app.stop();
        ctx.close();
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        HttpResponse<String> response = post("/api/auth/admin", "{\"password\":\"wrong\"}", null);

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("口令不正确"), "body=" + response.body());
    }

    @Test
    void correctPasswordGrantsApiAccess() throws Exception {
        HttpResponse<String> login = post("/api/auth/admin", "{\"password\":\"s3cret\"}", null);
        assertEquals(200, login.statusCode());

        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.startsWith("sid="), "Set-Cookie=" + setCookie);
        assertTrue(setCookie.contains("HttpOnly"), "Set-Cookie=" + setCookie);
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        HttpResponse<String> me = get("/api/auth/me", cookie);
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("true"), "body=" + me.body());
    }

    @Test
    void apiWithoutCookieIsUnauthorized() throws Exception {
        HttpResponse<String> me = get("/api/auth/me", null);

        assertEquals(401, me.statusCode());
        assertTrue(me.body().contains("未登录"), "body=" + me.body());
    }

    private HttpResponse<String> post(String path, String json, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
