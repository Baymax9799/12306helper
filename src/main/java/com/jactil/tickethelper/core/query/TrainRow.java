package com.jactil.tickethelper.core.query;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条车次记录。
 *
 * @param secretStr     下单第一步 submitOrderRequest 需要，从 queryI 的第 0 段取
 * @param trainLocation 下单过程需要的 train_location，从第 36 段取（待实盘确认）
 * @param prices        席别名 -> 票价，解析不出来时为空 Map
 */
public record TrainRow(String trainNo,
                       String fromCode,
                       String toCode,
                       String fromName,
                       String toName,
                       String startTime,
                       String arriveTime,
                       String duration,
                       String date,
                       String secretStr,
                       String trainLocation,
                       Map<String, SeatInfo> seats,
                       Map<String, BigDecimal> prices) {

    /** 取某席别；不存在时返回 null。 */
    public SeatInfo seat(String name) {
        return seats.get(name);
    }

    /** 取某席别票价；没有就返回 null。 */
    public BigDecimal price(String name) {
        return prices.get(name);
    }

    public Map<String, SeatInfo> seatsOrdered() {
        return new LinkedHashMap<>(seats);
    }

    public String route() {
        return fromName + "→" + toName;
    }
}
