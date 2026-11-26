package com.jactil.tickethelper.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jactil.tickethelper.core.client.TicketClient;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 短信验证码登录。
 *
 * <p>存在的理由：扫码需要一块屏幕，而 NAS 的意义是人不在家。手边只有手机时，
 * 扫码这条路会失效，短信登录能让人在任何地方恢复会话。
 *
 * <p>密码只在这一次请求里用（SM4 加密后即丢弃），不落盘、不写日志。
 */
public final class SmsLoginFlow {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record SendResult(boolean ok, String message) {
    }

    public record LoginResult(boolean ok, String username, String message) {
    }

    private final TicketClient client;
    private final String base;

    public SmsLoginFlow(TicketClient client) {
        this(client, "https://kyfw.12306.cn");
    }

    public SmsLoginFlow(TicketClient client, String base) {
        this.client = client;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public SendResult sendCode(String username) throws IOException {
        ensureSession();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("appid", "otn");
        form.put("username", username);
        form.put("castNum", "3452");
        String body = client.post(base + "/passport/web/getMessageCode", form);
        JsonNode root = JSON.readTree(body);
        int code = root.path("result_code").asInt(-1);
        if (code == 0) {
            return new SendResult(true, "验证码已发送，请查收短信");
        }
        return new SendResult(false, root.path("result_message").asText(body));
    }

    public LoginResult login(String username, String password, String smsCode) throws IOException {
        ensureSession();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", username);
        form.put("password", PasswordCipher.encrypt(password));
        form.put("appid", "otn");
        form.put("randCode", smsCode);
        form.put("checkMode", "0");
        form.put("sessionId", "");
        form.put("sig", "");
        form.put("if_check_slide_passcode_token", "");
        form.put("scene", "");

        String body = client.post(base + "/passport/web/login", form);
        JsonNode root = JSON.readTree(body);
        int code = root.path("result_code").asInt(-1);
        if (code != 0) {
            return new LoginResult(false, null, root.path("result_message").asText(body));
        }
        String username2 = AuthChain.complete(client, base, root.path("uamtk").asText(""));
        return new LoginResult(true, username2, "登录成功");
    }

    private void ensureSession() throws IOException {
        client.get(base + "/otn/resources/login.html");
        client.post(base + "/otn/login/conf", Map.of());
    }
}
