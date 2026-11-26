package com.jactil.tickethelper.notify;

import com.jactil.tickethelper.core.config.Settings;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;

/** 通用 Webhook：把事件原样 POST 到任意 URL，方便接 ntfy / 飞书 / 自建服务。 */
public final class WebhookNotifier implements Notifier {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Settings.Webhook cfg;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(15))
            .build();

    public WebhookNotifier(Settings.Webhook cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "Webhook";
    }

    @Override
    public boolean enabled() {
        return cfg != null && cfg.url != null && !cfg.url.isBlank();
    }

    @Override
    public void send(String title, String body) throws IOException {
        String payload = "{\"title\":" + quote(title) + ",\"body\":" + quote(body) + "}";
        Request request = new Request.Builder()
                .url(cfg.url)
                .post(RequestBody.create(payload, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Webhook 返回 HTTP " + response.code());
            }
        }
    }

    private static String quote(String raw) {
        return "\"" + (raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")) + "\"";
    }
}
