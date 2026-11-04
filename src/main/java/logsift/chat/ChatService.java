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
     */
    public record State(ChatEngine engine, String user, List<ChatEngine.Model> models, ChatEngine.Quota quota,
                        String problem) {
        public boolean ready() {
            return problem == null && engine != null;
        }
    }

    /** Makes the assistant for a {@code copilot} program (tests put their own). */
    public interface EngineFactory {
        /** The engine, or null when there's no assistant to be had (then {@link #missing} says why). */
        ChatEngine make(ChatSettings settings, Path home);

        default String missing() {
            return "GitHub Copilot isn't installed. Install it (npm install -g @github/copilot), sign in once in a "
                    + "terminal (copilot, then /login), then try again.";
        }
    }

    /** The real one: the person's own copilot program, in its own folder (~/.copilot), where its sign-in is. */
    public static final EngineFactory COPILOT = (settings, home) -> {
        Path cli = CopilotLocator.find(settings.copilot());
        return cli == null ? null : new CopilotEngine(cli, null, home.resolve("chat").resolve("work"));
    };

    private static volatile EngineFactory factory = COPILOT;
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
        factory = f == null ? COPILOT : f;
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

    public synchronized ChatSettings settings() {
        return settings;
    }

    public synchronized void saveSettings(ChatSettings s) {
        boolean newProgram = !java.util.Objects.equals(s.copilot(), settings.copilot());
        settings = s;
        try {
            s.save(home);
        } catch (java.io.IOException e) {
            // kept for this run
        }
        if (newProgram) {
            reconnect();
        }
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
            return new State(null, null, List.of(), null, f.missing());
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
                return new State(e, null, List.of(), null, "Not signed in to GitHub Copilot. Sign in once in a terminal "
                        + "(copilot, then /login), then try again.");
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
