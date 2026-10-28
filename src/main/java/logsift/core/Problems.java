package logsift.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Unexpected errors in background work (bugs: a NullPointerException in an analysis, say) go to
 * one place, which the app shows and logs ({@link #setSink}). Expected failures (a file that
 * can't be read, a connection refused, a cancelled search, a mistyped regex) are not bugs: the
 * code that meets them says so in its own words.
 */
public final class Problems {
    private Problems() {}

    private static volatile Consumer<Throwable> sink;

    /** Where unexpected errors go (the app's error reporter); null: nowhere. */
    public static void setSink(Consumer<Throwable> s) {
        sink = s;
    }

    /** The error inside the wrappers that only carry it (ExecutionException and the like). */
    public static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof ExecutionException || t instanceof CompletionException
                || t instanceof java.lang.reflect.InvocationTargetException
                || t instanceof java.lang.reflect.UndeclaredThrowableException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Whether a failure is expected in normal use: reading or writing (a missing file, no
     * permission, a network drive or server gone), being stopped (cancelled, interrupted,
     * shutting down), a file changing while it is read, or a mistyped regular expression.
     */
    public static boolean isExpected(Throwable e) {
        Throwable t = unwrap(e);
        return t instanceof IOException || t instanceof UncheckedIOException
                || t instanceof InterruptedException || t instanceof CancellationException
                || t instanceof TimeoutException || t instanceof RejectedExecutionException
                || t instanceof FileChangedException || t instanceof java.util.regex.PatternSyntaxException
                || t instanceof com.jcraft.jsch.JSchException || t instanceof com.jcraft.jsch.SftpException;
    }

    /** Reports an error that is a bug; expected failures are left to the caller's own message. */
    public static void unexpected(Throwable e) {
        if (e == null || isExpected(e)) {
            return;
        }
        Consumer<Throwable> s = sink;
        if (s != null) {
            s.accept(unwrap(e));
        }
    }

    /**
     * A task for a background thread or timer whose result nobody reads: a bug in it is reported
     * instead of lost (and a repeating task keeps running).
     */
    public static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                unexpected(t);
            }
        };
    }

    /** Like {@link #guard(Runnable)}; the error still reaches the task's Future too. */
    public static <T> Callable<T> guard(Callable<T> task) {
        return () -> {
            try {
                return task.call();
            } catch (Exception | Error e) {
                unexpected(e);
                throw e;
            }
        };
    }
}
