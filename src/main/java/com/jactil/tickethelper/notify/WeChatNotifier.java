package com.jactil.tickethelper.notify;

import com.jactil.tickethelper.core.config.Settings;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;

/**
 * 微信侧的三条通路。个人做不到「自己申请公众号发模板消息」（需认证服务号），
 * 所以这里用等价且零门槛的三条：Server酱、PushPlus、企业微信群机器人。
 */
public final class WeChatNotifier implements Notifier {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Settings.Wechat cfg;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(15))
            .build();

    public WeChatNotifier(Settings.Wechat cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "微信(" + cfg.provider + ")";
    }

    @Override
    public boolean enabled() {
        if (cfg == null) {
            return false;
        }
        if ("wecom".equalsIgnoreCase(cfg.provider)) {
            return !cfg.webhookUrl.isBlank();
        }
        return !cfg.key.isBlank();
    }

    @Override
    public void send(String title, String body) throws IOException {
        String provider = cfg.provider == null ? "serverchan" : cfg.provider.toLowerCase();
        switch (provider) {
            case "wecom" -> sendWecom(title, body);
            case "pushplus" -> sendPushPlus(title, body);
            default -> sendServerChan(title, body);
        }
    }

    private void sendServerChan(String title, String body) throws IOException {
        String url = "https://sctapi.ftqq.com/" + cfg.key + ".send";
        FormBody form = new FormBody.Builder().add("title", title).add("desp", body).build();
        post(url, form);
    }

    private void sendPushPlus(String title, String body) throws IOException {
        String payload = "{\"token\":" + quote(cfg.key) + ",\"title\":" + quote(title)
                + ",\"content\":" + quote(body) + ",\"template\":\"markdown\"}";
        post("https://www.pushplus.plus/send", RequestBody.create(payload, JSON));
    }

    private void sendWecom(String title, String body) throws IOException {
        String payload = "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":"
                + quote("**" + title + "**\n" + body) + "}}";
        post(cfg.webhookUrl, RequestBody.create(payload, JSON));
    }

    private void post(String url, RequestBody body) throws IOException {
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("推送失败 HTTP " + response.code() + "：" + text);
            }
        }
    }

    private static String quote(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
