package logsift.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The assistant for the whole app: one store of chats and one connection to Copilot, shared by
 * every window, started the first time someone opens the chat (never before).
 */
public final class ChatService {

    /**
     * How the assistant was found and connected.
     *
     * @param engine  the assistant, or null when there's none
     * @param user    who is signed in, or null
     * @param models  the models to choose from
     * @param quota   what's left of the allowance, or null
     * @param problem what's wrong, for people (not installed, not signed in, didn't start), or null
     * @param signedOut it started, but no one is signed in
     */
    public record State(ChatEngine engine, String user, List<ChatEngine.Model> models, ChatEngine.Quota quota,
                        String problem, boolean signedOut) {
        public State(ChatEngine engine, String user, List<ChatEngine.Model> models, ChatEngine.Quota quota,
                     String problem) {
            this(engine, user, models, quota, problem, false);
        }

        public boolean ready() {
            return problem == null && engine != null;
        }

        /** No one is signed in, and they can sign in from Logsift. */
        public boolean canSignIn() {
            return signedOut && engine != null && engine.canSignIn();
        }
    }

    /** Makes the assistant for a {@code copilot} program (tests put their own). */
    public interface EngineFactory {
        /** The engine, or null when there's no assistant to be had (then {@link #missing} says why). */
        ChatEngine make(ChatSettings settings, Path home);

        default String missing() {
            return "GitHub Copilot isn't installed. Install it (npm install -g @github/copilot), then try again: "
                    + "you can sign in from here.";
        }

        /** Why there's no assistant with these settings. */
        default String missing(ChatSettings settings) {
            return missing();
        }
    }

    /** The real one: the person's own copilot program, in its own folder (~/.copilot), where its sign-in is. */
    public static final EngineFactory COPILOT = (settings, home) -> {
        Path cli = CopilotLocator.find(settings.copilot());
        // logsift.copilotHome: another folder for Copilot's own (its sign-in), to try signing in apart from ~/.copilot
        String own = System.getProperty("logsift.copilotHome");
        return cli == null ? null : new CopilotEngine(cli, own == null || own.isBlank() ? null : Path.of(own),
                home.resolve("chat").resolve("work"));
    };

    /** Claude: the person's own claude program, signed in with their Claude account. */
    public static final EngineFactory CLAUDE = (settings, home) -> {
        Path cli = CopilotLocator.findProgram("claude", settings.claude());
        return cli == null ? null : new ClaudeEngine(cli, home.resolve("chat").resolve("claude-work"));
    };

    /** The assistant chosen in Settings → Assistant: Copilot unless it's Claude. */
    public static final EngineFactory CHOSEN = new EngineFactory() {
        @Override
        public ChatEngine make(ChatSettings settings, Path home) {
            return (ChatSettings.CLAUDE.equals(settings.engineOrDefault()) ? CLAUDE : COPILOT).make(settings, home);
        }

        @Override
        public String missing(ChatSettings settings) {
            return ChatSettings.CLAUDE.equals(settings.engineOrDefault())
                    ? "Claude Code isn't installed. Install it (see claude.com/claude-code), sign in once in a terminal "
                    + "(claude auth login), then try again."
                    : missing();
        }
    };

    private static volatile EngineFactory factory = CHOSEN;
    private static ChatService instance;

