package logsift.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logsift updating itself: from GitHub's releases, or its snapshot builds (the latest of main, to
 * try fixes first). The file for this computer is downloaded over HTTPS with its certificate
 * checked (never otherwise), from the release's own address (never one read from an answer), and
 * checked against the release's SHA-256 sums; then made ready beside the app, and put in place by
 * a small script once Logsift has quit, which starts it again.
 */
public final class SelfUpdate {
    private SelfUpdate() {
    }

    static final String API = "https://api.github.com/repos/bdarwin/logsift";
    static final String DOWNLOAD = "https://github.com/bdarwin/logsift/releases/download/";
    static final String PAGES = "https://github.com/bdarwin/logsift/releases/tag/";
    /** The file of SHA-256 sums each release has (from 1.4.1 on). */
    public static final String SUMS = "SHA256SUMS.txt";
    /** The snapshot builds' release: the latest of main, made again on each push. */
    public static final String SNAPSHOT = "snapshot";

    /** Hosts a download may come from (GitHub sends assets on to its own storage). */
    static final List<String> HOSTS = List.of("github.com", "api.github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com", "github-releases.githubusercontent.com");

    // ---------------------------------------------------------------- this build

    /** Where updates come from. */
    public enum Channel {
        RELEASES("releases"), SNAPSHOTS("snapshots");

        public final String key;

        Channel(String key) {
            this.key = key;
        }

        public static Channel of(String key, Channel or) {
            for (Channel c : values()) {
                if (c.key.equals(key)) {
                    return c;
                }
            }
            return or;
        }
    }

    /**
     * What this Logsift is.
     *
     * @param version its version
     * @param kind    release, snapshot, beta, or dev (built here: never updated)
     * @param commit  the commit it was built from, or ""
     */
    public record Build(String version, String kind, String commit) {
        /** This one: from its jar's manifest; {@code logsift.build} and {@code logsift.commit} for tests. */
        public static Build current() {
            String kind = System.getProperty("logsift.build");
            String commit = System.getProperty("logsift.commit");
            if (kind == null || commit == null) {
                java.util.jar.Attributes a = manifest();
                if (kind == null) {
                    kind = a == null ? null : a.getValue("Logsift-Build");
                }
                if (commit == null) {
                    commit = a == null ? null : a.getValue("Logsift-Commit");
                }
            }
            return new Build(Version.current(), kind == null || kind.isBlank() ? "dev" : kind,
                    commit == null ? "" : commit.strip());
        }

        /** Updates come from its own kind, unless the person chose. */
        public Channel defaultChannel() {
            return kind.equals("snapshot") ? Channel.SNAPSHOTS : Channel.RELEASES;
        }

        /** A build that can update itself: a release or a snapshot (a beta, or one built here, can't). */
        public boolean updates() {
            return kind.equals("release") || kind.equals("snapshot");
        }

        public String shortCommit() {
            return commit.length() > 7 ? commit.substring(0, 7) : commit;
        }
    }

