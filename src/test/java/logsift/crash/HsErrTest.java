package logsift.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import logsift.crash.CrashReport.Finding;
import logsift.crash.CrashReport.FrameKind;
import logsift.crash.CrashReport.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Java crash reports (hs_err_pid*.log): how the crash happened, the code to blame, which other
 * threads may have caused it, and what to try. Three files are real (made on macOS, then
 * trimmed and given neutral paths); the others follow what HotSpot writes on Linux and Windows.
 */
class HsErrTest {

    static String text(String name) throws IOException {
        try (InputStream in = HsErrTest.class.getResourceAsStream("/logsift/crash/" + name)) {
            assertNotNull(in, name);
            // Windows checkouts may turn line ends into \r\n: the tests edit the text by lines
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    static CrashReport report(String name) throws IOException {
        return HsErr.parse(text(name)).report();
    }

    /** The finding whose title starts with prefix. */
    static Finding finding(CrashReport r, String prefix) {
        for (Finding f : r.findings()) {
            if (f.title().startsWith(prefix)) {
                return f;
            }
        }
        fail("No finding starting with \"" + prefix + "\" in:\n"
                + r.findings().stream().map(Finding::title).collect(Collectors.joining("\n")));
        return null;
    }

    static boolean hasFinding(CrashReport r, String prefix) {
        return r.findings().stream().anyMatch(f -> f.title().startsWith(prefix));
    }

    static String fact(CrashReport r, String label) {
        return r.facts().stream().filter(f -> f.label().equals(label)).map(CrashReport.Fact::value).findFirst().orElse(null);
    }

    static CrashReport.Thread thread(CrashReport r, String name) {
        return r.threads().stream().filter(t -> name.equals(t.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no thread " + name));
    }

    static CrashReport.Module module(CrashReport r, String name) {
        return r.modules().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no module " + name));
    }

    // ---------------------------------------------------------------- real files (macOS)

    @Test
    void unsafeReadAtANullAddress() throws IOException {
        CrashReport r = report("hs_err_mac_unsafe.log");
        assertEquals(CrashReport.Kind.JAVA_CRASH, r.kind());
        assertEquals("SIGSEGV in the JVM: Unsafe_GetLong, at a null address", r.title());
        assertTrue(r.where().endsWith("called from Crash.boom()"), r.where());
        Finding f = finding(r, "Memory read or written through Unsafe at a bad address");
        assertEquals(Severity.ERROR, f.severity());
        assertTrue(f.detail().contains("near zero") && f.detail().contains("Called from Crash.boom()"), f.detail());
        assertTrue(r.summary().contains("0x0000000000000008, a null pointer"), r.summary());
        // the JVM's start-up lookups (NoSuchMethodError in linkResolver) are no clue
        assertFalse(hasFinding(r, "Just before"));
        assertFalse(hasFinding(r, "Shortly before"));
        assertTrue(hasFinding(r, "It crashed while starting"));
        assertTrue(hasFinding(r, "No core dump was written"));

        CrashReport.Thread t = r.threads().get(0);
        assertTrue(t.crashed());
        assertEquals("main", t.name());
        assertEquals("10243", t.id());
        assertEquals("crashed — in the JVM", t.state());
        assertTrue(t.frames().get(0).text().startsWith("JVM  [libjvm.dylib+0x7c9214]  Unsafe_GetLong"));
        assertEquals(FrameKind.SYSTEM, t.frames().get(0).kind());
        CrashReport.Frame lambda = t.frames().get(5);
        assertEquals("java.lang.invoke.LambdaForm$MH.invoke(Object, Object, Object) +52  (interpreted)", lambda.text());
        assertEquals(FrameKind.JAVA, lambda.kind(), "the JVM's generated classes aren't the program's");
        CrashReport.Frame boom = t.frames().get(10);
        assertEquals("Crash.boom() +55  (interpreted)", boom.text());
        assertEquals(FrameKind.OWN, boom.kind());

        assertEquals("blocked or waiting", thread(r, "worker-0").state());
        assertEquals("a garbage collector thread", thread(r, "G1 Main Marker").state());
        assertEquals(1, r.threads().stream().filter(CrashReport.Thread::crashed).count());
        assertEquals("G1", fact(r, "Garbage collector"));
        assertEquals("6912", fact(r, "Process id"));
        assertEquals("JDK", module(r, "libjvm.dylib").symbols());
        assertEquals("system", module(r, "libsystem_pthread.dylib").symbols());
    }

    @Test
    void jniLibraryPassesABadPointerToMemmove() throws IOException {
        CrashReport r = report("hs_err_mac_jni.log");
        assertEquals("SIGSEGV in native code: _platform_memmove in libsystem_platform.dylib, at a null address", r.title());
        assertEquals("at [libsystem_platform.dylib+0x3508]  _platform_memmove+0x1a8, called by Java_Jni_crunch in "
                + "libboom.dylib, from Java Jni.crunch(int)", r.where());
        Finding f = finding(r, "libboom.dylib passed a bad pointer or size to the system");
        assertTrue(f.detail().contains("on the thread \"image-worker-1\""), f.detail());
        assertTrue(f.tryThis().contains("-Xcheck:jni"));
        Finding foreign = finding(r, "1 native library not from the JDK or the system");
        assertTrue(foreign.detail().contains("libboom.dylib"));

        CrashReport.Thread t = r.threads().get(0);
        assertEquals("image-worker-1", t.name());
        assertEquals("crashed — running native code", t.state());
        assertEquals(FrameKind.SYSTEM, t.frames().get(0).kind(), "the C library");
        assertEquals(FrameKind.OWN, t.frames().get(1).kind(), "libboom");
        assertEquals("Jni.crunch(int) +0  (interpreted)", t.frames().get(2).text());
        assertEquals("Jni$$Lambda.run() +0  (interpreted)", t.frames().get(4).text(), "hidden class address dropped");
        assertEquals("the crash is here", module(r, "libsystem_platform.dylib").note());
        assertEquals("not the JDK's or the system's", module(r, "libboom.dylib").note());
    }

    @Test
    void garbageCollectorCrashAfterTheHeapWasDamaged() throws IOException {
        CrashReport r = report("hs_err_mac_gc.log");
        assertEquals("SIGSEGV in the garbage collector: G1AdjustClosure::do_oop_work<narrowOop>", r.title());
        Finding f = finding(r, "The garbage collector crashed");
        assertTrue(f.detail().contains("damaged earlier") && f.detail().contains("isn't the one that crashed"));
        assertTrue(f.tryThis().contains("-XX:+VerifyBeforeGC"));
        // which thread set it off: the GC operation and who holds the Heap_lock
        Finding during = finding(r, "It crashed during G1CollectFull (System.gc())");
        assertTrue(during.detail().contains("asked for by \"<un-named>\""), during.detail());
        assertTrue(finding(r, "JVM locks held at the crash").title().contains("Heap_lock (held by \"<un-named>\")"));
        assertTrue(hasFinding(r, "The JVM was at safepoint"));

        CrashReport.Thread t = r.threads().get(0);
        assertEquals("GC Thread#5", t.name());
        assertEquals("42755", t.id());
        assertEquals("crashed — a garbage collector thread", t.state());
        assertTrue(t.frames().stream().allMatch(x -> x.kind() == FrameKind.SYSTEM));
    }

    // ---------------------------------------------------------------- Linux

    @Test
    void linuxJniLibraryInAContainer() throws IOException {
        CrashReport r = report("hs_err_linux_rocksdb.log");
        assertEquals("SIGSEGV in native code: rocksdb::DBImpl::GetImpl in librocksdbjni4182741372468362451.so, "
                + "at a null address", r.title());
        assertTrue(r.where().endsWith("called from org.rocksdb.RocksDB.get(long, byte[], int, int), used by "
                + "com.acme.orders.store.OrderStore.find(String)"), r.where());
        Finding f = finding(r, "A native library crashed: librocksdbjni4182741372468362451.so");
        assertTrue(f.detail().contains("/tmp/librocksdbjni4182741372468362451.so"));
        assertTrue(f.detail().contains("used by com.acme.orders.store.OrderStore.find(String)"), f.detail());
        assertTrue(f.detail().contains("Thread: \"order-store-reader-3\""));
        // other threads in native code at once: the library may not be thread-safe
        Finding others = finding(r, "2 other threads were in native code at the same time");
        assertTrue(others.title().contains("order-store-compactor") && others.title().contains("XNIO-1 I/O-1"));
        assertFalse(others.title().contains("order-store-reader-3"), "not the crashed thread itself");
        assertTrue(hasFinding(r, "Agents in the JVM: apm-agent.jar"));
        assertTrue(hasFinding(r, "This JDK was built "));
        Finding core = finding(r, "A core dump should be at /app/core.1");
        assertTrue(core.detail().contains("apport"));
        assertFalse(hasFinding(r, "It crashed while starting"), "a day after start");
        assertFalse(hasFinding(r, "Loaded just before the crash"), "loaded at 12 s, crashed a day later");

        assertEquals("Ubuntu 22.04.3 LTS", fact(r, "System"));
        assertEquals("6.0 GB", fact(r, "Container memory limit"));
        assertEquals("24.0 hours", fact(r, "Running for"));

        CrashReport.Thread t = r.threads().get(0);
        assertEquals("87", t.id());
        assertEquals("org.rocksdb.RocksDB.get(long, byte[], int, int)  (compiled)", t.frames().get(4).text());
        assertEquals("com.acme.orders.store.OrderStore.find(String)  (compiled by C2)", t.frames().get(5).text());
        assertEquals(FrameKind.OWN, t.frames().get(0).kind());
        assertEquals(FrameKind.SYSTEM, t.frames().get(t.frames().size() - 1).kind(), "libc");
        // JDK 17 "Other Threads": named and unnamed
        assertEquals("the JVM's own thread (runs safepoint operations)", thread(r, "VM Thread").state());
        assertEquals("the JVM's periodic task thread", thread(r, "WatcherThread").state());
        assertEquals("24", thread(r, "WatcherThread").id());
        assertEquals("JDK", module(r, "libjvm.so").symbols());
        assertEquals("system", module(r, "libc.so.6").symbols());
        assertEquals(1, r.modules().stream().filter(m -> m.name().startsWith("librocksdbjni")).count(),
                "maps lines of one file make one module");
    }

    @Test
    void nativeOutOfMemoryInAContainer() throws IOException {
        CrashReport r = report("hs_err_linux_oom_container.log");
        assertTrue(r.title().startsWith("The JVM ran out of native memory: Native memory allocation (mmap) failed to map "
                + "16777216 bytes"), r.title());
        assertNull(r.where(), "no problematic frame");
        Finding f = finding(r, "Out of native memory");
        assertTrue(f.detail().contains("the container's memory limit is 2.0 GB and the heap may grow to 2.0 GB"), f.detail());
        assertTrue(f.tryThis().contains("MaxRAMPercentage") && f.tryThis().contains("75%"), f.tryThis());
        assertTrue(hasFinding(r, "The heap may grow to 2.0 GB on a machine with 2.0 GB"));
        assertTrue(hasFinding(r, "It failed while growing the Java heap"));
        assertTrue(hasFinding(r, "The heap was full"));
        // who asked: the VM operation names the thread, not its address
        Finding safepoint = finding(r, "The JVM was at safepoint");
        assertTrue(safepoint.detail().contains("requested by thread \"invoice-batch-1\""), safepoint.detail());
        assertEquals(1, r.findings().stream().filter(x -> x.title().contains("Java thread")).count(),
                "the thread count once");

        CrashReport.Thread t = r.threads().get(0);
        assertEquals("VM Thread", t.name());
        assertEquals("33", t.id(), "from [id=33]");
        assertEquals(1, r.threads().stream().filter(x -> "VM Thread".equals(x.name())).count(), "not listed twice");
    }

    @Test
    void c2CompilerCrashSaysWhichMethodToExclude() throws IOException {
        CrashReport r = report("hs_err_linux_c2.log");
        assertEquals("SIGSEGV in the JIT compiler: PhaseIdealLoop::build_loop_late_post_work, at a null address", r.title());
        Finding f = finding(r, "The JIT compiler crashed compiling com.acme.pricing.RuleEngine.evaluate()");
        assertTrue(f.detail().contains("C2 CompilerThread0"));
        assertTrue(f.tryThis().contains("-XX:CompileCommand=exclude,com.acme.pricing.RuleEngine::evaluate"), f.tryThis());
        assertEquals("Parallel", fact(r, "Garbage collector"));
        assertEquals("Red Hat Enterprise Linux release 9.2 (Plow)", fact(r, "System"));
        assertTrue(hasFinding(r, "4 Java threads and 1 JVM thread"));
        assertEquals("crashed — running native code", r.threads().get(0).state());
        assertEquals("running Java code", thread(r, "pricing-worker-1").state());
        assertTrue(r.sections().stream().anyMatch(s -> s.title().equals("Being compiled")
                && s.text().contains("RuleEngine::evaluate")));
    }

    @Test
    void internalErrorFoundByTheGarbageCollector() throws IOException {
        CrashReport r = report("hs_err_internal_guarantee.log");
        assertEquals("The JVM stopped on an internal error: guarantee(cl.n_failures() == 0) failed: 3 failures in "
                + "region verification", r.title());
        Finding f = finding(r, "A check in the JVM failed: g1RemSet.cpp:1123");
        assertTrue(f.detail().contains("The garbage collector (GC Thread#3) found it"), f.detail());
        assertTrue(f.tryThis().contains("bug tracker"));
        // who may have damaged the heap: threads in native code, their libraries, who asked for the GC
        Finding nat = finding(r, "2 other threads were running native code at the time");
        assertEquals(Severity.WARN, nat.severity());
        assertTrue(nat.detail().contains("epollEventLoopGroup-2-1") && nat.detail().contains("lz4-compressor-1"));
        Finding libs = finding(r, "2 native libraries not from the JDK or the system");
        assertEquals(Severity.WARN, libs.severity());
        assertTrue(libs.detail().contains("liblz4-java-48211.so"));
        Finding during = finding(r, "It crashed during G1CollectForAllocation");
        assertTrue(during.detail().contains("\"lz4-compressor-1\""), during.detail());
        assertEquals("CentOS Linux release 7.9.2009 (Core)", fact(r, "System"));
        assertEquals("31402", r.threads().get(0).id());
        assertEquals("crashed — a garbage collector thread", r.threads().get(0).state());
        assertTrue(finding(r, "A core dump should be at /var/lib/stream/core.31337").tryThis() == null);
    }

    @Test
    void sigbusInAMappedFile() throws IOException {
        CrashReport r = report("hs_err_linux_sigbus_mapped.log");
        assertEquals("SIGBUS in the JVM: StubRoutines::jlong_disjoint_arraycopy", r.title());
        assertEquals("at StubRoutines::jlong_disjoint_arraycopy, called from com.acme.index.SegmentReader.readBlock(long)",
                r.where());
        Finding f = finding(r, "A mapped file changed under the program: /data/index/segment_0042.dat");
        assertTrue(f.detail().startsWith("The address 0x00007f7c1e000000 is in /data/index/segment_0042.dat"), f.detail());
        assertTrue(f.tryThis().contains("rename"));
        assertTrue(r.summary().contains("memory-mapped file"));
        assertEquals("Debian GNU/Linux 12 (bookworm)", fact(r, "System"));
        assertEquals("6.1 days", fact(r, "Running for"));
        assertTrue(r.modules().stream().noneMatch(m -> m.name().endsWith(".dat")), "a data file isn't a library");
    }

    @Test
    void crashInCompiledJavaCode() throws IOException {
        CrashReport r = report("hs_err_linux_compiled_java.log");
        assertEquals("SIGSEGV in compiled Java code", r.title());
        assertEquals("at com.acme.feed.Decoder.decodeVarint(ByteBuffer)  (compiled by C2)", r.where());
        Finding f = finding(r, "The crash is in JIT-compiled Java code: com.acme.feed.Decoder.decodeVarint(ByteBuffer)");
        assertTrue(f.tryThis().contains("-XX:CompileCommand=exclude,com.acme.feed.Decoder::decodeVarint"), f.tryThis());
        Finding nat = finding(r, "1 other thread was running native code at the time");
        assertTrue(nat.detail().contains("shm-reader"));
        assertTrue(hasFinding(r, "Methods being compiled at the crash"));
        assertTrue(hasFinding(r, "Agents in the JVM: libasyncProfiler.so"), "from the JVMTI agents section");
        // an OutOfMemoryError on the crashed thread just before, reported once
        Finding oom = finding(r, "Shortly before: OutOfMemoryError");
        assertTrue(oom.detail().contains("Java heap space"));
        assertFalse(hasFinding(r, "Just before"), "the out-of-memory rule already says it");
        assertTrue(hasFinding(r, "The heap was full"));
        CrashReport.Thread t = r.threads().get(0);
        assertEquals(FrameKind.OWN, t.frames().get(0).kind());
        assertEquals(FrameKind.JAVA, t.frames().get(3).kind(), "java.lang.Thread.run");
    }

    // ---------------------------------------------------------------- Windows

    @Test
    void windowsAccessViolationInALibraryLoadedJustBefore() throws IOException {
        CrashReport r = report("hs_err_windows_sqlite.log");
        assertEquals("EXCEPTION_ACCESS_VIOLATION in native code: sqlite-3.42.0.0-6c4a7c25-sqlitejdbc.dll, at a null address",
                r.title());
        assertEquals("at [sqlite-3.42.0.0-6c4a7c25-sqlitejdbc.dll+0x3c4d], called from org.sqlite.core.NativeDB.step(long), "
                + "used by com.acme.inventory.SyncJob.flush(List)", r.where());
        assertTrue(r.summary().startsWith("The program read from memory"), r.summary());
        Finding f = finding(r, "A native library crashed: sqlite-3.42.0.0-6c4a7c25-sqlitejdbc.dll");
        assertTrue(f.detail().contains("C:\\Users\\svc\\AppData\\Local\\Temp\\sqlite-3.42.0.0-6c4a7c25-sqlitejdbc.dll"));
        assertTrue(hasFinding(r, "Loaded just before the crash: sqlite-3.42.0.0-6c4a7c25-sqlitejdbc.dll"));
        assertTrue(hasFinding(r, "1 other thread was in native code at the same time: inventory-report"));
        Finding old = finding(r, "This JDK was built ");
        assertTrue(old.title().endsWith("(2022-01-20)"), old.title());
        assertTrue(old.tryThis().contains("Java 17"));
        Finding core = finding(r, "A core dump should be at C:\\apps\\inventory\\hs_err_pid1234.mdmp");
        assertTrue(core.detail().contains("open the .mdmp in Logsift"));
        assertEquals("Open hs_err_pid1234.mdmp in Logsift.", core.tryThis());

        // the native walk stopped early: the Java frames follow it
        CrashReport.Thread t = r.threads().get(0);
        assertEquals("inventory-sync", t.name());
        assertEquals(FrameKind.OWN, t.frames().get(0).kind());
        assertEquals("org.sqlite.core.NativeDB.step(long) +0  (interpreted)", t.frames().get(3).text());
        assertEquals("com.acme.inventory.SyncJob.flush(List) +57  (interpreted)", t.frames().get(5).text());
        assertEquals("Windows Server 2019 , 64 bit Build 17763 (10.0.17763.4737)", fact(r, "System"));
        assertEquals("JDK", module(r, "jvm.dll").symbols());
        assertEquals("system", module(r, "ntdll.dll").symbols());
        assertEquals("system", module(r, "KERNEL32.DLL").symbols());
        assertEquals("the crash is here", module(r, "sqlite-3.42.0.0-6c4a7c25-sqlitejdbc.dll").note());
        assertEquals("the JVM's periodic task thread", thread(r, "WatcherThread").state());
    }

    @Test
    void windowsMemoryInMegabytes() throws IOException {
        HsErr h = HsErr.parse(text("hs_err_windows_sqlite.log"));
        assertEquals(32767L * 1024, h.physicalK);
        assertEquals(9213L * 1024, h.physicalFreeK);
        assertEquals(3600.104512, h.elapsedSeconds, 1e-6);
    }

    @Test
    void theWindowsDumpNextToTheReport(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("hs_err_pid1234.log");
        Files.writeString(log, text("hs_err_windows_sqlite.log"));
        HsErr h = HsErr.read(log);
        assertNull(h.coreDumpFile(log), "not written yet");
        Path mdmp = Files.writeString(dir.resolve("hs_err_pid1234.mdmp"), "MDMP");
        assertEquals(mdmp, h.coreDumpFile(log));
        assertEquals("C:\\apps\\inventory\\hs_err_pid1234.mdmp", h.coreDumpPath());
    }

    // ---------------------------------------------------------------- JDK 8, agents

    @Test
    void jdk8VmThreadCrashInWorkForAnAgent() throws IOException {
        CrashReport r = report("hs_err_jdk8_vmthread_agent.log");
        assertEquals("SIGSEGV in the JVM: JvmtiEnvBase::get_stack_trace", r.title());
        Finding vm = finding(r, "The JVM's own thread crashed during GetAllStackTraces");
        assertTrue(vm.detail().contains("asked for by \"apm-sampler\""), vm.detail());
        Finding agent = finding(r, "The crash is in work done for an agent: libapmagent.so");
        assertEquals(Severity.ERROR, agent.severity());
        assertFalse(hasFinding(r, "Agents in the JVM"), "said once");
        assertTrue(finding(r, "The JVM was at safepoint").detail().contains("requested by thread \"apm-sampler\""));
        Finding old = finding(r, "This JDK was built 8 years ago");
        assertTrue(old.tryThis().contains("Java 8"), old.tryThis());
        assertTrue(hasFinding(r, "No core dump was written"), "\"Failed to write core dump\"");
        // JDK 8 writes the time at the end
        assertEquals("83 min", fact(r, "Running for"));
        assertEquals("Tue Mar  3 10:20:30 2020", fact(r, "Crashed at"));
        assertEquals("CentOS Linux release 7.5.1804 (Core)", fact(r, "System"));
        CrashReport.Thread t = r.threads().get(0);
        assertEquals("2995", t.id());
        assertEquals("VMThread", t.name(), "unnamed in JDK 8");
        assertEquals(1, r.threads().stream().filter(x -> "2995".equals(x.id())).count());
        assertEquals("JDK", module(r, "libjvm.so").symbols(), "jre/lib/amd64/server");
        assertEquals("not the JDK's or the system's", module(r, "libapmagent.so").note());
    }

    // ---------------------------------------------------------------- the crashed thread's last exception

    @Test
    void anExceptionOnTheCrashedThreadJustBefore() throws IOException {
        String t = text("hs_err_linux_rocksdb.log").replace("Internal exceptions (0 events):\nNo events",
                "Internal exceptions (3 events):\n"
                        + "Event: 86380.100 Thread 0x00007f3a58012340 Exception <a 'java/lang/IllegalStateException'"
                        + "{0x00000000f0a1b2c3}: too early> (0x00000000f0a1b2c3) \nthrown [src/hotspot/share/prims/jni.cpp, line 516]\n"
                        + "Event: 86405.552 Thread 0x00007f3a58013a10 Exception <a 'org/rocksdb/RocksDBException'"
                        + "{0x00000000f1a2b3c4}: Corruption: block checksum mismatch> (0x00000000f1a2b3c4) \n"
                        + "thrown [src/hotspot/share/prims/jni.cpp, line 516]\n"
                        + "Event: 86409.118 Thread 0x00007f3a58012340 Exception <a 'org/rocksdb/RocksDBException'"
                        + "{0x00000000f1a2b4d8}: Corruption: block checksum mismatch> (0x00000000f1a2b4d8) \n"
                        + "thrown [src/hotspot/share/prims/jni.cpp, line 516]");
        CrashReport r = HsErr.parse(t).report();
        Finding f = finding(r, "Just before, this thread got org.rocksdb.RocksDBException: Corruption: block checksum mismatch");
        assertEquals(Severity.WARN, f.severity());
        assertTrue(f.detail().contains("(3 s before the crash)"), f.detail());
        assertTrue(f.detail().contains("bad input (a damaged or truncated file)"), f.detail());
        assertTrue(f.detail().contains("also thrown on \"order-store-compactor\""), f.detail());
        assertTrue(f.tryThis().contains("retry with the same input"));
        assertEquals(1, r.findings().stream().filter(x -> x.title().startsWith("Just before")).count());
    }

    @Test
    void jpegDecoderCrashAfterABadFile() throws IOException {
        String t;
        try (InputStream in = HsErrTest.class.getResourceAsStream("/visual/hs_err_pid31337.log")) {
            assertNotNull(in);
            t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        CrashReport r = HsErr.parse(t).report();
        Finding f = finding(r, "Just before, this thread got java.io.IOException: Premature end of JPEG file");
        assertTrue(f.detail().contains("bad input (a damaged or truncated file) may have set off the crash in the native "
                + "code"), f.detail());
        assertTrue(f.detail().contains("also thrown on \"thumbnail-worker-1\""), f.detail());
        assertTrue(f.tryThis().contains("validate that input"));
        Finding core = finding(r, "A core dump should be at /srv/images/core.31337");
        assertEquals(Severity.INFO, core.severity());
        assertTrue(core.detail().contains("coredumpctl list 31337"), core.detail());
        assertTrue(core.detail().contains("every thread"));
        assertTrue(r.where().contains("called by tjDecompress2 in libturbojpeg.so.0"), r.where());
    }

    @Test
    void anOldExceptionOrOneOnAnotherThreadIsNoClue() throws IOException {
        String t = text("hs_err_linux_rocksdb.log").replace("Internal exceptions (0 events):\nNo events",
                "Internal exceptions (2 events):\n"
                        + "Event: 80000.100 Thread 0x00007f3a58012340 Exception <a 'java/io/IOException'"
                        + "{0x00000000f0a1b2c3}: an hour before> (0x00000000f0a1b2c3) \nthrown [src/hotspot/share/prims/jni.cpp, line 516]\n"
                        + "Event: 86410.552 Thread 0x00007f3a58013a10 Exception <a 'java/io/IOException'"
                        + "{0x00000000f1a2b3c4}: on another thread> (0x00000000f1a2b3c4) \nthrown [src/hotspot/share/prims/jni.cpp, line 516]");
        CrashReport r = HsErr.parse(t).report();
        assertFalse(hasFinding(r, "Just before"));
    }

    // ---------------------------------------------------------------- detection and small parts

    @Test
    void looksLikeACrashReport() throws IOException {
        for (String n : List.of("hs_err_mac_unsafe.log", "hs_err_mac_jni.log", "hs_err_mac_gc.log", "hs_err_linux_rocksdb.log",
                "hs_err_windows_sqlite.log", "hs_err_linux_oom_container.log", "hs_err_linux_c2.log",
                "hs_err_internal_guarantee.log", "hs_err_linux_sigbus_mapped.log", "hs_err_linux_compiled_java.log",
                "hs_err_jdk8_vmthread_agent.log")) {
            String head = text(n);
            assertTrue(HsErr.looksLike(head.substring(0, Math.min(head.length(), 4096))), n);
        }
        assertFalse(HsErr.looksLike("2026-10-29 10:00:00 INFO Starting\n2026-10-29 10:00:01 ERROR java.lang."
                + "NullPointerException\n\tat com.acme.App.main(App.java:12)\n"));
        assertFalse(HsErr.looksLike(""));
    }

    @Test
    void javaFrames() {
        assertEquals("com.acme.Order.total(int, String[], long[][])",
                HsErr.javaMethod("j  com.acme.Order.total(I[Ljava/lang/String;[[J)V+12"));
        assertEquals("com.acme.Feed.next()",
                HsErr.javaMethod("J 18890 c2 com.acme.Feed.next()Lcom/acme/Message; (240 bytes) @ 0x00007fa1b4c31a0c [0x0+0x1]"));
        assertEquals("Jni$$Lambda.run()", HsErr.javaMethod("j  Jni$$Lambda+0x0000007001000c28.run()V+0"));
        assertEquals("java.lang.Thread.run()", HsErr.javaMethod("j  java.lang.Thread.run()V+11 java.base@21.0.2"));
        assertNull(HsErr.javaMethod("V  [libjvm.so+0x833d75]  JavaCalls::call_helper(JavaValue*)+0x315"));
        assertEquals("com.acme", HsErr.javaClass("j  com.acme.Order.total()V+1").substring(0, 8));
        assertEquals("boolean, char, double[]", HsErr.params("ZC[D"));
        assertTrue(HsErr.isJdkClass("jdk.internal.misc.Unsafe"));
        assertTrue(HsErr.isJdkClass("sun.misc.Unsafe"));
        assertFalse(HsErr.isJdkClass("com.acme.Unsafe"));
    }

    @Test
    void nativeFramesAndLibraries() {
        assertEquals("libfoo.so", HsErr.frameLibrary("C  [libfoo.so+0x1234]  foo+0x10"));
        assertEquals("foo", HsErr.frameFunction("C  [libfoo.so+0x1234]  foo+0x10"));
        assertNull(HsErr.frameFunction("C  [sqlitejdbc.dll+0x3c4d]"));
        assertEquals("Java_Jni_crunch in libboom.dylib", HsErr.nativeName("[libboom.dylib+0x444]  Java_Jni_crunch+0x3c"));
        assertEquals("StubRoutines::jlong_disjoint_arraycopy", HsErr.stubName("v  ~StubRoutines::jlong_disjoint_arraycopy"));
        assertEquals("StubRoutines::call_stub", HsErr.stubName("v  ~StubRoutines::call_stub 0x0000000118f48154"));
        assertNull(HsErr.stubName("V  [libjvm.so+0x1]  f()+0x1"));
        // the three ways "Dynamic libraries" are written
        assertEquals("C:\\WINDOWS\\SYSTEM32\\ntdll.dll",
                HsErr.library("0x00007ffb3e8f0000 - 0x00007ffb3eae8000 \tC:\\WINDOWS\\SYSTEM32\\ntdll.dll"));
        assertEquals("/usr/lib/libz.1.dylib", HsErr.library("0x000000019812e000 \t/usr/lib/libz.1.dylib"));
        assertEquals("/usr/lib64/libc.so.6",
                HsErr.library("7f1cb7000000-7f1cb7028000 r--p 00000000 fd:00 1050212                    /usr/lib64/libc.so.6"));
        assertNull(HsErr.library("7f7c1e000000-7f7c3e000000 r--s 00000000 08:11 655361   /data/index/segment_0042.dat"),
                "a data file");
        assertTrue(HsErr.isSystemLibrary("C:\\Windows\\System32\\kernel32.dll"));
        assertTrue(HsErr.isSystemLibrary("/lib/x86_64-linux-gnu/libpthread.so.0"));
        assertFalse(HsErr.isSystemLibrary("/opt/app/lib/libfoo.so"));
        assertTrue(HsErr.gcFunction("void G1AdjustClosure::do_oop_work<narrowOop>(narrowOop*)"));
        assertFalse(HsErr.gcFunction("PhaseIdealLoop::build_loop_late_post_work(Node*, bool)"));
    }

    @Test
    void readsAFile(@TempDir Path dir) throws IOException {
        Path f = Files.writeString(dir.resolve("hs_err_pid4242.log"), text("hs_err_linux_c2.log").replace("\n", "\r\n"));
        CrashReport r = HsErr.read(f).report();
        assertEquals("SIGSEGV in the JIT compiler: PhaseIdealLoop::build_loop_late_post_work, at a null address", r.title(),
                "Windows line ends");
    }

    // ---------------------------------------------------------------- as text

    @Test
    void summaryText() throws IOException {
        CrashReport r = report("hs_err_mac_jni.log");
        String s = CrashText.summary(r, 4);
        assertTrue(s.startsWith("Java crash report: SIGSEGV in native code: _platform_memmove in libsystem_platform.dylib"), s);
        assertTrue(s.contains("\nAt [libsystem_platform.dylib+0x3508]"), s);
        assertTrue(s.contains("[ERROR] libboom.dylib passed a bad pointer or size to the system"));
        assertTrue(s.contains("\nCrashed thread 26883 \"image-worker-1\":\n"), s);
        assertTrue(s.contains("[libboom.dylib+0x444]  Java_Jni_crunch+0x3c  ← own code"), s);
        assertTrue(s.contains("more frames"), "cut at 4");
        assertTrue(s.contains("- Java: OpenJDK Runtime Environment Homebrew (21.0.11)"));
        assertTrue(s.contains("× blocked or waiting"), s);
    }

    @Test
    void threadsAndModulesText() throws IOException {
        CrashReport r = report("hs_err_internal_guarantee.log");
        String all = CrashText.threads(r, null, 5, 50);
        assertTrue(all.contains("Thread 31402 \"GC Thread#3\" — crashed — a garbage collector thread"), all);
        String nat = CrashText.threads(r, "native", 5, 50);
        assertTrue(nat.contains("epollEventLoopGroup-2-1") && nat.contains("lz4-compressor-1"), nat);
        assertFalse(nat.contains("kafka-producer-network-thread"), nat);
        String mods = CrashText.modules(r, "lz4", 10);
        assertTrue(mods.startsWith("1 module matching “lz4”:"), mods);
        assertTrue(mods.contains("liblz4-java-48211.so  (not the JDK's or the system's)"), mods);
    }
}
