package com.jactil.tickethelper.core.config;

/**
 * 全部可调配置。落盘为 /data/settings.json；敏感字段由 {@link SettingsStore} 负责加密。
 */
public final class Settings {

    /** BCrypt 哈希，不加密（本身不可逆）。 */
    public String adminPasswordHash = "";

    public Smtp smtp = new Smtp();
    public Wechat wechat = new Wechat();
    public Webhook webhook = new Webhook();

    /** 普通任务的基础轮询间隔。 */
    public int intervalMs = 1000;
    /** 每日最多创建几单（12306 规则上限 3，默认保守 1）。 */
    public int dailyOrderLimit = 1;
    /** 邮件开关。 */
    public boolean emailEnabled = true;
    /** 微信推送开关。 */
    public boolean wechatEnabled = false;
    /** 通用 Webhook 开关。 */
    public boolean webhookEnabled = false;
    /** 事件保留天数。 */
    public int eventRetentionDays = 30;

    public static final class Smtp {
        public String host = "";
        public int port = 465;
        public String username = "";
        /** 明文在内存，落盘为 enc: 开头。 */
        public String password = "";
        public String from = "";
        public String to = "";
        public boolean ssl = true;
    }

    public static final class Wechat {
        /** serverchan | pushplus | wecom */
        public String provider = "serverchan";
        /** 落盘加密。 */
        public String key = "";
        /** 仅 wecom 用。 */
        public String webhookUrl = "";
    }

    public static final class Webhook {
        public String url = "";
        public String method = "POST";
        public String contentType = "application/json";
    }
}
