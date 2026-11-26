package com.jactil.tickethelper.core.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jactil.tickethelper.core.client.TicketClient;
import okhttp3.HttpUrl;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * 余票查询。只做「发请求 + 解析」，不含轮询、不含筛选——那是 engine 的事。
 */
public final class LeftTicketQuery {

    private static final String BASE = "https://kyfw.12306.cn/otn/leftTicket/queryI";

    private final TicketClient client;

    public LeftTicketQuery(TicketClient client) {
        this.client = client;
    }

    public List<TrainRow> query(LocalDate date, String fromCode, String toCode) throws IOException {
        return query(date, fromCode, toCode, "ADULT", code -> null);
    }

    public List<TrainRow> query(LocalDate date, String fromCode, String toCode,
                                String purposeCodes, Function<String, String> stationName) throws IOException {
        HttpUrl url = HttpUrl.get(BASE).newBuilder()
                .addQueryParameter("leftTicketDTO.train_date", date.toString())
                .addQueryParameter("leftTicketDTO.from_station", fromCode)
                .addQueryParameter("leftTicketDTO.to_station", toCode)
                .addQueryParameter("purpose_codes", purposeCodes)
                .addQueryParameter("_json_att", "")
                .build();
        String body = client.get(url.toString());
        return TicketParser.parse(body, stationName);
    }

    /** 供单测直接喂 JSON。 */
    static List<TrainRow> parseForTest(String json) throws IOException {
        return TicketParser.parse(json, code -> code);
    }

    static String toJson(String rawLine, String fromName, String toName) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "data", java.util.Map.of(
                            "result", List.of(rawLine),
                            "map", java.util.Map.of("IZQ", fromName, "IOQ", toName))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
