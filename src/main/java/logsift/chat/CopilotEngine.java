package logsift.chat;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantReasoningDeltaEvent;
import com.github.copilot.generated.AssistantUsageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.SessionTitleChangedEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * GitHub Copilot, through its SDK and the person's own Copilot program ({@code copilot}), signed
 * in as they are there: their subscription pays. Its sessions are kept in Logsift's folder, not
 * with the person's own Copilot sessions. Only Logsift's tools are offered: no shell, no file
 * writes, no web.
 */
public final class CopilotEngine implements ChatEngine {
    /** How long starting may take before it's given up. */
    static final long START_SECONDS = 60;

    private final Path cli;
    private final Path home;
    private final Path work;
    private CopilotClient client;
    private final ExecutorService tools = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "logsift-chat-tool");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param cli  the {@code copilot} program
     * @param home Copilot's own folder, or null for the person's ({@code ~/.copilot}), where the
     *             program keeps its sign-in: a folder of its own would leave it signed out
     * @param work the folder Copilot works in, in Logsift's: never one of the person's projects
     */
    public CopilotEngine(Path cli, Path home, Path work) {
        this.cli = cli;
        this.home = home;
        this.work = java.util.Objects.requireNonNull(work, "work");
    }

    /** Copilot's folder, or null for the person's own. */
    Path home() {
        return home;
    }

    /** The folder Copilot works in, made if it isn't there. */
    Path work() throws java.io.IOException {
        return java.nio.file.Files.createDirectories(work);
    }

    @Override
    public String name() {
        return "GitHub Copilot";
    }

    @Override
    public synchronized void start() throws Exception {
        if (client != null) {
            return;
        }
        CopilotClientOptions o = new CopilotClientOptions()
                .setCliPath(cli.toString())
                .setUseLoggedInUser(true)
                .setEnvironment(CopilotLocator.environment(cli));
        if (home != null) {
            o.setCopilotHome(home.toString());
        }
        CopilotClient c = new CopilotClient(o);
        try {
            c.start().get(START_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            c.close();
            throw new Exception("Copilot didn't start: " + why(e), e);
        }
        client = c;
    }

    private synchronized CopilotClient client() throws Exception {
        start();
        return client;
    }

    @Override
    public String user() throws Exception {
        var s = client().getAuthStatus().get(30, TimeUnit.SECONDS);
        return s.isAuthenticated() ? (s.getLogin() == null ? "" : s.getLogin()) : null;
    }

    @Override
    public List<Model> models() throws Exception {
        List<Model> out = new ArrayList<>();
        for (ModelInfo m : client().listModels().get(30, TimeUnit.SECONDS)) {
            out.add(model(m));
        }
        return out;
    }

    static Model model(ModelInfo m) {
        double in = 0;
        double outPrice = 0;
        if (m.getBilling() != null && m.getBilling().getTokenPrices() != null) {
            var p = m.getBilling().getTokenPrices();
            double per = p.batchSize() == null || p.batchSize() == 0 ? 1_000_000 : p.batchSize();
            in = p.inputPrice() == null ? 0 : p.inputPrice() * 1_000_000 / per;
            outPrice = p.outputPrice() == null ? 0 : p.outputPrice() * 1_000_000 / per;
        }
        long ctx = m.getCapabilities() != null && m.getCapabilities().getLimits() != null
                ? m.getCapabilities().getLimits().getMaxContextWindowTokens() : 0;
        List<String> efforts = m.getSupportedReasoningEfforts() == null ? List.of() : m.getSupportedReasoningEfforts();
        // "auto" has no policy; others say "enabled" when the person may use them
        boolean enabled = m.getPolicy() == null || m.getPolicy().getState() == null
                || "enabled".equalsIgnoreCase(m.getPolicy().getState());
        return new Model(m.getId(), m.getName() == null ? m.getId() : m.getName(), in, outPrice, ctx, efforts,
                m.getDefaultReasoningEffort(), enabled);
    }

    @Override
    public Quota quota() throws Exception {
        var q = client().getRpc().account.getQuota().get(30, TimeUnit.SECONDS);
        if (q == null || q.quotaSnapshots() == null) {
            return null;
        }
        var p = q.quotaSnapshots().get("premium_interactions");
        if (p == null) {
            return null;
        }
        return new Quota(num(p.usedRequests()), num(p.entitlementRequests()), Boolean.TRUE.equals(p.isUnlimitedEntitlement()),
                p.remainingPercentage() == null ? 100 : p.remainingPercentage(),
                p.resetDate() == null ? null : p.resetDate().toString());
    }

    private static long num(Number n) {
        return n == null ? 0 : n.longValue();
    }

    @Override
    public Conversation open(Setup setup, Listener listener) throws Exception {
        CopilotClient c = client();
        List<ToolDefinition> defs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ToolSpec t : setup.tools()) {
            defs.add(ToolDefinition.createSkipPermission(t.name(), t.description(), t.schema(), inv -> {
                CompletableFuture<Object> f = new CompletableFuture<>();
                tools.submit(() -> {
                    try {
                        ToolHandler.Result r = t.handler().call(inv.getToolCallId(), inv.getArguments());
                        f.complete(r.failed() ? "Error: " + r.text() : r.text());
                    } catch (Exception | Error e) {
                        f.complete("Error: " + why(e));
                    }
                });
                return f;
            }).defer(t.often() ? com.github.copilot.rpc.ToolDefer.NEVER : com.github.copilot.rpc.ToolDefer.AUTO));
            names.add(t.name());
        }
        // Logsift's own instructions instead of Copilot's coding-agent ones: shorter, and about logs
        SystemMessageConfig system = new SystemMessageConfig().setMode(com.github.copilot.SystemMessageMode.REPLACE)
                .setContent(setup.instructions());
        Path work = work();
        CopilotSession s;
        if (setup.resume() != null) {
            ResumeSessionConfig r = new ResumeSessionConfig().setModel(setup.model()).setTools(defs)
                    .setAvailableTools(names).setSystemMessage(system);
            r.setOnPermissionRequest(CopilotEngine::permission);
            r.setStreaming(true);
            if (setup.effort() != null) {
                r.setReasoningEffort(setup.effort());
            }
            s = c.resumeSession(setup.resume(), r).get(60, TimeUnit.SECONDS);
        } else {
            SessionConfig cfg = new SessionConfig().setModel(setup.model()).setTools(defs).setAvailableTools(names)
                    .setSystemMessage(system).setStreaming(true).setWorkingDirectory(work.toString())
                    .setOnPermissionRequest(CopilotEngine::permission);
            if (setup.effort() != null) {
                cfg.setReasoningEffort(setup.effort());
            }
            s = c.createSession(cfg).get(60, TimeUnit.SECONDS);
        }
        s.on(e -> {
            ChatEvent ev = event(e);
            if (ev != null) {
                listener.on(ev);
            }
        });
        return new Conversation() {
            @Override
            public String id() {
                return s.getSessionId();
            }

            @Override
            public void send(String text) throws Exception {
                s.send(text).get(60, TimeUnit.SECONDS);
            }

            @Override
            public void abort() {
                s.abort();
            }

            @Override
            public void setModel(String model, String effort) throws Exception {
                s.setModel(model, effort).get(30, TimeUnit.SECONDS);
            }

            @Override
            public void close() {
                s.close();
            }
        };
    }

    /** Only Logsift's own tools run, and they ask nothing: anything else the model tries is refused. */
    private static CompletableFuture<PermissionRequestResult> permission(com.github.copilot.rpc.PermissionRequest r,
                                                                         com.github.copilot.rpc.PermissionInvocation i) {
        if ("custom-tool".equals(r.getKind())) {
            return CompletableFuture.completedFuture(PermissionRequestResult.approveOnce());
        }
        return CompletableFuture.completedFuture(PermissionRequestResult.reject(
                "Logsift's assistant only reads logs with Logsift's tools."));
    }

    /** What an SDK event means here, or null for one the window doesn't show. */
    static ChatEvent event(SessionEvent e) {
        if (e instanceof AssistantMessageDeltaEvent d && d.getData() != null && d.getData().deltaContent() != null
                && d.getData().parentToolCallId() == null) {
            return new ChatEvent.Text(d.getData().messageId(), d.getData().deltaContent());
        }
        if (e instanceof AssistantReasoningDeltaEvent d && d.getData() != null && d.getData().deltaContent() != null) {
            return new ChatEvent.Thinking(d.getData().deltaContent());
        }
        if (e instanceof ToolExecutionStartEvent t && t.getData() != null) {
            return new ChatEvent.ToolStarted(t.getData().toolCallId(), t.getData().toolName(),
                    JsonText.of(t.getData().arguments()));
        }
        if (e instanceof ToolExecutionCompleteEvent t && t.getData() != null) {
            return new ChatEvent.ToolFinished(t.getData().toolCallId(), !Boolean.FALSE.equals(t.getData().success()));
        }
        if (e instanceof AssistantUsageEvent u && u.getData() != null) {
            var d = u.getData();
            double nano = d.copilotUsage() != null && d.copilotUsage().totalNanoAiu() != null
                    ? d.copilotUsage().totalNanoAiu() : 0;
            return new ChatEvent.Usage(d.model(), num(d.inputTokens()) + num(d.cacheReadTokens()) + num(d.cacheWriteTokens()),
                    num(d.outputTokens()), num(d.cacheReadTokens()), nano, num(d.duration()));
        }
        if (e instanceof SessionTitleChangedEvent t && t.getData() != null && t.getData().title() != null) {
            return new ChatEvent.Title(t.getData().title());
        }
        if (e instanceof SessionErrorEvent err && err.getData() != null) {
            return new ChatEvent.Failed(err.getData().message() == null ? "Copilot failed" : err.getData().message());
        }
        if (e instanceof SessionIdleEvent i) {
            return new ChatEvent.Idle(i.getData() != null && Boolean.TRUE.equals(i.getData().aborted()));
        }
        return null;
    }

    /** The message of an exception, without the wrappers futures add. */
    static String why(Throwable e) {
        Throwable t = e;
        while ((t instanceof java.util.concurrent.ExecutionException || t instanceof java.util.concurrent.CompletionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof java.util.concurrent.TimeoutException) {
            return "no answer in time";
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    @Override
    public synchronized void close() {
        tools.shutdownNow();
        if (client != null) {
            try {
                client.stop().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                client.forceStop();
            }
            client.close();
            client = null;
        }
    }

    /** Arguments as the window shows them, from what the SDK gives (maps, lists, values). */
    static final class JsonText {
        static String of(Object v) {
            StringBuilder b = new StringBuilder();
            write(b, v);
            return b.toString();
        }

        private static void write(StringBuilder b, Object v) {
            if (v == null) {
                b.append("null");
            } else if (v instanceof Map<?, ?> m) {
                b.append('{');
                boolean first = true;
                for (var e : new java.util.LinkedHashMap<>(m).entrySet()) {
                    if (!first) {
                        b.append(", ");
                    }
                    first = false;
                    write(b, String.valueOf(e.getKey()));
                    b.append(": ");
                    write(b, e.getValue());
                }
                b.append('}');
            } else if (v instanceof Iterable<?> l) {
                b.append('[');
                boolean first = true;
                for (Object o : l) {
                    if (!first) {
                        b.append(", ");
                    }
                    first = false;
                    write(b, o);
                }
                b.append(']');
            } else if (v instanceof Number || v instanceof Boolean) {
                b.append(v);
            } else if (v instanceof com.fasterxml.jackson.databind.JsonNode n) {
                b.append(n.toString());
            } else {
                String s = v.toString();
                b.append('"');
                for (char ch : s.toCharArray()) {
                    switch (ch) {
                        case '"' -> b.append("\\\"");
                        case '\\' -> b.append("\\\\");
                        case '\n' -> b.append("\\n");
                        default -> b.append(ch);
                    }
                }
                b.append('"');
            }
        }
    }
}
