package com.jactil.tickethelper.core.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * queryI 响应里「席别列」的位置表。
 *
 * <p>索引来自真实响应记录的逐段核对（G2907 广州南→深圳北，57 段），
 * 不是凭记忆写的：见 {@code src/test/resources/leftTicket-G2907.txt}。
 *
 * <p><b>已知不确定</b>：24 / 25 / 26 / 30 这四段的语义与部分公开资料互相矛盾
 * （软座 vs 无座、高级软卧 vs 特等座）。这里沿用上游 JavaFx-TicketHelper 的映射，
 * 因为它是作者在真实界面上验证过的；如果要改，只改这一张表，别散落到解析代码里。
 * 实盘联调时请用一趟已知票情的车次复核一次。
 */
public final class SeatCatalog {

    public record Column(int index, String code, String name) {
    }

    public static final List<Column> COLUMNS = List.of(
            new Column(24, "1", "无座"),
            new Column(25, "9", "商务座/特等座"),
            new Column(26, "2", "软座"),
            new Column(27, "1", "硬座"),
            new Column(28, "3", "硬卧"),
            new Column(29, "4", "软卧"),
            new Column(30, "6", "高级软卧"),
            new Column(31, "M", "一等座"),
            new Column(32, "O", "二等座"),
            new Column(33, "P", "优选一等座"));

    private SeatCatalog() {
    }

    public static Optional<Column> byName(String name) {
        return COLUMNS.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public static Optional<Column> byCode(String code) {
        return COLUMNS.stream().filter(c -> c.code().equals(code)).findFirst();
    }

    /** 解析全部席别列；空字段记为「不存在」。 */
    public static Map<String, SeatInfo> parse(String[] fields) {
        Map<String, SeatInfo> out = new LinkedHashMap<>();
        for (Column c : COLUMNS) {
            String raw = c.index() < fields.length ? fields[c.index()].trim() : "";
            out.put(c.name(), new SeatInfo(c.code(), c.name(), toCount(raw), raw));
        }
        return out;
    }

    static int toCount(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return SeatInfo.ABSENT;
        }
        if ("无".equals(raw)) {
            return 0;
        }
        if ("有".equals(raw)) {
            return SeatInfo.UNKNOWN_AVAILABLE;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            // 「*」等特殊标记：当作有票但数量未知
            return SeatInfo.UNKNOWN_AVAILABLE;
        }
    }
}
