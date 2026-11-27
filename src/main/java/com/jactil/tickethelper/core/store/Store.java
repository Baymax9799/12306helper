package com.jactil.tickethelper.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 全部持久化操作。刻意做成单一入口：SQL 集中、事务边界清楚，方便 7×24 场景审计。
 */
public final class Store {

    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
    };

    public record TaskRow(long id, String name, String status, String mode, String from, String to,
                          List<String> dates, List<String> trains, List<String> seatTypes,
                          List<Long> passengerIds, int intervalMs, Instant startAt,
                          Instant createdAt, Instant updatedAt) {
    }

    public record PassengerRow(long id, String pid, String nameMasked, String idType,
                               String idNoMasked, String ticketType, Instant syncedAt) {
    }

    public record OrderRow(long id, Long taskId, String orderNo, String trainNo, String seatType,
                           String amount, String status, Instant createdAt) {
    }

    public record EventRow(long id, Long taskId, String level, String message, Instant createdAt) {
    }

    public record SessionRow(String cookiesJson, String railDeviceId, Instant expiresAt) {
    }

    private final Db db;

    public Store(Db db) {
        this.db = db;
    }

    // ---------- task ----------

    public long insertTask(TaskRow t) {
        String sql = """
                INSERT INTO task(name,status,mode,from_station,to_station,dates_json,trains_json,
                                 seat_types_json,passenger_ids_json,interval_ms,start_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, t.name());
                ps.setString(2, t.status());
                ps.setString(3, t.mode());
                ps.setString(4, t.from());
                ps.setString(5, t.to());
                ps.setString(6, json(t.dates()));
                ps.setString(7, json(t.trains()));
                ps.setString(8, json(t.seatTypes()));
                ps.setString(9, json(t.passengerIds()));
                ps.setInt(10, t.intervalMs());
                ps.setString(11, t.startAt() == null ? null : t.startAt().toString());
                ps.setString(12, t.createdAt().toString());
                ps.setString(13, t.updatedAt().toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("写入任务失败", e);
            }
        }
    }

    public void updateTask(TaskRow t) {
        String sql = """
                UPDATE task SET name=?,status=?,mode=?,from_station=?,to_station=?,dates_json=?,
                  trains_json=?,seat_types_json=?,passenger_ids_json=?,interval_ms=?,start_at=?,updated_at=?
                WHERE id=?""";
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
                ps.setString(1, t.name());
                ps.setString(2, t.status());
                ps.setString(3, t.mode());
                ps.setString(4, t.from());
                ps.setString(5, t.to());
                ps.setString(6, json(t.dates()));
                ps.setString(7, json(t.trains()));
                ps.setString(8, json(t.seatTypes()));
                ps.setString(9, json(t.passengerIds()));
                ps.setInt(10, t.intervalMs());
                ps.setString(11, t.startAt() == null ? null : t.startAt().toString());
                ps.setString(12, Instant.now().toString());
                ps.setLong(13, t.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新任务失败", e);
            }
        }
    }

    public void updateTaskStatus(long id, String status) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("UPDATE task SET status=?, updated_at=? WHERE id=?")) {
                ps.setString(1, status);
                ps.setString(2, Instant.now().toString());
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新任务状态失败", e);
            }
        }
    }

    public Optional<TaskRow> findTask(long id) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM task WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapTask(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询任务失败", e);
            }
        }
    }

    public List<TaskRow> findAllTasks() {
        List<TaskRow> out = new ArrayList<>();
        synchronized (db) {
            try (Statement st = db.conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM task ORDER BY id")) {
                while (rs.next()) {
                    out.add(mapTask(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询任务列表失败", e);
            }
        }
        return out;
    }

    public void deleteTask(long id) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement("DELETE FROM task WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除任务失败", e);
            }
        }
    }

    private TaskRow mapTask(ResultSet rs) throws SQLException {
        String startAt = rs.getString("start_at");
        return new TaskRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("mode"),
                rs.getString("from_station"),
                rs.getString("to_station"),
                parseList(rs.getString("dates_json"), STR_LIST),
                parseList(rs.getString("trains_json"), STR_LIST),
                parseList(rs.getString("seat_types_json"), STR_LIST),
                parseList(rs.getString("passenger_ids_json"), LONG_LIST),
                rs.getInt("interval_ms"),
                startAt == null ? null : Instant.parse(startAt),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    // ---------- passenger ----------

    public void replacePassengers(long accountId, List<PassengerRow> rows) {
        // 12306 返回的乘客里 code 可能重复甚至为空，原样批量插入会撞 UNIQUE 约束，
        // 整批回滚——用户看到的就是「写入乘车人失败」。这里先去重、再跳过无 pid 的行。
        Map<String, PassengerRow> unique = new LinkedHashMap<>();
        int skipped = 0;
        for (PassengerRow r : rows) {
            if (r.pid() == null || r.pid().isBlank()) {
                skipped++;
                continue;
            }
            unique.put(r.pid(), r);
        }
        if (skipped > 0) {
            log.warn("有 {} 位乘车人没有乘客 ID，已跳过（无法用于下单）", skipped);
        }
        synchronized (db) {
            try {
                db.conn().setAutoCommit(false);
                try (PreparedStatement del = db.conn()
                        .prepareStatement("DELETE FROM passenger WHERE account_id=?")) {
                    del.setLong(1, accountId);
                    del.executeUpdate();
                }
                String sql = """
                        INSERT INTO passenger(account_id,pid,name_masked,id_type,id_no_masked,ticket_type,synced_at)
                        VALUES(?,?,?,?,?,?,?)
                        ON CONFLICT(account_id,pid) DO UPDATE SET
                          name_masked=excluded.name_masked,
                          id_type=excluded.id_type,
                          id_no_masked=excluded.id_no_masked,
                          ticket_type=excluded.ticket_type,
                          synced_at=excluded.synced_at""";
                try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
                    for (PassengerRow r : unique.values()) {
                        ps.setLong(1, accountId);
                        ps.setString(2, r.pid());
                        ps.setString(3, r.nameMasked());
                        ps.setString(4, r.idType());
                        ps.setString(5, r.idNoMasked());
                        ps.setString(6, r.ticketType());
                        ps.setString(7, Instant.now().toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                db.conn().commit();
            } catch (SQLException e) {
                rollback();
                throw new IllegalStateException("写入乘车人失败：" + e.getMessage(), e);
            } finally {
                restoreAutoCommit();
            }
        }
    }

    public List<PassengerRow> findPassengers(long accountId) {
        List<PassengerRow> out = new ArrayList<>();
        synchronized (db) {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("SELECT * FROM passenger WHERE account_id=? ORDER BY id")) {
                ps.setLong(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PassengerRow(
                                rs.getLong("id"), rs.getString("pid"), rs.getString("name_masked"),
                                rs.getString("id_type"), rs.getString("id_no_masked"),
                                rs.getString("ticket_type"), Instant.parse(rs.getString("synced_at"))));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询乘车人失败", e);
            }
        }
        return out;
    }

    // ---------- order ----------

    public void insertOrder(Long taskId, String orderNo, String trainNo, String seatType, String amount) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement("""
                    INSERT INTO "order"(task_id,order_no,train_no,seat_type,amount,status,created_at)
                    VALUES(?,?,?,?,?,'PENDING_PAY',?)""")) {
                if (taskId == null) {
                    ps.setNull(1, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(1, taskId);
                }
                ps.setString(2, orderNo);
                ps.setString(3, trainNo);
                ps.setString(4, seatType);
                ps.setString(5, amount);
                ps.setString(6, Instant.now().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("写入订单失败", e);
            }
        }
    }

    public boolean orderExists(String orderNo) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("SELECT 1 FROM \"order\" WHERE order_no=?")) {
                ps.setString(1, orderNo);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询订单失败", e);
            }
        }
    }

    public List<OrderRow> findAllOrders() {
        List<OrderRow> out = new ArrayList<>();
        synchronized (db) {
            try (Statement st = db.conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM \"order\" ORDER BY id DESC")) {
                while (rs.next()) {
                    long taskId = rs.getLong("task_id");
                    out.add(new OrderRow(rs.getLong("id"), rs.wasNull() ? null : taskId,
                            rs.getString("order_no"), rs.getString("train_no"), rs.getString("seat_type"),
                            rs.getString("amount"), rs.getString("status"),
                            Instant.parse(rs.getString("created_at"))));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询订单列表失败", e);
            }
        }
        return out;
    }

    // ---------- event ----------

    public void appendEvent(Long taskId, String level, String message) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("INSERT INTO event(task_id,level,message,created_at) VALUES(?,?,?,?)")) {
                if (taskId == null) {
                    ps.setNull(1, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(1, taskId);
                }
                ps.setString(2, level);
                ps.setString(3, message);
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("写事件失败：{}", message, e);
            }
        }
    }

    public List<EventRow> eventsAfter(long afterId, int limit) {
        List<EventRow> out = new ArrayList<>();
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement(
                    "SELECT * FROM event WHERE id > ? ORDER BY id LIMIT ?")) {
                ps.setLong(1, afterId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long taskId = rs.getLong("task_id");
                        out.add(new EventRow(rs.getLong("id"), rs.wasNull() ? null : taskId,
                                rs.getString("level"), rs.getString("message"),
                                Instant.parse(rs.getString("created_at"))));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询事件失败", e);
            }
        }
        return out;
    }

    public long lastEventId() {
        synchronized (db) {
            try (Statement st = db.conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id),0) FROM event")) {
                return rs.next() ? rs.getLong(1) : 0;
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    public void purgeEventsBefore(Instant cutoff) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement("DELETE FROM event WHERE created_at < ?")) {
                ps.setString(1, cutoff.toString());
                int removed = ps.executeUpdate();
                if (removed > 0) {
                    log.info("清理过期事件 {} 条", removed);
                }
            } catch (SQLException e) {
                log.warn("清理事件失败", e);
            }
        }
    }

    // ---------- gate / stat ----------

    public void setGate(String state, String reason) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement("""
                    INSERT INTO gate(id,state,reason,updated_at) VALUES(1,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET state=excluded.state, reason=excluded.reason,
                                                  updated_at=excluded.updated_at""")) {
                ps.setString(1, state);
                ps.setString(2, reason);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新闸门失败", e);
            }
        }
    }

    public record GateRow(String state, String reason) {
    }

    public GateRow gate() {
        synchronized (db) {
            try (Statement st = db.conn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT state,reason FROM gate WHERE id=1")) {
                if (rs.next()) {
                    return new GateRow(rs.getString("state"), rs.getString("reason"));
                }
                return new GateRow("OPEN", null);
            } catch (SQLException e) {
                return new GateRow("OPEN", null);
            }
        }
    }

    public record DailyStat(int created, int paid, int cancelled) {
    }

    public DailyStat dailyStat(LocalDate day) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("SELECT * FROM daily_stat WHERE day=?")) {
                ps.setString(1, day.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new DailyStat(rs.getInt("orders_created"), rs.getInt("orders_paid"),
                                rs.getInt("orders_cancelled"));
                    }
                }
                return new DailyStat(0, 0, 0);
            } catch (SQLException e) {
                throw new IllegalStateException("查询每日统计失败", e);
            }
        }
    }

    public void bumpDailyStat(LocalDate day, String column, int delta) {
        if (!column.equals("orders_created") && !column.equals("orders_paid")
                && !column.equals("orders_cancelled")) {
            throw new IllegalArgumentException("非法统计列：" + column);
        }
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement(
                    "INSERT INTO daily_stat(day," + column + ") VALUES(?,?) "
                            + "ON CONFLICT(day) DO UPDATE SET " + column + "=" + column + "+excluded." + column)) {
                ps.setString(1, day.toString());
                ps.setInt(2, delta);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("更新每日统计失败", e);
            }
        }
    }

    // ---------- session ----------

    public Optional<SessionRow> loadSession(long accountId) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("SELECT * FROM session WHERE account_id=?")) {
                ps.setLong(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    String exp = rs.getString("expires_at");
                    return Optional.of(new SessionRow(rs.getString("cookies_json"),
                            rs.getString("rail_deviceid"), exp == null ? null : Instant.parse(exp)));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取会话失败", e);
            }
        }
    }

    public void saveSession(long accountId, String cookiesJson, String railDeviceId, Instant expiresAt) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn().prepareStatement("""
                    INSERT INTO session(account_id,cookies_json,rail_deviceid,updated_at,expires_at)
                    VALUES(?,?,?,?,?)
                    ON CONFLICT(account_id) DO UPDATE SET cookies_json=excluded.cookies_json,
                      rail_deviceid=excluded.rail_deviceid, updated_at=excluded.updated_at,
                      expires_at=excluded.expires_at""")) {
                ps.setLong(1, accountId);
                ps.setString(2, cookiesJson);
                ps.setString(3, railDeviceId);
                ps.setString(4, Instant.now().toString());
                ps.setString(5, expiresAt == null ? null : expiresAt.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("保存会话失败", e);
            }
        }
    }

    public void clearSession(long accountId) {
        synchronized (db) {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("DELETE FROM session WHERE account_id=?")) {
                ps.setLong(1, accountId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("清除会话失败", e);
            }
        }
    }

    // ---------- helpers ----------

    private void rollback() {
        try {
            db.conn().rollback();
        } catch (SQLException ignored) {
        }
    }

    private void restoreAutoCommit() {
        try {
            db.conn().setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private static <T> List<T> parseList(String raw, TypeReference<List<T>> type) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, type);
        } catch (Exception e) {
            return List.of();
        }
    }
}
