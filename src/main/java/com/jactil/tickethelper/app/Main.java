package com.jactil.tickethelper.app;

import com.jactil.tickethelper.core.config.AppPaths;
import com.jactil.tickethelper.web.App;
import com.jactil.tickethelper.web.AppContext;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程入口。只负责读环境变量、启动 HTTP、注册优雅停机。
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        int port = readPort();
        AppPaths paths = AppPaths.fromEnv();
        log.info("数据目录：{}", paths.dataDir());

        AppContext ctx = AppContext.boot(paths);
        Javalin app = App.create(ctx).start("0.0.0.0", port);
        log.info("TicketHelper-Linux 已启动，监听 http://0.0.0.0:{}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("收到停止信号，正在关闭…");
            app.stop();
            ctx.close();
            log.info("已停止。");
        }, "shutdown-hook"));
    }

    static int readPort() {
        String raw = System.getenv("PORT");
        if (raw == null || raw.isBlank()) {
            return 8080;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT 必须是整数，当前值：" + raw, e);
        }
    }
}
