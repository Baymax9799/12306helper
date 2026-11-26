package com.jactil.tickethelper.core.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会持久化的 CookieJar。
 *
 * <p>12306 的登录态全在 Cookie 里（_passport_session、RAIL_DEVICEID 等），进程重启后
 * 必须原样恢复，否则等于掉登录、并且风控指纹会变。写入用「临时文件 + 原子替换」，
 * 避免半截文件把会话写坏。
 */
public final class PersistentCookieStore implements CookieJar {

    private static final Logger log = LoggerFactory.getLogger(PersistentCookieStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_DOMAIN = "kyfw.12306.cn";
    private static final TypeReference<List<CookieDto>> TYPE = new TypeReference<>() {
    };

    /** 落盘形态；OkHttp 的 Cookie 没有公开的序列化方法。 */
    public record CookieDto(String name, String value, String domain, String path,
                            long expiresAt, boolean secure, boolean httpOnly) {
    }

    private final Path file;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private PersistentCookieStore(Path file) {
        this.file = file;
    }

    public static PersistentCookieStore open(Path file) {
        PersistentCookieStore store = new PersistentCookieStore(file);
        store.loadFromDisk();
        return store;
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> received) {
        lock.lock();
        try {
            for (Cookie c : received) {
                cookies.put(c.name(), c);
            }
            flush();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        lock.lock();
        try {
            List<Cookie> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            cookies.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
            for (Cookie c : cookies.values()) {
                if (c.matches(url)) {
                    out.add(c);
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** 用于把 Cookie 同步给别的组件（如日志/排障），不含密码类字段。 */
    public String dumpHeader() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (Cookie c : cookies.values()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(c.name()).append('=').append(c.value());
            }
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }

    /** 人工接管：粘贴浏览器里 "a=1; b=2" 形式的 Cookie。 */
    public int loadFromHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        lock.lock();
        try {
            int count = 0;
            for (String part : raw.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                int eq = p.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = p.substring(0, eq).trim();
                String value = p.substring(eq + 1).trim();
                if (name.isEmpty()) {
                    continue;
                }
                Cookie cookie = new Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(DEFAULT_DOMAIN)
                        .path("/")
                        .expiresAt(System.currentTimeMillis() + 7L * 24 * 3600 * 1000)
                        .build();
                cookies.put(name, cookie);
                count++;
            }
            flush();
            return count;
        } finally {
            lock.unlock();
        }
    }

    public String railDeviceId() {
        lock.lock();
        try {
            Cookie c = cookies.get("RAIL_DEVICEID");
            return c == null ? null : c.value();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return cookies.size();
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            cookies.clear();
            flush();
        } finally {
            lock.unlock();
        }
    }

    public Path file() {
        return file;
    }

    // ---------- 落盘 ----------

    private void flush() {
        List<CookieDto> dtos = new ArrayList<>(cookies.size());
        for (Cookie c : cookies.values()) {
            dtos.add(new CookieDto(c.name(), c.value(), c.domain(), c.path(),
                    c.expiresAt(), c.secure(), c.httpOnly()));
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(dtos), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("写 Cookie 文件失败：{}", file, e);
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            List<CookieDto> dtos = JSON.readValue(raw, TYPE);
            long now = System.currentTimeMillis();
            for (CookieDto d : dtos) {
                if (d.expiresAt() < now) {
                    continue;
                }
                Cookie.Builder builder = new Cookie.Builder()
                        .name(d.name())
                        .value(d.value())
                        .domain(d.domain())
                        .path(d.path())
                        .expiresAt(d.expiresAt());
                if (d.secure()) {
                    builder.secure();
                }
                if (d.httpOnly()) {
                    builder.httpOnly();
                }
                cookies.put(d.name(), builder.build());
            }
            log.info("已恢复 {} 个 Cookie", cookies.size());
        } catch (IOException e) {
            log.warn("读取 Cookie 文件失败，按未登录处理：{}", file, e);
        }
    }
}
