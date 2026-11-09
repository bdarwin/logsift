package logsift.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The folder of test data that {@code ./gradlew testData} writes. */
class TestDataTest {
    @TempDir
    Path dir;

    @Test
    void writesEachKindOnceAndKeepsItAfter() throws Exception {
        List<String> made = TestData.write(dir, List.of(1L), true, false);
        for (String f : List.of("logs/orders-service.log", "logs/big-1mb.log", "logs/windows-crlf.log",
                "logs/windows-utf16.log", "logs/windows-1252.log", "logs/app.json.log", "logs/access.log",
                "logs/python-worker.log", "logs/python-api.log", "logs/long-lines.log",
                "logs/dotnet/shop-api-serilog.log", "logs/dotnet/orders-nlog.log", "logs/dotnet/billing-log4net.log",
                "logs/dotnet/aspnet-console.log", "logs/rotated/app.log", "logs/rotated/app.log.1",
                "logs/rotated/app.log.2.gz", "csv/sales.csv", "csv/orders-monday.csv", "csv/orders-tuesday.csv",
                "csv/wide-2000.csv", "csv/wide-6000-5000-comma-crlf.csv", "csv/big-1m-rows.csv",
                "crashes/hs_err_linux_c2.log", "crashes/hs_err_pid31337.log", "crashes/orders-service.dmp",
                "gc/j25-g1.log", "gc/j8-cms.log", "threads/jstack-21.txt", "threads/virt-25.json")) {
            assertTrue(made.contains(f), f + " in " + made);
            assertTrue(Files.size(dir.resolve(f)) > 0, f);
        }
        assertTrue(Files.size(dir.resolve("logs/big-1mb.log")) >= 1 << 20);
        // GC logs come out unzipped; the rotated old one stays zipped
        assertTrue(Files.readString(dir.resolve("gc/j25-g1.log")).contains("Using G1"));
        assertTrue(Files.notExists(dir.resolve("logs/rotated/app.log.2.tmp")));
        // quick: nothing that needs a JVM of its own
        assertTrue(Files.notExists(dir.resolve("heap")));
        assertTrue(Files.notExists(dir.resolve("gc/live-g1-leaking.log")));
        assertEquals(1_000_001, Files.lines(dir.resolve("csv/big-1m-rows.csv")).count());
        // each .NET log is read in its own layout
        for (String[] f : new String[][] {{"shop-api-serilog.log", "Serilog"}, {"orders-nlog.log", "NLog"},
            {"billing-log4net.log", "log4net"}, {"aspnet-console.log", "ASP.NET Core"}}) {
            List<String> lines = Files.readAllLines(dir.resolve("logs/dotnet/" + f[0]));
            var d = logsift.core.Formats.detect(lines.subList(0, 200), List.of(), f[0], null, 2026);
            assertTrue(d.columns() != null && d.columns().name().startsWith(f[1]), f[0] + ": " + d.columns());
            assertTrue(d.entries() != null, f[0]);
        }
        String readme = Files.readString(dir.resolve("README.md"));
        assertTrue(readme.contains("big-1mb.log") && readme.contains("./gradlew testJvms"), readme);

        assertEquals(List.of(), TestData.write(dir, List.of(1L), true, false), "a second run keeps what's there");
        assertTrue(TestData.write(dir, List.of(1L), true, true).contains("csv/sales.csv"), "fresh writes again");
    }
}
