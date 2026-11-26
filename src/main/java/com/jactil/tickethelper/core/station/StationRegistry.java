package com.jactil.tickethelper.core.station;

import com.jactil.tickethelper.core.client.TicketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 站点库。数据来自 12306 的 station_name.js：
 * {@code var station_names ='@bjb|北京北|VAP|beijingbei|bjb|0|0357|...@...'}
 * 字段：0 简拼、1 中文名、2 三字码、3 全拼、4 首字母、5 序号、6 城市代码。
 */
public final class StationRegistry {

    private static final Logger log = LoggerFactory.getLogger(StationRegistry.class);
    public static final String SOURCE_URL = "https://kyfw.12306.cn/otn/resources/js/framework/station_name.js";

    public record Station(String code, String name, String pinyin) {
    }

    private final Map<String, String> nameToCode = new LinkedHashMap<>();
    private final Map<String, String> codeToName = new LinkedHashMap<>();
    private final List<Station> all = new ArrayList<>();

    public int size() {
        return all.size();
    }

    public void loadFromJs(String js) {
        if (js == null || js.isBlank()) {
            return;
        }
        String payload = js;
        int start = js.indexOf('\'');
        int end = js.lastIndexOf('\'');
        if (start >= 0 && end > start) {
            payload = js.substring(start + 1, end);
        }
        List<Station> parsed = new ArrayList<>();
        for (String chunk : payload.split("@")) {
            String[] f = chunk.split("\\|", -1);
            if (f.length < 3) {
                continue;
            }
            String name = f[1].trim();
            String code = f[2].trim();
            String pinyin = f.length > 3 ? f[3].trim() : "";
            if (name.isEmpty() || code.length() != 3) {
                continue;
            }
            parsed.add(new Station(code, name, pinyin));
        }
        if (parsed.isEmpty()) {
            log.warn("站点库解析结果为空，保留旧缓存");
            return;
        }
        synchronized (this) {
            nameToCode.clear();
            codeToName.clear();
            all.clear();
            for (Station s : parsed) {
                nameToCode.put(s.name(), s.code());
                codeToName.putIfAbsent(s.code(), s.name());
                all.add(s);
            }
        }
        log.info("站点库已加载：{} 个", parsed.size());
    }

    public void refresh(TicketClient client) {
        try {
            loadFromJs(client.get(SOURCE_URL));
        } catch (IOException e) {
            log.warn("刷新站点库失败，继续用旧缓存：{}", e.getMessage());
        }
    }

    public Optional<String> codeOf(String name) {
        synchronized (this) {
            return Optional.ofNullable(nameToCode.get(name));
        }
    }

    public String nameOf(String code) {
        synchronized (this) {
            return codeToName.getOrDefault(code, code);
        }
    }

    public boolean knows(String code) {
        synchronized (this) {
            return codeToName.containsKey(code);
        }
    }

    /** 按中文名或拼音前缀搜索，中文名精确匹配优先。 */
    public List<Station> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        String lower = q.toLowerCase(Locale.ROOT);
        List<Station> exact = new ArrayList<>();
        List<Station> prefix = new ArrayList<>();
        synchronized (this) {
            for (Station s : all) {
                if (s.name().equals(q) || s.code().equalsIgnoreCase(q)) {
                    exact.add(s);
                } else if (s.name().startsWith(q)
                        || s.pinyin().toLowerCase(Locale.ROOT).startsWith(lower)
                        || s.code().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    prefix.add(s);
                }
            }
        }
        List<Station> out = new ArrayList<>(exact);
        for (Station s : prefix) {
            if (out.size() >= limit) {
                break;
            }
            out.add(s);
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}
