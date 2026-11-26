package com.jactil.tickethelper.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jactil.tickethelper.core.client.TicketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * 扫码登录。
 *
 * <p>流程复用上游 JavaFx-TicketHelper 已验证的调用顺序：
 * create-qr64 拿 UUID 与 base64 图片 → 轮询 checkqr → result_code=2 时
 * 用 uamtk → uamauthclient 换 tk，最后 initMy12306Api 取用户名。
 *
 * <p>不碰密码、不碰滑块，是这套系统在无头环境里唯一可靠的登录方式。
 */
public final class QrLoginFlow {

    private static final Logger log = LoggerFactory.getLogger(QrLoginFlow.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record QrCode(String uuid, String imageBase64) {
    }

    public enum State {
        /** 还没扫 */
        WAITING,
        /** 已扫，等手机确认 */
        SCANNED,
        /** 登录成功，Cookie 已落地 */
        LOGGED_IN,
        /** 二维码过期 */
        EXPIRED,
        /** 服务端返回了别的错误 */
        FAILED
    }

    public record PollResult(State state, String username, String message) {
    }

    private final TicketClient client;
    private final String base;

    public QrLoginFlow(TicketClient client) {
        this(client, "https://kyfw.12306.cn");
    }

    public QrLoginFlow(TicketClient client, String base) {
        this.client = client;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 建码。第一次调用会先访问登录页拿到基础 Cookie。 */
    public QrCode create() throws IOException {
        ensureSession();
        String body = client.post(base + "/passport/web/create-qr64", Map.of("appid", "otn"));
        JsonNode root = JSON.readTree(body);
        int code = root.path("result_code").asInt(-1);
        if (code != 0) {
            throw new IOException("生成二维码失败：" + root.path("result_message").asText(body));
        }
        String uuid = root.path("uuid").asText("");
        String image = root.path("image").asText("");
        if (uuid.isEmpty() || image.isEmpty()) {
            throw new IOException("二维码响应缺少 uuid/image");
        }
        log.info("二维码已生成 uuid={}", uuid);
        return new QrCode(uuid, image);
    }

    public PollResult poll(String uuid) throws IOException {
        String body = client.post(base + "/passport/web/checkqr", Map.of("uuid", uuid, "appid", "otn"));
        JsonNode root = JSON.readTree(body);
        int code = root.path("result_code").asInt(-1);
        return switch (code) {
            case 0 -> new PollResult(State.WAITING, null, "等待扫码");
            case 1 -> new PollResult(State.SCANNED, null, "请在手机上确认登录");
            case 2 -> {
                String username = completeAuthChain(root.path("uamtk").asText(""));
                log.info("扫码登录成功，用户={}", username);
                yield new PollResult(State.LOGGED_IN, username, "登录成功");
            }
            case 3 -> new PollResult(State.EXPIRED, null, "二维码已过期，请重新生成");
            default -> new PollResult(State.FAILED, null,
                    root.path("result_message").asText("未知状态：" + code));
        };
    }

    /** 换票 + 取用户信息。失败不抛，登录态已由 Cookie 决定。 */
    private String completeAuthChain(String loginUamtk) {
        String username = "";
        try {
            String uamtkBody = client.post(base + "/passport/web/auth/uamtk", Map.of("appid", "otn"));
            JsonNode uamtk = JSON.readTree(uamtkBody);
            String tk = uamtk.path("newapptk").asText("");
            int code = uamtk.path("result_code").asInt(-1);
            if (tk.isEmpty() && !loginUamtk.isEmpty()) {
                log.debug("uamtk 未给 newapptk（code={}），降级用登录响应的 uamtk", code);
                tk = loginUamtk;
            }
            if (!tk.isEmpty()) {
                client.post(base + "/otn/uamauthclient", Map.of("tk", tk));
            }
            String me = client.get(base + "/otn/index/initMy12306Api");
            JsonNode data = JSON.readTree(me).path("data");
            username = data.path("user_name").asText(data.path("login_name").asText(""));
        } catch (Exception e) {
            log.warn("换票链未完全成功（登录态通常仍然有效）：{}", e.getMessage());
        }
        return username;
    }

    private void ensureSession() throws IOException {
        client.get(base + "/otn/resources/login.html");
        client.post(base + "/otn/login/conf", Map.of());
    }
}
