package com.jactil.tickethelper.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jactil.tickethelper.core.client.TicketClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 12306 拉未完成订单。
 *
 * <p><b>待实盘确认</b>：端点名与字段名（orderDBList / order_no）随版本可能变化，
 * 失败只记 WARN，不影响主流程；对账逻辑本身由 {@link OrderReconciler} 单测覆盖。
 */
public final class OrderFetcher implements OrderReconciler.Fetcher {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/otn/queryOrder/queryMyOrderNoComplete";

    private final TicketClient client;
    private final String base;

    public OrderFetcher(TicketClient client) {
        this(client, "https://kyfw.12306.cn");
    }

    public OrderFetcher(TicketClient client, String base) {
        this.client = client;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public List<String> unfinishedOrderNos() throws IOException {
        String body = client.post(base + PATH, java.util.Map.of("_json_att", ""));
        JsonNode data = JSON.readTree(body).path("data");
        JsonNode list = data.path("orderDBList");
        if (!list.isArray()) {
            throw new IOException("未完成订单接口返回的结构不认识（可能改版）：" + shorten(body));
        }
        List<String> out = new ArrayList<>(list.size());
        for (JsonNode node : list) {
            String orderNo = node.path("order_no").asText("");
            if (!orderNo.isBlank()) {
                out.add(orderNo);
            }
        }
        return out;
    }

    private static String shorten(String body) {
        return body == null ? "" : (body.length() <= 120 ? body : body.substring(0, 120) + "…");
    }
}
