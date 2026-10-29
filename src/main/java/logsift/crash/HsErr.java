package logsift.crash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import logsift.crash.CrashReport.Fact;
import logsift.crash.CrashReport.Finding;
import logsift.crash.CrashReport.FrameKind;
import logsift.crash.CrashReport.Severity;

/**
 * Explains a Java crash report (hs_err_pid*.log, written by HotSpot JVMs, Java 6 to 25, on
 * Windows, Linux and macOS): how the crash happened (in native code, in the JVM, in compiled
 * Java code, in the garbage collector or the JIT compiler, out of memory), the code to blame,
 * which other threads may have caused it, and what to try.
 */
public final class HsErr {

    private final String text;
    private final List<String> lines;

    // the header
    String signal = "";       // "SIGSEGV (0xb)", "EXCEPTION_ACCESS_VIOLATION (0xc0000005)"
    String pc = "";
    String pid = "";
    String tid = "";
    String internalError;     // "g1ConcurrentMark.cpp:123"
    String errorMessage;      // "guarantee(x) failed: …", "ShouldNotReachHere()"
    boolean outOfMemory;
    String oomDetail;         // "Native memory allocation (mmap) failed to map 12288 bytes for …"
    String jreVersion = "";
    String vmVersion = "";
    String problematic;       // "C  [libfoo.so+0x1234]  foo+0x10"
    String coreDump = "";

    // the summary
    String commandLine = "";
    String host = "";
    String time = "";
    double elapsedSeconds = -1;

    // the current thread
    String currentLine = "";  // "JavaThread "main" [_thread_in_vm, id=…]"
    String currentKind = "";  // JavaThread, VMThread, WorkerThread, ConcurrentGCThread, …
    String currentName = "";
    String currentState = "";
    String currentId = "";
    String currentAddress = ""; // "0x00007f3a50012800", as other parts of the file name threads
    final List<String> nativeFrames = new ArrayList<>();
    final List<String> javaFrames = new ArrayList<>();
    String siginfo = "";
    String compileTask;       // "C2: 1234 567 4 com.foo.Bar::baz (123 bytes)"
    String vmOperation;       // "G1CollectForAllocation, mode: safepoint, requested by thread 0x…"

    // the process
    record JThread(String address, String kind, String name, String state, String id, boolean daemon, boolean current) {}

    final List<JThread> threads = new ArrayList<>();
    String vmState = "";
    final List<String> ownedMutexes = new ArrayList<>();
    final List<String> activeCompiles = new ArrayList<>();
    String heapLine = "";
    long heapTotalK = -1;
    long heapUsedK = -1;
    String metaspaceLine = "";
    final Map<String, List<String>> events = new LinkedHashMap<>();
    final List<String> libraries = new ArrayList<>();
    /** Mapped files from Linux "maps" lines: start, end and path. */
    record Mapping(long start, long end, String path) {}

    final List<Mapping> mappings = new ArrayList<>();
    String jvmArgs = "";
    String javaCommand = "";
    String classPath = "";
    final Map<String, String> flags = new LinkedHashMap<>();
    final List<String> environment = new ArrayList<>();
    final List<String> jvmtiAgents = new ArrayList<>(); // "JVMTI agents:" (JDK 21 and later)

    // the system
    String os = "";
    String uname = "";
    String cpu = "";
    String memory = "";
    long physicalK = -1;
    long physicalFreeK = -1;
    long swapK = -1;
    long swapFreeK = -1;
    String rlimit = "";
    long containerLimitK = -1;
    String vmInfo = "";
    LocalDate builtOn;

    private HsErr(String text) {
        this.text = text;
        this.lines = text.lines().toList();
    }

    /** Whether the start of a file looks like a Java crash report. */
    public static boolean looksLike(String head) {
        return head.contains("A fatal error has been detected by the Java Runtime Environment")
                || head.contains("There is insufficient memory for the Java Runtime Environment to continue");
    }

    public static HsErr read(Path file) throws IOException {
        long size = Files.size(file);
        byte[] b;
        try (var in = Files.newInputStream(file)) {
            b = in.readNBytes((int) Math.min(size, 64L << 20));
        }
        return parse(new String(b, StandardCharsets.UTF_8));
    }

    public static HsErr parse(String text) {
        HsErr h = new HsErr(text.replace("\r\n", "\n"));
        h.parseAll();
        return h;
    }

    // ---------------------------------------------------------------- reading

    private static final Pattern SIGNAL = Pattern.compile("^#\\s+(\\S+ \\(0x[0-9a-fA-F]+\\)) at pc=(0x[0-9a-fA-F]+), pid=(\\d+), tid=(\\S+)");
    private static final Pattern INTERNAL = Pattern.compile("^#\\s+Internal Error \\((.*)\\), pid=(\\d+), tid=(\\S+)");
    private static final Pattern OOM_LINE = Pattern.compile("^#\\s+Out of Memory Error \\((.*)\\), pid=(\\d+), tid=(\\S+)");
    private static final Pattern THREAD_LINE = Pattern.compile(
            "^(=>)?\\s*(0x[0-9a-fA-F]+)\\s+(\\w+)\\s+(?:\"(.*)\"\\s*)?(daemon)?\\s*\\[(?:(_thread_\\w+), )?(?:id=(\\d+))?(.*)$");
    private static final Pattern ELAPSED = Pattern.compile("elapsed time: ([0-9.]+) seconds");
    private static final Pattern HEAP_TOTAL = Pattern.compile("total (\\d+)K, used (\\d+)K");
    private static final Pattern MEMORY = Pattern.compile("physical (\\d+)k\\((\\d+)k free\\)(?:, swap (\\d+)k\\((\\d+)k free\\))?");
    /** Windows: "system-wide physical 32767M (9213M free)". */
    private static final Pattern MEMORY_MB = Pattern.compile("physical (\\d+)M \\((\\d+)M free\\)");
    private static final Pattern BUILT = Pattern.compile("built on (?:\\w{3} )?(\\d{4}-\\d{2}-\\d{2}|\\w{3}\\s+\\d+ \\d{4})");

