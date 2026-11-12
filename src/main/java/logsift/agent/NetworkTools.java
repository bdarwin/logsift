package logsift.agent;

import java.net.URI;
import java.util.List;
import java.util.Map;
import logsift.core.Proxies;
import logsift.core.SelfUpdate;

/** How Logsift reaches the internet, for an agent helping when a download or an update check fails. */
public final class NetworkTools {
    private NetworkTools() {}

    public static List<Tool> all() {
        return List.of(NETWORK_CHECK);
    }

    private record Def(String name, String title, String description, Map<String, Object> inputSchema, Body body)
            implements Tool {
        @Override
        public String run(Args args, Workspace w) throws Exception {
            return body.run(args, w);
        }
    }

    @FunctionalInterface
    private interface Body {
        String run(Tool.Args a, Workspace w) throws Exception;
    }

    static final Tool NETWORK_CHECK = new Def("network_check", "How Logsift reaches the internet",
            "Says Logsift's proxy setting (" + Proxies.SETTING + ": automatic, manual or none) and the proxy GitHub "
                    + "and a web address go through. With test=true, also tries reaching GitHub's releases page as "
                    + "an update check does. Use when opening a log from a web address, or an update, fails.",
            new Tool.Schema()
                    .string("url", "Also say how this web address (a log's) is reached; nothing is downloaded.", false)
                    .bool("test", "Try reaching GitHub now (default false).")
                    .build(),
            (a, w) -> {
                Proxies.Setting s = Proxies.setting();
                StringBuilder b = new StringBuilder("Proxy setting: ");
                b.append(switch (s.mode()) {
                    case AUTOMATIC -> "automatic (this computer's network settings, else HTTPS_PROXY)";
                    case MANUAL -> "manual, " + s.host() + ":" + s.port()
                            + (s.bypass().isEmpty() ? "" : ", not for " + s.bypass());
                    case NONE -> "none (always direct)";
                }).append(".\n");
                b.append("GitHub is reached ").append(Proxies.how(URI.create("https://github.com/"))).append(".\n");
                String url = a.string("url", null);
                if (url != null && !url.isBlank()) {
                    URI u;
                    try {
                        u = logsift.core.UrlFetch.parse(url);
                    } catch (Exception e) {
                        throw new Tool.Failure("Not a web address: " + url);
                    }
                    if (u == null || u.getHost() == null) {
                        throw new Tool.Failure("Not a web address: " + url);
                    }
                    b.append(u.getHost()).append(" is reached ").append(Proxies.how(u)).append(".\n");
                }
                if (a.bool("test", false)) {
                    SelfUpdate.Reach r = SelfUpdate.testConnection();
                    b.append(r.ok() ? "Test: " : "Test failed: ").append(r.text()).append('\n');
                }
                b.append("The person changes the proxy in ").append(Proxies.SETTING).append('.');
                return b.toString();
            });
}