    private final Path home;
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "logsift-chat");
        t.setDaemon(true);
        return t;
    });
    private ChatStore store;
    private String storeProblem;
    private ChatSettings settings;
    private CompletableFuture<State> state;

    private ChatService(Path home) {
        this.home = home;
        this.settings = ChatSettings.load(home);
    }

    /** The app's assistant. */
    public static synchronized ChatService get() {
        if (instance == null) {
            instance = new ChatService(logsift.core.CacheSettings.home());
        }
        return instance;
    }

    /** For tests: another way to make the assistant, and a fresh service (the old one closed). */
    public static synchronized void useFactory(EngineFactory f) {
        factory = f == null ? CHOSEN : f;
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public Path home() {
        return home;
    }

    /** The chats; in memory if the file can't be had (then {@link #storeProblem} says why). */
    public synchronized ChatStore store() {
        if (store == null) {
            try {
                store = ChatStore.open(home.resolve("chat"));
            } catch (io.quackjvm.core.DatabaseInUse e) {
                storeProblem = "Another Logsift has the chats open, so this one's chats won't be kept.";
                store = ChatStore.inMemory();
            } catch (Exception | Error e) {
                storeProblem = "The chats couldn't be opened (" + e.getMessage() + "), so these won't be kept.";
                store = ChatStore.inMemory();
            }
        }
        return store;
    }

    public synchronized String storeProblem() {
        store();
        return storeProblem;
    }

    private ChatInstructions.Options options;

    /** Where the chat opens and what it's told (Settings → Assistant). */
    public synchronized ChatInstructions.Options options() {
        if (options == null) {
            options = ChatInstructions.Options.load(home);
        }
        return options;
    }

    public synchronized void saveOptions(ChatInstructions.Options o) {
        options = o;
        try {
            o.save(home);
        } catch (java.io.IOException e) {
            // kept for this run
        }
    }

    private ChatAudit.Settings audit;

    /** What the audit keeps and flags (Settings → Assistant → Audit). */
    public synchronized ChatAudit.Settings audit() {
        if (audit == null) {
            audit = ChatAudit.Settings.load(home);
        }
        return audit;
    }

    public synchronized void saveAudit(ChatAudit.Settings s) {
        audit = s;
        try {
            s.save(home);
        } catch (java.io.IOException e) {
            // kept for this run
        }
    }

    public synchronized ChatSettings settings() {
        return settings;
    }

    /** Told when another assistant (or program) is chosen: windows connect again. */
    private final List<Runnable> engineChanged = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void onEngineChanged(Runnable r) {
        engineChanged.add(r);
    }

    public void removeEngineChanged(Runnable r) {
        engineChanged.remove(r);
    }

    public void saveSettings(ChatSettings s) {
        if (saveSettingsAndCheck(s)) {
            engineChanged.forEach(Runnable::run);
        }
    }

    private synchronized boolean saveSettingsAndCheck(ChatSettings s) {
        boolean newProgram = !java.util.Objects.equals(s.copilot(), settings.copilot())
                || !java.util.Objects.equals(s.claude(), settings.claude())
                || !s.engineOrDefault().equals(settings.engineOrDefault());
        settings = s;
        try {
            s.save(home);
        } catch (java.io.IOException e) {
            // kept for this run
        }
        if (newProgram) {
            reconnect();
        }
        return newProgram;
    }

    /** Connects the first time; after that, how it went. */
    public synchronized CompletableFuture<State> state() {
        if (state == null) {
            state = CompletableFuture.supplyAsync(this::connect, work);
        }
        return state;
    }

    /** Connects again (after installing Copilot, or signing in). */
    public synchronized CompletableFuture<State> reconnect() {
        CompletableFuture<State> old = state;
        state = null;
        if (old != null) {
            old.thenAccept(s -> {
                if (s.engine() != null) {
                    s.engine().close();
                }
            });
        }
        return state();
    }

    private State connect() {
        ChatSettings s = settings();
        EngineFactory f = factory;
        ChatEngine e;
        try {
            e = f.make(s, home);
        } catch (RuntimeException ex) {
            return new State(null, null, List.of(), null, ex.getMessage());
        }
        if (e == null) {
            return new State(null, null, List.of(), null, f.missing(s));
        }
        // Copilot's program goes when Logsift does
        ChatEngine started = e;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(started::close, "logsift-chat-stop"));
        } catch (IllegalStateException alreadyStopping) {
            // quitting now
        }
        try {
            e.start();
            String user = e.user();
            if (user == null) {
                return new State(e, null, List.of(), null, e.canSignIn()
                        ? "Not signed in to GitHub Copilot. Sign in with your GitHub account: Logsift opens GitHub's page "
                        + "in your browser, and the assistant is ready when you're done there."
                        : "Not signed in to " + e.name() + ". " + e.signInHint(),
                        true);
            }
            List<ChatEngine.Model> models = e.models();
            ChatEngine.Quota q;
            try {
                q = e.quota();
            } catch (Exception ex) {
                q = null; // not every account has one
            }
            return new State(e, user, models, q, null);
        } catch (Exception ex) {
            return new State(e, null, List.of(), null, CopilotEngine.why(ex));
        }
    }

    /** Reads the allowance again (after an answer). */
    public CompletableFuture<ChatEngine.Quota> quota() {
        return state().thenApplyAsync(s -> {
            if (!s.ready()) {
                return null;
            }
            try {
                return s.engine().quota();
            } catch (Exception ex) {
                return s.quota();
            }
        }, work);
    }

    public synchronized void close() {
        if (state != null) {
            state.thenAccept(s -> {
                if (s.engine() != null) {
                    s.engine().close();
                }
            });
            state = null;
        }
        if (store != null) {
            store.close();
            store = null;
        }
        work.shutdown();
    }

    /** How a model is chosen when the person hasn't: theirs, else a cheap good one, else the first. */
    public static ChatEngine.Model pick(List<ChatEngine.Model> models, String wanted) {
        Function<String, ChatEngine.Model> byId = id -> models.stream()
                .filter(m -> m.id().equals(id) && m.enabled()).findFirst().orElse(null);
        if (wanted != null && byId.apply(wanted) != null) {
            return byId.apply(wanted);
        }
        if (byId.apply("auto") != null) {
            return byId.apply("auto");
        }
        return models.stream().filter(ChatEngine.Model::enabled).findFirst().orElse(models.isEmpty() ? null : models.get(0));
    }
}
