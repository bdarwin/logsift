package logsift.chat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import logsift.agent.LogCatalog;
import logsift.agent.Prompts;
import logsift.agent.Skill;

/**
 * What the assistant is told before the first question, in parts the person can see and turn off
 * (Settings → Assistant): Logsift's own instructions, where their logs are, the step-by-step
 * plans, the time now, and their own words.
 */
public final class ChatInstructions {
    private ChatInstructions() {}

    /**
     * The assistant's options, in Logsift's folder ({@code chat/assistant.properties}, and the
     * person's own instructions in {@code chat/instructions.md}).
     *
     * @param inTab     the chat opens as a tab (else beside the logs)
     * @param atStart   it opens when Logsift starts
     * @param locations it's told where the person's logs are (services, folders, time zones, notes)
     * @param plans     it's given the step-by-step plans
     * @param now       each question goes with the date and time now
     * @param own       the person's own instructions, or ""
     */
    public record Options(boolean inTab, boolean atStart, boolean locations, boolean plans, boolean now, String own) {
        public static final Options DEFAULT = new Options(true, true, true, true, true, "");

        public Options {
            own = own == null ? "" : own.strip();
        }

        static Path file(Path home) {
            return home.resolve("chat").resolve("assistant.properties");
        }

        static Path ownFile(Path home) {
            return home.resolve("chat").resolve("instructions.md");
        }

        public static Options load(Path home) {
            Properties p = new Properties();
            Path f = file(home);
            if (Files.isRegularFile(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    p.load(r);
                } catch (IOException | IllegalArgumentException e) {
                    p.clear();
                }
            }
            String own = "";
            try {
                Path o = ownFile(home);
                if (Files.isRegularFile(o)) {
                    own = Files.readString(o, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                own = "";
            }
            return new Options(!"side".equals(p.getProperty("where")), on(p, "atStart"), on(p, "locations"),
                    on(p, "plans"), on(p, "now"), own);
        }

        private static boolean on(Properties p, String key) {
            return !"false".equals(p.getProperty(key));
        }

        public void save(Path home) throws IOException {
            Properties p = new Properties();
            p.setProperty("where", inTab ? "tab" : "side");
            p.setProperty("atStart", String.valueOf(atStart));
            p.setProperty("locations", String.valueOf(locations));
            p.setProperty("plans", String.valueOf(plans));
            p.setProperty("now", String.valueOf(now));
            Path f = file(home);
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                p.store(w, "Logsift's assistant: where it opens and what it's told");
            }
            Path o = ownFile(home);
            if (own.isEmpty()) {
                Files.deleteIfExists(o); // Logsift's own file
            } else {
                Files.writeString(o, own + "\n", StandardCharsets.UTF_8);
            }
        }

        public Options withInTab(boolean v) {
            return new Options(v, atStart, locations, plans, now, own);
        }

        public Options withAtStart(boolean v) {
            return new Options(inTab, v, locations, plans, now, own);
        }

        public Options withLocations(boolean v) {
            return new Options(inTab, atStart, v, plans, now, own);
        }

        public Options withPlans(boolean v) {
            return new Options(inTab, atStart, locations, v, now, own);
        }

        public Options withNow(boolean v) {
            return new Options(inTab, atStart, locations, plans, v, own);
        }

        public Options withOwn(String v) {
            return new Options(inTab, atStart, locations, plans, now, v);
        }
    }

    /**
     * One part of what the assistant is told.
     *
     * @param key   its name in the options: logsift, locations, plans, now, own
     * @param title what it is, for people
     * @param what  a line on what it gives the assistant
     * @param text  the words themselves
     * @param on    whether it's told
     * @param fixed whether it can't be turned off
     */
    public record Part(String key, String title, String what, String text, boolean on, boolean fixed) {
        /** About how many tokens it reads (a token is about four characters). */
        public int tokens() {
            return (text.length() + 3) / 4;
        }
    }

    /** The parts, in the order they're told; those turned off are listed too. */
    public static List<Part> parts(Options o, LogCatalog catalog) {
        LogCatalog c = catalog == null ? LogCatalog.EMPTY : catalog;
        List<Part> l = new ArrayList<>();
        l.add(new Part("logsift", "Logsift's instructions",
                "How to read logs with Logsift's tools, and to cite each line as a link you can click.",
                ChatTools.instructions(c), true, true));
        l.add(new Part("locations", "Where your logs are",
                c.isEmpty() ? "Your services, their folders, files, time zones and notes. None described yet."
                        : "Your " + c.entries().size() + (c.entries().size() == 1 ? " service" : " services") + " ("
                                + Skill.serviceNames(c) + "): folders, files, time zones and notes, so a question "
                                + "names a service without a path.",
                Skill.locations(c, null).strip(), o.locations(), false));
        l.add(new Part("plans", "Step-by-step plans",
                "How to investigate a problem, explain a crash, compare a bad run with a good one, find what's "
                        + "slow and check a service's health.",
                plans(), o.plans(), false));
        l.add(new Part("now", "The date and time",
                "Each question goes with the time now on this computer, so \"this morning\" or \"an hour ago\" "
                        + "means what you mean.",
                NOW, o.now(), false));
        l.add(new Part("own", "Your own instructions",
                o.own().isEmpty() ? "Anything else it should know: names you use, what's normal, how to answer."
                        : "What you wrote below.",
                o.own().isEmpty() ? "" : "## The person's own instructions\n\n" + o.own(), !o.own().isEmpty(), false));
        return l;
    }

    /** All that's told: the parts that are on. */
    public static String text(Options o, LogCatalog catalog) {
        StringBuilder b = new StringBuilder();
        for (Part p : parts(o, catalog)) {
            if (p.on() && !p.text().isEmpty()) {
                if (b.length() > 0) {
                    b.append("\n\n");
                }
                b.append(p.text());
            }
        }
        return b.toString();
    }

    static final String NOW = """
            ## Times

            Each question starts with the date and time now on the person's computer, and its time zone. Read \
            "this morning", "yesterday", "since lunch" or "an hour ago" against it. A service's logs may be in \
            another time zone (see where the logs are; otherwise the times are as the log writes them): turn the \
            range into the log's own times before searching or querying, and say the range you used. If the log \
            doesn't reach that time, say what time it covers.""";

    /** The line on the time now that goes before a question. */
    public static String now(ZonedDateTime t) {
        String offset = t.getOffset().getId().equals("Z") ? "+00:00" : t.getOffset().getId();
        return "[Now] " + t.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)) + " ("
                + t.getZone().getId() + ", UTC" + offset + ").";
    }

    /** The plans agents get as prompts, for any problem: what to do, in order. */
    static String plans() {
        Map<String, String> args = Map.of("problem", "the problem the person asks about",
                "where", "the service or log they name (or call list_logs and pick the one it fits)",
                "file", "the crash file", "logs", "find them with list_logs",
                "bad", "the run that failed", "good", "one that worked");
        StringBuilder b = new StringBuilder("## Step-by-step plans\n\nFor these jobs, follow the plan, adapting it "
                + "to what the person asked; skip a step that doesn't fit.\n");
        for (Prompts.Prompt p : Prompts.all()) {
            b.append("\n### ").append(p.title()).append("\n\n").append(p.description()).append("\n\n")
                    .append(p.text().text(args).strip()).append('\n');
        }
        return b.toString().strip();
    }
}
