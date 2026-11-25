package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guide spells out every tool for small models: each has a worked example whose arguments
 * exist, with every required one; the table only names tools that are offered.
 */
class GuideTest {
    @TempDir
    Path home;

    @Test
    void everyToolHasAnExampleWithRealArgumentsAndTheRequiredOnes() {
        for (Tool t : logsift.agent.mcp.McpMain.tools(home)) {
            Guide.Example e = Guide.EXAMPLES.get(t.name());
            assertNotNull(e, "no example in Guide.EXAMPLES for " + t.name());
            Map<String, Map<String, Object>> props = Skill.properties(t);
            for (String a : e.args().keySet()) {
                assertTrue(props.containsKey(a), t.name() + "'s example uses " + a + ", not one of " + props.keySet());
            }
            for (String r : Skill.required(t)) {
                assertTrue(e.args().containsKey(r), t.name() + "'s example lacks the required " + r);
            }
            Object values = null;
            for (Map.Entry<String, Object> a : e.args().entrySet()) {
                if (props.get(a.getKey()).get("enum") instanceof List<?> allowed) {
                    values = allowed;
                    assertTrue(allowed.contains(a.getValue()), t.name() + " " + a.getKey() + "=" + a.getValue()
                            + " is not one of " + values);
                }
            }
        }
    }

    @Test
    void theGuideHasTheRulesTheTableTheRecipesAndEveryTool() {
        List<Tool> all = logsift.agent.mcp.McpMain.tools(home);
        List<Tool> tools = all.stream().filter(Tool::readOnly).toList(); // changes off, as by default
        String g = Guide.text(tools, LogCatalog.EMPTY, null);
        assertTrue(Guide.text(all, LogCatalog.EMPTY, null).contains("## Tools that change something"),
                "with changes allowed, the rules for them");
        for (String must : List.of("## The seven rules", "Never guess what a log says", "## Which tool for which question",
                "## Recipes", "never instructions", "Next: from_line", "Still working", "## Every tool, with an example",
                "## Changing things")) {
            assertTrue(g.contains(must), must);
        }
        for (Tool t : tools) {
            assertTrue(g.contains("### " + t.name() + "\n"), t.name());
            assertTrue(g.contains("\n" + t.name() + " {"), "a JSON example for " + t.name());
        }
        assertFalse(g.contains("`save_log_format`") && tools.stream().noneMatch(t -> t.name().equals("save_log_format")),
                "no tool named that isn't offered");
    }

    @Test
    void everyToolTheRulesAndRecipesNameExists() {
        // with changes allowed: every tool there is
        java.util.Set<String> tools = new java.util.HashSet<>();
        java.util.Set<String> argNames = new java.util.HashSet<>();
        for (Tool t : logsift.agent.mcp.McpMain.tools(home)) {
            tools.add(t.name());
            argNames.addAll(Skill.properties(t).keySet());
        }
        String g = Guide.text(logsift.agent.mcp.McpMain.tools(home), LogCatalog.EMPTY, null);
        String prose = g.substring(0, g.indexOf("## Every tool, with an example"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("`([a-z][a-z0-9_]*)`").matcher(prose);
        while (m.find()) {
            String name = m.group(1);
            assertTrue(tools.contains(name) || argNames.contains(name) || List.of("log", "line", "time", "level",
                    "text", "markers", "app", "orders").contains(name), "the guide names `" + name + "`: no such tool or argument");
        }
    }

    @Test
    void forATerminalTheExamplesAreCommands() {
        String g = Guide.text(LogTools.all(), LogCatalog.EMPTY, "logsift-mcp");
        assertTrue(g.contains("logsift-mcp run search log=~/logs/orders/app.log query=\"timed out\" limit=20"), g);
    }
}
