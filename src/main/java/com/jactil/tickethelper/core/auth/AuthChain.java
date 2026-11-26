package com.jactil.tickethelper.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jactil.tickethelper.core.client.TicketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 换票链：uamtk → uamauthclient → initMy12306Api。
 * 扫码登录与短信登录都要走这一步，抽出来免得两处各写一遍。
 */
final class AuthChain {

    private static final Logger log = LoggerFactory.getLogger(AuthChain.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthChain() {
    }

    /** 返回用户名；失败不抛异常（登录态已由 Cookie 决定）。 */
    static String complete(TicketClient client, String base, String loginUamtk) {
        String username = "";
        try {
            String uamtkBody = client.post(base + "/passport/web/auth/uamtk", Map.of("appid", "otn"));
            JsonNode uamtk = JSON.readTree(uamtkBody);
            String tk = uamtk.path("newapptk").asText("");
            int code = uamtk.path("result_code").asInt(-1);
            if (tk.isEmpty() && loginUamtk != null && !loginUamtk.isEmpty()) {
                log.debug("uamtk 没给 newapptk（code={}），降级用登录响应的 uamtk", code);
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
}
