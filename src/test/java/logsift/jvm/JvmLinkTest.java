package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JvmLinkTest {

    @Test
    void sameProgram() {
        assertTrue(JvmLink.sameProgram("orders-1.4.2.jar", "orders-1.5.0.jar"));
        assertTrue(JvmLink.sameProgram("orders.jar", "/opt/x/ORDERS.jar"));
        assertTrue(JvmLink.sameProgram("orders_v2.0.jar", "orders.jar"));
        assertTrue(JvmLink.sameProgram("com.acme.OrdersApplication", "OrdersApplication"));
        assertTrue(JvmLink.sameProgram("OrdersApplication", "com.acme.OrdersApplication"));
        assertFalse(JvmLink.sameProgram("orders.jar", "billing.jar"));
        assertFalse(JvmLink.sameProgram("com.acme.App", "com.other.App2"));
        assertFalse(JvmLink.sameProgram("", "x"));
    }

    @Test
    void picksTheSameProcessFirstThenTheProgram() {
        JvmProcess a = new JvmProcess(10, "orders.jar", "", "", "", false);
        JvmProcess b = new JvmProcess(20, "orders.jar", "", "", "", false);
        JvmProcess c = new JvmProcess(30, "billing.jar", "", "", "", false);
        JvmLink l = new JvmLink(JvmLink.Kind.LOCAL, "", 0, "", "orders.jar", 20);
        assertEquals(b, l.pick(List.of(a, b, c)));
        assertEquals(a, new JvmLink(JvmLink.Kind.LOCAL, "", 0, "", "orders.jar", 99).pick(List.of(a, b, c)));
        assertNull(new JvmLink(JvmLink.Kind.LOCAL, "", 0, "", "orders.jar", 30).pick(List.of(c)), "pid reused");
    }

    @Test
    void remembers() {
        JvmLink l = new JvmLink(JvmLink.Kind.SSH, "prod|7", 2222, "o p", "com.acme.App", 42);
        assertEquals(l, JvmLink.decode(l.encode()));
        JvmLink j = new JvmLink(JvmLink.Kind.JMX, "service:jmx:rmi:///jndi/rmi://h:1/jmxrmi", 0, "", "a.jar", 1);
        assertEquals(j, JvmLink.decode(j.encode()));
        assertNull(JvmLink.decode("nonsense"));
        assertNull(JvmLink.decode("BOGUS|a|1|b|c|2"));
        assertNull(JvmLink.decode(null));
        assertEquals("com.acme.App on prod|7", l.label());
        assertEquals("a.jar on service:jmx:rmi:///jndi/rmi://h:1/jmxrmi (JMX)", j.label());
        assertEquals("orders.jar on h:9010 (JMX)", new JvmLink(JvmLink.Kind.JMX, "h", 9010, "", "orders.jar", 1).label());
        assertTrue(l.samePlace(new JvmLink(JvmLink.Kind.SSH, "PROD|7", 2222, "x", "y", 1)));
        assertFalse(l.samePlace(new JvmLink(JvmLink.Kind.SSH, "prod|7", 22, "x", "y", 1)));
    }
}
