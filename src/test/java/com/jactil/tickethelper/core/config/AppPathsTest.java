package com.jactil.tickethelper.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPathsTest {

    @TempDir
    Path tmp;

    @Test
    void fallsBackToUserHomeWhenEnvMissing() {
        AppPaths paths = AppPaths.resolve(null, tmp.toString());

        assertEquals(tmp.resolve(".tickethelper"), paths.dataDir());
        assertTrue(Files.isDirectory(paths.dataDir()));
        assertTrue(Files.isDirectory(paths.logs()));
    }

    @Test
    void usesEnvDataDirWhenPresent() {
        Path dataDir = tmp.resolve("data");
        AppPaths paths = AppPaths.resolve(dataDir.toString(), "/home/ignored");

        assertEquals(dataDir.toAbsolutePath(), paths.dataDir());
        assertEquals(dataDir.toAbsolutePath().resolve("tickethelper.db"), paths.db());
        assertEquals(dataDir.toAbsolutePath().resolve("settings.json"), paths.settings());
        assertEquals(dataDir.toAbsolutePath().resolve("secret.key"), paths.secretKey());
        assertEquals(dataDir.toAbsolutePath().resolve("cookies.json"), paths.cookieStore());
    }
}
