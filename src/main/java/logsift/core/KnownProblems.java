package logsift.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Problems that are known to show up in logs in a recognizable way, each with what it usually
 * means and what to try: a database connection pool that ran out, a full thread pool, the JVM
 * out of memory, a worker killed for taking too long, a certificate the JVM doesn't trust.
 * Matched against the log's kinds of messages and its exceptions, so it costs nothing extra.
 */
public final class KnownProblems {
    private KnownProblems() {}

    /** A known problem: how to recognize it, what it means, what to try. */
    public record Signature(String id, boolean error, String title, Pattern pattern, String why, String tryThis) {}

    /** A known problem in the log: how often, where first and last, and a line that shows it. */
    public record Match(Signature signature, long count, long firstLine, long lastLine, String example) {}

    private static Signature sig(String id, boolean error, String title, String regex, String why, String tryThis) {
        return new Signature(id, error, title, Pattern.compile(regex), why, tryThis);
    }

    /** Numbers in kinds of messages are {@code <*>}: patterns use \S+ for them. */
    public static final List<Signature> ALL = List.of(
            // ---- Java: memory and the JVM
            sig("oom-heap", true, "The JVM ran out of heap memory",
                    "OutOfMemoryError: Java heap space|OutOfMemoryError: GC overhead limit exceeded",
                    "The heap was full and garbage collection could not free enough. Either the heap is too small for "
                            + "the load, or something keeps objects alive (a memory leak: a growing cache, list or map).",
                    "Compare heap histograms over time (JVM → Heap) to see which classes grow, or profile live "
                            + "objects. Add -XX:+HeapDumpOnOutOfMemoryError to get a heap dump next time. If nothing "
                            + "grows, raise -Xmx."),
            sig("oom-metaspace", true, "The JVM ran out of Metaspace",
                    "OutOfMemoryError: Metaspace|OutOfMemoryError: Compressed class space",
                    "Too many classes were loaded: often class loaders that are never released (redeploys, scripting, "
                            + "proxies or generated classes made again and again).",
                    "Look for classes generated over and over (a heap histogram by class loader). Raise "
                            + "-XX:MaxMetaspaceSize only if the number of classes is expected to be that high."),
            sig("oom-threads", true, "The JVM could not start more threads",
                    "OutOfMemoryError: unable to create (?:new )?native thread",
                    "The process hit the limit of threads (ulimit -u, the container's pids limit) or ran out of "
                            + "native memory for thread stacks. Often a thread leak: pools created and never shut down.",
                    "Take a thread dump (JVM → Attach) and look for many threads in pools with the same name. Check "
                            + "ulimit -u and the container's pids limit."),
            sig("oom-direct", true, "The JVM ran out of direct memory",
                    "OutOfMemoryError: Direct buffer memory|OutOfMemoryError: Cannot reserve \\S+ bytes of direct buffer",
                    "Off-heap buffers (NIO, Netty) were not released, or -XX:MaxDirectMemorySize is too small.",
                    "Profile native memory (JVM → Profile → Native memory). With Netty, enable its leak detection "
                            + "(-Dio.netty.leakDetection.level=paranoid) for a while."),
            sig("stack-overflow", true, "Endless recursion (StackOverflowError)", "StackOverflowError",
                    "A method called itself (or a cycle of methods) without end, often through equals/hashCode/"
                            + "toString of objects that point at each other, or JSON of a cyclic object.",
                    "Open the exception's stack (Exceptions tab): the frames that repeat are the cycle."),
            sig("jvm-crash", true, "The JVM crashed",
                    "A fatal error has been detected by the Java Runtime Environment|SIGSEGV \\(0xb\\)|EXCEPTION_ACCESS_VIOLATION",
                    "The JVM itself crashed, usually in native code (a JNI library, a driver) or a JVM bug.",
                    "Find the hs_err_pid file it wrote (in the working directory): its 'Problematic frame' names the "
                            + "library. Update that library or the JDK."),
            sig("gc-pauses", false, "Long garbage collection pauses",
                    "Pause Full|Full GC \\(|Thread starvation or clock leap detected|GC pause \\S+ exceeds",
                    "Full garbage collections stop the whole program. Many of them mean the heap is nearly full; "
                            + "'thread starvation or clock leap' means the program was stalled (GC or no CPU).",
                    "Check the heap after each GC: if it keeps growing, look for a leak (JVM → Heap). Otherwise "
                            + "give it more heap or CPU."),
            // ---- Java: pools and limits
            sig("db-pool", true, "The database connection pool ran out",
                    "Connection is not available, request timed out after|Unable to acquire JDBC Connection"
                            + "|Cannot get a connection, pool error|Timeout waiting for idle object"
                            + "|Could not get JDBC Connection|Timed out waiting for a free available connection",
                    "Every connection in the pool was in use for the whole wait. Slow queries hold connections "
                            + "longer, a code path may not return them (a leak), or the pool is small for the load.",
                    "Look at what ran just before: slow queries, a burst of requests. With HikariCP set "
                            + "leakDetectionThreshold to log where unreturned connections were taken. Compare "
                            + "maximumPoolSize with the number of threads that use the database."),
            sig("thread-pool", true, "A thread pool was full",
                    "All threads \\(\\S+\\) are currently busy|Maximum number of threads \\(\\S+\\) created"
                            + "|Thread pool is EXHAUSTED|RejectedExecutionException|rejected from java\\.util\\.concurrent",
                    "All threads of a pool were busy and new work was queued or rejected: something they wait on "
                            + "(a database, a remote call, a lock) got slow, or the load is higher than the pool.",
                    "Take a thread dump (JVM → Attach) while it happens: what the pool's threads are waiting on is "
                            + "the cause. Logsift's Threads tab points out full pools."),
            sig("open-files", true, "Too many open files", "Too many open files",
                    "The process reached its limit of open files and sockets: often connections or streams that "
                            + "are never closed, or a low limit.",
                    "Count open files over time (lsof -p PID | wc -l). If they grow, find what is not closed; "
                            + "otherwise raise ulimit -n."),
            sig("disk-full", true, "The disk is full", "No space left on device|There is not enough space on the disk",
                    "Writes failed because the disk (or a volume) was full, maybe filled by logs or temporary files.",
                    "df -h on the server; check log rotation and temporary folders."),
            sig("port-in-use", true, "The port was already in use",
                    "Address already in use|Port \\S+ was already in use|BindException",
                    "Another process (often an old instance that didn't stop) already listens on the port.",
                    "Find it with lsof -i :PORT (or netstat -ano on Windows) and stop it, or use another port."),
            sig("start-failed", true, "The application failed to start", "APPLICATION FAILED TO START"
                            + "|Application run failed|Error starting ApplicationContext",
                    "Startup stopped with an error; the lines after say why (a missing setting, a bean that could "
                            + "not be created, a port in use).",
                    "Read the 'Description' and 'Action' lines Spring Boot writes right after it."),
            // ---- Java: network and databases
            sig("tls-trust", true, "A certificate is not trusted",
                    "PKIX path building failed|unable to find valid certification path|SSLHandshakeException",
                    "The server's TLS certificate (or the company proxy's) is not in the JVM's trust store.",
                    "Import the certificate into the trust store the JVM uses (keytool -importcert), or point "
                            + "javax.net.ssl.trustStore at one that has it."),
            sig("dns", true, "A host name could not be resolved", "UnknownHostException|Name or service not known"
                            + "|nodename nor servname provided|Temporary failure in name resolution",
                    "A host name in the configuration is wrong, or DNS was not reachable from the server.",
                    "Check the host name in the settings; try nslookup HOST on the server."),
            sig("refused", false, "Connections were refused", "Connection refused",
                    "Nothing listened at the address: the service was down or restarting, or the port or host is wrong.",
                    "Check whether the service was up at that time (its own log); compare the address with its settings."),
            sig("timeouts", false, "Calls to another service timed out",
                    "SocketTimeoutException|Read timed out|connect timed out|ReadTimeoutException|TimeoutException"
                            + "|context deadline exceeded|DEADLINE_EXCEEDED",
                    "Another service (or the database) answered too slowly or not at all.",
                    "Look at that service's log at the same time (merge the logs by time); a timeout shorter than "
                            + "its normal answer time also does it."),
            sig("db-deadlock", true, "Database deadlocks",
                    "Deadlock found when trying to get lock|deadlock detected|was deadlocked on lock resources",
                    "Two transactions waited for each other's rows; the database stopped one.",
                    "Make transactions touch rows in the same order, keep them short, and retry the one stopped."),
            sig("db-lock-wait", false, "Waits for database locks timed out",
                    "Lock wait timeout exceeded|canceling statement due to lock timeout",
                    "A transaction held rows for long and others gave up waiting.",
                    "Find long transactions (a slow job, a transaction left open across a remote call)."),
            sig("db-clients", true, "The database refused more connections",
                    "too many clients already|remaining connection slots are reserved|Too many connections",
                    "The database's own connection limit was reached: too many pools or instances for its "
                            + "max_connections.",
                    "Add up maximumPoolSize over every instance and compare with the database's max_connections."),
            sig("kafka-rebalance", false, "Kafka consumers were thrown out of their group",
                    "CommitFailedException|consumer poll timeout has expired|max\\.poll\\.interval\\.ms"
                            + "|Offset commit failed|Attempt to heartbeat failed since group is rebalancing",
                    "A consumer took longer than max.poll.interval.ms between polls, so the group rebalanced and "
                            + "its partitions moved: messages get processed twice and lag grows.",
                    "Process each batch faster, poll fewer records (max.poll.records), or raise max.poll.interval.ms."),
            sig("rate-limited", false, "Calls were rate limited", "429 Too Many Requests|\\b429\\b.*(?:rate|limit)"
                            + "|Rate limit exceeded|TooManyRequests",
                    "Another service refused calls because too many came too fast.",
                    "Slow down or batch the calls, and retry with backoff."),
            sig("container-killed", true, "The process was killed (out of memory?)",
                    "OOMKilled|exit code 137|Killed process \\S+|signal 9|SIGKILL",
                    "The operating system or the container runtime killed the process, most often for using more "
                            + "memory than its limit. The JVM's heap plus everything off heap must fit the limit.",
                    "Compare the container's memory limit with -Xmx plus metaspace, threads and direct memory "
                            + "(JVM → Profile → Native memory); or use -XX:MaxRAMPercentage."),
            // ---- Python
            sig("gunicorn-timeout", true, "Web workers were killed for taking too long",
                    "WORKER TIMEOUT|Worker \\(pid:\\S+\\) was sent SIGKILL|Worker exiting \\(pid",
                    "Gunicorn killed workers that did not answer within its timeout (30 s by default), and booted "
                            + "new ones. The request they were handling was lost.",
                    "Find what those requests waited on (a slow query, a remote call). Raise --timeout only for "
                            + "work that is meant to be slow; use async workers or a task queue for it."),
            sig("asyncio-lost", false, "Errors in asyncio tasks were never handled",
                    "Task exception was never retrieved|Task was destroyed but it is pending",
                    "A background task failed (or was dropped) and nobody awaited it, so the error was only noticed "
                            + "when it was cleaned up, often much later.",
                    "Keep a reference to every task and await it, or add a done callback that logs its exception."),
            sig("celery-retry", false, "Background tasks were retried",
                    "Retry in \\S+s|raised unexpected:|WorkerLostError|Task \\S+ retry",
                    "Celery tasks failed and were retried; WorkerLostError means a worker process died (often "
                            + "killed for memory).",
                    "Look at the first failure of a task (its exception); for lost workers, check memory limits "
                            + "and max-tasks-per-child."),
            sig("py-memory", true, "Python ran out of memory", "(?<!OutOf)MemoryError",
                    "An allocation failed: a very large list, string or DataFrame, or memory that keeps growing.",
                    "Process data in chunks; to find growth, compare tracemalloc snapshots."),
            sig("py-recursion", true, "Endless recursion (RecursionError)", "RecursionError: maximum recursion depth",
                    "A function called itself without end.",
                    "The frames that repeat in the traceback are the cycle."),
            sig("py-db", true, "The database could not be reached",
                    "OperationalError.*(?:could not connect|Connection refused|server closed the connection"
                            + "|Lost connection|timeout expired)",
                    "The database was down, unreachable, or closed idle connections.",
                    "Check the database at that time; enable pool_pre_ping (SQLAlchemy) for dropped idle connections."));

