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

    @Test
    void fallsBackToContainerDataDirWhenEnvMissing() throws Exception {
        Path containerData = tmp.resolve("container-data");
        Files.createDirectories(containerData);

        AppPaths paths = AppPaths.fromEnv(null, tmp.resolve("home").toString(), containerData);

        assertEquals(containerData, paths.dataDir());
    }

    @Test
    void explicitEnvWinsOverContainerDataDir() throws Exception {
        Path containerData = tmp.resolve("container-data");
        Files.createDirectories(containerData);
        Path explicit = tmp.resolve("explicit");

        AppPaths paths = AppPaths.fromEnv(explicit.toString(), tmp.resolve("home").toString(), containerData);

        assertEquals(explicit.toAbsolutePath(), paths.dataDir());
    }

    @Test
    void missingContainerDirFallsBackToUserHome() {
        AppPaths paths = AppPaths.fromEnv(null, tmp.resolve("home").toString(),
                tmp.resolve("no-such-dir"));

        assertEquals(tmp.resolve("home").resolve(".tickethelper"), paths.dataDir());
    }
}
