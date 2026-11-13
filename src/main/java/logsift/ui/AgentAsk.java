package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;

/**
 * A coding agent outside Logsift (through logsift-mcp) that can't ask its person itself wants to
 * touch a running JVM: Logsift asks in its window. **Allow**, **Allow for This Session** (not for
 * a heap dump: asked each time) or **Deny**; no answer in five minutes is a no.
 */
final class AgentAsk extends JDialog {
    static final String TITLE = "A Coding Agent Asks";
    /** The answers, as the agent's process reads them. */
    static final int ALLOW = 0;
    static final int DENY = 2;
    static final int SESSION = 3;
    static final int WAIT_MS = 5 * 60_000;

    private final IntConsumer reply;
    private boolean answered;
    private final Timer timeout;
    final JButton allow = new JButton("Allow");
    final JButton session = new JButton("Allow for This Session");
    final JButton deny = new JButton("Deny");
    private static volatile AgentAsk lastForTest;

    /**
     * @param who        the agent ("GitHub Copilot 1.2")
     * @param what       what it wants: "take a heap dump of orders.jar on prod-7 (SSH)"
     * @param detail     what that does to the program
     * @param forSession whether "for this session" is offered
     */
    AgentAsk(Frame owner, String who, String what, String detail, boolean forSession, IntConsumer reply) {
        super(owner, TITLE, ModalityType.MODELESS);
        this.reply = reply;
        setName("agentAsk");
        JPanel p = new JPanel(new BorderLayout(12, 10));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        JLabel icon = new JLabel(Icons.of("circle-alert", Theme.pick(new Color(0xD97706), new Color(0xFBBF24)), 24));
        icon.setVerticalAlignment(JLabel.TOP);
        p.add(icon, BorderLayout.WEST);
        JLabel text = new JLabel("<html><div style='width:380px'><b>" + esc(who) + " wants to " + esc(what) + ".</b>"
                + (detail.isBlank() ? "" : "<br><br>" + esc(detail))
                + "<br><br><span style='color:" + String.format("#%06x",
                Theme.uiColor("Label.disabledForeground", Color.GRAY).getRGB() & 0xFFFFFF) + "'>"
                + (forSession ? "" : "A heap dump is asked each time. ")
                + "What agents may do: Settings → Agents → Running JVMs.</span></div></html>");
        text.setName("agentAskText");
        p.add(text, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(deny);
        if (forSession) {
            buttons.add(session);
        }
        buttons.add(allow);
        p.add(buttons, BorderLayout.SOUTH);
        setContentPane(p);
        allow.addActionListener(e -> answer(ALLOW));
        session.addActionListener(e -> answer(SESSION));
        deny.addActionListener(e -> answer(DENY));
        getRootPane().setDefaultButton(allow);
        getRootPane().registerKeyboardAction(e -> answer(DENY), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                answer(DENY);
            }
        });
        timeout = new Timer(WAIT_MS, e -> answer(DENY));
        timeout.setRepeats(false);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        lastForTest = this;
    }

    /** Shows it in front, the window too (it may be behind the agent's editor). */
    void ask() {
        Frame f = (Frame) getOwner();
        if (f != null) {
            if ((f.getExtendedState() & Frame.ICONIFIED) != 0) {
                f.setExtendedState(f.getExtendedState() & ~Frame.ICONIFIED);
            }
            f.toFront();
        }
        setAlwaysOnTop(true);
        setVisible(true);
        toFront();
        allow.requestFocusInWindow();
        try {
            if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar()
                    .isSupported(java.awt.Taskbar.Feature.USER_ATTENTION)) {
                java.awt.Taskbar.getTaskbar().requestUserAttention(true, false);
            }
        } catch (RuntimeException e) {
            // no taskbar here
        }
        timeout.start();
    }

    private void answer(int code) {
        if (answered) {
            return;
        }
        answered = true;
        timeout.stop();
        dispose();
        reply.accept(code);
    }

    static AgentAsk lastForTest() {
        return lastForTest;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
