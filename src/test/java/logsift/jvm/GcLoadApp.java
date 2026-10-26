package logsift.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A program for GC logs: it allocates for some seconds. With "leak" it also keeps a growing part
 * of what it allocates and calls System.gc() once; without, it allocates gently and keeps little.
 */
public final class GcLoadApp {
    private GcLoadApp() {}

    static final List<byte[]> KEPT = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        long seconds = Long.parseLong(args[0]);
        boolean leak = args.length > 1 && args[1].equals("leak");
        long end = System.currentTimeMillis() + seconds * 1000;
        Random r = new Random(1);
        List<byte[]> window = new ArrayList<>();
        boolean called = false;
        long n = 0;
        while (System.currentTimeMillis() < end) {
            window.add(new byte[256 + r.nextInt(4096)]);
            int keep = leak ? 20_000 : 1_000; // healthy: a small, steady set in use
            if (window.size() > keep) {
                window.subList(0, keep / 2).clear();
            }
            n++;
            if (leak && n % 200 == 0) {
                KEPT.add(new byte[1024]);
            }
            if (leak && !called && System.currentTimeMillis() > end - seconds * 500) {
                System.gc();
                called = true;
            }
            if (!leak && n % 500 == 0) {
                Thread.sleep(1); // a few MB a second
            }
        }
        System.out.println(KEPT.size() + " " + window.size());
    }
}
