package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where the logs are: reading and saving the lists, finding a service's files, names, guesses. */
class LogCatalogTest {

    @TempDir
    Path dir;

    private static LogCatalog.Service orders(String folder) {
        return new LogCatalog.Service("orders", "Order service", List.of(folder),
                List.of(new LogCatalog.FileKind("app*.log*", "Application log"),
                        new LogCatalog.FileKind("gc*.log", "GC log")), "UTC", "Timeouts before 06:00 are normal.");
    }

    private static void write(Path p, String text, long minutesAgo) throws IOException {
        Files.createDirectories(p.getParent());
        if (p.getFileName().toString().endsWith(".gz")) {
            try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(p))) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            Files.writeString(p, text);
        }
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - minutesAgo * 60_000));
    }

    @Test
    void savesAndReadsBack() throws IOException {
        Path f = dir.resolve("logs.json");
        LogCatalog.Service s = new LogCatalog.Service("billing", "Bills \"monthly\"\nand on demand",
                List.of("C:\\logs\\billing", "/var/log/billing"), List.of(new LogCatalog.FileKind("*.log", "All")), "",
                "");
        LogCatalog.save(f, List.of(orders("/var/log/orders"), s));
        List<LogCatalog.Service> back = LogCatalog.read(f);
        assertEquals(List.of(orders("/var/log/orders"), s), back);
        assertTrue(Files.readString(f).contains("\"name\": \"orders\""), "readable JSON, one key per line");
        assertEquals(List.of(), LogCatalog.parse("{\"services\": []}"));
        // a single "folder" is fine too; services without a name are skipped
        List<LogCatalog.Service> one = LogCatalog.parse("{\"services\":[{\"name\":\"a\",\"folder\":\"/x\"},{\"folder\":\"/y\"}]}");
        assertEquals(1, one.size());
        assertEquals(List.of("/x"), one.get(0).folders());
    }

    @Test
    void badFilesAreSaidNotThrown() throws IOException {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(LogCatalog.userFile(home), "{ not json");
        LogCatalog c = LogCatalog.load(home, null, List.of());
        assertTrue(c.isEmpty());
        assertEquals(1, c.problems().size());
        assertTrue(c.problems().get(0).contains("logs.json"), c.problems().toString());
    }

    @Test
    void projectListWinsAndItsFoldersAreRelative() throws IOException {
        Path home = Files.createDirectories(dir.resolve("home"));
        LogCatalog.save(LogCatalog.userFile(home), List.of(orders("/mine/orders"),
                new LogCatalog.Service("billing", "", List.of("/mine/billing"), List.of(), "", "")));
        Path project = Files.createDirectories(dir.resolve("shop"));
        LogCatalog.save(LogCatalog.projectFile(project), List.of(orders("build/logs")));
        Path deep = Files.createDirectories(project.resolve("src/main/java"));

        LogCatalog c = LogCatalog.load(home, deep, List.of());
        assertEquals(2, c.entries().size());
        LogCatalog.Entry o = c.entry("ORDERS");
        assertTrue(o.project(), "the project's orders, not mine");
        assertEquals(List.of(project.resolve("build/logs").toAbsolutePath().normalize()), o.folders());
        assertFalse(c.entry("billing").project());
        assertEquals(LogCatalog.projectFile(project), LogCatalog.findProject(deep));
        assertNull(LogCatalog.findProject(home));
    }

    @Test
    void rotatedCopiesShareAName() {
        assertEquals("app.log", LogCatalog.rotationKey("app.log"));
        assertEquals("app.log", LogCatalog.rotationKey("app.log.1"));
        assertEquals("app.log", LogCatalog.rotationKey("app.log.12.gz"));
        assertEquals("app.log", LogCatalog.rotationKey("app.2026-10-28.log"));
        assertEquals("app.log", LogCatalog.rotationKey("app-2026-10-28.1.log.gz"));
        assertEquals("app.log", LogCatalog.rotationKey("app.log.2026-10-28"));
        assertEquals("app.log", LogCatalog.rotationKey("app-20261028T1015.log"));
        assertEquals("hs_err_pid#.log", LogCatalog.rotationKey("hs_err_pid4242.log"));
        assertEquals("gc.log", LogCatalog.rotationKey("gc.log.0.current"
                .replace(".current", "")));
        assertEquals("access.log", LogCatalog.rotationKey("access.log"));
    }

    @Test
    void findsAndGroupsAServicesFiles() throws IOException {
        Path logs = dir.resolve("srv/orders/logs");
        String lines = "2026-10-28 10:00:00.000 INFO  [main] c.a.Orders - started\n".repeat(20);
        write(logs.resolve("app.log"), lines, 1);
        write(logs.resolve("app.log.1"), lines, 60 * 24);
        write(logs.resolve("app.log.2.gz"), lines, 60 * 48);
        write(logs.resolve("gc.log"), "[0.015s][info][gc] Using G1\n", 2);
        write(logs.resolve("archive/old/app.log.30.gz"), lines, 60 * 24 * 30); // within the depth looked at
        write(logs.resolve(".hidden/secret.log"), "x", 0);

        LogCatalog.Entry e = new LogCatalog.Entry(orders(logs.toString()), dir.resolve("logs.json"), false);
        List<LogCatalog.Group> groups = LogCatalog.scan(e);
        assertEquals(3, groups.size(), groups.toString());
        LogCatalog.Group app = groups.get(0);
        assertEquals("app.log", app.name());
        assertEquals("app.log*", app.pattern());
        assertEquals("Application log", app.description());
        assertEquals(3, app.files().size());
        assertEquals(logs.resolve("app.log"), app.current());
        assertEquals(logs.resolve("app.log"), app.files().get(0), "newest first");
        LogCatalog.Group gc = groups.get(1);
        assertEquals("GC log", gc.description());
        assertEquals(1, groups.get(2).files().size(), "the archived copy is in its own folder: its own group");

        LogCatalog c = LogCatalog.of(List.of(e));
        assertEquals(logs.resolve("app.log"), c.resolve("orders/app"));
        assertEquals(logs.resolve("app.log"), c.resolve("orders/app.log"));
        assertEquals(logs.resolve("app.log.2.gz"), c.resolve("orders/app.log.2.gz"));
        assertEquals(logs.resolve("gc.log"), c.resolve("Orders/GC"));
        assertNull(c.resolve("orders"), "two kinds of log: which one?");
        assertNull(c.resolve("billing/app"));
        assertEquals(e, c.entryFor(logs.resolve("app.log.1")));
    }

    @Test
    void guessesWhatAFileHolds() throws IOException {
        write(dir.resolve("gc.log"), "[2026-10-28T10:00:00.123+0000][0.015s][info][gc] Using G1\n"
                + "[2026-10-28T10:00:01.456+0000][1.348s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) "
                + "24M->4M(256M) 3.112ms\n"
                + "[2026-10-28T10:00:05.789+0000][5.681s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) "
                + "28M->6M(256M) 2.871ms\n", 0);
        write(dir.resolve("hs_err_pid1.log"), "#\n# A fatal error has been detected by the Java Runtime Environment:\n", 0);
        write(dir.resolve("orders.csv"), "order_id,placed_at,amount\n1,2026-10-28 10:00:00,12.50\n2,2026-10-28 10:01:00,3\n", 0);
        write(dir.resolve("app.log.gz"),
                "2026-10-28 10:00:00.000 INFO  [main] c.a.Orders - started\n".repeat(30), 0);
        write(dir.resolve("empty.log"), "", 0);
        assertEquals("Java GC log", LogCatalog.guess(dir.resolve("gc.log"), List.of()));
        assertEquals("Java crash report (hs_err)", LogCatalog.guess(dir.resolve("hs_err_pid1.log"), List.of()));
        String csv = LogCatalog.guess(dir.resolve("orders.csv"), List.of());
        assertTrue(csv.startsWith("CSV") && csv.contains("order_id, placed_at, amount"), csv);
        String app = LogCatalog.guess(dir.resolve("app.log.gz"), List.of());
        assertTrue(app.startsWith("Log ("), "read through the .gz: " + app);
        assertFalse(app.contains("[time]"), "the format's name, not its layout: " + app);
        assertEquals("Empty", LogCatalog.guess(dir.resolve("empty.log"), List.of()));
        assertNull(LogCatalog.guess(dir.resolve("missing.log"), List.of()));
    }

    @Test
    void foldersWorkOnOtherComputers() {
        String home = System.getProperty("user.home");
        java.util.Map<String, String> env = java.util.Map.of("LOG_DIR", "/data/logs");
        assertEquals("/data/logs/orders", LogCatalog.expand("${LOG_DIR}/orders", env));
        assertEquals("/data/logs/orders", LogCatalog.expand("$LOG_DIR/orders", env));
        assertEquals("/var/log/orders", LogCatalog.expand("${ORDERS_LOGS:-/var/log/orders}", env));
        assertNull(LogCatalog.expand("${NOT_SET}/orders", env), "a variable this computer doesn't set: skipped");
        assertEquals(home + "/logs", LogCatalog.expand("~/logs", env));

        Path project = Path.of(home, "work", "shop").toAbsolutePath();
        assertEquals("build/logs", LogCatalog.portable(project.resolve("build/logs").toString(), project));
        assertEquals("~/logs/orders", LogCatalog.portable(Path.of(home, "logs", "orders").toString(), null));
        assertEquals("/var/log/orders", LogCatalog.portable("/var/log/orders", project));
        assertEquals("${LOG_DIR}/x", LogCatalog.portable("${LOG_DIR}/x", project), "written by hand: kept");
    }

    @Test
    void anEntrysFoldersExpandVariables() throws IOException {
        Path f = dir.resolve("logs.json");
        LogCatalog.Entry e = new LogCatalog.Entry(new LogCatalog.Service("a", "", List.of("~/x", "${SURELY_NOT_SET_42}/y"),
                List.of(), "", ""), f, false);
        assertEquals(List.of(Path.of(System.getProperty("user.home"), "x")), e.folders());
    }

    @Test
    void matchesGlobsInAnyCase() {
        assertTrue(LogCatalog.matches("app*.log*", "APP.log.1"));
        assertTrue(LogCatalog.matches("hs_err_pid*.log", "hs_err_pid42.log"));
        assertFalse(LogCatalog.matches("gc*.log", "app.log"));
    }
}
