package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import org.junit.jupiter.api.Test;

/**
 * The packaged app runs on a Java trimmed to the modules the build lists. Tests run on a whole
 * JDK, so a module that is only found at run time (like the one that looks up a JMX port) is
 * checked here, on exactly that list.
 */
class PackagedModulesTest {
    /** Run on the packaged modules: tries a JMX port and prints how it failed. */
    public static void main(String[] args) {
        try {
            JMXConnectorFactory.connect(
                    new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + args[0] + "/jmxrmi")).close();
            System.out.println("connected");
        } catch (Exception e) {
            System.out.println(JmxJvms.describe(e));
        }
    }

    @Test
    void aJmxPortIsReachedWithThePackagedModules() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/snapshot.yml"));
        Matcher m = Pattern.compile("--add-modules\\s+(\\S+)").matcher(workflow);
        assertTrue(m.find(), "the module list");
        int port;
        try (var s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort(); // free, so nothing answers: the lookup must get as far as connecting
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process p = new ProcessBuilder(List.of(java.toString(), "--limit-modules", m.group(1),
                "-cp", System.getProperty("java.class.path"), PackagedModulesTest.class.getName(),
                String.valueOf(port))).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor(), out);
        assertTrue(out.startsWith("nothing answers on that port"), out);
    }
}