    private static java.util.jar.Attributes manifest() {
        try {
            var src = SelfUpdate.class.getProtectionDomain().getCodeSource();
            if (src == null) {
                return null;
            }
            Path p = Path.of(src.getLocation().toURI());
            if (!Files.isRegularFile(p)) {
                return null;
            }
            try (java.util.jar.JarFile j = new java.util.jar.JarFile(p.toFile())) {
                return j.getManifest() == null ? null : j.getManifest().getMainAttributes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- what's new

    /**
     * A newer Logsift to update to.
     *
     * @param tag     its release: v1.4.1, or snapshot
     * @param version its version (a snapshot: the version it was built as)
     * @param commit  its commit (a snapshot's), or ""
     * @param date    when it was published, or null
     */
    public record Available(String tag, String version, String commit, Instant date) {
        public boolean snapshot() {
            return tag.equals(SNAPSHOT);
        }

        /** "Logsift 1.4.1", or "The snapshot of 7 Nov (abc1234)". */
        public String title() {
            if (!snapshot()) {
                return "Logsift " + version;
            }
            String when = date == null ? "" : java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
                    .format(date.atZone(java.time.ZoneId.systemDefault())) + " ";
            return "The snapshot build of " + when + "(" + (commit.length() > 7 ? commit.substring(0, 7) : commit) + ")";
        }

        /** Its page on GitHub. */
        public String page() {
            return PAGES + tag;
        }
    }

    /** Whether {@code a} is newer than {@code b}: a later release, or a snapshot of another commit. */
    public static boolean newer(Available a, Build b) {
        return a.snapshot() ? !a.commit().equalsIgnoreCase(b.commit()) : Version.isNewer(a.version(), b.version());
    }

    /** Where to ask: GitHub, or {@code logsift.updateBase} (tests: a server here). */
    static String base() {
        String b = System.getProperty("logsift.updateBase");
        return b == null || b.isBlank() ? null : b.replaceAll("/+$", "");
    }

    private static void allowed() throws IOException {
        if (!enabled()) {
            throw new IOException("Update checks are turned off.");
        }
    }

    static String api() {
        String b = base();
        return b == null ? API : b + "/repos/bdarwin/logsift";
    }

    /** The repository's web pages (not its API). */
    static String web() {
        String b = base();
        return b == null ? "https://github.com/bdarwin/logsift" : b + "/bdarwin/logsift";
    }

    static {
        Proxies.init(); // an office's proxy (the app does it first thing, before anything connects)
    }

    static String download() {
        String b = base();
        return b == null ? DOWNLOAD : b + "/bdarwin/logsift/releases/download/";
    }

    /** Whether updates can be looked for at all ({@code logsift.updateUrl=none} turns them off). */
    public static boolean enabled() {
        return UpdateChecker.url() != null || System.getProperty("logsift.updateBase") != null;
    }

    /** What's newer than {@code b} on {@code channel}, if anything. */
    public static Optional<Available> check(Channel channel, Build b) throws IOException {
        allowed();
        try {
            return checkNow(channel, b);
        } catch (IOException e) {
            throw Proxies.explain(e, URI.create(web()), "Logsift");
        }
    }

    private static Optional<Available> checkNow(Channel channel, Build b) throws IOException {
        // GitHub's web pages and downloads first: its API answers a network only 60 times an hour,
        // and at an office everyone shares one network
        if (channel == Channel.SNAPSHOTS) {
            String build = peek(download() + SNAPSHOT + "/" + BUILD);
            if (build != null) {
                return snapshot(build(build, "commit"), build(build, "date"), b);
            }
            String json = peek(api() + "/releases/tags/" + SNAPSHOT); // a snapshot built before BUILD.txt
            return json == null ? Optional.empty() : snapshot(json, b);
        }
        String latest = latestTag();
        if (latest != null) {
            String v = latest.startsWith("v") ? latest.substring(1) : latest;
            return UpdateChecker.isPlainVersion(v) && Version.isNewer(v, b.version())
                    ? Optional.of(new Available("v" + v, v, "", null)) : Optional.empty();
        }
        String json = peek(api() + "/releases/latest");
        if (json == null) {
            return Optional.empty();
        }
        String tag = field(json, "tag_name");
        if (tag == null) {
            return Optional.empty();
        }
        String v = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!UpdateChecker.isPlainVersion(v) || !Version.isNewer(v, b.version())) {
            return Optional.empty();
        }
        return Optional.of(new Available("v" + v, v, "", instant(field(json, "published_at"))));
    }

    /** The snapshot release's answer: newer when it was built from another commit than this one. */
    static Optional<Available> snapshot(String json, Build b) {
        return snapshot(field(json, "target_commitish"), field(json, "published_at"), b);
    }

    private static Optional<Available> snapshot(String commit, String date, Build b) {
        if (commit == null || !commit.matches("[0-9a-f]{7,40}") || commit.equalsIgnoreCase(b.commit())) {
            return Optional.empty();
        }
        return Optional.of(new Available(SNAPSHOT, "", commit, instant(date)));
    }

    /** The file beside each build saying what it was built from: "commit=…" and "date=…" lines. */
    static final String BUILD = "BUILD.txt";

    private static String build(String text, String key) {
        for (String line : text.split("\\R")) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).strip();
            }
        }
        return null;
    }

    /**
     * The latest release's tag, from where GitHub's page for it leads ({@code /releases/latest} to
     * {@code /releases/tag/v1.4.1}); null when it doesn't say.
     */
    static String latestTag() throws IOException {
        String to;
        try {
            to = leadsTo(web() + "/releases/latest", false);
        } catch (IOException e) {
            if (!Tls.isTrustProblem(e)) {
                throw e;
            }
            to = leadsTo(web() + "/releases/latest", true);
        }
        int at = to == null ? -1 : to.indexOf("/releases/tag/");
        if (at < 0) {
            return null;
        }
        String tag = to.substring(at + "/releases/tag/".length()).replaceAll("[?#].*$", "");
        return tag.matches("v?[0-9][0-9A-Za-z.+-]*") ? tag : null;
    }

    private static String leadsTo(String url, boolean anyCertificate) throws IOException {
        URI u = URI.create(url);
        check(u);
        HttpURLConnection c = connect(u, anyCertificate);
        try {
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String l = c.getHeaderField("Location");
                return l == null ? null : u.resolve(l).toString();
            }
            if (code == 200 || code == 404) {
                return null;
            }
            throw refused(c, code);
        } finally {
            c.disconnect();
        }
    }

    /** A web page where a file was asked for: a proxy's "blocked" page, or a sign-in page. */
    static boolean webPage(HttpURLConnection c) {
        String t = c.getContentType();
        return t != null && t.toLowerCase(Locale.ROOT).startsWith("text/html");
    }

    static IOException blocked(String file, String page) {
        return new IOException("Your network sent a web page instead of " + file + " (an office proxy that blocks "
                + "downloads, or wants you to sign in on a page, does this). Download it in your browser from " + page
                + ", or ask your IT team to allow github.com.");
    }

    /** What GitHub's (or a proxy's) refusal means, in words. */
    static IOException refused(HttpURLConnection c, int code) {
        if (code == 407) {
            URI u;
            try {
                u = c.getURL().toURI();
            } catch (java.net.URISyntaxException e) {
                u = null;
            }
            return Proxies.signInNeeded(u == null ? null : Proxies.proxyFor(u), "it", null);
        }
        if ((code == 403 || code == 429) && "0".equals(c.getHeaderField("X-RateLimit-Remaining"))) {
            String when = "";
            try {
                long reset = Long.parseLong(c.getHeaderField("X-RateLimit-Reset").strip());
                when = " after " + java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        .format(Instant.ofEpochSecond(reset).atZone(java.time.ZoneId.systemDefault()));
            } catch (RuntimeException e) {
                // no time given
            }
            return new IOException("GitHub answers only 60 checks an hour from one network, and yours (an office "
                    + "shares one) has had them. Try again" + (when.isEmpty() ? " in an hour" : when));
        }
        return new IOException("GitHub answered " + code);
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\/", "/") : null;
    }

    // ---------------------------------------------------------------- this computer

    /** How this Logsift was installed, so how it's updated. */
    public enum Kind {
        /** Logsift.app (from the .dmg): the new one goes in its place. */
        MAC_APP,
        /** Installed with the .msi: the new .msi is run. */
        WINDOWS_MSI,
        /** A folder (the Windows .zip, the Linux .tar.gz): the new folder goes in its place. */
        FOLDER,
        /** The plain zip for any Java 21: its folder goes in its place. */
        JAVA_ZIP,
        /** Installed with the .deb, or somewhere this user can't write: downloaded, and shown, to install by hand. */
        BY_HAND
    }

    /**
     * Where this Logsift is and what to download for it.
     *
     * @param kind     how it's updated
     * @param app      the app (Logsift.app, the Logsift folder, the unpacked zip's folder)
     * @param launcher what starts it again (inside {@code app})
     * @param target   the platform: macos-arm64, windows-x64, linux-x64…
     * @param ext      the file to download: dmg, msi, zip, tar.gz, deb
     */
    public record Install(Kind kind, Path app, Path launcher, String target, String ext) {
        /** The file of release {@code a} for this computer. */
        public String asset(Available a) {
            String label = a.snapshot() ? SNAPSHOT : a.version();
            return "Logsift-" + label + "-" + (kind == Kind.JAVA_ZIP ? "any-java21" : target) + "." + ext;
        }

        /** Put in place by Logsift (else: downloaded, and shown, to install by hand). */
        public boolean automatic() {
            return kind != Kind.BY_HAND;
        }
    }

    /** This Logsift's install, or null when it can't be updated (run from classes, an unknown computer). */
    public static Install install() {
        return install(System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                System.getProperty("jpackage.app-path"), codeSource(), System.getenv("LOCALAPPDATA"));
    }

    private static Path codeSource() {
        try {
            var src = SelfUpdate.class.getProtectionDomain().getCodeSource();
            return src == null ? null : Path.of(src.getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    /** The platform's name in the files' names, or null. */
    static String target(String os, String arch) {
        String o = os.toLowerCase(Locale.ROOT);
        String a = arch.toLowerCase(Locale.ROOT);
        String cpu = a.equals("aarch64") || a.equals("arm64") ? "arm64"
                : a.equals("amd64") || a.equals("x86_64") || a.equals("x64") ? "x64" : null;
        if (cpu == null) {
            return null;
        }
        String sys = o.contains("mac") ? "macos" : o.contains("win") ? "windows" : o.contains("linux") ? "linux" : null;
        return sys == null ? null : sys + "-" + cpu;
    }

    /**
     * @param appPath   the launcher jpackage started (its {@code jpackage.app-path}), or null
     * @param code      where Logsift's classes are (its jar), or null
     * @param localApps Windows' %LOCALAPPDATA% (where the .msi installs for one person), or null
     */
    static Install install(String os, String arch, String appPath, Path code, String localApps) {
        String target = target(os, arch);
        if (target == null) {
            return null;
        }
        if (appPath != null && !appPath.isBlank()) {
            Path launcher = Path.of(appPath).toAbsolutePath().normalize();
            if (target.startsWith("macos")) {
                // …/Logsift.app/Contents/MacOS/Logsift
                Path app = launcher.getParent() == null ? null : launcher.getParent().getParent();
                app = app == null ? null : app.getParent();
                if (app == null || !app.getFileName().toString().endsWith(".app")) {
                    return null;
                }
                return new Install(writable(app) ? Kind.MAC_APP : Kind.BY_HAND, app, launcher, target, "dmg");
            }
            if (target.startsWith("windows")) {
                // …\Logsift\Logsift.exe
                Path app = launcher.getParent();
                if (app == null) {
                    return null;
                }
                boolean msi = localApps != null && !localApps.isBlank()
                        && app.startsWith(Path.of(localApps).toAbsolutePath().normalize());
                if (msi) {
                    return new Install(Kind.WINDOWS_MSI, app, launcher, target, "msi");
                }
                return new Install(writable(app) ? Kind.FOLDER : Kind.BY_HAND, app, launcher, target, "zip");
            }
            // Linux: …/Logsift/bin/Logsift; the .deb puts it in /opt/logsift, only root may change it
            Path app = launcher.getParent() == null ? null : launcher.getParent().getParent();
            if (app == null) {
                return null;
            }
            if (app.startsWith("/opt") || app.startsWith("/usr") || !writable(app)) {
                return new Install(Kind.BY_HAND, app, launcher, target, target.equals("linux-x64") ? "deb" : "tar.gz");
            }
            return new Install(Kind.FOLDER, app, launcher, target, "tar.gz");
        }
        // the plain zip: …/logsift-1.4.1/lib/logsift-1.4.1.jar, started by …/bin/logsift
        if (code != null && Files.isRegularFile(code) && code.getParent() != null
                && code.getParent().getFileName().toString().equals("lib") && code.getParent().getParent() != null) {
            Path app = code.getParent().getParent();
            boolean windows = target.startsWith("windows");
            Path launcher = app.resolve("bin").resolve(windows ? "logsift.bat" : "logsift");
            if (!Files.isRegularFile(launcher)) {
                return null;
            }
            return new Install(writable(app) ? Kind.JAVA_ZIP : Kind.BY_HAND, app, launcher, target, "zip");
        }
        return null; // run from classes: built here
    }

    private static boolean writable(Path app) {
        Path parent = app.getParent();
        return Files.isWritable(app) && parent != null && Files.isWritable(parent);
    }

    // ---------------------------------------------------------------- downloading

    /** Whether to stop. */
    public interface Cancel extends BooleanSupplier {
    }

    /**
     * Downloads release {@code a}'s file for {@code i} into {@code dir} and checks it against the
     * release's SHA-256 sums. A file that doesn't match is removed.
     *
     * @param progress told the bytes read so far, and (first) -1 - the size when known
     */
    public static Path download(Available a, Install i, Path dir, LongConsumer progress, Cancel cancel)
            throws IOException {
        allowed();
        try {
            return downloadNow(a, i, dir, progress, cancel);
        } catch (java.io.InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            if (Tls.isTrustProblem(e)) {
                throw new IOException("Your network shows GitHub with a certificate neither Java nor this computer "
                        + "trusts (an office proxy that looks inside secure connections does this), so " + a.title()
                        + " can't be downloaded safely. Ask your IT team to add their certificate to this computer, "
                        + "or download it from its page in your browser: " + a.page(), e);
            }
            throw Proxies.explain(e, URI.create(download()), a.title());
        }
    }

    private static Path downloadNow(Available a, Install i, Path dir, LongConsumer progress, Cancel cancel)
            throws IOException {
        String asset = i.asset(a);
        String sums = get(download() + a.tag() + "/" + SUMS, 1 << 20);
        if (sums == null) {
            throw new IOException(a.title() + " has no checksums (" + SUMS + "), so it can't be checked: download it "
                    + "from its page instead.");
        }
        String want = sum(sums, asset);
        if (want == null) {
            throw new IOException(a.title() + " has no " + asset + ".");
        }
        Files.createDirectories(dir);
        Path f = dir.resolve(asset);
        Path part = dir.resolve(asset + ".part");
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        HttpURLConnection c = open(download() + a.tag() + "/" + asset);
        try {
            if (c.getResponseCode() != 200) {
                throw refused(c, c.getResponseCode());
            }
            if (webPage(c)) {
                throw blocked(asset, a.page());
            }
            long size = c.getContentLengthLong();
            progress.accept(-1 - Math.max(0, size));
            long read = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel.getAsBoolean()) {
                        throw new java.io.InterruptedIOException("Stopped.");
                    }
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                    read += n;
                    progress.accept(read);
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part); // our own half-downloaded file
            throw e;
        } finally {
            c.disconnect();
        }
        String got = HexFormat.of().formatHex(sha.digest());
        if (!got.equalsIgnoreCase(want)) {
            Files.deleteIfExists(part);
            throw new IOException(asset + " isn't what the release says it is (its SHA-256 doesn't match), so it "
                    + "wasn't used. Try again later.");
        }
        Files.move(part, f, StandardCopyOption.REPLACE_EXISTING);
        return f;
    }

    /** The sum for {@code name} in a SHA256SUMS file ({@code <hex>  <name>}, as sha256sum writes it). */
    static String sum(String sums, String name) {
        for (String line : sums.split("\\R")) {
            Matcher m = Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?(\\S.*)$").matcher(line.strip());
            if (m.matches() && m.group(2).strip().equals(name)) {
                return m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * What GitHub says of a release, to see if it's newer. Behind a company proxy whose certificate
     * isn't trusted, it's asked again without checking it: only a version or a commit is read, and
     * a download is always checked (see {@link #open}).
     */
    static String peek(String url) throws IOException {
        try {
            return get(url, 1 << 22, false);
        } catch (IOException e) {
            if (!Tls.isTrustProblem(e)) {
                throw e;
            }
            return get(url, 1 << 22, true);
        }
    }

    /** A small text from GitHub, or null when not there (404). */
    static String get(String url, int max) throws IOException {
        return get(url, max, false);
    }

    private static String get(String url, int max, boolean anyCertificate) throws IOException {
        HttpURLConnection c = open(url, anyCertificate);
        try {
            int code = c.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                throw refused(c, code);
            }
            if (webPage(c)) {
                throw blocked(URI.create(url).getPath().replaceAll(".*/", ""), web() + "/releases");
            }
            try (InputStream in = c.getInputStream()) {
                return new String(in.readNBytes(max), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * A connection to {@code url}, following GitHub's redirects only to its own hosts, with
     * certificates checked (Java's and this computer's, never skipped).
     */
    static HttpURLConnection open(String url) throws IOException {
        return open(url, false);
    }

    private static HttpURLConnection open(String url, boolean anyCertificate) throws IOException {
        String at = url;
        for (int hops = 0; hops < 6; hops++) {
            URI u = URI.create(at);
            check(u);
            HttpURLConnection c = connect(u, anyCertificate);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                at = u.resolve(c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new IOException("Too many redirects from " + url);
    }

    /** A connection that doesn't follow redirects (they're checked: GitHub's own hosts only). */
    private static HttpURLConnection connect(URI u, boolean anyCertificate) throws IOException {
        HttpURLConnection c = (HttpURLConnection) u.toURL().openConnection();
        if (c instanceof javax.net.ssl.HttpsURLConnection https) {
            Tls.apply(https, anyCertificate);
        }
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "Logsift/" + Version.current());
        String at = u.toString();
        c.setRequestProperty("Accept", at.contains("api.github.com") || at.contains("/repos/")
                ? "application/vnd.github+json" : at.endsWith("/releases/latest") ? "text/html" : "application/octet-stream");
        return c;
    }

    /** Only GitHub's own hosts, over HTTPS (a test's own server, when it says so). */
    static void check(URI u) throws IOException {
        String b = base();
        if (b != null) {
            URI t = URI.create(b);
            if (java.util.Objects.equals(u.getHost(), t.getHost()) && u.getPort() == t.getPort()) {
                return;
            }
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                || !HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT))) {
            throw new IOException("Not downloading from " + u.getHost() + ": updates come only from GitHub.");
        }
    }

    // ---------------------------------------------------------------- putting it in place

    /**
     * Makes the downloaded file ready beside the app ({@code <app>.new}), while Logsift runs: the
     * .dmg's app copied out, the archive unpacked. Nothing of the running app changes.
     *
     * @return what's ready (the new app, or the .msi itself)
     */
    public static Path prepare(Install i, Path downloaded) throws IOException, InterruptedException {
        if (i.kind() == Kind.WINDOWS_MSI || i.kind() == Kind.BY_HAND) {
            return downloaded;
        }
        Path ready = Path.of(i.app() + ".new");
        deleteTree(ready); // one made before and not used
        Path stage = Files.createTempDirectory(i.app().getParent(), ".logsift-update");
        try {
            switch (i.kind()) {
                case MAC_APP -> {
                    Path mnt = Files.createTempDirectory("logsift-update-dmg");
                    run(List.of("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint",
                            mnt.toString(), downloaded.toString()));
                    try {
                        Path app = findApp(mnt);
                        run(List.of("ditto", app.toString(), ready.toString()));
                    } finally {
                        run(List.of("hdiutil", "detach", mnt.toString(), "-force"));
                        Files.deleteIfExists(mnt);
                    }
                }
                case FOLDER, JAVA_ZIP -> {
                    Path out = stage.resolve("out");
                    Files.createDirectories(out);
                    if (downloaded.getFileName().toString().endsWith(".tar.gz")) {
                        run(List.of("tar", "-xzf", downloaded.toString(), "-C", out.toString()));
                    } else {
                        unzip(downloaded, out);
                    }
                    Path top = only(out);
                    Files.move(top, ready);
                }
                default -> throw new IllegalStateException(i.kind().name());
            }
        } finally {
            deleteTree(stage);
        }
        Path launcher = ready.resolve(i.app().relativize(i.launcher()));
        if (!Files.isRegularFile(launcher)) {
            deleteTree(ready);
            throw new IOException("The download has no " + i.app().relativize(i.launcher()) + ", so it wasn't used.");
        }
        return ready;
    }

    private static Path findApp(Path mnt) throws IOException {
        try (var s = Files.list(mnt)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".app")).findFirst()
                    .orElseThrow(() -> new IOException("The .dmg has no app in it."));
        }
    }

    /** The one folder an archive unpacked into. */
    private static Path only(Path out) throws IOException {
        try (var s = Files.list(out)) {
            List<Path> l = s.toList();
            if (l.size() != 1 || !Files.isDirectory(l.get(0))) {
                throw new IOException("The download isn't one folder, so it wasn't used.");
            }
            return l.get(0);
        }
    }

    /** Unpacks a zip; on Linux and macOS, what's in a bin folder (and the runtime's) can be run. */
    static void unzip(Path zip, Path out) throws IOException {
        Path root = out.toAbsolutePath().normalize();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path p = root.resolve(e.getName().replace('\\', '/')).normalize();
                if (!p.startsWith(root)) {
                    throw new IOException("The zip has a file outside its folder: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(p);
                    continue;
                }
                Files.createDirectories(p.getParent());
                Files.copy(in, p, StandardCopyOption.REPLACE_EXISTING);
                String parent = p.getParent().getFileName().toString();
                if (parent.equals("bin") || p.getFileName().toString().equals("jspawnhelper")) {
                    p.toFile().setExecutable(true, false);
                }
            }
        }
    }

    private static void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IOException(cmd.get(0) + " failed: " + out.strip());
        }
    }

    /** Removes a folder Logsift made itself for the update (never anything else). */
    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        }
    }

    /**
     * The script that waits for this Logsift ({@code pid}) to quit, puts {@code ready} in place
     * of the app (or runs the .msi), and starts it again when {@code restart}.
     */
    public static Path script(Install i, Path ready, long pid, boolean restart, Path dir) throws IOException {
        Files.createDirectories(dir);
        boolean windows = i.target().startsWith("windows");
        Path log = dir.resolve("update.log");
        Path f = dir.resolve(windows ? "update.cmd" : "update.sh");
        String text = windows ? windowsScript(i, ready, pid, restart, log) : posixScript(i, ready, pid, restart, log);
        Files.writeString(f, text, windows ? java.nio.charset.Charset.defaultCharset() : StandardCharsets.UTF_8);
        if (!windows) {
            f.toFile().setExecutable(true, true);
        }
        return f;
    }

    static String q(Path p) {
        return "'" + p.toString().replace("'", "'\\''") + "'";
    }

    static String posixScript(Install i, Path ready, long pid, boolean restart, Path log) {
        String start = i.kind() == Kind.MAC_APP ? "open " + q(i.app())
                : "nohup " + q(i.launcher()) + " >/dev/null 2>&1 &";
        return "#!/bin/sh\n"
                + "# Logsift's update: when Logsift has quit, the new one goes in its place"
                + (restart ? ", and starts.\n" : ".\n")
                + "exec >>" + q(log) + " 2>&1\n"
                + "echo \"$(date) updating " + i.app().toString().replace("\"", "") + "\"\n"
                + "while kill -0 " + pid + " 2>/dev/null; do sleep 0.3; done\n"
                + "APP=" + q(i.app()) + "\n"
                + "NEW=" + q(ready) + "\n"
                + "OLD=\"$APP.old\"\n"
                + "[ -d \"$NEW\" ] || { echo 'nothing ready'; exit 1; }\n"
                + "rm -rf \"$OLD\"\n"
                + "mv \"$APP\" \"$OLD\" || { echo 'could not move the app'; exit 1; }\n"
                + "if mv \"$NEW\" \"$APP\"; then rm -rf \"$OLD\"; echo done; "
                + "else mv \"$OLD\" \"$APP\"; echo 'put back as it was'; fi\n"
                + (restart ? start + "\n" : "");
    }

    static String windowsScript(Install i, Path ready, long pid, boolean restart, Path log) {
        String app = i.app().toString();
        StringBuilder b = new StringBuilder("@echo off\r\n")
                .append("rem Logsift's update: when Logsift has quit, the new one goes in its place")
                .append(restart ? ", and starts.\r\n" : ".\r\n")
                .append("set \"LOG=").append(log).append("\"\r\n")
                .append("echo %DATE% %TIME% updating >> \"%LOG%\"\r\n")
                // Windows' own programs by their paths: another find (Git's) may come first on the PATH
                .append("set \"SYS=%SystemRoot%\\System32\"\r\n")
                .append(":wait\r\n")
                .append("\"%SYS%\\tasklist.exe\" /FI \"PID eq ").append(pid).append("\" /FO CSV /NH 2>nul | \"%SYS%\\findstr.exe\" /C:\"\\\"")
                .append(pid).append("\\\"\" >nul && (\"%SYS%\\PING.EXE\" -n 2 127.0.0.1 >nul & goto wait)\r\n");
        if (i.kind() == Kind.WINDOWS_MSI) {
            b.append("\"%SYS%\\msiexec.exe\" /i \"").append(ready).append("\" /passive /norestart >> \"%LOG%\" 2>&1\r\n")
                    .append("echo msiexec %ERRORLEVEL% >> \"%LOG%\"\r\n");
        } else {
            b.append("set \"APP=").append(app).append("\"\r\n")
                    .append("set \"NEW=").append(ready).append("\"\r\n")
                    .append("if not exist \"%NEW%\" (echo nothing ready >> \"%LOG%\" & exit /b 1)\r\n")
                    .append("if exist \"%APP%.old\" rmdir /s /q \"%APP%.old\"\r\n")
                    // a file still in use (antivirus, a viewer): a few tries
                    .append("set N=0\r\n")
                    .append(":move\r\n")
                    .append("move \"%APP%\" \"%APP%.old\" >nul 2>&1 && goto moved\r\n")
                    .append("set /a N+=1\r\n")
                    .append("if %N% GEQ 20 (echo could not move the app >> \"%LOG%\" & exit /b 1)\r\n")
                    .append("\"%SYS%\\PING.EXE\" -n 2 127.0.0.1 >nul\r\n")
                    .append("goto move\r\n")
                    .append(":moved\r\n")
                    .append("move \"%NEW%\" \"%APP%\" >nul 2>&1 && (rmdir /s /q \"%APP%.old\" & echo done >> \"%LOG%\") "
                            + "|| (move \"%APP%.old\" \"%APP%\" >nul & echo put back as it was >> \"%LOG%\")\r\n");
        }
        if (restart) {
            b.append("start \"\" \"").append(i.launcher()).append("\"\r\n");
        }
        return b.toString();
    }

    /** Starts the script, apart from Logsift: it goes on when Logsift has quit. */
    public static void start(Path script, Path dir) throws IOException {
        Path out = dir.resolve("update-start.log");
        ProcessBuilder pb = script.getFileName().toString().endsWith(".cmd")
                ? new ProcessBuilder("cmd", "/c", "start", "", "/min", "cmd", "/c", script.toString())
                : new ProcessBuilder("nohup", "/bin/sh", script.toString());
        // Windows: start opens its own window and returns; nothing of it is kept open here
        pb.redirectErrorStream(true).redirectOutput(script.getFileName().toString().endsWith(".cmd")
                ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.appendTo(out.toFile()));
        Process p = pb.start();
        p.getOutputStream().close(); // nothing to read: it doesn't wait for input
    }

    /** Where updates are downloaded and their scripts kept: Logsift's own folder. */
    public static Path dir(Path home) {
        return home.resolve("updates");
    }
}
