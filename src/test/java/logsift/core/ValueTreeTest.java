package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Loggers (or other values) as a tree, and hiding branches of it. */
class ValueTreeTest {
    private static final List<ColumnStats.Value> LOGGERS = List.of(
            new ColumnStats.Value("com.acme.db.Pool", 50, 0),
            new ColumnStats.Value("com.acme.db.PoolMonitor", 5, 0),
            new ColumnStats.Value("com.acme.web.Http", 30, 0),
            new ColumnStats.Value("com.acme.web", 2, 0),
            new ColumnStats.Value("org.hibernate.SQL", 10, 0),
            new ColumnStats.Value("main", 3, 0));

    @Test
    void buildsACompactTree() {
        ValueTree t = ValueTree.of(LOGGERS);
        assertEquals(100, t.root().total());
        List<ValueTree.Node> top = t.root().children();
        assertEquals("com.acme", top.get(0).label(), "single-child chains are joined");
        assertEquals(87, top.get(0).total());
        assertEquals("org.hibernate.SQL", top.get(1).label());
        assertEquals("main", top.get(2).label());
        ValueTree.Node db = top.get(0).children().get(0);
        assertEquals("db", db.label());
        assertEquals("com.acme.db", db.path());
        assertEquals(top.get(0), db.parent());
        ValueTree.Node web = top.get(0).children().get(1);
        assertTrue(web.isValue(), "com.acme.web is a logger and a package");
        assertEquals(2, web.own());
    }

    @Test
    void hidesBranchesAsAFilter() {
        ValueTree t = ValueTree.of(LOGGERS);
        ValueTree.Node acme = t.root().children().get(0);
        ValueTree.Node db = acme.children().get(0);
        ValueTree.Node web = acme.children().get(1);
        assertFalse(t.anyHidden());
        t.setShown(db, false);
        assertEquals(ValueTree.State.SOME, t.state(acme));
        assertEquals(List.of(new ValueTree.Hidden("com.acme.db", true)), t.hidden());
        // hide com.acme.web's own lines but not com.acme.web.Http
        t.setShown(web, false);
        t.setShown(web.children().get(0), true);
        assertEquals(List.of(new ValueTree.Hidden("com.acme.db", true), new ValueTree.Hidden("com.acme.web", false)),
                t.hidden());
        t.showOnly(db.children().get(1));
        assertEquals(ValueTree.State.SOME, t.state(t.root()));
        t.showAll();
        assertFalse(t.anyHidden());

        LogFormat f = new CustomFormat("x", "{time} {level} {logger} - {message}", "", "").compile();
        String regex = ValueTree.lineRegex(f, "logger", List.of(new ValueTree.Hidden("com.acme.db", true),
                new ValueTree.Hidden("main", false)));
        Pattern p = Pattern.compile(regex);
        assertTrue(p.matcher("2026-10-21 10:00:00 INFO com.acme.db.Pool - x").find());
        assertTrue(p.matcher("2026-10-21 10:00:00 INFO com.acme.db - x").find());
        assertFalse(p.matcher("2026-10-21 10:00:00 INFO com.acme.dbx.Pool - x").find(), "a branch, not a prefix");
        assertTrue(p.matcher("2026-10-21 10:00:00 INFO main - x").find());
        assertFalse(p.matcher("2026-10-21 10:00:00 INFO main.Worker - x").find(), "only main itself");
        assertFalse(p.matcher("    at com.acme.db.Pool.get(Pool.java:1)").find());

        // a column ending at a | (and a value with spaces)
        LogFormat pipes = new CustomFormat("p", "{time}|{level}|{component}|{message}", "", "").compile();
        String r2 = ValueTree.lineRegex(pipes, "component", List.of(new ValueTree.Hidden("Order Service", false)));
        assertTrue(Pattern.compile(r2).matcher("2026-10-21 10:00:00|I|Order Service|x").find());
        assertFalse(Pattern.compile(r2).matcher("2026-10-21 10:00:00|I|Order Service 2|x").find());
        assertNull(ValueTree.lineRegex(pipes, "component", List.of()));

        // JSON lines: by the field
        LogFormat json = LogFormat.detect(List.of(
                "{\"ts\":\"2026-10-21T10:00:00Z\",\"level\":\"INFO\",\"logger\":\"com.acme.db.Pool\",\"msg\":\"a\"}",
                "{\"ts\":\"2026-10-21T10:00:01Z\",\"level\":\"INFO\",\"logger\":\"com.acme.web\",\"msg\":\"b\"}",
                "{\"ts\":\"2026-10-21T10:00:02Z\",\"level\":\"WARN\",\"logger\":\"main\",\"msg\":\"c\"}"));
        String r3 = ValueTree.lineRegex(json, "logger", List.of(new ValueTree.Hidden("com.acme", true)));
        assertTrue(Pattern.compile(r3).matcher("{\"logger\":\"com.acme.db.Pool\",\"msg\":\"a\"}").find());
        assertFalse(Pattern.compile(r3).matcher("{\"logger\":\"main\"}").find());
    }
}
