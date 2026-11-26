package com.jactil.tickethelper.web;

import com.jactil.tickethelper.core.config.AppPaths;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthTest {

    @TempDir
    Path tmp;

    @Test
    void healthzReturnsOk() throws Exception {
        AppContext ctx = AppContext.boot(AppPaths.resolve(tmp.resolve("data").toString(), "unused"));
        Javalin app = App.create(ctx).start("127.0.0.1", 0);
        try {
            int port = app.port();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"ok\""), "body=" + response.body());
        } finally {
            app.stop();
            ctx.close();
        }
    }
}
