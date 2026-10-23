package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which JVM writes a log: from the log's own lines, the file server, who has it open, and links. */
class LogOriginTest {

    private static JvmProcess jvm(long pid, String name, String command) {
        return new JvmProcess(pid, name, command, "ops", "1 h", false);
    }

    @Test
    void springBootStartupLines() {
        LogOrigin.Hint h = LogOrigin.scan(List.of(
                "2026-10-22T10:00:00.000+02:00  INFO 4242 --- [orders] [           main] c.a.o.OrdersApplication"
                        + "                  : Starting OrdersApplication v1.4.2 using Java 21.0.4 with PID 4242"
                        + " (/opt/orders/orders-1.4.2.jar started by ops in /opt/orders)"));
        assertEquals(new LogOrigin.Hint("orders-1.4.2.jar", "", 4242, "ops"), h);

        // Spring Boot 2: the host too; run from classes: the main class
        h = LogOrigin.scan(List.of("2021-05-01 10:00:00.000  INFO 77 --- [main] c.a.Billing : Starting Billing"
                + " using Java 11.0.13 on prod-7 with PID 77 (/srv/billing/classes started by svc in /srv/billing)"));
        assertEquals(new LogOrigin.Hint("Billing", "prod-7", 77, "svc"), h);

        // Spring Boot 1
        h = LogOrigin.scan(List.of("Starting App on box.acme.com with PID 9 (/x/app.jar started by me in /x)"));
        assertEquals(new LogOrigin.Hint("app.jar", "box.acme.com", 9, "me"), h);
        assertEquals("app.jar on box.acme.com (PID 9)", h.label());
    }

    @Test
    void theLastStartWinsAndThePidColumnSaysWhoWroteLast() {
        LogOrigin.Hint h = LogOrigin.scan(List.of(
                "Starting App using Java 21 with PID 10 (/a/app.jar started by me in /a)",
                "2026-10-22T10:00:00Z  INFO 10 --- [main] x : up",
                "Starting App using Java 21 with PID 20 (/a/app.jar started by me in /a)",
                "2026-10-22T11:00:00Z  INFO 20 --- [main] x : up"));
        assertEquals(20, h.pid());
        // only the tail was read, after a restart: the pid on the lines
        h = LogOrigin.scan(List.of("Starting App using Java 21 with PID 10 (/a/app.jar started by me in /a)",
                "2026-10-22 11:00:00.000  WARN 31 --- [nio-8080-exec-1] x : slow"));
        assertEquals(31, h.pid());
        assertEquals("app.jar", h.program());
        assertTrue(LogOrigin.scan(List.of("2026-10-22 10:00:00 INFO [main] nothing here", "pid of x")).isEmpty());
    }

    @Test
    void fileServers() {
        assertEquals("files01", LogOrigin.uncHost("\\\\files01\\logs\\orders\\app.log"));
        assertEquals("files01", LogOrigin.uncHost("//files01/logs/app.log"));
        assertNull(LogOrigin.uncHost("C:\\logs\\app.log"));
        String mac = """
                /dev/disk3s1s1 on / (apfs, sealed, local, read-only, journaled)
                //ops@files01.acme.com/logs on /Volumes/logs (smbfs, nodev, nosuid, mounted by ops)
                files02:/export/logs on /private/nfs (nfs, nodev)
                /dev/disk5 on /Volumes/logs/usb (apfs, local)
                """;
        assertEquals("files01.acme.com", LogOrigin.mountHost(mac, "/Volumes/logs/orders/app.log"));
        assertEquals("files02", LogOrigin.mountHost(mac, "/private/nfs/app.log"));
        assertNull(LogOrigin.mountHost(mac, "/Users/me/app.log"));
        assertNull(LogOrigin.mountHost(mac, "/Volumes/logs/usb/app.log"), "a local disk inside the share");
        String linux = """
                /dev/sda1 on / type ext4 (rw,relatime)
                //files03/share on /mnt/share type cifs (rw,vers=3.0)
                files04:/var/log on /mnt/nfs type nfs4 (rw)
                """;
        assertEquals("files03", LogOrigin.mountHost(linux, "/mnt/share/app.log"));
        assertEquals("files04", LogOrigin.mountHost(linux, "/mnt/nfs/app.log"));
        assertNull(LogOrigin.mountHost(linux, "/mnt/sharex/app.log"));
        String netUse = """
                Status       Local     Remote                    Network
                -------------------------------------------------------------------------------
                OK           Z:        \\\\files05\\logs           Microsoft Windows Network
                """;
        assertEquals("files05", LogOrigin.netUseHost(netUse, 'Z'));
        assertEquals("files05", LogOrigin.netUseHost(netUse, 'z'));
        assertNull(LogOrigin.netUseHost(netUse, 'Y'));
    }

