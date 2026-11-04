package logsift.ui;

import java.awt.Color;
import java.util.List;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * The assistant's answers (Markdown) as HTML that Swing shows: headings, lists, code, tables
 * and links. Its own HTML is shown as text, never run, and only links to Logsift's lines and
 * to the web are kept.
 */
final class ChatMarkdown {
    private ChatMarkdown() {
    }

    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    /** Links that go somewhere safe: a line in Logsift, or a web page. Anything else loses its link. */
    private static final AttributeProvider LINKS = (Node node, String tag, java.util.Map<String, String> attrs) -> {
        if (node instanceof Link && attrs.containsKey("href") && !allowed(attrs.get("href"))) {
            attrs.remove("href");
        }
    };

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true)
            .attributeProviderFactory(c -> LINKS).build(); // links: only Logsift's and the web (LINKS)

    static boolean allowed(String href) {
        String h = href.strip().toLowerCase(java.util.Locale.ROOT);
        return h.startsWith("logsift:") || h.startsWith("https://") || h.startsWith("http://")
                || LineLink.parse(href) != null || FileLink.parse(href) != null;
    }

    /** The body of an answer as HTML (no page around it). */
    static String body(String markdown) {
        return RENDERER.render(PARSER.parse(markdown == null ? "" : markdown));
    }

    /** A whole page for a JEditorPane, in the theme's colors and fonts. */
    static String page(String markdown, java.awt.Font font) {
        return "<html><head><style>" + css(font) + "</style></head><body>" + body(markdown) + "</body></html>";
    }

    static String css(java.awt.Font font) {
        Color fg = Theme.foreground();
        Color dim = Theme.dimmedForeground();
        Color code = Theme.pick(new Color(0xF2F3F5), new Color(0x2B2D30));
        Color link = Theme.uiColor("Component.linkColor", Theme.pick(new Color(0x2F6FDB), new Color(0x6CA4F8)));
        Color rule = Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY);
        String mono = Theme.monoFont(font.getSize2D()).getFamily();
        int size = font.getSize();
        return "body{font-family:'" + font.getFamily() + "';font-size:" + size + "pt;color:" + hex(fg)
                + ";margin:0}"
                + "p{margin-top:0;margin-bottom:6px}"
                + "h1,h2,h3,h4{margin-top:6px;margin-bottom:4px}"
                + "h1{font-size:" + (size + 4) + "pt}h2{font-size:" + (size + 2) + "pt}h3,h4{font-size:" + (size + 1) + "pt}"
                + "ul,ol{margin-top:0;margin-bottom:6px;margin-left:18px}"
                + "li{margin-bottom:2px}"
                + "code{font-family:'" + mono + "';background:" + hex(code) + "}"
                + "pre{font-family:'" + mono + "';background:" + hex(code) + ";padding:6px;margin-top:2px;margin-bottom:6px}"
                + "a{color:" + hex(link) + ";text-decoration:none}"
                + "blockquote{color:" + hex(dim) + ";margin-left:8px;padding-left:6px;border-left:2px solid " + hex(rule) + "}"
                + "table{border-collapse:collapse;margin-bottom:6px}"
                + "th,td{border:1px solid " + hex(rule) + ";padding:2px 6px}"
                + "th{background:" + hex(code) + "}";
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * A line link the assistant wrote: {@code logsift:log:1:1204} or {@code logsift:log:1:1204-1230}.
     *
     * @param handle the log's handle ({@code log:1})
     * @param from   the first line (1-based)
     * @param to     the last line (1-based), {@code from} for one line
     */
    record LineLink(String handle, long from, long to) {
        static LineLink parse(String href) {
            if (href == null) {
                return null;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)(?:logsift:)?(log:\\d+):(\\d+)(?:-(\\d+))?").matcher(href.strip());
            if (!m.matches()) {
                return null;
            }
            try {
                long from = Long.parseLong(m.group(2));
                long to = m.group(3) == null ? from : Long.parseLong(m.group(3));
                return from < 1 ? null : new LineLink(m.group(1).toLowerCase(java.util.Locale.ROOT), from, Math.max(from, to));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * A link to a file on this computer, as models write them: {@code /var/log/app.log},
     * {@code file:///var/log/app.log}, {@code ~/logs/app.log} or {@code C:\logs\app.log}, with or
     * without a line at the end ({@code :1204}, {@code #L1204}). Opened in Logsift, never run.
     *
     * @param line the line (1-based), 0 for none
     */
    record FileLink(java.nio.file.Path path, long line) {
        private static final java.util.regex.Pattern LINE = java.util.regex.Pattern.compile("(?:[:#]L?(\\d+))(?:-\\d+)?$");

        static FileLink parse(String href) {
            if (href == null || href.isBlank()) {
                return null;
            }
            String h = href.strip();
            if (!h.regionMatches(true, 0, "file:", 0, 5)) {
                try {
                    // as the page has it: Markdown's renderer escapes spaces, backslashes…
                    h = java.net.URLDecoder.decode(h.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            long line = 0;
            java.util.regex.Matcher m = LINE.matcher(h);
            if (m.find() && !h.matches("(?i)[a-z]:\\d+")) {
                try {
                    line = Long.parseLong(m.group(1));
                    h = h.substring(0, m.start());
                } catch (NumberFormatException e) {
                    line = 0;
                }
            }
            java.nio.file.Path p;
            try {
                if (h.regionMatches(true, 0, "file:", 0, 5)) {
                    p = java.nio.file.Path.of(java.net.URI.create(h.replace(" ", "%20").replace("\\", "/")));
                } else if (h.startsWith("~/")) {
                    p = java.nio.file.Path.of(System.getProperty("user.home"), h.substring(2));
                } else if (h.startsWith("/") || h.matches("(?i)^[a-z]:[\\\\/].*") || h.startsWith("\\\\")) {
                    p = java.nio.file.Path.of(h);
                } else {
                    return null;
                }
            } catch (RuntimeException e) {
                return null;
            }
            return new FileLink(p.toAbsolutePath().normalize(), Math.max(0, line));
        }
    }
}
