package com.jactil.tickethelper.core.query;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 queryI 里的票价字段。
 *
 * <p>真实数据长这样（G2907 广州南→深圳北）：
 * {@code 9019950003M009950000O007450000O007453000}。
 * 按 10 个字符一组切：首位是席别代码，后 9 位里前 6 位是「分」为单位的价格。
 * 所以是 9=199.50（商务座）、M=99.50（一等座）、O=74.50（二等座）。
 *
 * <p>票价字段在响应里的位置不固定，这里在 33~47 段之间顺序探测，
 * 只有整段都能按 10 字符切开且每组都合格式时才算命中，避免误把别的字段当票价。
 */
public final class PriceParser {

    private static final Pattern GROUP = Pattern.compile("^([0-9A-Za-z])(\\d{9})$");
    private static final int SCAN_FROM = 33;
    private static final int SCAN_TO = 48;

    private PriceParser() {
    }

    /** 返回 席别名 -> 票价；解析不出来就是空 Map，不影响余票主流程。 */
    public static Map<String, BigDecimal> parse(String[] fields) {
        for (int i = SCAN_FROM; i < Math.min(fields.length, SCAN_TO); i++) {
            String raw = fields[i] == null ? "" : fields[i].trim();
            Map<String, BigDecimal> byCode = parseGroups(raw);
            if (byCode.isEmpty()) {
                continue;
            }
            Map<String, BigDecimal> byName = new LinkedHashMap<>();
            byCode.forEach((code, price) ->
                    SeatCatalog.byCode(code).ifPresent(col -> byName.putIfAbsent(col.name(), price)));
            if (!byName.isEmpty()) {
                return byName;
            }
        }
        return Map.of();
    }

    /** 整段必须全部符合「1 位席别代码 + 9 位数字」，否则返回空。 */
    static Map<String, BigDecimal> parseGroups(String raw) {
        if (raw == null || raw.length() < 20 || raw.length() % 10 != 0) {
            return Map.of();
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (int i = 0; i + 10 <= raw.length(); i += 10) {
            Matcher m = GROUP.matcher(raw.substring(i, i + 10));
            if (!m.matches()) {
                return Map.of();
            }
            String code = m.group(1).toUpperCase();
            int fen = Integer.parseInt(m.group(2).substring(0, 6));
            out.putIfAbsent(code, BigDecimal.valueOf(fen, 2));
        }
        return out;
    }
}
