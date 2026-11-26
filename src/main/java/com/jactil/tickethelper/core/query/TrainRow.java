package com.jactil.tickethelper.core.query;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条车次记录。
 *
 * @param secretStr     下单第一步 submitOrderRequest 需要，从 queryI 的第 0 段取
 * @param trainLocation 下单过程需要的 train_location，从第 36 段取（待实盘确认）
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
                       Map<String, SeatInfo> seats) {

    /** 取某席别；不存在时返回 null。 */
    public SeatInfo seat(String name) {
        return seats.get(name);
    }

    public Map<String, SeatInfo> seatsOrdered() {
        return new LinkedHashMap<>(seats);
    }

    public String route() {
        return fromName + "→" + toName;
    }
}
