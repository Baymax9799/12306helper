package com.jactil.tickethelper.core.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接与建表。单连接 + 外部同步，够 NAS 这个量级用；WAL 模式让读写不互相阻塞。
 */
public final class Db implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    private final Connection conn;

    private Db(Connection conn) {
        this.conn = conn;
    }

    public static Db open(Path file) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
            }
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("打开数据库失败：" + file, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    /** 幂等建表。 */
    public void migrate() {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS account (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  note TEXT,
                  login_name TEXT,
                  created_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS session (
                  account_id INTEGER PRIMARY KEY,
                  cookies_json TEXT,
                  rail_deviceid TEXT,
                  updated_at TEXT,
                  expires_at TEXT
                )""",
                """
                CREATE TABLE IF NOT EXISTS passenger (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  account_id INTEGER NOT NULL,
                  pid TEXT NOT NULL,
                  name_masked TEXT NOT NULL,
                  id_type TEXT,
                  id_no_masked TEXT,
                  ticket_type TEXT,
                  synced_at TEXT NOT NULL,
                  UNIQUE(account_id, pid)
                )""",
                """
                CREATE TABLE IF NOT EXISTS task (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  status TEXT NOT NULL,
                  mode TEXT NOT NULL,
                  from_station TEXT NOT NULL,
                  to_station TEXT NOT NULL,
                  dates_json TEXT NOT NULL,
                  trains_json TEXT NOT NULL,
                  seat_types_json TEXT NOT NULL,
                  passenger_ids_json TEXT NOT NULL,
                  interval_ms INTEGER NOT NULL,
                  start_at TEXT,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS "order" (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  task_id INTEGER,
                  order_no TEXT NOT NULL UNIQUE,
                  train_no TEXT,
                  seat_type TEXT,
                  amount TEXT,
                  status TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS event (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  task_id INTEGER,
                  level TEXT NOT NULL,
                  message TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS gate (
                  id INTEGER PRIMARY KEY CHECK (id = 1),
                  state TEXT NOT NULL,
                  reason TEXT,
                  updated_at TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS daily_stat (
                  day TEXT PRIMARY KEY,
                  orders_created INTEGER NOT NULL DEFAULT 0,
                  orders_paid INTEGER NOT NULL DEFAULT 0,
                  orders_cancelled INTEGER NOT NULL DEFAULT 0
                )""",
                "CREATE INDEX IF NOT EXISTS idx_event_created ON event(id)",
                "CREATE INDEX IF NOT EXISTS idx_task_status ON task(status)"
        };
        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql.trim());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("建表失败", e);
        }
        log.debug("数据库结构已就绪");
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("关闭数据库失败", e);
        }
    }
}
