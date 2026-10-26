package logsift.core;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Certificates for HTTPS: trusted when Java trusts them or when the computer does (Windows' and
 * macOS's own stores, where a company's proxy certificate usually is); or not checked at all,
 * when the person says so for a server.
 */
final class Tls {
    private Tls() {
    }

    private static volatile SSLSocketFactory checked;
    private static volatile SSLSocketFactory unchecked;

    static void apply(HttpsURLConnection c, boolean anyCertificate) {
        try {
            if (anyCertificate) {
                c.setSSLSocketFactory(unchecked());
                c.setHostnameVerifier((host, session) -> true);
            } else {
                c.setSSLSocketFactory(checked());
            }
        } catch (java.security.GeneralSecurityException e) {
            // the JVM's defaults stay
        }
    }

    /** Whether a failed handshake was about trusting the certificate (not, say, a protocol). */
    static boolean isTrustProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CertificateException || t instanceof java.security.cert.CertPathValidatorException
                    || t instanceof java.security.cert.CertPathBuilderException) {
                return true;
            }
            String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (m.contains("pkix") || m.contains("certification path") || m.contains("certificate")) {
                return true;
            }
        }
        return false;
    }

    private static SSLSocketFactory checked() throws java.security.GeneralSecurityException {
        SSLSocketFactory f = checked;
        if (f == null) {
            List<X509TrustManager> all = new ArrayList<>();
            all.add(managerOf(null)); // Java's own
            for (String type : systemStores()) {
                try {
                    KeyStore ks = KeyStore.getInstance(type);
                    ks.load(null, null);
                    all.add(managerOf(ks));
                } catch (Exception e) {
                    // not on this system
                }
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new AnyOf(all)}, null);
            f = ctx.getSocketFactory();
            checked = f;
        }
        return f;
    }

    private static List<String> systemStores() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("Windows-ROOT", "Windows-MY");
        }
        if (os.contains("mac")) {
            return List.of("KeychainStore-ROOT", "KeychainStore");
        }
        return List.of(); // Linux: Java already uses the system's CA bundle
    }

    private static X509TrustManager managerOf(KeyStore ks) throws java.security.GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new java.security.KeyStoreException("no X509 trust manager");
    }

    private static SSLSocketFactory unchecked() throws java.security.GeneralSecurityException {
        SSLSocketFactory f = unchecked;
        if (f == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            f = ctx.getSocketFactory();
            unchecked = f;
        }
        return f;
    }

    /** Trusts what any of its managers trusts. */
    private static final class AnyOf implements X509TrustManager {
        private final List<X509TrustManager> all;

        AnyOf(List<X509TrustManager> all) {
            this.all = all;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            all.get(0).checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException first = null;
            for (X509TrustManager m : all) {
                try {
                    m.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first != null ? first : new CertificateException("no trust store");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> out = new ArrayList<>();
            for (X509TrustManager m : all) {
                out.addAll(List.of(m.getAcceptedIssuers()));
            }
            return out.toArray(new X509Certificate[0]);
        }
    }
}
