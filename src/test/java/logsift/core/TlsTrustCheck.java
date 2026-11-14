package logsift.core;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * For the Windows build: does Logsift's HTTPS trust a certificate only Windows trusts (an office
 * proxy's)? Serves HTTPS on this computer with a certificate signed by a test authority, then
 * connects as Logsift does. Run on a runtime made with the app's own modules.
 *
 * <pre>TlsTrustCheck server.p12 password trusted|untrusted</pre>
 *
 * Exits 0 when the answer is the one expected, 1 otherwise. Only java.base: the app's runtime has
 * no HTTP server.
 */
public final class TlsTrustCheck {
    private TlsTrustCheck() {
    }

    public static void main(String[] args) throws Exception {
        boolean expectTrusted = args[2].equals("trusted");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(args[0])) {
            ks.load(in, args[1].toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, args[1].toCharArray());
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        try (SSLServerSocket ss = (SSLServerSocket) server.getServerSocketFactory()
                .createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try (SSLSocket s = (SSLSocket) ss.accept()) {
                        s.startHandshake();
                        InputStream in = s.getInputStream();
                        byte[] buf = new byte[4096];
                        int n = in.read(buf); // the request (small)
                        if (n > 0) {
                            OutputStream out = s.getOutputStream();
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                                    .getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                        }
                    } catch (Exception e) {
                        // a client that didn't trust us hangs up: the answer is the client's
                    }
                }
            }, "tls-check-server");
            t.setDaemon(true);
            t.start();

            HttpsURLConnection c = (HttpsURLConnection) URI.create("https://localhost:" + ss.getLocalPort() + "/")
                    .toURL().openConnection();
            Tls.apply(c, false); // as updates and Open URL connect
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            boolean trusted;
            String said;
            try {
                int code = c.getResponseCode();
                trusted = true;
                said = "HTTP " + code;
            } catch (java.io.IOException e) {
                if (!Tls.isTrustProblem(e)) {
                    throw e; // something else broke: not an answer
                }
                trusted = false;
                said = e.getMessage() + Tls.unreadableNote();
            }
            System.out.println((trusted ? "trusted: " : "not trusted: ") + said);
            if (trusted != expectTrusted) {
                System.out.println("expected " + (expectTrusted ? "trusted" : "not trusted"));
                System.exit(1);
            }
            if (!expectTrusted && !Tls.unreadableNote().isEmpty()) {
                System.out.println("this computer's certificates couldn't be read");
                System.exit(1);
            }
        }
    }
}
