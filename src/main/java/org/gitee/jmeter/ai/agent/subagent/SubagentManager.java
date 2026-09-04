package org.gitee.jmeter.ai.agent.subagent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.run.AgentRunResult;
import org.gitee.jmeter.ai.agent.run.AgentRunSpec;
import org.gitee.jmeter.ai.agent.run.AgentRunner;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spawns and supervises background subagents.
 *
 * <p>Ports Nanobot's {@code SubagentManager}. A spawn returns immediately; the
 * subagent runs on a dedicated pool with an isolated read-only toolset and its
 * own ephemeral session, then hands its result back through a {@link ResultSink}
 * so the main agent can absorb it within the same turn.
 *
 * <p>Two invariants matter:
 * <ul>
 *   <li>Every spawn builds its OWN {@link AgentRunner} (cheap, keeps concurrent
 *       runs isolated from any future per-run state). Cancel/shutdown
 *       interrupts reach the task's pool thread directly through
 *       {@code handle.runThread} — {@link AgentRunner} does not track its own
 *       thread.</li>
 *   <li>Subagents run through {@code agentRunner.run(spec)} directly, never
 *       through {@code AgentLoop.processMessage}, whose session bookkeeping is
 *       not governed by {@code persistSession}.</li>
 * </ul>
 */
