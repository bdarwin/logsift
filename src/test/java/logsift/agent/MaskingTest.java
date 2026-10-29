package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** What is hidden before text goes to an agent, and what isn't. */
class MaskingTest {
    private final Masking secrets = new Masking(Masking.Options.DEFAULT);

    private String mask(Masking m, String s) {
        return m.mask(s, null);
    }

    @Test
    void hidesSecrets() {
        assertEquals("Authorization: Bearer ‹masked:token›", mask(secrets, "Authorization: Bearer abcDEF123456.xyz-_"));
        assertEquals("token ‹masked:jwt› ok", mask(secrets,
                "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U ok"));
        assertEquals("login password=‹masked:secret› user=bob", mask(secrets, "login password=hunter22 user=bob"));
        assertEquals("{\"apiKey\": \"‹masked:secret›\", \"n\": 1}", mask(secrets, "{\"apiKey\": \"abc123xyz\", \"n\": 1}"));
        assertEquals("jdbc:postgresql://app:‹masked:password›@db:5432/orders",
                mask(secrets, "jdbc:postgresql://app:s3cr3t@db:5432/orders"));
        assertEquals("key ‹masked:aws-key›", mask(secrets, "key AKIAIOSFODNN7EXAMPLE"));
        assertEquals("gh ‹masked:token›", mask(secrets, "gh " + "ghp" + "_abcdefghijklmnopqrstuvwxyz0123456789"));
        assertEquals("paid with ‹masked:card›", mask(secrets, "paid with 4111 1111 1111 1111"));
        assertEquals("‹masked:private-key›", mask(secrets, "-----BEGIN RSA PRIVATE KEY----- MIIEow"));
    }

    @Test
    void leavesTheRestAlone() {
        for (String s : List.of(
                "2026-10-21 10:00:01.000 INFO [main] Heartbeat ok, queue size 12",
                "order 1234567890123 placed in 42 ms", // not a card number (Luhn)
                "trace id 4bf92f3577b34da6a3ce929d0e0e4736, span 00f067aa0ba902b7",
                "user alice@example.com from 10.1.2.3",
                "at com.acme.Token.parse(Token.java:42)",
                "tests pass: 120 of 120",
                "std::string at 10:00:01.123 and 12:30",
                "Foo::bar took 3 ms")) {
            assertEquals(s, mask(secrets, s), s);
        }
    }

    @Test
    void hidesEmailsIpsAndOwnPatternsWhenAsked() {
        Masking ips = new Masking(new Masking.Options(false, false, true, List.of()));
        assertEquals("at 10:00:01.123 from ‹masked:ip› and ‹masked:ip› and ‹masked:ip›",
                mask(ips, "at 10:00:01.123 from ::1 and 2001:db8:85a3:0:0:8a2e:370:7334 and 192.168.0.1"));
        for (String s : List.of("std::string Foo::bar at 10:00:01.123 and 12:30", "build 1.2.3.4567",
                "uuid 123e4567-e89b-12d3-a456-426614174000", "MAC 00:1A:2B:3C:4D:5E")) {
            assertEquals(s, mask(ips, s), s);
        }
        Masking m = new Masking(new Masking.Options(true, true, true, List.of("cust-\\d{6}", "([")));
        AtomicLong n = new AtomicLong();
        assertEquals("‹masked:email› from ‹masked:ip› and ‹masked:ip› for ‹masked:custom›",
                m.mask("alice@example.com from 10.1.2.3 and fe80::1:2:3:4 for cust-123456", n));
        assertEquals(4, n.get());
        assertEquals(4, m.total());
        assertEquals(List.of("(["), m.badPatterns(), "a broken pattern is set aside");
        assertEquals("secrets, emails, IP addresses, 2 own patterns", m.options().describe());
    }

    @Test
    void canBeOff() {
        Masking off = new Masking(Masking.Options.NONE);
        assertEquals("password=hunter22", mask(off, "password=hunter22"));
        assertEquals("nothing", Masking.Options.NONE.describe());
    }

    @Test
    void luhn() {
        assertTrue(Masking.luhn("4111111111111111"));
        assertTrue(!Masking.luhn("4111111111111112"));
    }

    @Test
    void numbersInNamesAndPathsAreNotCards() {
        Masking m = new Masking(Masking.Options.DEFAULT);
        String path = "/tmp/junit-8535496816683098565/logs/app.log";
        assertEquals(path, m.mask(path, null), "a temporary folder's number is part of its name");
        assertEquals("build_4111111111111111.log", m.mask("build_4111111111111111.log", null));
        assertEquals("C:\\Temp\\4111111111111111\\x", m.mask("C:\\Temp\\4111111111111111\\x", null));
        assertEquals("paid with ‹masked:card›", m.mask("paid with 4111 1111 1111 1111", null));
        assertEquals("card=‹masked:card›.", m.mask("card=4111111111111111.", null), "a sentence's full stop");
        assertEquals("card ‹masked:card›, ok", m.mask("card 4111-1111-1111-1111, ok", null));
    }
}