    /** The known problems in a log's messages and exceptions, most important first. */
    public static List<Match> find(List<MessageStats.Template> templates, Exceptions.Result exceptions) {
        Map<Signature, long[]> found = new LinkedHashMap<>(); // {count, first, last}
        Map<Signature, String> example = new LinkedHashMap<>();
        for (MessageStats.Template t : templates == null ? List.<MessageStats.Template>of() : templates) {
            for (Signature s : ALL) {
                if (s.pattern.matcher(t.text()).find()) {
                    add(found, s, t.count(), t.firstLine(), t.lastLine());
                    example.putIfAbsent(s, Insights.display(t.text()));
                }
            }
        }
        if (exceptions != null) {
            java.util.Set<Signature> inMessages = new java.util.HashSet<>(found.keySet());
            for (Exceptions.Group g : exceptions.groups()) {
                java.util.Set<Signature> seen = new java.util.HashSet<>(); // one group counts once per problem
                for (Exceptions.Thrown th : g.sample().chain()) {
                    String text = th.type() + ": " + th.message();
                    for (Signature s : ALL) {
                        if (!inMessages.contains(s) && !seen.contains(s) && s.pattern.matcher(text).find()) {
                            seen.add(s);
                            add(found, s, g.count(), g.firstLine(), g.lastLine());
                            example.putIfAbsent(s, text.length() > 200 ? text.substring(0, 197) + "…" : text);
                        }
                    }
                }
            }
        }
        List<Match> out = new ArrayList<>();
        for (var e : found.entrySet()) {
            long[] v = e.getValue();
            out.add(new Match(e.getKey(), v[0], v[1], v[2], example.get(e.getKey())));
        }
        out.sort(Comparator.comparing((Match m) -> !m.signature.error).thenComparing(Comparator.comparingLong(Match::count)
                .reversed()));
        return out;
    }

    /** Adds a message kind's or an exception group's lines to a problem. */
    private static void add(Map<Signature, long[]> found, Signature s, long count, long first, long last) {
        long[] v = found.get(s);
        if (v == null) {
            found.put(s, new long[] {count, first, last});
        } else {
            v[0] += count;
            v[1] = Math.min(v[1], first);
            v[2] = Math.max(v[2], last);
        }
    }
}
