package logsift.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import logsift.agent.Tool;
import logsift.agent.ToolCall;
import logsift.agent.Workspace;

/**
 * Logsift's agent tools for the window's assistant: the same tools, rules, masking and audit log
 * as for coding agents over MCP, in a workspace that reads the window's open logs.
 */
public final class ChatTools {
    private ChatTools() {
    }

    /** The tools, run in {@code w}. */
    public static List<ChatEngine.ToolSpec> of(List<Tool> tools, Workspace w) {
        List<ChatEngine.ToolSpec> out = new ArrayList<>();
        for (Tool t : tools) {
            out.add(new ChatEngine.ToolSpec(t.name(), t.description(), t.inputSchema(), (id, args) -> {
                ToolCall c = ToolCall.run(t, new Tool.Args(args == null ? Map.of() : args), w);
                if (c.text() == null) {
                    return new ChatEngine.ToolHandler.Result("Stopped.", true);
                }
                return new ChatEngine.ToolHandler.Result(c.text(), c.failed());
            }, OFTEN.contains(t.name())));
        }
        return out;
    }

    /** The tools most questions about a log need; the rest are found by the model when needed. */
    static final java.util.Set<String> OFTEN = java.util.Set.of("list_logs", "open_log", "list_open", "insights",
            "exceptions", "search", "read_lines", "top_messages", "metrics", "query");

    /** What the assistant is told first: Logsift's rules for agents, then how to answer in the window. */
    public static String instructions(logsift.agent.LogCatalog catalog) {
        return logsift.agent.mcp.McpServer.instructions(catalog) + "\n\n" + WINDOW;
    }

    static final String WINDOW = """
            You are the assistant inside Logsift's window: the person is looking at the logs it has open and reads \
            your answers beside them. Each question may start with what they are looking at (the open log's handle, \
            the lines chosen): use those handles with the tools, without opening the file again.
            Answer in Markdown, short: the finding first, then the evidence. Several items with the same \
            fields (errors by count, slow requests, hosts): a Markdown table. Cite every line you rely on as a link \
            the person can click to see it, written exactly like [app.log:1204](logsift:log:1:1204) (the file's \
            name, the line, then logsift: the handle and the line); a range: [app.log:1204-1230](logsift:log:1:1204-1230). \
            A file not open: link its path, [app.log:12](/var/log/app.log:12). \
            Never invent line numbers: only ones the tools gave. Never draw charts in text: after analyze_gc the chat shows the heap chart \
            just above your answer ("the chart above"), with Show GC Charts for the rest; otherwise give a table.""";
}
