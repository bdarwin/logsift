package logsift.agent;

import java.io.IOException;

/**
 * One call of a tool, as every way in runs it (MCP, the window's assistant): the answer or what
 * went wrong, audited, a failure's text masked like any answer.
 *
 * @param text    what goes back to the model; null when cancelled
 * @param failed  whether it's an error the model should read as one
 * @param outcome for the audit log: ok, still working, refused, cancelled, failed
 */
public record ToolCall(String text, boolean failed, String outcome) {

    /** Runs {@code tool} with {@code args} in {@code w}. An interrupt cancels it. */
    public static ToolCall run(Tool tool, Tool.Args args, Workspace w) {
        String text;
        boolean failed = false;
        String outcome;
        try {
            text = tool.run(args, w);
            outcome = "ok";
        } catch (Workspace.StillWorking e) {
            text = e.getMessage();
            outcome = "still working";
        } catch (Tool.Failure | IOException e) {
            text = e.getMessage();
            failed = true;
            outcome = "refused";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "cancelled";
            text = null;
        } catch (Exception | Error e) {
            text = "Logsift failed: " + e;
            failed = true;
            outcome = "failed";
        }
        w.audit(tool.name(), args.brief(), text == null ? 0 : text.length(), outcome);
        if (failed && text != null) {
            text = w.masking().mask(text, null);
        }
        return new ToolCall(text, failed, outcome);
    }
}
