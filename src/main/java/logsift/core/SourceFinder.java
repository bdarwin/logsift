package logsift.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the source file of a stack frame in the user's source folders: {@code
 * com.acme.shop.PriceCalculator (PriceCalculator.java:88)} is {@code
 * …/com/acme/shop/PriceCalculator.java}, and {@code File "/app/orders/payment.py"} (a path on
 * the server) is the {@code orders/payment.py} in a source folder. The folders are looked
 * through once and remembered until they change.
 */
public final class SourceFinder {

    /** Folders that hold no source of the program (build output, dependencies, tools). */
    private static final Set<String> SKIP = Set.of(".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", "build",
            "target", "out", "node_modules", ".venv", "venv", "__pycache__", ".tox", ".mypy_cache",
            ".pytest_cache", "dist", ".eggs", "site-packages", ".next", "obj");

    private static final Set<String> EXTENSIONS = Set.of("java", "kt", "kts", "scala", "groovy", "py", "pyx",
            "js", "ts", "go", "rb", "cs", "php");

    /** Most files remembered (a home folder by mistake must not take all memory). */
    static final int MAX_FILES = 400_000;

    private final List<Path> roots;
    private Map<String, List<Path>> byName; // file name -> files with it
    private boolean truncated;

    public SourceFinder(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    public List<Path> roots() {
        return roots;
    }

    /** True when the folders had more files than are remembered. */
    public synchronized boolean isTruncated() {
        index();
        return truncated;
    }

    /** The source file of a frame, or null if none of the folders has it. */
    public synchronized Path find(Exceptions.Frame f) {
        index();
        if (f.cls() != null) {
            return findJava(f);
        }
        return f.file() == null ? null : findByPath(f.file());
    }

    private Path findJava(Exceptions.Frame f) {
        String cls = f.cls();
        int dot = cls.lastIndexOf('.');
        String pkg = dot < 0 ? "" : cls.substring(0, dot);
        String simple = cls.substring(dot + 1);
        int inner = simple.indexOf('$');
        if (inner > 0) {
            simple = simple.substring(0, inner);
        }
        List<String> names = new ArrayList<>();
        if (f.file() != null && f.file().contains(".")) {
            names.add(f.file());
        }
        for (String ext : new String[] {".java", ".kt", ".scala", ".groovy"}) {
            names.add(simple + ext);
        }
        String dir = pkg.replace('.', '/');
        for (String name : names) {
            List<Path> found = byName.getOrDefault(name, List.of());
            Path best = null;
            for (Path p : found) {
                String s = p.toString().replace('\\', '/');
                if (dir.isEmpty() || s.endsWith("/" + dir + "/" + name)) {
                    if (best == null || s.contains("/main/") && !best.toString().contains("main")) {
                        best = p;
                    }
                }
            }
            if (best != null) {
                return best;
            }
            if (found.size() == 1) {
                return found.get(0); // a Kotlin file with another package layout
            }
        }
        return null;
    }

    /** The file whose path ends with the most folders in common with a path from the server. */
    Path findByPath(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        String name = parts[parts.length - 1];
        List<Path> found = byName.getOrDefault(name, List.of());
        Path best = null;
        int bestScore = -1;
        for (Path p : found) {
            int score = 0;
            Path q = p.getParent();
            for (int i = parts.length - 2; i >= 0 && q != null && q.getFileName() != null; i--) {
                if (!q.getFileName().toString().equals(parts[i])) {
                    break;
                }
                score++;
                q = q.getParent();
            }
            if (score > bestScore) {
                best = p;
                bestScore = score;
            }
        }
        return best;
    }

    private void index() {
        if (byName != null) {
            return;
        }
        Map<String, List<Path>> map = new HashMap<>();
        int[] count = {0};
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        String n = d.getFileName() == null ? "" : d.getFileName().toString();
                        return !d.equals(root) && (SKIP.contains(n) || n.startsWith(".")) ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                        String n = file.getFileName().toString();
                        int dot = n.lastIndexOf('.');
                        if (dot > 0 && EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                            map.computeIfAbsent(n, k -> new ArrayList<>(1)).add(file);
                            if (++count[0] >= MAX_FILES) {
                                truncated = true;
                                return FileVisitResult.TERMINATE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // a folder that can't be read has no sources for us
            }
        }
        byName = map;
    }
}
