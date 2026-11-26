package logsift.chat;

import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.CopilotSession;
import com.microsoft.copilot.generated.AssistantMessageDeltaEvent;
import com.microsoft.copilot.generated.AssistantReasoningDeltaEvent;
import com.microsoft.copilot.generated.AssistantUsageEvent;
import com.microsoft.copilot.generated.SessionErrorEvent;
import com.microsoft.copilot.generated.SessionEvent;
import com.microsoft.copilot.generated.SessionIdleEvent;
import com.microsoft.copilot.generated.SessionTitleChangedEvent;
import com.microsoft.copilot.generated.ToolExecutionCompleteEvent;
import com.microsoft.copilot.generated.ToolExecutionStartEvent;
import com.microsoft.copilot.rpc.ClientGitHubOptions;
import com.microsoft.copilot.rpc.ClientInfo;
import com.microsoft.copilot.rpc.CopilotClientMode;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.rpc.ResumeSessionOptions;
import com.microsoft.copilot.rpc.RuntimeConnection;
import com.microsoft.copilot.rpc.SessionEventOptions;
import com.microsoft.copilot.rpc.SessionInferenceOptions;
import com.microsoft.copilot.rpc.SessionPermissionOptions;
import com.microsoft.copilot.rpc.SessionToolOptions;
import com.microsoft.copilot.rpc.SystemMessageConfig;
import com.microsoft.copilot.rpc.ToolDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * GitHub Copilot, through its SDK and the Copilot runtime Logsift brings (made with that SDK), or
 * a {@code copilot} program the person chose. Signed in as they are in Copilot (its {@code /login},
 * a GitHub CLI login or a token): their subscription pays. Only Logsift's tools are offered: no
 * shell, no file writes, no web.
 *
 * <p>Logsift brings its own runtime because Copilot programs people install change their protocol:
 * Copilot CLI 1.0.113 talks protocol 4, which no 1.x SDK can start, and SDK 2 can't start a CLI
 * older than that.
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
     * @param cli  the {@code copilot} program chosen, or null for the runtime Logsift brings
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

    /** The {@code copilot} program chosen, or null for the runtime Logsift brings. */
    Path cli() {
        return cli;
    }

    /** Copilot's folder: the one given, else the person's own ({@code ~/.copilot}), where its sign-in is. */
    Path copilotHome() {
        return home != null ? home : Path.of(System.getProperty("user.home"), ".copilot");
    }

    /** Variables changed for Copilot's process (null: removed), for tests to keep it from any sign-in. */
    private Map<String, String> environmentChanges = Map.of();

    CopilotEngine withEnvironmentChanges(Map<String, String> changes) {
        environmentChanges = java.util.Objects.requireNonNull(changes);
        return this;
    }

    private Map<String, String> environment() {
        Map<String, String> env = cli == null ? CopilotLocator.environment() : CopilotLocator.environment(cli);
        environmentChanges.forEach((k, v) -> {
            env.keySet().removeIf(old -> old.equalsIgnoreCase(k));
            if (v != null) {
                env.put(k, v);
            }
        });
        return env;
    }

    @Override
    public synchronized void start() throws Exception {
        if (client != null) {
            return;
        }
        // its own process: a crash in Copilot can't take Logsift down
        var runtime = RuntimeConnection.forSpawnedStdio()
                .setEnvironment(environment())
                .setBaseDirectory(copilotHome().toString());
        if (cli != null) {
            runtime.setPath(cli.toString());
        }
        // EMPTY: nothing of Copilot's coding agent (its tools, plugins, instructions); only what's asked for
        CopilotClientOptions o = new CopilotClientOptions().setMode(CopilotClientMode.EMPTY).setRuntime(runtime)
                .setGitHub(new ClientGitHubOptions().setUseLoggedInUser(true))
                .setClientInfo(new ClientInfo().setApplicationName("Logsift"));
        CopilotClient c = new CopilotClient(o);
        try {
            c.start().get(START_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            c.close();
            throw new Exception(startFailed(e, cli), e);
        }
        client = c;
    }

    /** Why Copilot didn't start, in words the person can act on. */
    static String startFailed(Throwable e, Path cli) {
        String why = why(e);
        if (why.toLowerCase(java.util.Locale.ROOT).contains("protocol version mismatch")) {
            if (cli == null) {
                return "Copilot didn't start: its runtime doesn't match this Logsift. Install Logsift again.";
            }
            // "SDK supports versions 4-4, but server reports version 3"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("versions? (\\d+)-(\\d+).*?version (\\d+)")
                    .matcher(why);
            if (m.find()) {
                int high = Integer.parseInt(m.group(2));
                int theirs = Integer.parseInt(m.group(3));
                if (theirs < high) {
                    return "Copilot didn't start: the program chosen (" + cli + ") is older than this Logsift can talk to. "
                            + "Choose Use Logsift's Copilot (nothing to install), or update that program (copilot update) "
                            + "and try again.";
                }
                return "Copilot didn't start: the program chosen (" + cli + ") is newer than this Logsift can talk to. "
                        + "Choose Use Logsift's Copilot (nothing to install); a newer Logsift will talk to it.";
            }
            return "Copilot didn't start: the program chosen (" + cli + ") is a version this Logsift can't talk to. "
                    + "Choose Use Logsift's Copilot, or update the program (copilot update), then try again.";
        }
        return "Copilot didn't start: " + why;
    }

    private synchronized CopilotClient client() throws Exception {
        start();
        return client;
    }

    @Override
    public String signInHint() {
        return "Sign in once in a terminal (copilot, then /login), then try again.";
    }

    @Override
    public String user() throws Exception {
        var s = client().getAuthStatus().get(30, TimeUnit.SECONDS);
        return s.isAuthenticated() ? (s.getLogin() == null ? "" : s.getLogin()) : null;
    }

    @Override
    public boolean canSignIn() {
        return true;
    }

    /** How long a sign-in in the browser may take. */
    static final long SIGN_IN_MINUTES = 15;

    /**
     * Copilot's own sign-in (as {@code /login} does it): GitHub's page in the browser, and Copilot
     * catches the answer on this computer. It keeps the sign-in where {@code /login} does. Runs in
     * a session of its own, with no model and no tools: it costs nothing.
     */
    @Override
    public SignIn signIn() throws Exception {
        CopilotClient c = client();
        java.nio.file.Files.createDirectories(work);
        CopilotSession s = c.createSession(new CreateSessionOptions().setWorkingDirectory(work.toString())
                .setTools(new SessionToolOptions().setAvailableTools(List.of()))
                .setPermissions(new SessionPermissionOptions().setOnRequest(CopilotEngine::permission)))
                .get(60, TimeUnit.SECONDS);
        try {
            var begun = s.getRpc().accounts.login.begin(new com.microsoft.copilot.generated.rpc.SessionAccountsLoginBeginParams(
                    s.getSessionId(), com.microsoft.copilot.generated.rpc.LoginProviderKind.GITHUBDOTCOM, null, null, null))
                    .get(60, TimeUnit.SECONDS);
            Object step = begun.step();
            String kind = stepKind(step);
            java.nio.file.Files.deleteIfExists(signInLog()); // this sign-in's steps only
            note("sign-in begun (" + (cli == null ? "Logsift's Copilot" : cli) + ", folder " + copilotHome() + "): " + kind);
            String url = switch (kind) {
                case "open-url" -> stepField(step, "url");
                case "completed" -> null; // signed in already
                case "error" -> throw new Exception(signInError(step));
                default -> throw new Exception("Copilot wants to sign in a way Logsift can't show (" + kind
                        + "). Sign in once in a terminal (copilot, then /login).");
            };
            if (url == null && !"completed".equals(kind)) {
                throw new Exception("Copilot gave no page to sign in on.");
            }
            return new CopilotSignIn(c, s, begun.flowId(), url);
        } catch (Exception e) {
            s.close();
            throw e;
        }
    }

    /** Where the last sign-in's steps are written (no tokens): Logsift's chat folder. */
    Path signInLog() {
        Path chat = work.getParent() == null ? work : work.getParent();
        return chat.resolve("sign-in.log");
    }

    /** One line of the sign-in log. */
    void note(String line) {
        try {
            Path f = signInLog();
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, java.time.LocalDateTime.now().withNano(0) + " " + line
                    + System.lineSeparator(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            // only a log
        }
    }

    /** The login in an account's description (a map from Copilot), or null. */
    static String accountLogin(Object authInfo) {
        if (authInfo instanceof java.util.Map<?, ?> m) {
            for (String k : List.of("login", "user", "username")) {
                if (m.get(k) != null) {
                    return m.get(k).toString();
                }
            }
        }
        return null;
    }

    /** A step of Copilot's sign-in, as it comes: its kind ("open-url", "awaiting", "completed", "error"…). */
    static String stepKind(Object step) {
        if (step instanceof com.microsoft.copilot.generated.rpc.AuthLoginStep st) {
            return st.getKind();
        }
        if (step instanceof java.util.Map<?, ?> m && m.get("kind") != null) {
            return m.get("kind").toString();
        }
        return step == null ? "none" : "unknown";
    }

    /** A field of a step: the page ({@code url}), why it failed ({@code message}), what it asks ({@code prompt}). */
    static String stepField(Object step, String name) {
        if (step instanceof java.util.Map<?, ?> m) {
            Object v = m.get(name);
            return v == null ? null : v.toString();
        }
        if (step instanceof com.microsoft.copilot.generated.rpc.AuthLoginStepOpenUrl u && name.equals("url")) {
            return u.getUrl();
        }
        if (step instanceof com.microsoft.copilot.generated.rpc.AuthLoginStepError e && name.equals("message")) {
            return e.getMessage();
        }
        if (step instanceof com.microsoft.copilot.generated.rpc.AuthLoginStepInputRequired i && name.equals("prompt")) {
            return i.getPrompt();
        }
        return null;
    }

    /** What a finished step says of how it ended: "completed", "declined"…, or null when it doesn't say. */
    static String stepStatus(Object step) {
        if (step instanceof com.microsoft.copilot.generated.rpc.AuthLoginStepCompleted d && d.getResult() != null
                && d.getResult().status() != null) {
            return d.getResult().status().getValue();
        }
        if (step instanceof java.util.Map<?, ?> m && m.get("result") instanceof java.util.Map<?, ?> r
                && r.get("status") != null) {
            return r.get("status").toString();
        }
        return null;
    }

    private static String signInError(Object step) {
        String m = stepField(step, "message");
        return "The sign-in failed" + (m == null || m.isBlank() ? "." : ": " + m);
    }

    /** Copilot's sign-in under way, in its own session. */
    private final class CopilotSignIn implements SignIn {
        private final CopilotClient client;
        private final CopilotSession session;
        private String flow; // a new one when an account is chosen
        private final String url;
        private volatile boolean done;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        CopilotSignIn(CopilotClient client, CopilotSession session, String flow, String url) {
            this.client = client;
            this.session = session;
            this.flow = flow;
            this.url = url;
            done = url == null;
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public String await(java.util.function.BooleanSupplier stop) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(SIGN_IN_MINUTES);
            CompletableFuture<?> next = null;
            boolean stepsWork = true; // a step Copilot sends that the SDK can't read: the status still tells
            try {
                while (!done) {
                    if (stop.getAsBoolean()) {
                        return null;
                    }
                    if (System.nanoTime() > deadline) {
                        throw new Exception("The sign-in took too long. Try again.");
                    }
                    if (next == null && stepsWork) {
                        next = session.getRpc().accounts.login.advance(
                                new com.microsoft.copilot.generated.rpc.SessionAccountsLoginAdvanceParams(
                                        session.getSessionId(), flow, null));
                    }
                    if (next != null) {
                        try {
                            Object step = next.get(1, TimeUnit.SECONDS);
                            next = null;
                            switch (stepKind(step)) {
                                case "completed" -> {
                                    String status = stepStatus(step);
                                    note("step completed: " + status);
                                    if (status == null || status.equals("completed")) {
                                        done = true;
                                        keep(step);
                                    } else if (status.equals("needs_account_selection") && chooseAccount(step)) {
                                        // signed in on GitHub with several accounts: the one just authorized, again
                                    } else if (status.equals("declined")) {
                                        throw new Exception("The sign-in was declined on GitHub.");
                                    } else {
                                        throw new Exception("Copilot needs an answer Logsift can't give (" + status
                                                + "). Sign in once in a terminal (copilot, then /login).");
                                    }
                                }
                                case "error" -> throw new Exception(signInError(step));
                                case "input-required" -> throw new Exception("Copilot asks: " + stepField(step, "prompt")
                                        + ". Sign in once in a terminal (copilot, then /login).");
                                default -> {
                                    note("step " + stepKind(step));
                                    Thread.sleep(500); // awaiting the browser
                                }
                            }
                        } catch (java.util.concurrent.TimeoutException stillWaiting) {
                            // the browser isn't done yet
                        } catch (java.util.concurrent.ExecutionException e) {
                            note("a step Logsift can't read: " + why(e) + "; going by the status");
                            next = null;
                            stepsWork = false;
                        }
                    } else {
                        Thread.sleep(1000);
                    }
                    if (!done && client.getAuthStatus().get(10, TimeUnit.SECONDS).isAuthenticated()) {
                        note("signed in (by the status)");
                        done = true;
                    }
                }
                var status = client.getAuthStatus().get(30, TimeUnit.SECONDS);
                note("after: authenticated=" + status.isAuthenticated() + " login=" + status.getLogin() + " type="
                        + status.getAuthType() + (status.getStatusMessage() == null ? "" : " says: " + status.getStatusMessage()));
                if (!status.isAuthenticated()) {
                    String said = status.getStatusMessage();
                    throw new Exception("GitHub authorized Copilot, but Copilot didn't keep the sign-in"
                            + (said == null || said.isBlank() ? "" : " (" + said + ")") + ". What happened is in "
                            + signInLog() + ". Signing in once in a terminal (copilot, then /login) may work.");
                }
                return status.getLogin() == null ? "" : status.getLogin();
            } finally {
                if (next != null) {
                    next.cancel(true);
                }
                close();
            }
        }

        /** The account just authorized, when GitHub has several: begins again with it. */
        private boolean chooseAccount(Object step) throws Exception {
            if (!(step instanceof com.microsoft.copilot.generated.rpc.AuthLoginStepCompleted d) || d.getResult() == null
                    || d.getResult().accounts() == null || d.getResult().accounts().isEmpty()) {
                return false;
            }
            var result = d.getResult();
            var pick = result.accounts().stream().filter(a -> a.login() != null && a.login().equals(result.login()))
                    .findFirst().orElse(result.accounts().get(0));
            note("several accounts (" + result.accounts().size() + "): choosing " + pick.login() + " on " + pick.host());
            var begun = session.getRpc().accounts.login.begin(new com.microsoft.copilot.generated.rpc.SessionAccountsLoginBeginParams(
                    session.getSessionId(), com.microsoft.copilot.generated.rpc.LoginProviderKind.GITHUBDOTCOM, null,
                    com.microsoft.copilot.generated.rpc.AuthLoginMode.SELECTACCOUNT, pick.selectionId()))
                    .get(60, TimeUnit.SECONDS);
            flow = begun.flowId();
            if ("completed".equals(stepKind(begun.step())) && (stepStatus(begun.step()) == null
                    || "completed".equals(stepStatus(begun.step())))) {
                done = true;
                keep(begun.step());
            }
            return true;
        }

        /**
         * Keeps the sign-in where Copilot's own /login does (the system's credential store), so the
         * next start of Copilot finds it too: a sign-in made in a session may otherwise last only as
         * long as this process. The token goes from Copilot to Copilot; Logsift keeps none of it.
         */
        private void keep(Object step) {
            try {
                String login = step instanceof com.microsoft.copilot.generated.rpc.AuthLoginStepCompleted d
                        && d.getResult() != null ? d.getResult().login() : null;
                String host = step instanceof com.microsoft.copilot.generated.rpc.AuthLoginStepCompleted d
                        && d.getResult() != null ? d.getResult().host() : null;
                note("signed in as " + login + " on " + host);
                var users = client.getRpc().account.getAllUsers().get(30, TimeUnit.SECONDS);
                note("Copilot knows " + users.size() + " account(s)");
                for (var u : users) {
                    if (u.token() == null || u.token().isBlank()) {
                        continue;
                    }
                    String who = accountLogin(u.authInfo());
                    if (login != null && who != null && !login.equals(who)) {
                        continue;
                    }
                    var r = client.getRpc().account.login(new com.microsoft.copilot.generated.rpc.AccountLoginParams(
                            host == null ? "https://github.com" : host, login == null ? who : login, u.token()))
                            .get(30, TimeUnit.SECONDS);
                    note("kept for the next start: in the system's credential store=" + r.storedInVault());
                    return;
                }
                note("no account with a token to keep");
            } catch (Exception e) {
                note("couldn't keep the sign-in: " + why(e));
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (!done) {
                try {
                    session.getRpc().accounts.login.cancel(
                            new com.microsoft.copilot.generated.rpc.SessionAccountsLoginCancelParams(session.getSessionId(), flow))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // gone already
                }
            }
            session.close();
        }
    }

    @Override
    public List<Model> models() throws Exception {
        List<Model> out = new ArrayList<>();
        for (com.microsoft.copilot.generated.rpc.Model m : client().listModels().get(30, TimeUnit.SECONDS)) {
            out.add(model(m));
        }
        return out;
    }

    static Model model(com.microsoft.copilot.generated.rpc.Model m) {
        double in = 0;
        double outPrice = 0;
        if (m.billing() != null && m.billing().tokenPrices() != null) {
            var p = m.billing().tokenPrices();
            double per = p.batchSize() == null || p.batchSize() == 0 ? 1_000_000 : p.batchSize();
            in = p.inputPrice() == null ? 0 : p.inputPrice() * 1_000_000 / per;
            outPrice = p.outputPrice() == null ? 0 : p.outputPrice() * 1_000_000 / per;
        }
        long ctx = m.capabilities() != null && m.capabilities().limits() != null
                && m.capabilities().limits().maxContextWindowTokens() != null
                ? m.capabilities().limits().maxContextWindowTokens() : 0;
        List<String> efforts = m.supportedReasoningEfforts() == null ? List.of() : m.supportedReasoningEfforts();
        // "auto" has no policy; others say "enabled" when the person may use them
        boolean enabled = m.policy() == null || m.policy().state() == null
                || "enabled".equalsIgnoreCase(m.policy().state().getValue());
        return new Model(m.id(), m.name() == null ? m.id() : m.name(), in, outPrice, ctx, efforts,
                m.defaultReasoningEffort(), enabled);
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
            }).defer(t.often() ? com.microsoft.copilot.rpc.ToolDefer.NEVER : com.microsoft.copilot.rpc.ToolDefer.AUTO));
            names.add(t.name());
        }
        // Logsift's own instructions instead of Copilot's coding-agent ones: shorter, and about logs
        SystemMessageConfig system = new SystemMessageConfig().setMode(com.microsoft.copilot.SystemMessageMode.REPLACE)
                .setContent(setup.instructions());
        Path work = work();
        CopilotSession s;
        SessionToolOptions toolOptions = new SessionToolOptions().setDefinitions(defs).setAvailableTools(names);
        SessionPermissionOptions permissions = new SessionPermissionOptions().setOnRequest(CopilotEngine::permission);
        SessionInferenceOptions inference = setup.effort() == null ? null
                : new SessionInferenceOptions().setReasoningEffort(setup.effort());
        if (setup.resume() != null) {
            ResumeSessionOptions r = new ResumeSessionOptions().setModel(setup.model()).setTools(toolOptions)
                    .setSystemMessage(system).setPermissions(permissions)
                    .setEvents(new SessionEventOptions().setStreaming(true));
            if (inference != null) {
                r.setInference(inference);
            }
            s = c.resumeSession(setup.resume(), r).get(60, TimeUnit.SECONDS);
        } else {
            CreateSessionOptions cfg = new CreateSessionOptions().setModel(setup.model()).setTools(toolOptions)
                    .setSystemMessage(system).setWorkingDirectory(work.toString()).setPermissions(permissions)
                    .setEvents(new SessionEventOptions().setStreaming(true));
            if (inference != null) {
                cfg.setInference(inference);
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
    private static CompletableFuture<PermissionRequestResult> permission(com.microsoft.copilot.rpc.PermissionRequest r,
                                                                         com.microsoft.copilot.rpc.PermissionInvocation i) {
        if ("custom-tool".equals(r.getKind())) {
            return CompletableFuture.completedFuture(PermissionRequestResult.approveOnce());
        }
        return CompletableFuture.completedFuture(PermissionRequestResult.reject(
                "Logsift's assistant only reads logs with Logsift's tools."));
    }

    /** What an SDK event means here, or null for one the window doesn't show. */
    static ChatEvent event(SessionEvent e) {
        if (e instanceof AssistantMessageDeltaEvent d && d.getData() != null && d.getData().deltaContent() != null
                && e.getAgentId() == null) { // a subagent's words aren't the answer
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
