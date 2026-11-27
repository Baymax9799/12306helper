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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 查询入口的输入校验：城市名、日期窗口。 */
class QueryApiTest {

    private static final String STATIONS = "var station_names ='"
            + "@bji|北京|BJP|beijing|bj|1|0357|"
            + "@gzq|广州|GZQ|guangzhou|gz|2|0401|"
            + "@gzn|广州南|IZQ|guangzhounan|gzn|3|0401|"
            + "@szb|深圳北|IOQ|shenzhenbei|szb|4|0401|';";

    @TempDir
    Path tmp;

    private Javalin app;
    private AppContext ctx;
    private HttpClient http;
    private String base;
    private String cookie;

    @BeforeEach
    void setUp() throws Exception {
        ctx = AppContext.boot(AppPaths.resolve(tmp.resolve("data").toString(), "unused"));
        Settings settings = ctx.settings();
        settings.adminPasswordHash = Passwords.hash("pw");
        ctx.updateSettings(settings);
        ctx.stations.loadFromJs(STATIONS);

        app = App.create(ctx).start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
        http = HttpClient.newHttpClient();
        cookie = post("/api/auth/admin", "{\"password\":\"pw\"}")
                .headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    @AfterEach
    void tearDown() {
        app.stop();
        ctx.close();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String queryJson(String date, String from, String to) {
        return "{\"date\":\"" + date + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    @Test
    void cityNameIsRejectedWithStationCandidates() throws Exception {
        // fixture 里没有叫「深圳」的车站，只有「深圳北」，正好覆盖「填了城市名」的场景
        HttpResponse<String> response = post("/api/query",
                queryJson(LocalDate.now().plusDays(1).toString(), "GZQ", "深圳"));

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("不是一个具体车站"), response.body());
        assertTrue(response.body().contains("深圳北"), "应列出候选站点：" + response.body());
    }

    @Test
    void pastDateIsRejected() throws Exception {
        HttpResponse<String> response = post("/api/query",
                queryJson(LocalDate.now().minusDays(1).toString(), "GZQ", "IOQ"));

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("不能查过去的日期"), response.body());
    }

    @Test
    void dateBeyondFifteenDaysIsRejected() throws Exception {
        HttpResponse<String> response = post("/api/query",
                queryJson(LocalDate.now().plusDays(20).toString(), "GZQ", "IOQ"));

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("15 天"), response.body());
    }

    @Test
    void unknownStationIsRejected() throws Exception {
        HttpResponse<String> response = post("/api/query",
                queryJson(LocalDate.now().plusDays(1).toString(), "火星站", "IOQ"));

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("没有这个车站"), response.body());
    }

    @Test
    void taskNameIsGeneratedWhenMissing() throws Exception {
        String body = "{\"mode\":\"GRAB\",\"from\":\"GZQ\",\"to\":\"IOQ\",\"dates\":[\""
                + LocalDate.now().plusDays(1) + "\"],\"seatTypes\":[\"二等座\"],"
                + "\"passengerIds\":[],\"intervalMs\":1000}";

        HttpResponse<String> response = post("/api/tasks", body);

        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.body().contains("广州→深圳北"), "任务名应自动生成：" + response.body());
    }

    @Test
    void taskDateBeyondWindowIsRejected() throws Exception {
        String body = "{\"mode\":\"GRAB\",\"from\":\"GZQ\",\"to\":\"IOQ\",\"dates\":[\""
                + LocalDate.now().plusDays(20) + "\"],\"seatTypes\":[\"二等座\"],"
                + "\"passengerIds\":[],\"intervalMs\":1000}";

        HttpResponse<String> response = post("/api/tasks", body);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("15 天"), response.body());
    }
}
