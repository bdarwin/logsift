package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Logsift updating itself: what's newer, the file for this computer, downloaded from GitHub only
 * and checked against the release's sums, made ready beside the app, and put in place once it quits.
 */
class SelfUpdateTest {
    @TempDir
    Path dir;

    private HttpServer server;
    /** What the test's GitHub serves, by path. */
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> redirects = new ConcurrentHashMap<>();
    private String was;

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            String p = x.getRequestURI().getPath();
            if (redirects.containsKey(p)) {
                x.getResponseHeaders().add("Location", redirects.get(p));
                x.sendResponseHeaders(302, -1);
            } else if (files.containsKey(p)) {
                byte[] b = files.get(p);
                x.sendResponseHeaders(200, b.length);
                x.getResponseBody().write(b);
            } else {
                x.sendResponseHeaders(404, -1);
            }
            x.close();
        });
        server.start();
        was = System.getProperty("logsift.updateBase");
        System.setProperty("logsift.updateBase", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        if (was == null) {
            System.clearProperty("logsift.updateBase");
        } else {
            System.setProperty("logsift.updateBase", was);
        }
    }

    private static final String REPO = "/repos/bdarwin/logsift";
    private static final String DL = "/bdarwin/logsift/releases/download/";

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    // ---------------------------------------------------------------- what's newer

    @Test
    void aNewerReleaseIsFound() throws Exception {
        SelfUpdate.Build b = new SelfUpdate.Build("1.4.0", "release", "aaa");
        files.put(REPO + "/releases/latest", """
                {"tag_name": "v1.4.1", "published_at": "2026-11-08T09:00:00Z", "html_url": "https://evil.example/x"}"""
                .getBytes(StandardCharsets.UTF_8));
        SelfUpdate.Available a = SelfUpdate.check(SelfUpdate.Channel.RELEASES, b).orElseThrow();
        assertEquals("v1.4.1", a.tag());
        assertEquals("Logsift 1.4.1", a.title());
        assertEquals("https://github.com/bdarwin/logsift/releases/tag/v1.4.1", a.page(), "never the answer's own link");
        assertEquals(Instant.parse("2026-11-08T09:00:00Z"), a.date());
        assertEquals(Optional.empty(), SelfUpdate.check(SelfUpdate.Channel.RELEASES,
                new SelfUpdate.Build("1.4.1", "release", "")), "the same: nothing newer");
        files.put(REPO + "/releases/latest", "{\"tag_name\": \"v1.4.1; rm -rf /\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(Optional.empty(), SelfUpdate.check(SelfUpdate.Channel.RELEASES, b), "only a plain version");
    }

    @Test
    void aSnapshotIsNewerWhenBuiltFromAnotherCommit() throws Exception {
        files.put(REPO + "/releases/tags/snapshot", """
                {"tag_name": "snapshot", "target_commitish": "61a7f1d0123456789abcdef0123456789abcdef0",
                 "published_at": "2026-11-07T10:00:00Z"}""".getBytes(StandardCharsets.UTF_8));
        SelfUpdate.Build old = new SelfUpdate.Build("1.4.1", "snapshot", "3c4f2d3000000000000000000000000000000000");
        SelfUpdate.Available a = SelfUpdate.check(SelfUpdate.Channel.SNAPSHOTS, old).orElseThrow();
        assertTrue(a.snapshot());
        assertTrue(a.title().startsWith("The snapshot build of ") && a.title().endsWith("(61a7f1d)"), a.title());
        assertEquals("https://github.com/bdarwin/logsift/releases/tag/snapshot", a.page());
        SelfUpdate.Build same = new SelfUpdate.Build("1.4.1", "snapshot", "61a7f1d0123456789abcdef0123456789abcdef0");
        assertEquals(Optional.empty(), SelfUpdate.check(SelfUpdate.Channel.SNAPSHOTS, same));
        assertTrue(SelfUpdate.newer(a, old));
        assertFalse(SelfUpdate.newer(a, same), "this one is it");
        // a release that chose snapshots: the snapshot is offered
        assertTrue(SelfUpdate.check(SelfUpdate.Channel.SNAPSHOTS, new SelfUpdate.Build("1.4.0", "release", "")).isPresent());
        files.remove(REPO + "/releases/tags/snapshot");
        assertEquals(Optional.empty(), SelfUpdate.check(SelfUpdate.Channel.SNAPSHOTS, old), "no snapshot published");
    }

    @Test
    void turnedOffNothingIsAsked() {
        System.clearProperty("logsift.updateBase"); // the tests' logsift.updateUrl=none, as a person can set it
        assertFalse(SelfUpdate.enabled());
        IOException e = assertThrows(IOException.class, () -> SelfUpdate.check(SelfUpdate.Channel.SNAPSHOTS,
                new SelfUpdate.Build("1.4.1", "snapshot", "x")));
        assertEquals("Update checks are turned off.", e.getMessage());
        assertThrows(IOException.class, () -> SelfUpdate.download(new SelfUpdate.Available("v9", "9", "", null),
                new SelfUpdate.Install(SelfUpdate.Kind.FOLDER, dir, dir.resolve("x"), "linux-x64", "tar.gz"), dir,
                n -> { }, () -> false));
    }

    @Test
    void theBuildSaysWhatItIsAndWhereItsUpdatesComeFrom() {
        assertEquals(SelfUpdate.Channel.SNAPSHOTS, new SelfUpdate.Build("1.4.1", "snapshot", "x").defaultChannel());
        assertEquals(SelfUpdate.Channel.RELEASES, new SelfUpdate.Build("1.4.1", "release", "x").defaultChannel());
        assertTrue(new SelfUpdate.Build("1.4.1", "release", "").updates());
        assertFalse(new SelfUpdate.Build("1.4.1", "dev", "").updates(), "built here");
        assertFalse(new SelfUpdate.Build("1.4.1", "beta", "").updates(), "a beta is from a branch");
        assertEquals("dev", SelfUpdate.Build.current().kind(), "these tests run from classes");
        assertEquals(SelfUpdate.Channel.SNAPSHOTS, SelfUpdate.Channel.of("snapshots", SelfUpdate.Channel.RELEASES));
        assertEquals(SelfUpdate.Channel.RELEASES, SelfUpdate.Channel.of("nightly", SelfUpdate.Channel.RELEASES));
    }

    // ---------------------------------------------------------------- this computer

    @Test
    void theFileForThisComputer() throws Exception {
        SelfUpdate.Available rel = new SelfUpdate.Available("v1.4.1", "1.4.1", "", null);
        SelfUpdate.Available snap = new SelfUpdate.Available("snapshot", "", "abc1234", null);
        Path apps = Files.createDirectories(dir.resolve("Applications"));
        Path mac = Files.createDirectories(apps.resolve("Logsift.app/Contents/MacOS")).resolve("Logsift");
        SelfUpdate.Install m = SelfUpdate.install("Mac OS X", "aarch64", mac.toString(), null, null);
        assertEquals(SelfUpdate.Kind.MAC_APP, m.kind());
        assertEquals(apps.resolve("Logsift.app"), m.app());
        assertEquals("Logsift-1.4.1-macos-arm64.dmg", m.asset(rel));
        assertEquals("Logsift-snapshot-macos-arm64.dmg", m.asset(snap));
        assertEquals("Logsift-1.4.1-macos-x64.dmg",
                SelfUpdate.install("Mac OS X", "x86_64", mac.toString(), null, null).asset(rel));

        Path local = Files.createDirectories(dir.resolve("AppData/Local"));
        Path exe = Files.createDirectories(local.resolve("Logsift")).resolve("Logsift.exe");
        SelfUpdate.Install msi = SelfUpdate.install("Windows 11", "amd64", exe.toString(), null, local.toString());
        assertEquals(SelfUpdate.Kind.WINDOWS_MSI, msi.kind(), "installed for one person by the .msi");
        assertEquals("Logsift-1.4.1-windows-x64.msi", msi.asset(rel));
        Path unzipped = Files.createDirectories(dir.resolve("tools/Logsift")).resolve("Logsift.exe");
        SelfUpdate.Install zip = SelfUpdate.install("Windows 11", "aarch64", unzipped.toString(), null, local.toString());
        assertEquals(SelfUpdate.Kind.FOLDER, zip.kind(), "unzipped somewhere");
        assertEquals("Logsift-snapshot-windows-arm64.zip", zip.asset(snap));

        Path tar = Files.createDirectories(dir.resolve("apps/Logsift/bin")).resolve("Logsift");
        SelfUpdate.Install lin = SelfUpdate.install("Linux", "amd64", tar.toString(), null, null);
        assertEquals(SelfUpdate.Kind.FOLDER, lin.kind());
        assertEquals(dir.resolve("apps/Logsift"), lin.app());
        assertEquals("Logsift-1.4.1-linux-x64.tar.gz", lin.asset(rel));
        SelfUpdate.Install deb = SelfUpdate.install("Linux", "amd64", "/opt/logsift/bin/Logsift", null, null);
        assertEquals(SelfUpdate.Kind.BY_HAND, deb.kind(), "the .deb's: root's to change");
        assertEquals("Logsift-1.4.1-linux-x64.deb", deb.asset(rel));
        assertFalse(deb.automatic());

        Path any = Files.createDirectories(dir.resolve("logsift-1.4.0/lib")).resolve("logsift-1.4.0.jar");
        Files.writeString(any, "jar");
        Files.createDirectories(dir.resolve("logsift-1.4.0/bin"));
        Files.writeString(dir.resolve("logsift-1.4.0/bin/logsift"), "#!/bin/sh");
        SelfUpdate.Install j = SelfUpdate.install("Linux", "amd64", null, any, null);
        assertEquals(SelfUpdate.Kind.JAVA_ZIP, j.kind());
        assertEquals("Logsift-1.4.1-any-java21.zip", j.asset(rel));

        assertNull(SelfUpdate.install("Linux", "amd64", null, dir.resolve("classes"), null), "run from classes");
        assertNull(SelfUpdate.install("SunOS", "sparc", tar.toString(), null, null));
    }

    // ---------------------------------------------------------------- downloading

    private SelfUpdate.Install folder() throws IOException {
        Path launcher = Files.createDirectories(dir.resolve("apps/Logsift/bin")).resolve("Logsift");
        return SelfUpdate.install("Linux", "amd64", launcher.toString(), null, null);
    }

    @Test
    void theDownloadIsCheckedAgainstTheReleasesSums() throws Exception {
        SelfUpdate.Install i = folder();
        SelfUpdate.Available a = new SelfUpdate.Available("v1.4.1", "1.4.1", "", null);
        byte[] good = "the new Logsift".getBytes(StandardCharsets.UTF_8);
        files.put(DL + "v1.4.1/Logsift-1.4.1-linux-x64.tar.gz", good);
        files.put(DL + "v1.4.1/SHA256SUMS.txt", (sha("other".getBytes()) + "  Logsift-1.4.1-linux-x64.deb\n"
                + sha(good) + "  Logsift-1.4.1-linux-x64.tar.gz\n").getBytes(StandardCharsets.UTF_8));
        Path updates = dir.resolve("updates");
        long[] seen = {0, 0};
        Path f = SelfUpdate.download(a, i, updates, n -> {
            if (n < 0) {
                seen[0] = -1 - n;
            } else {
                seen[1] = n;
            }
        }, () -> false);
        assertEquals("the new Logsift", Files.readString(f));
        assertEquals(good.length, seen[0], "its size first");
        assertEquals(good.length, seen[1]);

        // changed on the way: not used, and not kept
        files.put(DL + "v1.4.1/Logsift-1.4.1-linux-x64.tar.gz", "something else".getBytes(StandardCharsets.UTF_8));
        Files.delete(f);
        IOException e = assertThrows(IOException.class, () -> SelfUpdate.download(a, i, updates, n -> { }, () -> false));
        assertTrue(e.getMessage().contains("SHA-256 doesn't match"), e.getMessage());
        try (var left = Files.list(updates)) {
            assertEquals(0, left.count(), "nothing left of it");
        }
        // no sums (1.4.0 and before): not downloaded
        files.remove(DL + "v1.4.1/SHA256SUMS.txt");
        e = assertThrows(IOException.class, () -> SelfUpdate.download(a, i, updates, n -> { }, () -> false));
        assertTrue(e.getMessage().contains("has no checksums"), e.getMessage());
    }

    @Test
    void onlyGitHubsOwnHosts() throws Exception {
        assertThrows(IOException.class, () -> SelfUpdate.check(URI.create("https://evil.example/x")));
        assertThrows(IOException.class, () -> SelfUpdate.check(URI.create("http://github.com/x")), "not plain HTTP");
        SelfUpdate.check(URI.create("https://objects.githubusercontent.com/x"));
        SelfUpdate.check(URI.create("https://github.com/bdarwin/logsift/releases/download/v1/x"));
        // a redirect elsewhere is not followed
        SelfUpdate.Install i = folder();
        SelfUpdate.Available a = new SelfUpdate.Available("v1.4.1", "1.4.1", "", null);
        redirects.put(DL + "v1.4.1/SHA256SUMS.txt", "https://evil.example/sums");
        IOException e = assertThrows(IOException.class,
                () -> SelfUpdate.download(a, i, dir.resolve("u"), n -> { }, () -> false));
        assertTrue(e.getMessage().contains("evil.example"), e.getMessage());
    }

    @Test
    void sumsAreReadAsSha256sumWritesThem() {
        String h = "a".repeat(64);
        assertEquals(h, SelfUpdate.sum(h + "  Logsift-1.4.1-macos-arm64.dmg\n", "Logsift-1.4.1-macos-arm64.dmg"));
        assertEquals(h, SelfUpdate.sum(h.toUpperCase() + " *Logsift-1.4.1-macos-arm64.dmg\r\n", "Logsift-1.4.1-macos-arm64.dmg"));
        assertNull(SelfUpdate.sum(h + "  Logsift-1.4.1-macos-arm64.dmg.evil\n", "Logsift-1.4.1-macos-arm64.dmg"));
        assertNull(SelfUpdate.sum("xyz  Logsift-1.4.1-macos-arm64.dmg\n", "Logsift-1.4.1-macos-arm64.dmg"));
    }

    // ---------------------------------------------------------------- putting it in place

    /** A zip of one folder, as the Windows .zip and the plain Java zip are. */
    private static byte[] zip(String top, Map<String, String> entries) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new java.util.zip.ZipEntry(top + "/" + e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void aZipIsUnpackedBesideTheAppAndItsScriptsCanRun() throws Exception {
        Path app = Files.createDirectories(dir.resolve("logsift-1.4.0"));
        Files.createDirectories(app.resolve("lib"));
        Files.createDirectories(app.resolve("bin"));
        Path jar = Files.writeString(app.resolve("lib/logsift-1.4.0.jar"), "old");
        Files.writeString(app.resolve("bin/logsift"), "old");
        Files.writeString(app.resolve("bin/logsift.bat"), "old");
        SelfUpdate.Install i = SelfUpdate.install(System.getProperty("os.name"), System.getProperty("os.arch"), null,
                jar, null);
        assertEquals(SelfUpdate.Kind.JAVA_ZIP, i.kind());
        Path z = dir.resolve("Logsift-1.4.1-any-java21.zip");
        Files.write(z, zip("logsift-1.4.1", Map.of("lib/logsift-1.4.1.jar", "new", "bin/logsift", "#!/bin/sh\necho new",
                "bin/logsift.bat", "@echo new")));
        Path ready = SelfUpdate.prepare(i, z);
        assertEquals(Path.of(app + ".new"), ready);
        assertEquals("new", Files.readString(ready.resolve("lib/logsift-1.4.1.jar")));
        if (!OS.WINDOWS.isCurrentOs()) {
            assertTrue(Files.isExecutable(ready.resolve("bin/logsift")));
        }
        assertEquals("old", Files.readString(jar), "the running app isn't touched");
        try (var s = Files.list(dir)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith(".logsift-update")), "no leftovers");
        }
        // a zip that would write outside its folder isn't used
        Files.write(z, zip("..", Map.of("evil", "x")));
        assertThrows(IOException.class, () -> SelfUpdate.prepare(i, z));
        assertFalse(Files.exists(dir.resolve("evil")));
    }

    @Test
    void theScriptPutsTheNewAppInPlaceOnceLogsiftHasQuitAndStartsIt() throws Exception {
        boolean windows = OS.WINDOWS.isCurrentOs();
        // the app as unzipped: a folder with its launcher (a script here, so the test sees it start)
        Path app = Files.createDirectories(dir.resolve("apps/Logsift"));
        Path started = dir.resolve("started.txt");
        String launcherName = windows ? "Logsift.cmd" : "bin/Logsift";
        Path launcher = app.resolve(launcherName);
        Files.createDirectories(launcher.getParent());
        Files.writeString(app.resolve("version.txt"), "old");
        Files.writeString(launcher, "old");
        Path ready = Files.createDirectories(dir.resolve("apps/Logsift.new"));
        Files.writeString(ready.resolve("version.txt"), "new");
        Path newLauncher = ready.resolve(launcherName);
        Files.createDirectories(newLauncher.getParent());
        Files.writeString(newLauncher, windows ? "@echo started> \"" + started + "\"\r\n"
                : "#!/bin/sh\necho started > '" + started + "'\n");
        newLauncher.toFile().setExecutable(true);
        SelfUpdate.Install i = new SelfUpdate.Install(SelfUpdate.Kind.FOLDER, app, launcher,
                windows ? "windows-x64" : "linux-x64", windows ? "zip" : "tar.gz");
        // "Logsift": a process that quits a moment later
        Process running = new ProcessBuilder(windows ? List.of("ping", "-n", "3", "127.0.0.1")
                : List.of("sleep", "2")).start();
        Path updates = dir.resolve("updates");
        Path script = SelfUpdate.script(i, ready, running.pid(), true, updates);
        SelfUpdate.start(script, updates);
        Thread.sleep(500);
        assertEquals("old", Files.readString(app.resolve("version.txt")), "nothing changes while it runs");
        assertTrue(running.waitFor(20, TimeUnit.SECONDS));
        long until = System.currentTimeMillis() + 30_000;
        while (!Files.exists(started) && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        assertTrue(Files.exists(started), "started again: " + read(updates.resolve("update.log")));
        assertEquals("new", Files.readString(app.resolve("version.txt")));
        assertFalse(Files.exists(ready), "moved in");
        assertFalse(Files.exists(Path.of(app + ".old")), "the old one gone: " + read(updates.resolve("update.log")));
    }

    @Test
    void whenTheNewAppCantGoInTheOldOneStays() throws Exception {
        boolean windows = OS.WINDOWS.isCurrentOs();
        Path app = Files.createDirectories(dir.resolve("apps/Logsift"));
        Files.writeString(app.resolve("version.txt"), "old");
        SelfUpdate.Install i = new SelfUpdate.Install(SelfUpdate.Kind.FOLDER, app, app.resolve("Logsift"),
                windows ? "windows-x64" : "linux-x64", "zip");
        Path updates = dir.resolve("updates");
        // nothing ready (removed in between): the script stops at once
        Path script = SelfUpdate.script(i, dir.resolve("apps/Logsift.new"), ProcessHandle.current().pid() + 999_999,
                false, updates);
        Process p = new ProcessBuilder(windows ? List.of("cmd", "/c", script.toString())
                : List.of("/bin/sh", script.toString())).redirectErrorStream(true).start();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals("old", Files.readString(app.resolve("version.txt")));
        assertTrue(read(updates.resolve("update.log")).contains("nothing ready"), read(updates.resolve("update.log")));
    }

    private static String read(Path f) {
        try {
            return Files.exists(f) ? Files.readString(f) : "(no log)";
        } catch (IOException e) {
            return e.toString();
        }
    }

    @Test
    void theScriptsQuoteTheirPaths() {
        Path app = Path.of("/Users/o'neil/Apps/Logsift.app");
        SelfUpdate.Install i = new SelfUpdate.Install(SelfUpdate.Kind.MAC_APP, app,
                app.resolve("Contents/MacOS/Logsift"), "macos-arm64", "dmg");
        String s = SelfUpdate.posixScript(i, Path.of(app + ".new"), 42, true, Path.of("/tmp/u.log"));
        assertTrue(s.contains("APP='/Users/o'\\''neil/Apps/Logsift.app'"), s);
        assertTrue(s.contains("while kill -0 42"), s);
        assertTrue(s.endsWith("open '/Users/o'\\''neil/Apps/Logsift.app'\n"), "starts it again: " + s);
        assertFalse(SelfUpdate.posixScript(i, Path.of(app + ".new"), 42, false, Path.of("/tmp/u.log")).contains("open "),
                "when Logsift quits: not started again");
        SelfUpdate.Install w = new SelfUpdate.Install(SelfUpdate.Kind.WINDOWS_MSI, Path.of("C:/Users/me/AppData/Local/Logsift"),
                Path.of("C:/Users/me/AppData/Local/Logsift/Logsift.exe"), "windows-x64", "msi");
        String c = SelfUpdate.windowsScript(w, Path.of("C:/Users/me/.logsift/updates/Logsift-1.4.1-windows-x64.msi"), 42,
                true, Path.of("C:/u.log"));
        assertTrue(c.contains("msiexec /i \"") && c.contains("/passive"), c);
        assertTrue(c.contains("PID eq 42"), c);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void aDmgsAppIsCopiedOutBesideTheApp() throws Exception {
        // a .dmg like the release's: Logsift.app inside
        Path src = Files.createDirectories(dir.resolve("dmg-src/Logsift.app/Contents/MacOS"));
        Files.writeString(src.resolve("Logsift"), "#!/bin/sh\n");
        src.resolve("Logsift").toFile().setExecutable(true);
        Path dmg = dir.resolve("Logsift-1.4.1-macos-arm64.dmg");
        Process p = new ProcessBuilder("hdiutil", "create", "-quiet", "-srcfolder", dir.resolve("dmg-src").toString(),
                "-volname", "Logsift", "-format", "UDZO", dmg.toString()).redirectErrorStream(true).start();
        assertEquals(0, p.waitFor(), new String(p.getInputStream().readAllBytes()));
        Path installed = Files.createDirectories(dir.resolve("Applications/Logsift.app/Contents/MacOS"));
        Files.writeString(installed.resolve("Logsift"), "old");
        SelfUpdate.Install i = SelfUpdate.install("Mac OS X", "aarch64", installed.resolve("Logsift").toString(), null, null);
        Path ready = SelfUpdate.prepare(i, dmg);
        assertEquals(dir.resolve("Applications/Logsift.app.new"), ready);
        assertTrue(Files.isExecutable(ready.resolve("Contents/MacOS/Logsift")));
        assertEquals("old", Files.readString(installed.resolve("Logsift")));
    }

    @Test
    void anArchiveWithoutTheAppIsntUsed() throws Exception {
        Path app = Files.createDirectories(dir.resolve("apps/Logsift"));
        Files.createDirectories(app.resolve("bin"));
        Files.writeString(app.resolve("bin/Logsift"), "old");
        SelfUpdate.Install i = new SelfUpdate.Install(SelfUpdate.Kind.FOLDER, app, app.resolve("bin/Logsift"),
                "windows-x64", "zip");
        Path z = dir.resolve("x.zip");
        Files.write(z, zip("Logsift", Map.of("readme.txt", "no launcher")));
        IOException e = assertThrows(IOException.class, () -> SelfUpdate.prepare(i, z));
        assertTrue(e.getMessage().contains("has no"), e.getMessage());
        assertFalse(Files.exists(Path.of(app + ".new")));
        assertNotNull(i);
    }
}
