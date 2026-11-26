package com.jactil.tickethelper.core.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * queryI 的 JSON → {@link TrainRow}。每段用 | 分隔，索引见 {@link SeatCatalog}。
 */
public final class TicketParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 分隔符必须转义：| 是正则的或运算。 */
    private static final String SEP = "\\|";

    private TicketParser() {
    }

    public static List<TrainRow> parse(String body, Function<String, String> stationName) throws IOException {
        JsonNode root = JSON.readTree(body);
        JsonNode data = root.path("data");
        JsonNode result = data.path("result");
        if (result.isMissingNode() || !result.isArray()) {
            throw new IOException("响应里没有 data.result，可能是被风控或需要重新登录：" + abbreviate(body));
        }

        Map<String, String> codeToName = new HashMap<>();
        JsonNode map = data.path("map");
        if (map.isObject()) {
            map.fields().forEachRemaining(e -> codeToName.put(e.getKey(), e.getValue().asText()));
        }

        List<TrainRow> out = new ArrayList<>(result.size());
        for (JsonNode node : result) {
            TrainRow row = parseRow(node.asText(), codeToName, stationName);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    static TrainRow parseRow(String raw, Map<String, String> codeToName,
                             Function<String, String> fallback) {
        String[] f = raw.split(SEP, -1);
        if (f.length < 34) {
            return null;
        }
        String fromCode = f[6];
        String toCode = f[7];
        Function<String, String> nameOf = code -> {
            String name = codeToName.get(code);
            if (name != null) {
                return name;
            }
            String fb = fallback == null ? null : fallback.apply(code);
            return fb == null ? code : fb;
        };
        return new TrainRow(
                f[3],
                fromCode,
                toCode,
                nameOf.apply(fromCode),
                nameOf.apply(toCode),
                f[8],
                f[9],
                f[10],
                f[13],
                f[0],
                f.length > 36 ? f[36] : "",
                SeatCatalog.parse(f));
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 120 ? body : body.substring(0, 120) + "…";
    }
}
