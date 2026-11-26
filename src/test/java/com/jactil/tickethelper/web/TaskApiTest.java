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

/** 任务 CRUD 的接口级测试：验证校验规则与启停前置条件。 */
class TaskApiTest {

    private static final String STATIONS = "var station_names ='"
            + "@bji|北京|BJP|beijing|bj|1|0357|"
            + "@shh|上海|SHH|shanghai|sh|2|0079|"
            + "@gzq|广州南|IZQ|guangzhounan|gzn|3|0401|"
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

        HttpResponse<String> login = send("POST", "/api/auth/admin", "{\"password\":\"pw\"}");
        cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    @AfterEach
    void tearDown() {
        app.stop();
        ctx.close();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String taskJson(String from, String to) {
        return "{\"name\":\"回上海\",\"mode\":\"GRAB\",\"from\":\"" + from + "\",\"to\":\"" + to + "\","
                + "\"dates\":[\"2026-12-02\",\"2026-12-01\"],\"trains\":[],\"seatTypes\":[\"二等座\"],"
                + "\"passengerIds\":[],\"intervalMs\":1000,\"startAt\":\"\"}";
    }

    @Test
    void createsTaskAndNormalizesDates() throws Exception {
        HttpResponse<String> created = send("POST", "/api/tasks", taskJson("广州南", "深圳北"));

        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"from\":\"IZQ\""), created.body());
        assertTrue(created.body().contains("\"fromName\":\"广州南\""), created.body());
        assertTrue(created.body().indexOf("2026-12-01") < created.body().indexOf("2026-12-02"),
                "日期应升序：" + created.body());

        HttpResponse<String> list = send("GET", "/api/tasks", null);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("回上海"));
    }

    @Test
    void rejectsUnknownStation() throws Exception {
        HttpResponse<String> response = send("POST", "/api/tasks", taskJson("火星站", "深圳北"));

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("不是有效车站"), response.body());
    }

    @Test
    void rejectsPastStartTime() throws Exception {
        String body = taskJson("广州南", "深圳北").replace("\"startAt\":\"\"",
                "\"startAt\":\"2020-01-01T00:00:00Z\"");

        HttpResponse<String> response = send("POST", "/api/tasks", body);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("开抢时刻已过"), response.body());
    }

    @Test
    void rejectsUnknownSeatType() throws Exception {
        String body = taskJson("广州南", "深圳北").replace("[\"二等座\"]", "[\"火箭座\"]");

        HttpResponse<String> response = send("POST", "/api/tasks", body);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("未知席别"), response.body());
    }

    @Test
    void startRequiresLogin() throws Exception {
        String created = send("POST", "/api/tasks", taskJson("广州南", "深圳北")).body();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        HttpResponse<String> started = send("POST", "/api/tasks/" + id + "/start", null);

        assertEquals(409, started.statusCode());
        assertTrue(started.body().contains("还没登录"), started.body());
    }

    @Test
    void deleteRemovesTask() throws Exception {
        String created = send("POST", "/api/tasks", taskJson("广州南", "深圳北")).body();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        assertEquals(200, send("DELETE", "/api/tasks/" + id, null).statusCode());
        assertEquals(404, send("GET", "/api/tasks/" + id, null).statusCode());
    }

    @Test
    void metaExposesSeatCatalog() throws Exception {
        HttpResponse<String> meta = send("GET", "/api/meta", null);

        assertEquals(200, meta.statusCode());
        assertTrue(meta.body().contains("二等座"), meta.body());
        assertTrue(meta.body().contains("minIntervalMs"), meta.body());
    }

    @Test
    void gateStartsOpenWithZeroOrders() throws Exception {
        HttpResponse<String> gate = send("GET", "/api/gate", null);

        assertEquals(200, gate.statusCode());
        assertTrue(gate.body().contains("\"state\":\"OPEN\""), gate.body());
        assertTrue(gate.body().contains("\"created\":0"), gate.body());
    }
}