    @Test
    void choosing() {
        List<JvmProcess> running = List.of(jvm(300, "orders-1.5.0.jar", "java -jar /opt/orders/orders-1.5.0.jar"),
                jvm(200, "com.acme.Billing", "java -Dlogging.file.name=/var/log/billing/billing.log com.acme.Billing"),
                jvm(100, "worker.jar", "java -jar worker.jar"));
        LogOrigin.Hint none = LogOrigin.Hint.NONE;
        assertEquals(100, LogOrigin.choose(running, Set.of(100L), null, none, "box", null).jvm().pid());
        assertEquals("has this log open", LogOrigin.choose(running, Set.of(100L), null, none, "box", null).why());
        // remembered by program: a new version of the jar, a new process id
        JvmLink before = new JvmLink(JvmLink.Kind.LOCAL, "", 0, "", "orders-1.4.2.jar", 99);
        assertEquals(300, LogOrigin.choose(running, Set.of(), before, none, "box", null).jvm().pid());
        // the log's pid, on this host only
        LogOrigin.Hint billing = new LogOrigin.Hint("Billing", "box", 200, "");
        assertEquals(200, LogOrigin.choose(running, Set.of(), null, billing, "box.acme.com", null).jvm().pid());
        assertNull(LogOrigin.choose(running, Set.of(), null, new LogOrigin.Hint("", "elsewhere", 200, ""), "box", null));
        // a pid reused by another program
        assertEquals(200, LogOrigin.choose(running, Set.of(), null, new LogOrigin.Hint("Billing", "", 100, ""), "box",
                null).jvm().pid(), "by the program's name then");
        // the command line names the log's folder
        LogOrigin.Match m = LogOrigin.choose(running, Set.of(), null, none, "box", "/var/log/billing/billing-2026-10-21.log");
        assertEquals(200, m.jvm().pid());
        assertNull(LogOrigin.choose(running, Set.of(), null, none, "box", "/var/log/bill/x.log"));
        assertNull(LogOrigin.choose(running, Set.of(), null, none, "box", null));
        // a log from another host isn't matched here by its folder either
        assertNull(LogOrigin.choose(running, Set.of(), null, new LogOrigin.Hint("x.jar", "prod-7", 1, ""), "box",
                "/var/log/billing/billing.log"));
    }

    @Test
    void hosts() {
        assertTrue(LogOrigin.sameHost("prod-7", "PROD-7.acme.com"));
        assertFalse(LogOrigin.sameHost("prod-7", "prod-8"));
        assertFalse(LogOrigin.sameHost("10.0.0.1", "10.0.0.2"));
        assertTrue(LogOrigin.sameHost("10.0.0.1", "10.0.0.1"));
        assertFalse(LogOrigin.sameHost("", "x"));
    }

    @Test
    void findsTheJvmThatHasTheLogOpen(@TempDir Path dir) throws Exception {
        assumeFalse(LocalJvms.isWindows(), "Windows doesn't say who has a file open");
        Path log = dir.resolve("fixture.log");
        Process app = FixtureApp.start(false, log);
        try {
            List<JvmProcess> jvms = LocalJvms.list();
            Set<Long> open = LogOrigin.openedBy(log, jvms.stream().map(JvmProcess::pid).toList());
            assertEquals(Set.of(app.pid()), open);
            LogOrigin.Hint h = LogOrigin.scan(log, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(app.pid(), h.pid());
            assertEquals("FixtureApp", h.program());
            LogOrigin.Match m = LogOrigin.choose(jvms, Set.of(), null, h, LogOrigin.localHost(), log.toString());
            assertNotNull(m, "by the pid in the log");
            assertEquals(app.pid(), m.jvm().pid());
            assertTrue(Files.readString(log).contains("[io-8080-exec-10]"), "Spring Boot cuts long thread names");
        } finally {
            FixtureApp.stop(app);
        }
    }
}
