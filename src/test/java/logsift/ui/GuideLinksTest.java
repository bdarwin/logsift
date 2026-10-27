package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The user guide (docs/guide, published with GitHub Pages) has the pages the app links to, and its
 * links and pictures point at files that exist, so a renamed page or screenshot is caught here.
 */
class GuideLinksTest {
    private static final Path GUIDE = Path.of("docs", "guide");
    private static final Pattern MD_LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");

    @Test
    void theAppLinksToPagesThatExist() {
        assertTrue(HelpSupport.DOCS.endsWith("/guide/"), HelpSupport.DOCS);
        assertTrue(Files.isRegularFile(GUIDE.resolve("index.md")), "docs/guide/index.md");
        String gc = HelpSupport.guide("gc");
        assertTrue(gc.startsWith(HelpSupport.DOCS) && gc.endsWith("gc.html"), gc);
        assertTrue(Files.isRegularFile(GUIDE.resolve("gc.md")), "docs/guide/gc.md");
    }

    @Test
    void linksAndPicturesInTheGuideResolve() throws IOException {
        List<String> broken = new ArrayList<>();
        List<Path> pages;
        try (var list = Files.list(GUIDE)) {
            pages = list.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        assertTrue(pages.size() > 10, "guide pages: " + pages);
        for (Path page : pages) {
            String text = stripCode(Files.readString(page));
            Matcher m = MD_LINK.matcher(text);
            while (m.find()) {
                String target = m.group(1);
                if (target.matches("^[a-z]+:.*")) {
                    continue; // https:, mailto:
                }
                int hash = target.indexOf('#');
                String file = hash < 0 ? target : target.substring(0, hash);
                String anchor = hash < 0 ? null : target.substring(hash + 1);
                Path to = file.isEmpty() ? page : page.getParent().resolve(file).normalize();
                if (!Files.isRegularFile(to)) {
                    broken.add(page.getFileName() + ": " + target + " (no file)");
                } else if (anchor != null && to.toString().endsWith(".md") && !anchors(to).contains(anchor)) {
                    broken.add(page.getFileName() + ": " + target + " (no such heading)");
                }
            }
        }
        assertTrue(broken.isEmpty(), "broken links in docs/guide:\n" + String.join("\n", broken));
    }

    /** Heading ids as the Markdown renderers make them: lower case, punctuation dropped, spaces to dashes. */
    private static Set<String> anchors(Path page) throws IOException {
        Set<String> ids = new HashSet<>();
        Matcher m = HEADING.matcher(stripCode(Files.readString(page)));
        while (m.find()) {
            String h = m.group(1).replaceAll("[*`]", "").toLowerCase(Locale.ROOT);
            ids.add(h.replaceAll("[^\\p{L}\\p{N} _-]", "").trim().replace(' ', '-'));
        }
        return ids;
    }

    /** Leaves out fenced code blocks, where brackets aren't links. */
    private static String stripCode(String md) {
        return md.replaceAll("(?s)```.*?```", "");
    }
}