public class SubagentManager {
    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);

    private static final String ANNOUNCE_TEMPLATE = "/templates/subagent/subagent_announce.md";

    private final AiService aiService;
    private final ContextBuilder contextBuilder;
    private final SessionManager sessionManager;
    private final ToolRegistry mainRegistry;
    private final ResultSink resultSink;
    /** Generation settings inherited from the main agent so subagents answer at the same temperature/token budget. */
    private final GenerationSettings generationSettings;

    private final int maxConcurrent;
    private final int maxIterations;
    private final long toolTimeoutMs;
    private final int toolResultMaxChars;
    /** 完成态状态可查询保留时长(秒),超期由 {@link #pruneTerminalStatuses()} 回收。 */
    private final long statusRetentionSeconds;
    /** 每会话完成态状态保留上限,超出按最旧淘汰(内存有界,防晚到结果无限累积)。 */
    private final int statusMaxCompleted;

    private final ExecutorService executor;

    private final Map<String, RunningSubagent> running = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();
    private final Map<String, SubagentStatus> statuses = new ConcurrentHashMap<>();

    /** Guards the check-then-register of the concurrency limit, per main session. */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    private volatile ToolRegistry subagentToolset;

    /**
     * For callers that only need toolset scoping and bookkeeping, with no LLM
     * (nothing can be spawned without an AiService).
     */
    SubagentManager(ContextBuilder contextBuilder,
                    SessionManager sessionManager,
                    ToolRegistry mainRegistry,
                    ResultSink resultSink) {
        this(null, contextBuilder, sessionManager, mainRegistry, resultSink);
    }

    public SubagentManager(AiService aiService,
                           ContextBuilder contextBuilder,
                           SessionManager sessionManager,
                           ToolRegistry mainRegistry,
                           ResultSink resultSink) {
        this.aiService = aiService;
        this.contextBuilder = contextBuilder;
        this.sessionManager = sessionManager;
        this.mainRegistry = mainRegistry;
        this.resultSink = resultSink;
        this.generationSettings = aiService != null ? aiService.getGenerationSettings() : null;

        this.maxConcurrent = Math.max(1, AiConfig.getSubagentMaxConcurrent());
        this.maxIterations = AiConfig.getSubagentMaxIterations();
        this.toolTimeoutMs = AiConfig.getToolTimeoutMs();
        this.toolResultMaxChars = AiConfig.getToolResultMaxChars();
        this.statusRetentionSeconds = AiConfig.getSubagentStatusRetentionSeconds();
        this.statusMaxCompleted = Math.max(0, AiConfig.getSubagentStatusMaxCompleted());

        AtomicInteger threadSeq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "subagent-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(maxConcurrent, factory);

        log.info("SubagentManager initialized (maxConcurrent={}, maxIterations={})",
            maxConcurrent, maxIterations);
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    /**
     * Build the isolated subagent toolset: INCLUDE only tools tagged with the
     * subagent scope. Tools keeping the default {@code {core}} — spawn and
     * subagent_status among them — are absent, which is what stops a subagent
     * from spawning another one. Built once; the main registry is static after
     * startup. Tool instances are shared with the main registry, and so is its
     * executor — a private one here would never be disposed.
     */
    ToolRegistry getSubagentToolset() {
        ToolRegistry cached = subagentToolset;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (subagentToolset == null) {
                // Carry the parent's timeout too: ToolRegistry(Executor) would reset it
                // to the hardcoded default, so a configured agent.tools.timeout.ms would
                // apply to the main agent but silently not to subagents.
                ToolRegistry filtered = new ToolRegistry(toolTimeoutMs, mainRegistry.getExecutor());
                for (String name : mainRegistry.getToolNames()) {
                    Tool tool = mainRegistry.get(name);
                    if (tool != null && tool.getScopes().contains(Tool.SCOPE_SUBAGENT)) {
                        filtered.register(tool);
                    }
                }
                log.info("Built subagent toolset with {} tools: {}",
                    filtered.size(), filtered.getToolNames());
                subagentToolset = filtered;
            }
            return subagentToolset;
        }
    }

    /**
     * Spawn a subagent. Returns immediately with a receipt for the LLM.
     *
     * @param task what the subagent should do
     * @param label short display label (optional)
     * @param mainSessionKey session that spawned it — where the result returns
     * @param turnToken identity of the spawning turn, so a late result is not
     *                  injected into an unrelated later turn
     * @return receipt text, or an error string if the concurrency limit is hit
     */
    public String spawn(String task, String label, String mainSessionKey, TurnToken turnToken) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String displayLabel = (label != null && !label.isBlank())
            ? label
            : (task.length() > 30 ? task.substring(0, 30) + "..." : task);

        SubagentStatus status = new SubagentStatus(
            taskId, displayLabel, task, mainSessionKey, Instant.now());
        AtomicBoolean abortFlag = new AtomicBoolean(false);

        // Check-and-register atomically: with concurrent tool calls two spawns
        // could otherwise both pass a bare count check.
        Object lock = sessionLocks.computeIfAbsent(mainSessionKey, k -> new Object());
        synchronized (lock) {
            int active = getRunningCountBySession(mainSessionKey);
            if (active >= maxConcurrent) {
                return String.format(
                    "Cannot spawn subagent: concurrency limit reached (%d/%d running). "
                    + "Wait for a running subagent to complete before spawning a new one.",
                    active, maxConcurrent);
            }
            statuses.put(taskId, status);
            sessionTasks.computeIfAbsent(mainSessionKey, k -> ConcurrentHashMap.newKeySet()).add(taskId);
            RunningSubagent handle = new RunningSubagent(taskId, mainSessionKey, abortFlag, turnToken);
            running.put(taskId, handle);
            handle.future = executor.submit(
                () -> runSubagent(taskId, task, displayLabel, mainSessionKey, turnToken, status, abortFlag, handle));
        }

        log.info("Spawned subagent [{}]: {}", taskId, displayLabel);
        return String.format(
            "Subagent [%s] started (id: %s). Its result will arrive in this conversation when it completes.",
            displayLabel, taskId);
    }

    private void runSubagent(String taskId, String task, String label, String mainSessionKey,
                             TurnToken turnToken, SubagentStatus status, AtomicBoolean abortFlag,
                             RunningSubagent handle) {
        handle.started = true;
        // 池线程直达中断：cancelBySession/shutdown 经 handle.runThread.interrupt()
        // 命中本任务——显式方案，不依赖下方 future.cancel(true) 的隐式中断副作用
        // （防未来 cancel(true)→cancel(false) 静默丢子代理中断）。句目随 releaseSlot
        // 从 running 表摘除后句柄不可达，引用无需显式清空。
        handle.runThread = Thread.currentThread();
        log.info("Subagent [{}] starting task: {}", taskId, label);
        try {
            // Own runner per spawn: keeps concurrent runs isolated from any
            // future per-run state (interrupt targeting no longer reasons about
            // the runner — it goes through handle.runThread above).
            // Null consolidator: subagents never consolidate memory (persistSession=false
            // gates both call sites); ContextWindowManager tolerates null.
            AgentRunner runner = new AgentRunner(
                getSubagentToolset(),
                null,
                contextBuilder,
                sessionManager,
                aiService,
                maxIterations,
                toolResultMaxChars,
                toolTimeoutMs);

            List<Message> initial = List.of(
                Message.system(buildSubagentPrompt()),
                Message.user(task));

            // AgentRunner.run is synchronous and executes inline on this subagent
            // pool thread — which is what keeps subagents off the main agent's
            // thread (the old "NO runExecutor, must not join on our own bounded
            // pool" starvation concern is gone with the async wrapper).
            AgentRunSpec spec = AgentRunSpec.builder()
                .sessionKey(AgentRunSpec.SUBAGENT_SESSION_PREFIX + taskId)
                .initialMessages(initial)
                .persistSession(false)
                .failOnToolError(true)
                .maxIterations(maxIterations)
                .model(AiConfig.getDefaultModel())
                // Same generation settings as the main agent: a subagent running on
                // SDK defaults would answer with a different temperature and token
                // budget than everything else in the conversation.
                .temperature(generationSettings.getTemperature())
                .maxTokens(generationSettings.getMaxTokens())
                .reasoningEffort(generationSettings.getReasoningEffort())
                .hook(new SubagentHook(taskId, status))
                .abortFlag(abortFlag)
                .build();

            AgentRunResult result = runner.run(spec);

            if (abortFlag.get()) {
                status.markError("Cancelled");
                log.info("Subagent [{}] cancelled", taskId);
                return;
            }

            // isSuccess() only says the run did not throw: a run aborted by
            // failOnToolError still reports success with an error string as its
            // content. Announcing that as "completed successfully" would have the
            // main agent relay a tool failure as a finding.
            boolean toolAborted = "tool_error".equals(result.getStopReason());
            if (result.isSuccess() && !toolAborted) {
                String content = result.getContent() != null && !result.getContent().isBlank()
                    ? result.getContent()
                    : "Task completed but no final response was generated.";
                status.markFinished(content, "ok");
                log.info("Subagent [{}] completed successfully", taskId);
                announceResult(taskId, label, task, content, mainSessionKey, turnToken, true);
            } else {
                String error = result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : (toolAborted && result.getContent() != null
                        ? result.getContent()
                        : "Error: subagent execution failed.");
                status.markError(error);
                log.warn("Subagent [{}] failed: {}", taskId, error);
                announceResult(taskId, label, task, error, mainSessionKey, turnToken, false);
            }
        } catch (Throwable e) {
            // Throwable（含 Error）：run() 同步直调后异常不再经 .join() 包成
            // CompletionException，Error 会裸穿——不在这里收口就静默死进无人
            // get() 的 executor future，子代理状态永挂 running、结果无人公告。
            log.error("Subagent [" + taskId + "] failed", e);
            status.markError(String.valueOf(e.getMessage()));
            announceResult(taskId, label, task, "Error: " + e.getMessage(),
                mainSessionKey, turnToken, false);
        } finally {
            releaseSlot(taskId, mainSessionKey);
        }
    }

    /**
     * Release a subagent's bookkeeping. Idempotent, because it runs either from the
     * task's own finally or — when the task was cancelled before it ever started —
     * from {@link #cancelBySession}. Missing the second path would leave the slot
     * occupied forever: the session would look permanently busy, so every later turn
     * would block on the drain wait and every later spawn would be refused.
     *
     * <p>Note the session lock is deliberately NOT evicted: dropping it while another
     * thread holds it would let the next spawn synchronize on a fresh monitor and slip
     * past the concurrency check. The map is bounded by the number of sessions.
     */
    private void releaseSlot(String taskId, String mainSessionKey) {
        running.remove(taskId);
        Set<String> ids = sessionTasks.get(mainSessionKey);
        if (ids != null) {
            ids.remove(taskId);
            if (ids.isEmpty()) {
                sessionTasks.remove(mainSessionKey);
            }
        }
        pruneTerminalStatuses();
    }

    /**
     * Bound the terminal-status store so a long-lived instance never accumulates
     * unbounded {@code statuses} entries (each late/undeliverable result stays
     * queryable via {@code subagent_status}, but only for a window).
     *
     * <p>Two independent bounds, both evaluated on every finish (cheap relative to a
     * run). A knob value of {@code 0} disables that bound:
     * <ul>
     *   <li><b>TTL</b> — a terminal status whose {@code finishedAt} is older than
     *       {@code statusRetentionSeconds} is dropped ({@code 0} = never by TTL).</li>
     *   <li><b>Per-session cap</b> — within one session, terminal statuses are kept
     *       newest-first; the oldest are dropped beyond {@code statusMaxCompleted}
     *       ({@code 0} = never by count).</li>
     * </ul>
     * Running (non-terminal) statuses are never touched.
     */
    private void pruneTerminalStatuses() {
        Instant now = Instant.now();
        long retentionMs = statusRetentionSeconds * 1000L;

        // TTL pass + collect terminal statuses grouped by session for the cap pass.
        Map<String, List<SubagentStatus>> terminalBySession = new java.util.HashMap<>();
        for (SubagentStatus status : statuses.values()) {
            if (!status.isTerminal()) {
                continue;
            }
            if (statusRetentionSeconds > 0) {
                Instant finishedAt = status.getFinishedAt();
                if (finishedAt != null && now.toEpochMilli() - finishedAt.toEpochMilli() > retentionMs) {
                    statuses.remove(status.getTaskId());
                    continue;
                }
            }
            if (statusMaxCompleted > 0) {
                terminalBySession.computeIfAbsent(status.getMainSessionKey(), k -> new ArrayList<>())
                    .add(status);
            }
        }

        // Per-session cap pass: keep newest, drop oldest beyond the cap.
        if (statusMaxCompleted <= 0) {
            return;
        }
        for (List<SubagentStatus> sessionTerminal : terminalBySession.values()) {
            if (sessionTerminal.size() <= statusMaxCompleted) {
                continue;
            }
            sessionTerminal.sort(Comparator.comparing(
                s -> s.getFinishedAt() != null ? s.getFinishedAt() : s.getStartedAt()));
            int excess = sessionTerminal.size() - statusMaxCompleted;
            for (int i = 0; i < excess; i++) {
                statuses.remove(sessionTerminal.get(i).getTaskId());
            }
        }
    }

    /**
     * Hand the result to the main agent's injection queue, but only if the turn
     * that spawned this subagent is still the active one — otherwise the text
     * would be injected into an unrelated later turn and derail it. When it
     * cannot be delivered the status is kept so the main agent can still pull it
     * via {@code subagent_status}.
     */
    private void announceResult(String taskId, String label, String task, String result,
                                String mainSessionKey, TurnToken turnToken, boolean ok) {
        String announcement = renderAnnouncement(label, task, result, ok);

        // The turn check happens inside offer(), atomically with the enqueue: doing
        // it here first would leave a window for the turn to end in between.
        Object identity = turnToken != null ? turnToken.identity() : null;
        boolean delivered = resultSink != null
            && resultSink.offer(mainSessionKey, identity, announcement);
        if (delivered) {
            log.debug("Subagent [{}] result delivered to session {}", taskId, mainSessionKey);
        } else {
            log.info("Subagent [{}] result undeliverable (no active run); kept for status query", taskId);
        }
    }

    private String renderAnnouncement(String label, String task, String result, boolean ok) {
        String template = loadTemplate();
        String statusText = ok ? "completed successfully" : "failed";
        return template
            .replace("{{label}}", label == null ? "" : label)
            .replace("{{status_text}}", statusText)
            .replace("{{task}}", task == null ? "" : task)
            .replace("{{result}}", result == null ? "" : result);
    }

    private String loadTemplate() {
        try (var in = SubagentManager.class.getResourceAsStream(ANNOUNCE_TEMPLATE)) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Could not load subagent announce template, using fallback: {}", e.getMessage());
        }
        return "Subagent [{{label}}] {{status_text}}.\n\nTask: {{task}}\n\nResult:\n{{result}}\n";
    }

    /** Focused system prompt: no memory, no bootstrap files — just identity, workspace and skills. */
    private String buildSubagentPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a subagent spawned by the main JMeter AI agent to complete one specific task.\n")
          .append("Work autonomously and finish the task with the read-only tools available to you.\n")
          .append("You cannot modify the test plan, run tests, write files, or spawn other subagents.\n")
          .append("When done, reply with a concise, self-contained summary of what you found — ")
          .append("your reply is handed back to the main agent verbatim.\n\n")
          .append("Current Time: ")
          .append(java.time.LocalDateTime.now()
              .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
          .append('\n');

        try {
            String skills = contextBuilder.getSkillsLoader().buildSkillsSummary();
            if (skills != null && !skills.isEmpty()) {
                sb.append("\n# Skills\n\n")
                  .append("Read a skill's SKILL.md with the read_file tool to use it.\n\n")
                  .append(skills);
            }
        } catch (Exception e) {
            log.warn("Could not build skills summary for subagent prompt: {}", e.getMessage());
        }
        return sb.toString();
    }

    /** Number of subagents still running for a session, regardless of which turn spawned them. */
    public int getRunningCountBySession(String sessionKey) {
        return countRunning(sessionKey, false);
    }

    /**
     * Number of running subagents whose spawning turn is still active — the
     * "should the main agent block?" signal.
     *
     * <p>Must not count subagents left over from an earlier turn: their results are
     * discarded on arrival (the turn that asked for them is gone), so waiting on them
     * would park the current turn for the full drain timeout with no possible payoff.
     */
    public int getWaitableCountBySession(String sessionKey) {
        return countRunning(sessionKey, true);
    }

    private int countRunning(String sessionKey, boolean onlyActiveTurn) {
        Set<String> ids = sessionTasks.get(sessionKey);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String id : ids) {
            RunningSubagent handle = running.get(id);
            if (handle == null) {
                continue;
            }
            if (onlyActiveTurn && handle.turnToken != null && !handle.turnToken.isActive()) {
                continue;
            }
            count++;
        }
        return count;
    }

    public int getRunningCount() {
        return running.size();
    }

    public SubagentStatus getStatus(String taskId) {
        return statuses.get(taskId);
    }

    /** Statuses for a session; completed ones included only if asked for. */
    public List<SubagentStatus> getStatuses(String sessionKey, boolean includeCompleted) {
        List<SubagentStatus> result = new ArrayList<>();
        for (SubagentStatus status : statuses.values()) {
            if (sessionKey != null && !sessionKey.equals(status.getMainSessionKey())) {
                continue;
            }
            if (!includeCompleted && status.isTerminal()) {
                continue;
            }
            result.add(status);
        }
        result.sort(Comparator.comparing(SubagentStatus::getStartedAt));
        return result;
    }

    /**
     * Cancel every subagent of a session. Sets the abort flag BEFORE interrupting
     * so the run exits at its next abort check even if the interrupt lands in a
     * spot that swallows it.
     *
     * @return number of subagents cancelled
     */
    public int cancelBySession(String sessionKey) {
        Set<String> ids = sessionTasks.get(sessionKey);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (String id : new ArrayList<>(ids)) {
            RunningSubagent handle = running.get(id);
            if (handle == null) {
                continue;
            }
            handle.abortFlag.set(true);
            if (handle.runThread != null) {
                handle.runThread.interrupt();
            }
            boolean neverStarted = handle.future != null
                && handle.future.cancel(true)
                && !handle.started;
            if (neverStarted) {
                // The task was still queued, so its body — and its cleanup — will
                // never run. Release the slot here or it stays occupied forever.
                SubagentStatus status = statuses.get(id);
                if (status != null) {
                    status.markError("Cancelled before it started");
                }
                releaseSlot(id, sessionKey);
            }
            cancelled++;
        }
        if (cancelled > 0) {
            log.info("Cancelled {} subagent(s) for session {}", cancelled, sessionKey);
        }
        return cancelled;
    }

    /** Cancel everything in flight and shut the pool down. */
    public void shutdown() {
        for (RunningSubagent handle : running.values()) {
            handle.abortFlag.set(true);
            if (handle.runThread != null) {
                handle.runThread.interrupt();
            }
        }
        executor.shutdownNow();
        running.clear();
        sessionTasks.clear();
        statuses.clear();
        log.info("SubagentManager shutdown complete");
    }

    /** Identity of the turn a subagent was spawned from. */
    public interface TurnToken {
        /** Whether that turn is still the active one for its session. */
        boolean isActive();

        /**
         * The underlying turn object, handed to the sink so it can re-validate
         * atomically with the enqueue. Defaults to the token itself for callers
         * (mainly tests) that have no separate identity.
         */
        default Object identity() {
            return this;
        }
    }

    private static class RunningSubagent {
        final String taskId;
        final String mainSessionKey;
        final AtomicBoolean abortFlag;
        /** The turn that spawned this subagent; used to scope drain waits and delivery. */
        final TurnToken turnToken;
        /** 执行本任务的池线程（任务体开头赋值）：取消/shutdown 的 interrupt 直达目标（见 runSubagent 注释）。 */
        volatile Thread runThread;
        volatile Future<?> future;
        /** Set when the task body begins; false means a cancel skipped it entirely. */
        volatile boolean started;

        RunningSubagent(String taskId, String mainSessionKey, AtomicBoolean abortFlag, TurnToken turnToken) {
            this.taskId = taskId;
            this.mainSessionKey = mainSessionKey;
            this.abortFlag = abortFlag;
            this.turnToken = turnToken;
        }
    }
}
