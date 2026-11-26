package com.jactil.tickethelper.core.client;

import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.jactil.tickethelper.core.time.ServerClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 12306 的唯一出口。所有请求共享同一套 Cookie 与 UA。
 *
 * <p>刻意关闭自动跟随重定向：12306 用 302 跳回登录页表示「会话失效或被风控」，
 * 这个信号必须让上层看见，而不是被 OkHttp 悄悄跟进去。
 */
public final class TicketClient {

    private static final Logger log = LoggerFactory.getLogger(TicketClient.class);
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final PersistentCookieStore store;
    private final ServerClock clock;

    public TicketClient(PersistentCookieStore store, ServerClock clock) {
        this.store = store;
        this.clock = clock;
        this.http = new OkHttpClient.Builder()
                .cookieJar(store)
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(20))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .build();
    }

    public String get(String url) throws IOException {
        return execute(new Request.Builder().url(url).get().headers(baseHeaders(url)).build());
    }

    public String post(String url, Map<String, String> form) throws IOException {
        FormBody.Builder body = new FormBody.Builder();
        if (form != null) {
            form.forEach(body::add);
        }
        return execute(new Request.Builder()
                .url(url)
                .post(body.build())
                .headers(baseHeaders(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://kyfw.12306.cn")
                .build());
    }

    public String postJson(String url, String json) throws IOException {
        return execute(new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON_TYPE))
                .headers(baseHeaders(url))
                .header("Origin", "https://kyfw.12306.cn")
                .build());
    }

    private okhttp3.Headers baseHeaders(String url) {
        return new okhttp3.Headers.Builder()
                .add("User-Agent", UA)
                .add("Accept", "application/json, text/javascript, */*; q=0.01")
                .add("Accept-Language", "zh-CN,zh;q=0.9")
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Referer", refererFor(url))
                .build();
    }

    private static String refererFor(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        String path = parsed == null ? "" : parsed.encodedPath();
        if (path.startsWith("/otn/leftTicket")) {
            return "https://kyfw.12306.cn/otn/leftTicket/init";
        }
        if (path.startsWith("/otn/confirmPassenger")) {
            return "https://kyfw.12306.cn/otn/confirmPassenger/initDc";
        }
        return "https://kyfw.12306.cn/otn/resources/login.html";
    }

    private String execute(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            clock.syncFromDateHeader(response.header("Date"));
            int code = response.code();
            String body = response.body() == null ? "" : response.body().string();
            if (code == 301 || code == 302 || code == 303 || code == 307) {
                String location = response.header("Location");
                log.debug("收到 {}，Location={}", code, location);
                throw new RedirectedException(code, location, body);
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + code + " for " + request.url());
            }
            return body;
        }
    }

    public List<Cookie> cookies() {
        return store.loadForRequest(HttpUrl.get("https://kyfw.12306.cn/"));
    }

    public PersistentCookieStore store() {
        return store;
    }

    public ServerClock clock() {
        return clock;
    }

    /** 302 到登录页 = 会话失效或被风控。 */
    public static final class RedirectedException extends IOException {
        public final int status;
        public final String location;

        RedirectedException(int status, String location, String body) {
            super("12306 返回 " + status + " -> " + location + "（通常是会话失效或触发风控）");
            this.status = status;
            this.location = location;
        }
    }
}
