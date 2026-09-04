package org.gitee.jmeter.ai.agent.run;

/**
 * Thread-local identity of the agent run currently executing on this thread.
 *
 * <p>Tools receive only a parameter map ({@code Tool.execute(Map)}), so a tool
 * like {@code spawn} has no way to learn which session it was called from.
 * {@link AgentRunner} binds this context around the run, and
 * {@code ToolRegistry} replays it onto the tool-executor threads used for
 * concurrent tool execution, so it is visible wherever a tool runs.
 *
 * <p>Always clear in a finally block: the run threads are reused (the dedicated
 * agent-loop thread across turns, pooled subagent threads across runs), and a
 * stale session key would route a subagent result into the wrong session.
 */
public final class AgentRunContext {

    private static final ThreadLocal<AgentRunContext> CURRENT = new ThreadLocal<>();

    private final String sessionKey;
    private final String runId;

    public AgentRunContext(String sessionKey, String runId) {
        this.sessionKey = sessionKey;
        this.runId = runId;
    }

    public String getSessionKey() { return sessionKey; }

    public String getRunId() { return runId; }

    /** Bind the context for the current thread. */
    public static void set(AgentRunContext context) {
        CURRENT.set(context);
    }

    /** The context bound to the current thread, or null if none. */
    public static AgentRunContext current() {
        return CURRENT.get();
    }

    /** Remove the binding. Must be called in a finally block on reused threads. */
    public static void clear() {
        CURRENT.remove();
    }

    @Override
    public String toString() {
        return "AgentRunContext{sessionKey=" + sessionKey + ", runId=" + runId + '}';
    }
}
