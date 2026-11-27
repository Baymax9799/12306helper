package com.jactil.tickethelper.core.passenger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jactil.tickethelper.core.client.TicketClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 12306 同步乘车人。
 *
 * <p>本地**只存掩码**：姓名保留首字、证件号保留后 4 位。下单时要用的真实姓名与证件号
 * 在下单那一刻重新拉取并只放在内存里，不落库。
 */
public final class PassengerSync {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/otn/confirmPassenger/getPassengerDTOs";

    /** 只在内存里流转的真实乘车人。 */
    public record Remote(String pid, String name, String idTypeCode, String idTypeName,
                         String idNo, String ticketType) {
    }

    private final TicketClient client;
    private final String base;

    public PassengerSync(TicketClient client) {
        this(client, "https://kyfw.12306.cn");
    }

    public PassengerSync(TicketClient client, String base) {
        this.client = client;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public List<Remote> fetch() throws IOException {
        String body = client.post(base + PATH, java.util.Map.of("_json_att", ""));
        JsonNode root = JSON.readTree(body);
        JsonNode list = root.path("data").path("normal_passengers");
        if (!list.isArray()) {
            throw new IOException("没有拿到乘车人列表（可能未登录）：" + shorten(body));
        }
        List<Remote> out = new ArrayList<>(list.size());
        for (JsonNode node : list) {
            String idNo = node.path("passenger_id_no").asText("");
            String pid = node.path("code").asText("");
            if (pid.isBlank()) {
                // 有的账号里 code 是空的。没有稳定 ID 就没法在本地选中、也没法在下单时对上，
                // 用证件号派生一个，保证同步和下单两次算出来是同一个。
                pid = "auto-" + shortHash(idNo);
            }
            out.add(new Remote(
                    pid,
                    node.path("passenger_name").asText(""),
                    node.path("passenger_id_type_code").asText(""),
                    node.path("passenger_id_type_name").asText(""),
                    idNo,
                    node.path("passenger_type_name").asText(node.path("passenger_type").asText(""))));
        }
        return out;
    }

    /** 证件号派生的稳定短 ID；证件号为空时退回姓名哈希。 */
    static String shortHash(String raw) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((raw == null ? "" : raw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(raw).hashCode());
        }
    }

    /** 张来福 -> 张**；两个字：张三 -> 张*。 */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /** 110101199001011234 -> ****1234 */
    public static String maskIdNo(String idNo) {
        if (idNo == null || idNo.length() <= 4) {
            return "****";
        }
        return "****" + idNo.substring(idNo.length() - 4);
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 100 ? body : body.substring(0, 100) + "…";
    }
}
