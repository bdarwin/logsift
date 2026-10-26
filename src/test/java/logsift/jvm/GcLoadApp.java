package logsift.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A program for GC logs: it allocates for some seconds. With "leak" it also keeps a growing part
 * of what it allocates and calls System.gc() once; without, it allocates gently and keeps nothing.
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
            if (window.size() > 20_000) {
                window.subList(0, 10_000).clear();
            }
            n++;
            if (leak && n % 200 == 0) {
                KEPT.add(new byte[1024]);
            }
            if (leak && !called && System.currentTimeMillis() > end - seconds * 500) {
                System.gc();
                called = true;
            }
            if (!leak && n % 2_000 == 0) {
                Thread.sleep(1); // about 20 MB a second
            }
        }
        System.out.println(KEPT.size() + " " + window.size());
    }
}
