package logsift.core;

/** Logsift's own version, and comparing version numbers like {@code 1.10.2}. */
public final class Version {
    private Version() {}

    /**
     * The running version: from the jar's manifest, or the {@code logsift.version} system
     * property (tests), or {@code "dev"} when run from classes.
     */
    public static String current() {
        String v = System.getProperty("logsift.version");
        if (v == null) {
            v = Version.class.getPackage().getImplementationVersion();
        }
        return v == null ? "dev" : v;
    }

    /**
     * Compares two versions number by number ({@code 1.10.0 > 1.9.3}); a leading {@code v} is
     * ignored, and anything after a {@code -} (like {@code -beta}) sorts before the plain number.
     */
    public static int compare(String a, String b) {
        String[] pa = core(a).split("\\.");
        String[] pb = core(b).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? number(pa[i]) : 0;
            long y = i < pb.length ? number(pb[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        boolean preA = strip(a).contains("-");
        boolean preB = strip(b).contains("-");
        return preA == preB ? 0 : preA ? -1 : 1;
    }

    /** True if {@code candidate} is a newer version than {@code running} ("dev" never updates). */
    public static boolean isNewer(String candidate, String running) {
        if (running == null || running.equals("dev") || candidate == null) {
            return false;
        }
        return compare(candidate, running) > 0;
    }

    private static String strip(String v) {
        v = v.trim();
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    private static String core(String v) {
        v = strip(v);
        int dash = v.indexOf('-');
        return dash >= 0 ? v.substring(0, dash) : v;
    }

    private static long number(String s) {
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Long.parseLong(s.substring(0, Math.min(end, 18)));
    }
}