    private void parseAll() {
        int section = 0; // 0 header, 1 summary, 2 thread, 3 process, 4 system
        String block = null; // the named part being read in a section
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("---------------  S U M M A R Y")) {
                section = 1;
                block = null;
                continue;
            }
            if (l.startsWith("---------------  T H R E A D")) {
                section = 2;
                block = null;
                continue;
            }
            if (l.startsWith("---------------  P R O C E S S")) {
                section = 3;
                block = null;
                continue;
            }
            if (l.startsWith("---------------  S Y S T E M")) {
                section = 4;
                block = null;
                continue;
            }
            switch (section) {
                case 0 -> header(l, i);
                case 1 -> summary(l);
                case 2 -> block = thread(l, block);
                case 3 -> block = process(l, block);
                case 4 -> block = system(l, block);
                default -> { }
            }
        }
    }

    private void header(String l, int i) {
        Matcher m = SIGNAL.matcher(l);
        if (m.find()) {
            signal = m.group(1);
            pc = m.group(2);
            pid = m.group(3);
            tid = m.group(4);
            return;
        }
        m = INTERNAL.matcher(l);
        if (m.find()) {
            internalError = m.group(1);
            pid = m.group(2);
            tid = m.group(3);
            return;
        }
        m = OOM_LINE.matcher(l);
        if (m.find()) {
            outOfMemory = true;
            pid = m.group(2);
            tid = m.group(3);
            return;
        }
        String s = l.startsWith("#") ? l.substring(1).strip() : l.strip();
        if (s.startsWith("There is insufficient memory for the Java Runtime Environment")) {
            outOfMemory = true;
        } else if (s.startsWith("Native memory allocation") || s.startsWith("Java heap space")
                || s.startsWith("Out of swap space") || s.startsWith("Cannot create worker GC thread")
                || s.startsWith("Cannot create GC thread")) {
            oomDetail = s;
        } else if (s.startsWith("guarantee(") || s.startsWith("assert(") || s.startsWith("Error: ")
                || s.startsWith("fatal error") || s.startsWith("fatal error:")) {
            errorMessage = s.startsWith("Error: ") ? s.substring(7) : s;
        } else if (s.startsWith("JRE version:")) {
            jreVersion = s.substring(12).strip();
        } else if (s.startsWith("Java VM:")) {
            vmVersion = s.substring(8).strip();
        } else if (s.startsWith("Problematic frame:") && i + 1 < lines.size()) {
            problematic = lines.get(i + 1).replaceFirst("^#\\s*", "").strip();
        } else if (s.startsWith("Core dump will be written") || s.startsWith("No core dump will be written")
                || s.startsWith("CreateCoredumpOnCrash turned off") || s.startsWith("Failed to write core dump")
                || s.startsWith("Unable to get core dump")) {
            coreDump = s;
        }
    }

    private void summary(String l) {
        if (l.startsWith("Command Line:")) {
            commandLine = l.substring(13).strip();
        } else if (l.startsWith("Host:")) {
            host = l.substring(5).strip();
        } else if (l.startsWith("Time:")) {
            time = l.substring(5).strip();
            Matcher m = ELAPSED.matcher(l);
            if (m.find()) {
                elapsedSeconds = Double.parseDouble(m.group(1));
                time = time.substring(0, time.indexOf("elapsed")).strip();
            }
        }
    }

    private String thread(String l, String block) {
        if (l.startsWith("Current thread")) {
            Matcher at = Pattern.compile("^Current thread \\((0x[0-9a-fA-F]+)\\)").matcher(l);
            currentAddress = at.find() ? at.group(1) : "";
            currentLine = l.substring(l.indexOf(':') + 1).strip();
            Matcher m = Pattern.compile("^(\\w+)\\s+\"(.*?)\"\\s*(?:daemon\\s*)?\\[(?:(_thread_\\w+), )?(?:id=(\\d+))?")
                    .matcher(currentLine);
            if (m.find()) {
                currentKind = m.group(1);
                currentName = m.group(2);
                currentState = m.group(3) == null ? "" : m.group(3);
                currentId = m.group(4) == null ? "" : m.group(4);
                if (currentId.isEmpty()) {
                    Matcher id = Pattern.compile("id=(\\d+)").matcher(currentLine);
                    currentId = id.find() ? id.group(1) : "";
                }
            } else {
                Matcher k = Pattern.compile("^(\\w+)").matcher(currentLine);
                currentKind = k.find() ? k.group(1) : "";
                Matcher id = Pattern.compile("id=(\\d+)").matcher(currentLine);
                currentId = id.find() ? id.group(1) : "";
            }
            return null;
        }
        if (l.startsWith("Native frames:")) {
            return "native";
        }
        if (l.startsWith("Java frames:")) {
            return "java";
        }
        if (l.startsWith("siginfo:")) {
            siginfo = l.substring(8).strip();
            return null;
        }
        if (l.startsWith("Current CompileTask:")) {
            return "compile";
        }
        if (l.startsWith("VM_Operation") || l.startsWith("VM Operation")) {
            vmOperation = l.substring(l.indexOf(':') + 1).strip();
            return null;
        }
        if (l.isBlank()) {
            return "compile".equals(block) && compileTask == null ? block : null;
        }
        if ("native".equals(block) && frameLine(l)) {
            nativeFrames.add(l.strip());
        } else if ("java".equals(block) && frameLine(l)) {
            javaFrames.add(l.strip());
        } else if ("compile".equals(block)) {
            compileTask = l.strip();
            return null;
        }
        return block;
    }

    private static boolean frameLine(String l) {
        return l.matches("^\\s*[CVvjJA]\\s+.*") || l.startsWith("...<more frames>...");
    }

    private String process(String l, String block) {
        if (l.startsWith("Java Threads:")) {
            return "threads";
        }
        if (l.startsWith("Other Threads:")) {
            return "threads";
        }
        if (l.startsWith("Threads with active compile tasks:")) {
            return "compiles";
        }
        if (l.startsWith("VM state:")) {
            vmState = l.substring(9).strip();
            return null;
        }
        if (l.startsWith("VM Mutex/Monitor currently owned by a thread:")) {
            String rest = l.substring(l.indexOf(':') + 1).strip();
            if (!rest.equals("None") && !rest.isEmpty() && !rest.startsWith("(")) {
                ownedMutexes.add(rest);
            }
            return rest.equals("None") ? null : "mutex";
        }
        if (l.startsWith("Heap:")) {
            return "heap";
        }
        if (l.startsWith("Dynamic libraries:")) {
            return "libs";
        }
        if (l.startsWith("JVMTI agents:")) {
            String rest = l.substring(13).strip();
            return rest.equalsIgnoreCase("none") ? null : "jvmti";
        }
        if (l.startsWith("VM Arguments:")) {
            return "args";
        }
        if (l.startsWith("[Global flags]")) {
            return "flags";
        }
        if (l.startsWith("Environment Variables:")) {
            return "env";
        }
        Matcher ev = Pattern.compile("^(.+?) \\((\\d+) events\\):\\s*$").matcher(l);
        if (ev.find()) {
            String name = ev.group(1);
            events.put(name, new ArrayList<>());
            return "event:" + name;
        }
        if (block == null) {
            return null;
        }
        switch (block) {
            case "threads" -> {
                if (l.startsWith("Total:") || l.isBlank()) {
                    return l.isBlank() ? null : block;
                }
                Matcher m = THREAD_LINE.matcher(l);
                if (m.find()) {
                    String id = m.group(7);
                    if (id == null) {
                        Matcher idm = Pattern.compile("id=(\\d+)").matcher(l);
                        id = idm.find() ? idm.group(1) : "";
                    }
                    String name = m.group(4) == null ? m.group(3) : m.group(4); // JDK 8: "VMThread [stack: …]"
                    threads.add(new JThread(m.group(2), m.group(3), name, m.group(6) == null ? "" : m.group(6), id,
                            m.group(5) != null, m.group(1) != null));
                }
                return block;
            }
            case "compiles" -> {
                if (l.startsWith("Total:")) {
                    return null;
                }
                if (!l.isBlank()) {
                    activeCompiles.add(l.strip());
                }
                return block;
            }
            case "mutex" -> {
                if (l.isBlank()) {
                    return null;
                }
                if (!l.startsWith("(")) {
                    ownedMutexes.add(l.strip());
                }
                return block;
            }
            case "heap" -> {
                if (l.isBlank()) {
                    return null;
                }
                if (heapLine.isEmpty()) {
                    heapLine = l.strip();
                    Matcher m = HEAP_TOTAL.matcher(l);
                    if (m.find()) {
                        heapTotalK = Long.parseLong(m.group(1));
                        heapUsedK = Long.parseLong(m.group(2));
                    }
                } else if (l.strip().startsWith("Metaspace")) {
                    metaspaceLine = l.strip().replaceAll("\\s+", " ");
                }
                return block;
            }
            case "libs" -> {
                if (l.isBlank()) {
                    return null;
                }
                String lib = library(l);
                if (lib != null && !libraries.contains(lib)) {
                    libraries.add(lib);
                }
                Matcher mp = Pattern.compile("^([0-9a-fA-F]+)-([0-9a-fA-F]+)\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\d+\\s+(/\\S.*)$")
                        .matcher(l.strip());
                if (mp.find()) {
                    try {
                        mappings.add(new Mapping(Long.parseUnsignedLong(mp.group(1), 16), Long.parseUnsignedLong(mp.group(2), 16),
                                mp.group(3).strip()));
                    } catch (NumberFormatException e) {
                        // not an address range
                    }
                }
                return block;
            }
            case "args" -> {
                if (l.startsWith("jvm_args:")) {
                    jvmArgs = l.substring(9).strip();
                } else if (l.startsWith("java_command:")) {
                    javaCommand = l.substring(13).strip();
                } else if (l.startsWith("java_class_path")) {
                    classPath = l.substring(l.indexOf(':') + 1).strip();
                }
                return l.isBlank() ? null : block;
            }
            case "flags" -> {
                if (l.isBlank()) {
                    return null;
                }
                Matcher m = Pattern.compile("^\\s*\\S+\\s+(\\w+)\\s+:?=\\s*(\\S*)").matcher(l);
                if (m.find()) {
                    flags.put(m.group(1), m.group(2));
                }
                return block;
            }
            case "jvmti" -> {
                if (l.isBlank()) {
                    return null;
                }
                String a = l.strip().split("\\s+")[0].replace('\\', '/');
                a = a.substring(a.lastIndexOf('/') + 1);
                if (!a.isEmpty() && !jvmtiAgents.contains(a)) {
                    jvmtiAgents.add(a);
                }
                return block;
            }
            case "env" -> {
                if (l.isBlank()) {
                    return null;
                }
                environment.add(l.strip());
                return block;
            }
            default -> {
                if (block.startsWith("event:")) {
                    if (l.isBlank()) {
                        return null;
                    }
                    if (l.strip().equals("No events")) {
                        return block;
                    }
                    List<String> list = events.get(block.substring(6));
                    if (l.startsWith("Event:") || list.isEmpty()) {
                        list.add(l.strip());
                    } else {
                        list.set(list.size() - 1, list.get(list.size() - 1) + " " + l.strip());
                    }
                    return block;
                }
                return null;
            }
        }
    }

    /** The file of a "Dynamic libraries" line (Windows, macOS, or a Linux maps line); null if none. */
    static String library(String l) {
        String s = l.strip();
        Matcher win = Pattern.compile("^0x[0-9a-fA-F]+ - 0x[0-9a-fA-F]+\\s+(.+)$").matcher(s);
        if (win.find()) {
            return win.group(1).strip();
        }
        Matcher mac = Pattern.compile("^0x[0-9a-fA-F]+\\s+(/.+)$").matcher(s);
        if (mac.find()) {
            return mac.group(1).strip();
        }
        Matcher maps = Pattern.compile("^[0-9a-fA-F]+-[0-9a-fA-F]+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\d+\\s+(/\\S.*)$").matcher(s);
        if (maps.find()) {
            String p = maps.group(1).strip();
            String n = p.substring(p.lastIndexOf('/') + 1);
            return n.contains(".so") || n.endsWith(".dylib") || n.equals("java") ? p : null;
        }
        return null;
    }

    private String system(String l, String block) {
        String s = l.strip();
        if (s.startsWith("OS:")) {
            os = s.substring(3).strip();
            return "os";
        }
        if (s.startsWith("uname:")) {
            uname = s.substring(6).strip();
        } else if (s.startsWith("rlimit")) {
            rlimit = s.substring(s.indexOf(':') + 1).strip();
        } else if (s.startsWith("CPU:")) {
            cpu = s.substring(4).strip();
        } else if (s.startsWith("Memory:")) {
            memory = s.substring(7).strip();
            Matcher m = MEMORY.matcher(s);
            if (m.find()) {
                physicalK = Long.parseLong(m.group(1));
                physicalFreeK = Long.parseLong(m.group(2));
                if (m.group(3) != null) {
                    swapK = Long.parseLong(m.group(3));
                    swapFreeK = Long.parseLong(m.group(4));
                }
            } else {
                Matcher mb = MEMORY_MB.matcher(s);
                if (mb.find()) {
                    physicalK = Long.parseLong(mb.group(1)) * 1024;
                    physicalFreeK = Long.parseLong(mb.group(2)) * 1024;
                }
            }
        } else if (s.startsWith("elapsed time:") && elapsedSeconds < 0) {
            // JDK 8 writes the time at the end: "elapsed time: 5021 seconds (0d 1h 23m 41s)"
            Matcher m = ELAPSED.matcher(s);
            if (m.find()) {
                elapsedSeconds = Double.parseDouble(m.group(1));
            }
        } else if (s.startsWith("time:") && time.isEmpty()) {
            time = s.substring(5).strip();
        } else if (s.startsWith("memory_limit_in_bytes:")) {
            String v = s.substring(22).strip();
            Matcher m = Pattern.compile("^(\\d+)\\s*k").matcher(v);
            if (m.find()) {
                containerLimitK = Long.parseLong(m.group(1));
            } else if (v.matches("\\d+")) {
                containerLimitK = Long.parseLong(v) / 1024;
            }
        } else if (s.startsWith("vm_info:")) {
            vmInfo = s.substring(8).strip();
            Matcher m = BUILT.matcher(s);
            if (m.find()) {
                builtOn = date(m.group(1));
            }
        } else if ("os".equals(block) && (s.startsWith("PRETTY_NAME=") || s.startsWith("DISTRIB_DESCRIPTION="))) {
            os = s.substring(s.indexOf('=') + 1).replace("\"", "").strip(); // Linux: the distribution's own name
        } else if ("os".equals(block) && !s.isEmpty() && os.isEmpty() && !s.startsWith("DISTRIB_")) {
            os = s;
        }
        return "os".equals(block) && !s.isEmpty() && !s.contains(":") ? block : null;
    }

    private static LocalDate date(String s) {
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(s);
            }
            return LocalDate.parse(s.replaceAll("\\s+", " "),
                    java.time.format.DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- frames

    /** The library a native frame is in: "libfoo.so" from "C  [libfoo.so+0x1234]  foo+0x10". */
    static String frameLibrary(String frame) {
        Matcher m = Pattern.compile("\\[([^\\[\\]+]+)\\+0x").matcher(frame);
        return m.find() ? m.group(1).strip() : null;
    }

    /** The function of a native frame ("foo+0x10" → "foo"), or null. */
    static String frameFunction(String frame) {
        Matcher m = Pattern.compile("\\]\\s+(.+?)(\\+0x[0-9a-fA-F]+)?\\s*$").matcher(frame);
        return m.find() ? m.group(1).strip() : null;
    }

    /** The Java method of a j/J frame: "com.acme.Order.total(int)", or null. */
    static String javaMethod(String frame) {
        String s = frame.strip();
        if (!(s.startsWith("j ") || s.startsWith("J ") || s.startsWith("A "))) {
            return null;
        }
        // the token with the method: "j  a.B.c(I)V+13 java.base", "J 12 c2 a.B.c(I)V (45 bytes) @ …"
        String token = null;
        for (String t : s.split("\\s+")) {
            if (t.contains("(")) {
                token = t;
                break;
            }
        }
        if (token == null) {
            return null;
        }
        token = token.replaceAll("\\+0x[0-9a-fA-F]+", ""); // hidden classes: LambdaForm$MH+0x…
        Matcher m = Pattern.compile("^([\\w$.\\[\\]/]+)\\.([\\w$<>]+)\\(([^)]*)\\)").matcher(token);
        if (!m.find()) {
            return null;
        }
        return m.group(1).replace('/', '.') + "." + m.group(2) + "(" + params(m.group(3)) + ")";
    }

    /** The class of a Java frame, or null. */
    static String javaClass(String frame) {
        String m = javaMethod(frame);
        if (m == null) {
            return null;
        }
        String head = m.substring(0, m.indexOf('('));
        return head.substring(0, head.lastIndexOf('.'));
    }

    /** "(ILjava/lang/String;[J)V" parameters → "int, String, long[]". */
    static String params(String desc) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < desc.length()) {
            int dims = 0;
            while (i < desc.length() && desc.charAt(i) == '[') {
                dims++;
                i++;
            }
            if (i >= desc.length()) {
                break;
            }
            char c = desc.charAt(i);
            String t;
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) {
                    return desc;
                }
                String cls = desc.substring(i + 1, end).replace('/', '.');
                t = cls.substring(cls.lastIndexOf('.') + 1);
                i = end + 1;
            } else {
                t = switch (c) {
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'Z' -> "boolean";
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'S' -> "short";
                    case 'F' -> "float";
                    case 'D' -> "double";
                    case 'V' -> "void";
                    default -> String.valueOf(c);
                };
                i++;
            }
            out.add(t + "[]".repeat(dims));
        }
        return String.join(", ", out);
    }

    /** Classes of the JDK itself (and of the JVM's generated code). */
    static boolean isJdkClass(String cls) {
        return cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.") || cls.startsWith("sun.")
                || cls.startsWith("com.sun.") || cls.startsWith("org.ietf.") || cls.startsWith("org.w3c.")
                || cls.startsWith("org.xml.");
    }

    /** The JDK's folder, from where libjvm was loaded; null if not seen. */
    String javaHome() {
        for (String lib : libraries) {
            String l = lib.replace('\\', '/');
            String low = l.toLowerCase(Locale.ROOT);
            for (String marker : new String[] {"/lib/server/libjvm", "/jre/lib/amd64/server/libjvm", "/bin/server/jvm.dll",
                    "/lib/client/libjvm", "/bin/client/jvm.dll", "/lib/amd64/server/libjvm", "/lib/aarch64/server/libjvm"}) {
                int i = low.indexOf(marker);
                if (i > 0) {
                    String home = l.substring(0, i);
                    return home.endsWith("/jre") ? home.substring(0, home.length() - 4) : home;
                }
            }
        }
        return null;
    }

    /** A system library: the operating system's or the C runtime's, not the program's. */
    static boolean isSystemLibrary(String path) {
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String n = p.substring(p.lastIndexOf('/') + 1);
        return p.startsWith("/usr/lib/") || p.startsWith("/lib/") || p.startsWith("/lib64/") || p.startsWith("/system/")
                || p.startsWith("/usr/lib64/") || p.contains("/windows/system32/") || p.contains("/windows/syswow64/")
                || p.contains("/windows/winsxs/") || n.startsWith("libc.") || n.startsWith("libc-") || n.startsWith("libpthread")
                || n.startsWith("libdl") || n.startsWith("libm.") || n.startsWith("libm-") || n.startsWith("ld-linux")
                || n.startsWith("libgcc") || n.startsWith("libstdc++") || n.equals("ntdll.dll") || n.equals("kernel32.dll")
                || n.equals("kernelbase.dll") || n.startsWith("libsystem_") || n.startsWith("ucrtbase")
                || n.startsWith("vcruntime") || n.startsWith("msvcr") || n.startsWith("msvcp");
    }

    private boolean isJdkLibrary(String nameOrPath, String home) {
        String n = nameOrPath.replace('\\', '/');
        String base = n.substring(n.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (home != null && n.startsWith(home)) {
            return true;
        }
        if (n.contains("/")) {
            return false; // a full path outside the JDK
        }
        // a bare name in a frame: is the library of that name in the JDK?
        for (String lib : libraries) {
            String l = lib.replace('\\', '/');
            if (l.toLowerCase(Locale.ROOT).endsWith("/" + base)) {
                return home != null && l.startsWith(home);
            }
        }
        return base.startsWith("libjvm") || base.equals("jvm.dll") || base.startsWith("libjava") || base.equals("java.dll")
                || base.startsWith("libjli") || base.equals("jli.dll") || base.startsWith("libzip") || base.startsWith("libnio")
                || base.startsWith("libnet") || base.startsWith("libverify") || base.equals("zip.dll") || base.equals("nio.dll")
                || base.equals("net.dll");
    }

    private String fullPath(String lib) {
        for (String l : libraries) {
            String x = l.replace('\\', '/');
            if (x.toLowerCase(Locale.ROOT).endsWith("/" + lib.toLowerCase(Locale.ROOT))) {
                return l;
            }
        }
        return lib;
    }

    // ---------------------------------------------------------------- the report

    private static final Pattern REPEAT_SPACE = Pattern.compile("\\s{2,}");

    private CrashReport.Frame frame(String raw, String home) {
        String s = raw.strip();
        char t = s.isEmpty() ? '?' : s.charAt(0);
        if (t == 'j' || t == 'J' || t == 'A') {
            String m = javaMethod(s);
            String cls = javaClass(s);
            String how = t == 'j' ? "interpreted" : s.contains(" c1 ") ? "compiled by C1" : s.contains(" c2 ") ? "compiled by C2"
                    : t == 'A' ? "compiled ahead of time" : "compiled";
            Matcher bci = Pattern.compile("\\)\\S*\\+(\\d+)").matcher(s);
            String text = (m == null ? s : m) + (t == 'j' && bci.find() ? " +" + bci.group(1) : "") + "  (" + how + ")";
            boolean own = cls != null && !isJdkClass(cls) && !cls.startsWith("java.lang.invoke.LambdaForm");
            return new CrashReport.Frame(text, null, own ? FrameKind.OWN : FrameKind.JAVA, false);
        }
        if (t == 'v' || t == 'V') {
            return new CrashReport.Frame("JVM  " + REPEAT_SPACE.matcher(s.substring(1).strip()).replaceAll("  "), null,
                    FrameKind.SYSTEM, false);
        }
        if (t == 'C') {
            String lib = frameLibrary(s);
            boolean system = lib == null || isSystemLibrary(fullPath(lib)) || isJdkLibrary(fullPath(lib), home);
            return new CrashReport.Frame(REPEAT_SPACE.matcher(s.substring(1).strip()).replaceAll("  "), null,
                    system ? FrameKind.SYSTEM : FrameKind.OWN, false);
        }
        return new CrashReport.Frame(s, null, FrameKind.UNKNOWN, false);
    }

    private static String threadState(String s) {
        return switch (s) {
            case "_thread_in_native" -> "running native code";
            case "_thread_in_native_trans" -> "returning from native code";
            case "_thread_in_vm" -> "in the JVM";
            case "_thread_in_vm_trans" -> "entering or leaving the JVM";
            case "_thread_in_Java" -> "running Java code";
            case "_thread_in_Java_trans" -> "entering Java code";
            case "_thread_blocked" -> "blocked or waiting";
            case "_thread_blocked_trans" -> "leaving a wait";
            case "_thread_new", "_thread_new_trans" -> "just started";
            case "" -> "";
            default -> s;
        };
    }

    private static String threadKind(String kind, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "VMThread" -> "the JVM's own thread (runs safepoint operations)";
            case "WatcherThread" -> "the JVM's periodic task thread";
            case "WorkerThread", "GCTaskThread", "ConcurrentGCThread" -> n.contains("gc") || n.contains("g1") || n.contains("z ")
                    || n.contains("shenandoah") || n.contains("marker") || n.contains("refine") || n.contains("par")
                    ? "a garbage collector thread" : "a JVM worker thread";
            case "CompilerThread" -> "the JIT compiler";
            default -> n.contains("compilerthread") ? "the JIT compiler" : "";
        };
    }

    /** The report, with findings. */
    public CrashReport report() {
        String home = javaHome();
        List<Finding> f = new ArrayList<>();
        List<CrashReport.Frame> frames = new ArrayList<>();
        List<String> stack = stack();
        for (String raw : stack) {
            if (!raw.startsWith("...")) {
                frames.add(frame(raw, home));
            }
        }
        // the problematic frame and the code to blame
        String probLib = problematic == null ? null : frameLibrary(problematic);
        String probFunc = problematic == null ? null : frameFunction(problematic);
        char probType = problematic == null || problematic.isEmpty() ? '?' : problematic.charAt(0);
        String firstJava = null;
        String firstOwnJava = null;
        for (String raw : stack) {
            String m = javaMethod(raw);
            if (m == null) {
                continue;
            }
            if (firstJava == null) {
                firstJava = m;
            }
            String cls = javaClass(raw);
            if (firstOwnJava == null && cls != null && !isJdkClass(cls)) {
                firstOwnJava = m;
            }
        }
        String firstOwnNative = null;
        for (String raw : nativeFrames) {
            if (raw.startsWith("C")) {
                String lib = frameLibrary(raw);
                if (lib != null && !isSystemLibrary(fullPath(lib)) && !isJdkLibrary(fullPath(lib), home)) {
                    firstOwnNative = raw.substring(1).strip();
                    break;
                }
            }
        }
        String threadDesc = threadKind(currentKind, currentName);
        boolean gcThread = threadDesc.contains("garbage collector")
                || probFunc != null && gcFunction(probFunc);
        boolean compiler = currentName.contains("CompilerThread") || currentKind.equals("CompilerThread") || compileTask != null
                && !currentKind.equals("JavaThread");
        boolean vmThread = currentKind.equals("VMThread");
        String addr = siAddress();
        boolean nullAddr = addr != null && isNearNull(addr);
        String sigName = signal.isEmpty() ? "" : signal.substring(0, signal.indexOf(' ') > 0 ? signal.indexOf(' ') : signal.length());

        String title;
        String summary;
        String where = null;
        if (outOfMemory) {
            title = "The JVM ran out of native memory" + (oomDetail == null ? "" : ": " + shortOom());
            summary = "The JVM itself couldn't get memory from the operating system (this is not the Java heap's "
                    + "OutOfMemoryError: the process or the machine ran out).";
            oom(f);
        } else if (internalError != null) {
            title = "The JVM stopped on an internal error" + (errorMessage == null ? "" : ": " + cut(errorMessage, 160));
            summary = "A check inside the JVM failed (in " + internalError + "). This is a JVM bug, or the JVM's "
                    + "data was damaged (often by native code or Unsafe).";
            f.add(new Finding(Severity.ERROR, "A check in the JVM failed: " + internalError,
                    (errorMessage == null ? "" : errorMessage + ". ")
                            + (gcThread ? "The garbage collector (" + (currentName.isEmpty() ? currentKind : currentName)
                            + ") found it: the heap's data was likely damaged earlier, often by native code or Unsafe."
                            : ""),
                    "Search the JDK bug tracker for the message, and update the JDK: most such errors are fixed in a "
                            + "later update."));
        } else {
            String kind = sigName.isEmpty() ? "A crash" : sigName;
            String in;
            if (probType == 'C') {
                in = "in native code";
            } else if (probType == 'V' || probType == 'v') {
                in = gcThread ? "in the garbage collector" : compiler ? "in the JIT compiler" : "in the JVM";
            } else if (probType == 'J' || probType == 'A') {
                in = "in compiled Java code";
            } else if (probType == 'j') {
                in = "in interpreted Java code";
            } else {
                in = "";
            }
            String fn = probFunc == null ? null : probFunc.replaceAll("\\(.*$", "").replaceFirst("^.*\\s(?=\\S+$)", "");
            if (probType == 'v') {
                fn = stubName(problematic); // "v  ~StubRoutines::jlong_disjoint_arraycopy"
            }
            if (fn != null && (probType == 'V' || probType == 'v' || probType == 'C') && fn.length() <= 60
                    && !fn.startsWith("0x")) {
                in += ": " + fn + (probType == 'C' && probLib != null ? " in " + probLib : "");
            } else if (probType == 'C' && probLib != null) {
                in += ": " + probLib;
            }
            title = kind + (in.isEmpty() ? "" : " " + in) + (nullAddr ? ", at a null address" : "");
            summary = explainSignal(sigName, addr);
        }
        if (problematic != null) {
            where = "at " + (probType == 'J' || probType == 'j' || probType == 'A' ? frame(problematic, home).text()
                    : probType == 'v' && stubName(problematic) != null ? stubName(problematic)
                    : REPEAT_SPACE.matcher(problematic.substring(1).strip()).replaceAll("  "));
            if (probType == 'C' && firstOwnNative != null && !problematic.contains(firstOwnNative)) {
                where += ", called by " + nativeName(firstOwnNative);
            }
            String from = where.contains(", called by ") ? ", from Java " : ", called from ";
            if (firstOwnJava != null && !(probType == 'J' && problematic.contains(firstOwnJava.substring(0, firstOwnJava.indexOf('('))))) {
                where += from + firstOwnJava;
            } else if (firstJava != null && probType != 'J' && probType != 'j') {
                where += from + firstJava;
            }
            String user = probType == 'C' ? programCaller() : null;
            if (user != null && !where.contains(user)) {
                where += ", used by " + user;
            }
        }

        // how it happened
        if (!outOfMemory && internalError == null) {
            how(f, probType, probLib, probFunc, gcThread, compiler, vmThread, firstJava, firstOwnJava, firstOwnNative, home,
                    nullAddr, sigName);
        }
        // the other threads
        suspects(f, gcThread, probType, home);
        // the setting
        setting(f);

        // threads
        List<CrashReport.Thread> ts = new ArrayList<>();
        String curState = currentState.isEmpty() ? (threadDesc.isEmpty() ? "crashed" : threadDesc)
                : threadState(currentState);
        ts.add(new CrashReport.Thread(currentId.isEmpty() ? tid : currentId, currentName.isEmpty() ? currentKind : currentName,
                "crashed — " + curState, true, null, frames));
        for (JThread t : threads) {
            if (t.current() || !currentId.isEmpty() && t.id().equals(currentId)) {
                continue;
            }
            String st = !t.state().isEmpty() ? threadState(t.state()) : threadKind(t.kind(), t.name());
            ts.add(new CrashReport.Thread(t.id(), t.name(), st.isEmpty() ? t.kind() : st, false, null, List.of()));
        }

        // modules
        List<CrashReport.Module> mods = new ArrayList<>();
        for (String lib : libraries) {
            String n = lib.replace('\\', '/');
            String name = n.substring(n.lastIndexOf('/') + 1);
            String note = probLib != null && name.equalsIgnoreCase(probLib) ? "the crash is here"
                    : isJdkLibrary(lib, home) ? "" : isSystemLibrary(lib) ? "" : "not the JDK's or the system's";
            mods.add(new CrashReport.Module(name, "", lib, "", isJdkLibrary(lib, home) ? "JDK" : isSystemLibrary(lib) ? "system" : "",
                    note));
        }

        return new CrashReport(CrashReport.Kind.JAVA_CRASH, title, where, summary, f, facts(), ts, mods, sections());
    }

    /**
     * The crashed thread's frames: the native frames (which hold the Java ones too), with the
     * Java frames added when the native walk didn't get to them (it stops early on Windows).
     */
    private List<String> stack() {
        if (nativeFrames.isEmpty()) {
            return javaFrames;
        }
        boolean hasJava = false;
        for (String f : nativeFrames) {
            if (javaMethod(f) != null) {
                hasJava = true;
                break;
            }
        }
        if (hasJava || javaFrames.isEmpty()) {
            return nativeFrames;
        }
        List<String> all = new ArrayList<>(nativeFrames);
        all.addAll(javaFrames);
        return all;
    }

    /** "v  ~StubRoutines::jlong_disjoint_arraycopy 0x…" → "StubRoutines::jlong_disjoint_arraycopy"; null if not a stub. */
    static String stubName(String frame) {
        Matcher m = Pattern.compile("^v\\s+~?([\\w:<>$]+)").matcher(frame.strip());
        return m.find() ? m.group(1) : null;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private String shortOom() {
        String s = oomDetail;
        int i = s.indexOf(" for ");
        return cut(s, 140);
    }

    static boolean gcFunction(String f) {
        return f.matches("(?i).*(G1|ParallelScavenge|PSPromotion|PSScavenge|PSMark|MarkSweep|Shenandoah|ZBarrier|ZMark|ZRelocate"
                + "|XMark|CardTable|oopDesc::|OopClosure|ScavengeRoots|GenCollect|DefNew|Tenured|ConcurrentMark|CMSCollector"
                + "|ObjArrayKlass::oop_oop|InstanceKlass::oop_oop|Universe::heap|CollectedHeap|ReferenceProcessor|StringDedup).*");
    }

    private String siAddress() {
        Matcher m = Pattern.compile("si_addr: (0x[0-9a-fA-F]+)").matcher(siginfo);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("(?:reading|writing) address (0x[0-9a-fA-F]+)").matcher(siginfo);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("(?:execution|executing) (?:at )?address (0x[0-9a-fA-F]+)").matcher(siginfo);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isNearNull(String hex) {
        try {
            return Long.compareUnsigned(Long.parseUnsignedLong(hex.substring(2), 16), 0x10000) < 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String explainSignal(String sig, String addr) {
        String reading = siginfo.contains("writing") ? "wrote to" : siginfo.contains("reading") ? "read from" : "used";
        return switch (sig) {
            case "SIGSEGV", "EXCEPTION_ACCESS_VIOLATION" -> "The program " + reading + " memory it may not use"
                    + (addr == null ? "" : " (address " + addr + (isNearNull(addr) ? ", a null pointer" : "") + ")")
                    + ", and the operating system stopped it. Java code can't do that by itself: native code, "
                    + "Unsafe, or a JVM bug did.";
            case "SIGBUS" -> "A memory access failed" + (addr == null ? "" : " at " + addr) + ". On Linux this is "
                    + "usually a memory-mapped file that got shorter while mapped (another process truncated or "
                    + "replaced it), or /dev/shm or the disk being full.";
            case "SIGILL", "EXCEPTION_ILLEGAL_INSTRUCTION" -> "The processor met an instruction it can't run: code "
                    + "damaged, or built for a newer processor than this one.";
            case "SIGABRT" -> "Something called abort(): a native library's assertion, or the C library finding its "
                    + "memory damaged (double free, heap corruption).";
            case "SIGFPE", "EXCEPTION_INT_DIVIDE_BY_ZERO" -> "An arithmetic error in native code (division by zero).";
            case "EXCEPTION_STACK_OVERFLOW" -> "A thread used up its stack in native code or the JVM, where Java "
                    + "can't turn it into a StackOverflowError.";
            case "EXCEPTION_IN_PAGE_ERROR" -> "Windows couldn't read a page of a mapped file (a network share or "
                    + "disk went away).";
            default -> "The JVM stopped on " + (sig.isEmpty() ? "a fatal error" : sig) + ".";
        };
    }

    private void how(List<Finding> f, char probType, String probLib, String probFunc, boolean gcThread, boolean compiler,
                     boolean vmThread, String firstJava, String firstOwnJava, String firstOwnNative, String home,
                     boolean nullAddr, String sig) {
        String func = probFunc == null ? "" : probFunc;
        boolean unsafe = func.contains("Unsafe_") || nativeFrames.stream().limit(6).anyMatch(x ->
                x.contains("jdk.internal.misc.Unsafe.") || x.contains("sun.misc.Unsafe."));
        boolean mapped = nativeFrames.stream().anyMatch(x -> x.contains("MappedByteBuffer") || x.contains("java.util.zip.")
                || x.contains("ZipFile") || x.contains("libzip") || x.contains("ZIP_") || x.contains("Unsafe_Copy")
                || x.contains("arraycopy"));
        String file = mappedFileAt(siAddress());
        if (sig.equals("SIGBUS") && (mapped || file != null)) {
            f.add(new Finding(Severity.ERROR, "A mapped file changed under the program" + (file == null ? "" : ": " + file),
                    (file == null ? "" : "The address " + siAddress() + " is in " + file + ", which was mapped into "
                            + "memory. ")
                            + "The crash is in reading a memory-mapped file or a zip/jar. The usual cause: the file was "
                            + "truncated or replaced while the program had it open (a deploy overwriting jars, log "
                            + "rotation, another process writing the same file).",
                    "Don't overwrite jars or mapped files in place while the program runs: write a new file and "
                            + "rename it, or restart. Check free space in /dev/shm and /tmp."));
            return;
        }
        if (unsafe) {
            String who = firstOwnJava != null ? firstOwnJava : firstJava;
            f.add(new Finding(Severity.ERROR, "Memory read or written through Unsafe at a bad address",
                    "The crash is in sun.misc.Unsafe (or jdk.internal.misc.Unsafe), which reads and writes memory "
                            + "without checks." + (nullAddr ? " The address is near zero: an offset from a null "
                            + "base or a freed buffer." : "") + (who == null ? "" : " Called from " + who + "."),
                    "Check the addresses and offsets that code passes to Unsafe; libraries that use off-heap memory "
                            + "(direct buffers, memory-mapped stores) can hit this after a buffer was freed."));
            return;
        }
        if (compiler) {
            String task = compileTask == null ? null : compileTaskMethod(compileTask);
            f.add(new Finding(Severity.ERROR, "The JIT compiler crashed" + (task == null ? "" : " compiling " + task),
                    "A JIT compiler thread (" + currentName + ") crashed while compiling a method. This is a "
                            + "JVM bug, set off by that method's code.",
                    (task == null ? "" : "Work around it with -XX:CompileCommand=exclude," + task.replace("(", "::(")
                            .replaceAll("::\\(.*$", "").replaceAll("\\.([^.]+)$", "::$1") + " (the method then "
                            + "runs interpreted). ") + "Update the JDK: such bugs are usually fixed in a later update."));
            return;
        }
        if (gcThread) {
            f.add(new Finding(Severity.ERROR, "The garbage collector crashed",
                    "The crash is in the garbage collector, while it walked the heap. Usually the heap was damaged "
                            + "earlier, by native code (JNI) or Unsafe writing where it shouldn't; sometimes it is a "
                            + "JVM bug. The thread that did the damage isn't the one that crashed.",
                    "Update the JDK. To catch the damage closer to where it happens, run with -Xcheck:jni, or with "
                            + "-XX:+UnlockDiagnosticVMOptions -XX:+VerifyBeforeGC -XX:+VerifyAfterGC (slow). If it "
                            + "goes away with another collector (-XX:+UseParallelGC), report it as a JVM bug."));
            return;
        }
        if (vmThread) {
            String requester = null;
            if (vmOperation != null) {
                Matcher rm = Pattern.compile("requested by thread (0x[0-9a-fA-F]+)").matcher(vmOperation);
                if (rm.find()) {
                    requester = threadName(rm.group(1));
                }
            }
            f.add(new Finding(Severity.ERROR, "The JVM's own thread crashed"
                    + (vmOperation == null ? "" : " during " + vmOperation.split(",")[0]),
                    "The VM Thread runs work that stops all Java threads (safepoints): GC, deoptimization, class "
                            + "redefinition. A crash there is usually damaged JVM data, or a JVM bug."
                            + (requester == null ? "" : " The work was asked for by " + requester + "."),
                    "Update the JDK; if agents redefine classes (APM, profilers), try without them."));
            return;
        }
        if (probType == 'C') {
            boolean ownLib = probLib != null && !isSystemLibrary(fullPath(probLib)) && !isJdkLibrary(fullPath(probLib), home);
            String user = programCaller();
            if (ownLib) {
                f.add(new Finding(Severity.ERROR, "A native library crashed: " + probLib,
                        "The crash is in " + fullPath(probLib) + ", which is not part of the JDK"
                                + (firstJava == null ? "." : "; Java called it from " + firstJava
                                + (user == null ? "." : ", used by " + user + "."))
                                + (currentName.isEmpty() ? "" : " Thread: \"" + currentName + "\"."),
                        "Update that library (or the Java library that brings it, e.g. a database driver, a "
                                + "compression or networking library). Run with -Xcheck:jni to find misuse of JNI."));
            } else if (firstOwnNative != null) {
                String lib = frameLibrary("C  " + firstOwnNative);
                f.add(new Finding(Severity.ERROR, (lib == null ? "A native library" : lib)
                        + " passed a bad pointer or size to the system",
                        "The crash is reported in " + probLib + " (the C library or the system), but it was called by "
                                + nativeName(firstOwnNative) + ", which is not part of the JDK"
                                + (currentName.isEmpty() ? "" : ", on the thread \"" + currentName + "\"")
                                + (firstJava == null ? "" : ", from Java " + firstJava) + ".",
                        "Update that library (or the Java library that brings it). Run with -Xcheck:jni to find "
                                + "misuse of JNI."));
            } else if (probLib != null && isJdkLibrary(fullPath(probLib), home)) {
                f.add(new Finding(Severity.ERROR, "The crash is in the JDK's native code (" + probLib + ")",
                        firstJava == null ? null : "Called from " + firstJava + ".",
                        "Update the JDK. If the Java code using it is your own (files, zips, sockets), look at what it "
                                + "does at that point."));
            } else {
                f.add(new Finding(Severity.ERROR, "The crash is in system code (" + (probLib == null ? "?" : probLib) + ")",
                        firstJava == null ? null : "Reached from " + firstJava + ".",
                        "Look at the native frames below it: the first library that isn't the system's is likely to "
                                + "blame."));
            }
            return;
        }
        if (probType == 'J' || probType == 'A') {
            String m = javaMethod(problematic);
            f.add(new Finding(Severity.ERROR, "The crash is in JIT-compiled Java code" + (m == null ? "" : ": " + m),
                    "Java code compiled by the JIT crashed. Either the compiler made wrong code (a JVM bug) or "
                            + "something (Unsafe, native code) damaged the data that code used.",
                    m == null ? "Update the JDK." : "To test whether it is the compiler, run with "
                            + "-XX:CompileCommand=exclude," + m.substring(0, m.indexOf('(')).replaceAll("\\.([^.]+)$", "::$1")
                            + "; if the crash goes, report it as a JVM bug. Update the JDK."));
            return;
        }
        if (probType == 'V' || probType == 'v') {
            boolean jni = func.startsWith("jni_") || func.contains("JNI") || func.startsWith("checked_jni");
            f.add(new Finding(Severity.ERROR, "The crash is in the JVM" + (probFunc == null ? "" : " (" + probFunc + ")"),
                    (jni ? "It happened in a JNI call: native code passed the JVM a bad reference (one already "
                            + "freed, from another thread, or null). " : "")
                            + (firstJava == null ? "" : "The thread was running " + firstJava + ". ")
                            + "Crashes in the JVM are usually a JVM bug or data damaged by native code.",
                    jni ? "Run with -Xcheck:jni: it reports JNI misuse at the call. Update the native library."
                            : "Update the JDK, and search its bug tracker for " + (probFunc == null ? "the function"
                            : probFunc.replaceAll("\\(.*", "")) + "."));
        }
    }

    /** "[libfoo.so+0x444]  Java_Foo_bar+0x3c" → "Java_Foo_bar in libfoo.so". */
    static String nativeName(String frame) {
        String lib = frameLibrary("C  " + frame);
        String fn = frameFunction("C  " + frame);
        if (fn == null || fn.startsWith("0x") || fn.isEmpty()) {
            return lib == null ? frame : lib;
        }
        return fn.replaceAll("\\(.*$", "") + (lib == null ? "" : " in " + lib);
    }

    private static String compileTaskMethod(String task) {
        Matcher m = Pattern.compile("([\\w$.]+)::([\\w$<>]+)").matcher(task);
        return m.find() ? m.group(1) + "." + m.group(2) + "()" : null;
    }

    /** Which other threads may be involved. */
    private void suspects(List<Finding> f, boolean gcThread, char probType, String home) {
        List<String> inNative = new ArrayList<>();
        Map<String, Integer> states = new LinkedHashMap<>();
        for (JThread t : threads) {
            if (!t.state().isEmpty()) {
                states.merge(threadState(t.state()), 1, Integer::sum);
            }
            boolean self = t.current() || !currentId.isEmpty() && t.id().equals(currentId);
            if (!self && t.state().equals("_thread_in_native") && !t.name().equals("Signal Dispatcher")
                    && !t.name().contains("CompilerThread") && !t.name().equals("Attach Listener")) {
                inNative.add(t.name());
            }
        }
        boolean corruption = gcThread || vmThreadCrash() || internalError != null || probType == 'J'
                || signal.startsWith("SIGABRT");
        if (corruption && !inNative.isEmpty()) {
            f.add(new Finding(Severity.WARN, inNative.size() + (inNative.size() == 1 ? " other thread was" : " other threads were")
                    + " running native code at the time",
                    "Native code can damage memory that crashes another thread later: " + names(inNative, 10) + ".",
                    "See which libraries those threads use (their names often say: a driver, a compression library)."));
        } else if (probType == 'C' && !inNative.isEmpty()) {
            f.add(new Finding(Severity.INFO, inNative.size() + (inNative.size() == 1 ? " other thread was" : " other threads were")
                    + " in native code at the same time: " + names(inNative, 8),
                    "If they used the same native library at once, it may not be safe to call from several threads.",
                    null));
        }
        if (!ownedMutexes.isEmpty()) {
            List<String> held = new ArrayList<>();
            for (String m : ownedMutexes) {
                Matcher mm = Pattern.compile("\\]?\\s*(\\w+) - owner thread: (0x[0-9a-fA-F]+)").matcher(m);
                if (mm.find()) {
                    held.add(mm.group(1) + " (held by " + threadName(mm.group(2)) + ")");
                } else {
                    held.add(m);
                }
            }
            f.add(new Finding(Severity.INFO, "JVM locks held at the crash: " + String.join(", ", held),
                    "These threads were in the middle of JVM work that needs that lock.", null));
        }
        String op = vmOperation;
        if (op == null) {
            for (String e : events.getOrDefault("VM Operations", List.of())) {
                Matcher m = Pattern.compile("Executing (?:safepoint |coalesced safepoint |nested )?VM operation: (.+?)(?: done)?$")
                        .matcher(e);
                if (m.find()) {
                    op = m.group(1).strip();
                }
            }
        }
        if (gcThread && op != null) {
            String requester = null;
            for (String m : ownedMutexes) {
                Matcher mm = Pattern.compile("Heap_lock - owner thread: (0x[0-9a-fA-F]+)").matcher(m);
                if (mm.find()) {
                    requester = threadName(mm.group(1));
                }
            }
            f.add(new Finding(Severity.INFO, "It crashed during " + op,
                    requester == null ? null : "The collection was asked for by " + requester + " (it holds the "
                            + "Heap_lock: it was allocating, or called System.gc()).", null));
        }
        if (vmState.startsWith("at safepoint") || vmState.startsWith("synchronizing")) {
            f.add(new Finding(Severity.INFO, "The JVM was " + vmState.replace(" (normal execution)", ""),
                    "All Java threads were being stopped for JVM work (GC, deoptimization, class redefinition)"
                            + (op == null ? "." : ": " + withThreadNames(op) + "."), null));
        }
        if (!activeCompiles.isEmpty() && probType == 'J') {
            f.add(new Finding(Severity.INFO, "Methods being compiled at the crash", String.join("; ", activeCompiles), null));
        }
        // native libraries that aren't the JDK's or the system's
        List<String> foreign = new ArrayList<>();
        for (String lib : libraries) {
            if (!isJdkLibrary(lib, home) && !isSystemLibrary(lib) && !lib.toLowerCase(Locale.ROOT).endsWith(".exe")
                    && !lib.endsWith("/java")) {
                String n = lib.replace('\\', '/');
                foreign.add(n.substring(n.lastIndexOf('/') + 1));
            }
        }
        if (!foreign.isEmpty()) {
            f.add(new Finding(probType == 'C' || corruption ? Severity.WARN : Severity.INFO,
                    foreign.size() + " native " + (foreign.size() == 1 ? "library" : "libraries")
                            + " not from the JDK or the system",
                    "Loaded in the process: " + names(foreign, 12) + ". Native code like this is the most common "
                            + "cause of JVM crashes.", null));
        }
        List<String> loaded = recentlyLoaded(home);
        if (!loaded.isEmpty()) {
            f.add(new Finding(Severity.WARN, "Loaded just before the crash: " + names(loaded, 5),
                    "A native library loaded shortly before a crash is a good suspect.", null));
        }
        List<String> agents = new ArrayList<>();
        Matcher am = Pattern.compile("-(agentpath|agentlib|javaagent):([^\\s=]+)").matcher(jvmArgs + " " + commandLine);
        while (am.find()) {
            String a = am.group(2);
            String n = a.replace('\\', '/');
            n = n.substring(n.lastIndexOf('/') + 1);
            if (!agents.contains(n)) {
                agents.add(n);
            }
        }
        for (String a : jvmtiAgents) {
            if (!agents.contains(a)) {
                agents.add(a);
            }
        }
        List<String> redefined = events.getOrDefault("Classes redefined", List.of());
        boolean inJvmti = problematic != null && problematic.contains("Jvmti")
                || nativeFrames.stream().limit(8).anyMatch(x -> x.contains("Jvmti") || x.contains("JVMTI"));
        if (inJvmti && !agents.isEmpty()) {
            f.add(new Finding(Severity.ERROR, "The crash is in work done for an agent: " + names(agents, 4),
                    "The crash is in the JVM's tool interface (JVMTI), which agents use (to sample stacks, change "
                            + "classes, watch objects). The agent most likely asked for that work.",
                    "Update the agent, or run without it to confirm."));
        } else if (!agents.isEmpty() || !redefined.isEmpty()) {
            f.add(new Finding(probType == 'V' || probType == 'J' ? Severity.WARN : Severity.INFO,
                    agents.isEmpty() ? redefined.size() + " classes were redefined" : "Agents in the JVM: " + names(agents, 6),
                    "Agents (monitoring, profilers, instrumentation) change classes and run native code inside the "
                            + "JVM; they are a known source of crashes." + (redefined.isEmpty() ? "" : " "
                            + redefined.size() + " class redefinitions were recorded."),
                    "If it keeps crashing, try a run without them, or update them."));
        }
        // exceptions the crashed thread recorded just before
        List<String> internal = events.getOrDefault("Internal exceptions", List.of());
        Thrown last = lastOnCrashedThread();
        String grave = null;
        for (String e : internal) {
            if (e.contains("OutOfMemoryError") || e.contains("StackOverflowError") || e.contains("InternalError")) {
                grave = e;
            }
        }
        if (last != null && !(grave != null && grave.equals(last.event()))) {
            justBefore(f, last);
        }
        if (grave != null) {
            Matcher m = Pattern.compile("<a '([\\w/$]+)'").matcher(grave);
            String type = m.find() ? m.group(1).replace('/', '.') : "an error";
            f.add(new Finding(Severity.WARN, "Shortly before: " + type.substring(type.lastIndexOf('.') + 1),
                    cut(grave, 300), type.endsWith("StackOverflowError")
                    ? "Deep recursion near native code can end in a crash instead of an error: check the recursion."
                    : type.endsWith("OutOfMemoryError") ? "The program was short of memory before it crashed." : null));
        }
        if (!states.isEmpty()) {
            StringBuilder b = new StringBuilder();
            states.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                    .forEach(e -> b.append(b.isEmpty() ? "" : ", ").append(e.getValue()).append(' ').append(e.getKey()));
            int java = 0;
            for (JThread t : threads) {
                if (t.kind().equals("JavaThread")) {
                    java++;
                }
            }
            int jvm = threads.size() - java;
            f.add(new Finding(Severity.INFO, java + (java == 1 ? " Java thread and " : " Java threads and ") + jvm
                    + (jvm == 1 ? " JVM thread" : " JVM threads"),
                    "Java threads: " + b + ".", null));
        }
    }

    private boolean vmThreadCrash() {
        return currentKind.equals("VMThread");
    }

    private String threadName(String address) {
        for (JThread t : threads) {
            if (t.address().equalsIgnoreCase(address)) {
                return "\"" + t.name() + "\"" + (t.current() ? " (the crashed thread)" : "");
            }
        }
        return address;
    }

    private static String names(List<String> l, int max) {
        Set<String> u = new LinkedHashSet<>(l);
        List<String> out = new ArrayList<>(u);
        String s = String.join(", ", out.subList(0, Math.min(max, out.size())));
        return out.size() > max ? s + " and " + (out.size() - max) + " more" : s;
    }

    /** Non-JDK native libraries loaded in the last few seconds before the crash. */
    private List<String> recentlyLoaded(String home) {
        List<String> out = new ArrayList<>();
        List<String> dll = events.getOrDefault("Dll operation events", List.of());
        for (String e : dll) {
            Matcher m = Pattern.compile("^Event: ([0-9.]+) Loaded shared library (.+)$").matcher(e);
            if (m.find()) {
                double t = Double.parseDouble(m.group(1));
                String lib = m.group(2).strip();
                if (elapsedSeconds > 0 && elapsedSeconds - t <= 5 && elapsedSeconds > 10 && !isJdkLibrary(lib, home)
                        && !isSystemLibrary(lib)) {
                    String n = lib.replace('\\', '/');
                    out.add(n.substring(n.lastIndexOf('/') + 1));
                }
            }
        }
        return out;
    }

    private void oom(List<Finding> f) {
        String d = oomDetail == null ? "" : oomDetail;
        List<String> why = new ArrayList<>();
        long xmxK = flags.containsKey("MaxHeapSize") ? parseLong(flags.get("MaxHeapSize")) / 1024 : -1;
        if (containerLimitK > 0) {
            why.add("the container's memory limit is " + kb(containerLimitK) + (xmxK > 0 ? " and the heap may grow to "
                    + kb(xmxK) : ""));
        }
        if (physicalK > 0 && physicalFreeK >= 0) {
            why.add(kb(physicalFreeK) + " of " + kb(physicalK) + " physical memory was free"
                    + (swapK >= 0 ? ", swap " + kb(swapFreeK) + " of " + kb(swapK) + " free" : ""));
        }
        int javaThreads = 0;
        for (JThread t : threads) {
            if (t.kind().equals("JavaThread")) {
                javaThreads++;
            }
        }
        boolean threadsCause = d.contains("thread") || javaThreads > 2000;
        f.add(new Finding(Severity.ERROR, "Out of native memory" + (oomDetail == null ? "" : ": " + d),
                "The JVM asked the operating system for memory and didn't get it" + (why.isEmpty() ? "." : ": "
                        + String.join("; ", why) + "."),
                threadsCause ? "Too many threads: " + javaThreads + " Java threads. Find what creates them, or lower "
                        + "the stack size (-Xss)."
                        : containerLimitK > 0 && xmxK > containerLimitK * 0.75
                        ? "The heap leaves too little of the container's memory for the rest (metaspace, threads, "
                        + "direct buffers, code): lower -Xmx (or -XX:MaxRAMPercentage) to about 75% of the limit, "
                        + "or raise the limit."
                        : "Give the machine more memory or swap, run fewer processes on it, or lower -Xmx; with "
                        + "-XX:NativeMemoryTracking=summary, jcmd <pid> VM.native_memory shows what grows."));
        if (xmxK > 0 && physicalK > 0 && xmxK > physicalK * 0.9) {
            f.add(new Finding(Severity.WARN, "The heap may grow to " + kb(xmxK) + " on a machine with " + kb(physicalK),
                    "There's no room left for the JVM's other memory.", "Lower -Xmx."));
        }
        if (nativeFrames.stream().anyMatch(x -> x.contains("CollectedHeap::expand") || x.contains("Heap::expand")
                || x.contains("VirtualSpace::commit") || x.contains("VirtualSpace::expand"))) {
            f.add(new Finding(Severity.WARN, "It failed while growing the Java heap",
                    "The heap may grow to -Xmx, but the system couldn't give it the memory when it tried.",
                    "Set -Xmx to what the machine (or container) can really give, with room for the rest."));
        }
    }

    /** "requested by thread 0x…" with the thread's name. */
    private String withThreadNames(String text) {
        Matcher m = Pattern.compile("thread (0x[0-9a-fA-F]+)").matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement("thread " + threadName(m.group(1))));
        }
        m.appendTail(b);
        return b.toString();
    }

    /**
     * The program's own code that used a native library: the first Java frame, not the JDK's,
     * in another package than the library's Java side (com.acme… calling org.rocksdb…); null if none.
     */
    private String programCaller() {
        String libPackage = null;
        for (String raw : stack()) {
            String cls = javaClass(raw);
            if (cls == null || isJdkClass(cls)) {
                continue;
            }
            String pkg = topPackage(cls);
            if (libPackage == null) {
                libPackage = pkg;
            } else if (!pkg.equals(libPackage)) {
                return javaMethod(raw);
            }
        }
        return null;
    }

    /** The first two parts of a class's package ("com.acme" for com.acme.orders.Store); "" for none. */
    private static String topPackage(String cls) {
        int dot = cls.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String[] p = cls.substring(0, dot).split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : p[0];
    }

    /** The path the header says the core dump goes to; null if none. */
    String coreDumpPath() {
        Matcher m = Pattern.compile("\\(or dumping to (\\S+?)\\)").matcher(coreDump);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("Default location: ([A-Za-z]:\\\\\\S+|/\\S+)\\s*$").matcher(coreDump);
        return m.find() ? m.group(1) : null;
    }

    /** The mapped file (not a library) holding an address; null if none or not known. */
    String mappedFileAt(String hex) {
        if (hex == null) {
            return null;
        }
        long a;
        try {
            a = Long.parseUnsignedLong(hex.substring(2), 16);
        } catch (RuntimeException e) {
            return null;
        }
        for (Mapping m : mappings) {
            if (Long.compareUnsigned(a, m.start()) >= 0 && Long.compareUnsigned(a, m.end()) < 0) {
                String n = m.path().substring(m.path().lastIndexOf('/') + 1);
                return n.contains(".so") || n.equals("java") ? null : m.path();
            }
        }
        return null;
    }

    /** An exception the JVM recorded (Internal exceptions): when, on which thread, what. */
    record Thrown(double time, String thread, String type, String message, String event) {}

    private static final Pattern THROWN = Pattern.compile(
            "^Event: ([0-9.]+) Thread (0x[0-9a-fA-F]+) Exception <a '([\\w/$]+)'(?:\\{0x[0-9a-fA-F]+\\})?(?:: (.*?))?> \\(");

    List<Thrown> thrown() {
        List<Thrown> out = new ArrayList<>();
        for (String e : events.getOrDefault("Internal exceptions", List.of())) {
            Matcher m = THROWN.matcher(e);
            if (m.find()) {
                out.add(new Thrown(Double.parseDouble(m.group(1)), m.group(2), m.group(3).replace('/', '.'),
                        m.group(4) == null ? null : m.group(4).strip(), e));
            }
        }
        return out;
    }

    /** The JVM's own lookups (method handles, class loading) throw these all the time: not a clue. */
    private static boolean noise(Thrown t) {
        return t.event().contains("linkResolver.cpp") || t.type().equals("java.lang.ClassNotFoundException")
                || t.message() != null && t.message().contains("java.lang.invoke.");
    }

    /** The last exception on the crashed thread, within a minute before the crash; null if none. */
    private Thrown lastOnCrashedThread() {
        if (currentAddress.isEmpty()) {
            return null;
        }
        Thrown last = null;
        for (Thrown t : thrown()) {
            if (t.thread().equalsIgnoreCase(currentAddress) && !noise(t)
                    && (elapsedSeconds < 0 || elapsedSeconds - t.time() <= 60)) {
                last = t;
            }
        }
        return last;
    }

    private void justBefore(List<Finding> f, Thrown t) {
        String what = t.type() + (t.message() == null || t.message().isEmpty() ? "" : ": " + cut(t.message(), 200));
        String ago = elapsedSeconds >= 0 ? String.format(Locale.ROOT, " (%.0f s before the crash)",
                Math.max(0, elapsedSeconds - t.time())) : "";
        List<String> others = new ArrayList<>();
        for (Thrown o : thrown()) {
            if (!o.thread().equalsIgnoreCase(currentAddress) && o.type().equals(t.type())
                    && java.util.Objects.equals(o.message(), t.message())) {
                String n = threadName(o.thread());
                if (!others.contains(n)) {
                    others.add(n);
                }
            }
        }
        boolean nativeCrash = problematic != null && problematic.startsWith("C");
        f.add(new Finding(Severity.WARN, "Just before, this thread got " + what,
                "The crashed thread recorded this exception" + ago + ". It says what the thread was working on"
                        + (nativeCrash ? ": bad input (a damaged or truncated file) may have set off the crash in the "
                        + "native code." : ".")
                        + (others.isEmpty() ? "" : " The same exception was also thrown on " + names(others, 4) + "."),
                "Check what this thread was working on at that moment (the log around that time), validate that "
                        + "input, and retry with the same input to reproduce the crash."));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static String kb(long k) {
        return WindowsCrash.size(k * 1024);
    }

    /** The surroundings: heap, memory, JDK age, core dumps, a quick crash. */
    private void setting(List<Finding> f) {
        if (heapTotalK > 0 && heapUsedK >= 0 && heapUsedK > heapTotalK * 0.95) {
            f.add(new Finding(Severity.WARN, "The heap was full", "Used " + kb(heapUsedK) + " of " + kb(heapTotalK) + ".",
                    "The program was short of heap: look at what fills it (a heap dump)."));
        }
        if (containerLimitK > 0 && !outOfMemory) {
            long xmxK = flags.containsKey("MaxHeapSize") ? parseLong(flags.get("MaxHeapSize")) / 1024 : -1;
            if (xmxK > containerLimitK * 0.9) {
                f.add(new Finding(Severity.WARN, "The heap may be as big as the container",
                        "Heap up to " + kb(xmxK) + ", container limit " + kb(containerLimitK) + ".",
                        "Keep -Xmx to about 75% of the container's memory."));
            }
        }
        if (builtOn != null) {
            long months = ChronoUnit.MONTHS.between(builtOn, LocalDate.now());
            if (months >= 12) {
                f.add(new Finding(Severity.INFO, "This JDK was built " + (months / 12) + (months >= 24 ? " years" : " year")
                        + " ago (" + builtOn + ")", "Later updates of the same version fix many crashes.",
                        "Update to the latest update of Java " + javaFeature() + "."));
            }
        }
        if (elapsedSeconds >= 0 && elapsedSeconds < 10) {
            f.add(new Finding(Severity.INFO, "It crashed while starting (" + String.format(Locale.ROOT, "%.1f", elapsedSeconds)
                    + " s after start)", "Crashes at start are often a library or agent that doesn't fit this JDK or "
                    + "system.", null));
        }
        if (coreDump.startsWith("Core dump will be written")) {
            String path = coreDumpPath();
            boolean systemd = coreDump.contains("systemd-coredump");
            boolean apport = coreDump.contains("apport");
            boolean windows = path != null && path.toLowerCase(Locale.ROOT).endsWith(".mdmp");
            f.add(new Finding(Severity.INFO, path == null ? "A core dump should have been written"
                    : "A core dump should be at " + path,
                    "It holds the native stack of every thread at the crash, not just the crashed one"
                            + (windows ? ": open the .mdmp in Logsift to see them." : ".")
                            + (systemd ? " systemd-coredump may have kept it instead of that file: coredumpctl list"
                            + (pid.isEmpty() ? "" : " " + pid) + " shows it." : "")
                            + (apport ? " Ubuntu's apport may have kept it under /var/crash instead." : ""),
                    windows ? "Open " + path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1)
                            + " in Logsift." : null));
        }
        if (coreDump.startsWith("No core dump") || coreDump.contains("disabled")) {
            f.add(new Finding(Severity.INFO, "No core dump was written",
                    "A core dump (on Windows, a minidump) holds the native stacks of every thread.",
                    "Next time: ulimit -c unlimited before starting Java (Linux, macOS); on Windows, "
                            + "-XX:+CreateCoredumpOnCrash (the default) writes a .mdmp file Logsift can open."));
        }
    }

    private String javaFeature() {
        Matcher m = Pattern.compile("\\((\\d+)[.+)]").matcher(jreVersion);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("1\\.(\\d+)\\.").matcher(jreVersion);
        return m.find() ? m.group(1) : "this version";
    }

    /** A Windows dump the JVM wrote, named in the header; null if none. */
    public Path coreDumpFile(Path hsErrFile) {
        Matcher m = Pattern.compile("([A-Za-z]:\\\\[^\\s]+\\.mdmp|/\\S+\\.mdmp)").matcher(coreDump + " " + text.substring(0,
                Math.min(text.length(), 4000)));
        if (m.find()) {
            try {
                Path p = Path.of(m.group(1));
                if (Files.isRegularFile(p)) {
                    return p;
                }
            } catch (RuntimeException e) {
                // a path from another system
            }
        }
        if (hsErrFile != null && !pid.isEmpty()) {
            Path dir = hsErrFile.toAbsolutePath().getParent();
            if (dir != null) {
                for (String n : new String[] {"hs_err_pid" + pid + ".mdmp", "core." + pid, "java_pid" + pid + ".mdmp"}) {
                    Path p = dir.resolve(n);
                    if (Files.isRegularFile(p)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private List<Fact> facts() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!jreVersion.isEmpty()) {
            m.put("Java", jreVersion);
        }
        if (!vmVersion.isEmpty()) {
            m.put("JVM", vmVersion);
        }
        String gc = gcName();
        if (gc != null) {
            m.put("Garbage collector", gc);
        }
        if (!javaCommand.isEmpty()) {
            m.put("Program", javaCommand);
        }
        if (!jvmArgs.isEmpty()) {
            m.put("JVM options", jvmArgs);
        }
        if (!pid.isEmpty()) {
            m.put("Process id", pid);
        }
        if (!time.isEmpty()) {
            m.put("Crashed at", time);
        }
        if (elapsedSeconds >= 0) {
            m.put("Running for", WindowsCrash.duration((long) elapsedSeconds));
        }
        if (!currentLine.isEmpty()) {
            m.put("Crashed thread", currentLine.replaceAll("\\s{2,}", " "));
        }
        if (!siginfo.isEmpty()) {
            m.put("Signal", siginfo);
        }
        if (!heapLine.isEmpty()) {
            m.put("Heap", heapLine.replaceAll("\\s{2,}", " ")
                    + (heapTotalK > 0 ? " (" + Math.round(100.0 * heapUsedK / heapTotalK) + "% used)" : ""));
        }
        if (!metaspaceLine.isEmpty()) {
            m.put("Metaspace", metaspaceLine);
        }
        if (!host.isEmpty()) {
            m.put("Host", host);
        }
        if (!os.isEmpty() || !uname.isEmpty()) {
            m.put("System", !os.isEmpty() ? os : uname);
        }
        if (!memory.isEmpty()) {
            m.put("Memory", memory);
        }
        if (containerLimitK > 0) {
            m.put("Container memory limit", kb(containerLimitK));
        }
        if (!rlimit.isEmpty()) {
            m.put("Limits", rlimit);
        }
        if (!vmState.isEmpty()) {
            m.put("VM state", vmState);
        }
        if (!coreDump.isEmpty()) {
            m.put("Core dump", coreDump);
        }
        List<Fact> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Fact(k, v)));
        return out;
    }

    private String gcName() {
        String v = vmVersion.toLowerCase(Locale.ROOT);
        for (String g : new String[] {"g1 gc", "parallel gc", "serial gc", "z gc", "zgc", "shenandoah gc", "epsilon gc", "cms gc"}) {
            if (v.contains(g)) {
                return g.replace(" gc", "").toUpperCase(Locale.ROOT).replace("SHENANDOAH", "Shenandoah")
                        .replace("PARALLEL", "Parallel").replace("SERIAL", "Serial").replace("EPSILON", "Epsilon");
            }
        }
        if (flags.containsKey("UseG1GC") && "true".equals(flags.get("UseG1GC"))) {
            return "G1";
        }
        if ("true".equals(flags.get("UseZGC"))) {
            return "ZGC";
        }
        if ("true".equals(flags.get("UseParallelGC"))) {
            return "Parallel";
        }
        return null;
    }

    private List<CrashReport.Section> sections() {
        List<CrashReport.Section> s = new ArrayList<>();
        if (compileTask != null) {
            s.add(new CrashReport.Section("Being compiled", compileTask));
        }
        if (!javaFrames.isEmpty() && !nativeFrames.isEmpty()) {
            s.add(new CrashReport.Section("Java frames of the crashed thread", String.join("\n", javaFrames)));
        }
        StringBuilder args = new StringBuilder();
        if (!commandLine.isEmpty()) {
            args.append("Command line: ").append(commandLine).append('\n');
        }
        if (!jvmArgs.isEmpty()) {
            args.append("JVM options: ").append(jvmArgs).append('\n');
        }
        if (!javaCommand.isEmpty()) {
            args.append("Main: ").append(javaCommand).append('\n');
        }
        if (!classPath.isEmpty()) {
            args.append("Class path: ").append(classPath).append('\n');
        }
        if (!args.isEmpty()) {
            s.add(new CrashReport.Section("How it was started", args.toString().strip()));
        }
        for (Map.Entry<String, List<String>> e : events.entrySet()) {
            if (!e.getValue().isEmpty()) {
                s.add(new CrashReport.Section(e.getKey(), String.join("\n", e.getValue())));
            }
        }
        if (!environment.isEmpty()) {
            s.add(new CrashReport.Section("Environment variables", String.join("\n", environment)));
        }
        return s;
    }
}
