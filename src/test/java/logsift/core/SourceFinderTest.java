package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceFinderTest {

    private static Path touch(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        return p;
    }

    @Test
    void findsJavaByPackageAndPythonByPath(@TempDir Path dir) throws Exception {
        Path calc = touch(dir.resolve("shop/src/main/java/com/acme/shop/PriceCalculator.java"));
        touch(dir.resolve("shop/src/test/java/com/acme/other/PriceCalculator.java")); // same name, other package
        touch(dir.resolve("shop/build/generated/com/acme/shop/Order.java"));            // build output: skipped
        Path kt = touch(dir.resolve("shop/src/main/kotlin/Mailer.kt"));
        Path pay = touch(dir.resolve("orders-py/orders/payment.py"));
        touch(dir.resolve("orders-py/tests/payment.py"));
        touch(dir.resolve("orders-py/.venv/lib/python3.12/site-packages/requests/api.py"));
        SourceFinder f = new SourceFinder(List.of(dir.resolve("shop"), dir.resolve("orders-py"), dir.resolve("missing")));

        assertEquals(calc, f.find(Exceptions.frameOf("\tat com.acme.shop.PriceCalculator.total(PriceCalculator.java:42)")));
        assertEquals(calc, f.find(Exceptions.frameOf("\tat com.acme.shop.PriceCalculator$Rule.apply(PriceCalculator.java:9)")),
                "an inner class is in its outer class's file");
        assertNull(f.find(Exceptions.frameOf("\tat com.acme.shop.Order.total(Order.java:1)")), "not in build output");
        assertEquals(kt, f.find(Exceptions.frameOf("\tat com.acme.mail.Mailer.send(Mailer.kt:12)")),
                "a Kotlin file in another folder layout");
        assertEquals(pay, f.find(Exceptions.frameOf("  File \"/app/orders/payment.py\", line 88, in charge")),
                "the most folders in common with the server's path");
        assertNull(f.find(Exceptions.frameOf("  File \"/usr/lib/python3.12/site-packages/requests/api.py\", line 1")));
        assertNull(Exceptions.frameOf("just a line"));
    }
}
