package org.gitee.jmeter.ai.agent.subagent;

import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.presenter.TurnHandle;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.agent.run.AgentRunSpec;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.agent.turn.InjectionItem;
import org.gitee.jmeter.ai.agent.turn.Turn;
import org.gitee.jmeter.ai.agent.turn.TurnRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the load-bearing invariants of the async subagent mechanism that can be
 * verified without a live LLM or JMeter GUI: scope filtering, session-isolation
 * enforcement, blocking drain semantics, and run-context propagation.
 */
class SubagentIsolationTest {

    // ---- helpers ----

    private static class FakeTool extends AbstractTool {
        private final String name;
        private final Set<String> scopes;

        FakeTool(String name, Set<String> scopes) {
            this.name = name;
            this.scopes = scopes;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "fake " + name; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public Set<String> getScopes() { return scopes; }
        @Override protected ToolResult executeInternal(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }

    // ---- scope mechanism ----

    @Test
    void toolsDefaultToCoreScopeOnly() {
        // A tool that does not override getScopes() — i.e. every pre-existing tool.
        Tool tool = new Tool() {
            @Override public String getName() { return "legacy"; }
            @Override public String getDescription() { return "legacy"; }
            @Override public String getParameterSchema() { return "{}"; }
            @Override public ToolResult execute(Map<String, Object> parameters) {
                return ToolResult.success("ok");
            }
        };

        assertEquals(Set.of(Tool.SCOPE_CORE), tool.getScopes());
        assertFalse(tool.getScopes().contains(Tool.SCOPE_SUBAGENT),
            "default scope must not expose a tool to subagents");
    }

    @Test
    void subagentToolsetIncludesOnlySubagentScopedTools() {
        ToolRegistry main = new ToolRegistry();
        main.register(new FakeTool("read_only", Set.of(Tool.SCOPE_CORE, Tool.SCOPE_SUBAGENT)));
        main.register(new FakeTool("mutating", Set.of(Tool.SCOPE_CORE)));
        main.register(new FakeTool("spawn", Set.of(Tool.SCOPE_CORE)));
        main.register(new FakeTool("subagent_status", Set.of(Tool.SCOPE_CORE)));

        SubagentManager manager = new SubagentManager(SubagentTestSupport.contextBuilder(), null, main, (k, t, m) -> true);
        try {
            ToolRegistry subset = manager.getSubagentToolset();

            assertTrue(subset.has("read_only"), "subagent-scoped tool must be included");
            assertFalse(subset.has("mutating"), "core-only tool must be excluded");
            assertFalse(subset.has("spawn"), "spawn must be excluded — this is the recursion guard");
            assertFalse(subset.has("subagent_status"), "status tool must be excluded from subagents");
            assertEquals(1, subset.size());

            // The LLM tool list is derived from the registry, so exclusion is real.
            assertEquals(1, subset.getToolDefinitionObjects().size());
            assertEquals("read_only", subset.getToolDefinitionObjects().get(0).getName());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void subagentToolsetIsCachedAcrossCalls() {
        ToolRegistry main = new ToolRegistry();
        main.register(new FakeTool("read_only", Set.of(Tool.SCOPE_CORE, Tool.SCOPE_SUBAGENT)));

        SubagentManager manager = new SubagentManager(SubagentTestSupport.contextBuilder(), null, main, (k, t, m) -> true);
        try {
            assertSame(manager.getSubagentToolset(), manager.getSubagentToolset());
        } finally {
            manager.shutdown();
        }
    }

    // ---- session isolation invariants (enforced at spec construction) ----

    @Test
    void subagentSpecRejectsSessionPersistence() {
        AgentRunSpec.Builder builder = AgentRunSpec.builder()
            .sessionKey(AgentRunSpec.SUBAGENT_SESSION_PREFIX + "abc123")
            .initialMessages(List.of(Message.user("task")))
            .persistSession(true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(e.getMessage().contains("persistSession"));
    }

    @Test
    void subagentSpecRejectsInjectionCallback() {
        AgentRunSpec.Builder builder = AgentRunSpec.builder()
            .sessionKey(AgentRunSpec.SUBAGENT_SESSION_PREFIX + "abc123")
            .initialMessages(List.of(Message.user("task")))
            .persistSession(false)
            .injectionCallback(limit -> List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(e.getMessage().contains("injectionCallback"));
    }

    @Test
    void subagentSpecRequiresInitialMessages() {
        AgentRunSpec.Builder builder = AgentRunSpec.builder()
            .sessionKey(AgentRunSpec.SUBAGENT_SESSION_PREFIX + "abc123")
            .persistSession(false);

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void validSubagentSpecBuilds() {
        AgentRunSpec spec = AgentRunSpec.builder()
            .sessionKey(AgentRunSpec.SUBAGENT_SESSION_PREFIX + "abc123")
            .initialMessages(List.of(Message.user("task")))
            .persistSession(false)
            .failOnToolError(true)
            .build();

        assertFalse(spec.isPersistSession());
        assertTrue(spec.isFailOnToolError());
        assertNull(spec.getInjectionCallback());
    }

    @Test
    void mainAgentSpecStillDefaultsToPersisting() {
        AgentRunSpec spec = AgentRunSpec.builder()
            .userMessage("hello")
            .sessionKey("chat:main")
            .build();

        assertTrue(spec.isPersistSession(), "existing main-agent behaviour must be unchanged");
    }

    // ---- blocking drain ----

    @Test
    void drainBlockingReturnsReadyMessagesWithoutWaiting() {
        TurnRegistry registry = new TurnRegistry();
        Turn turn = armedTurn(registry, "s1");
        registry.offer("s1", "ready", false);

        long start = System.currentTimeMillis();
        List<String> items = texts(turn.drainBlocking(3, 10_000));

        assertEquals(List.of("ready"), items);
        assertTrue(System.currentTimeMillis() - start < 1_000, "must not block when a message is ready");
    }

    @Test
    void drainBlockingReturnsEmptyOnTimeout() {
        Turn turn = armedTurn(new TurnRegistry(), "s1");

        List<String> items = texts(turn.drainBlocking(3, 150));

        assertTrue(items.isEmpty());
    }

    @Test
    void drainBlockingWakesWhenResultArrives() throws Exception {
        TurnRegistry registry = new TurnRegistry();
        Turn turn = armedTurn(registry, "s1");

        AtomicReference<List<String>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            result.set(texts(turn.drainBlocking(3, 10_000)));
            done.countDown();
        });
        waiter.start();

        Thread.sleep(100);
        registry.offer("s1", "subagent result", false);

        assertTrue(done.await(5, TimeUnit.SECONDS), "waiter must wake when a result is offered");
        assertEquals(List.of("subagent result"), result.get());
    }

    @Test
    void drainBlockingRestoresInterruptFlagAndReturnsEmpty() throws Exception {
        Turn turn = armedTurn(new TurnRegistry(), "s1");

        AtomicReference<List<String>> result = new AtomicReference<>();
        AtomicBoolean interruptFlagSet = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            result.set(texts(turn.drainBlocking(3, 30_000)));
            interruptFlagSet.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });
        waiter.start();

        Thread.sleep(150);
        waiter.interrupt();

        assertTrue(done.await(5, TimeUnit.SECONDS), "interrupt must not leave the thread parked");
        assertTrue(result.get().isEmpty(), "interrupted drain returns empty rather than throwing");
        assertTrue(interruptFlagSet.get(), "interrupt flag must be restored so the loop aborts");
    }

    /**
     * 构造已注册进 registry 并武装队列的回合（drain 语义测试用；offer 侧走同一
     * 注册表路由，与生产 AgentLoop 同构）。
     */
    private static Turn armedTurn(TurnRegistry registry, String sessionKey) {
        Turn turn = new Turn(sessionKey,
                new TurnHandle(sessionKey, TurnOrigin.LOCAL_PANEL, "echo", false),
                null, false);
        registry.register(sessionKey, turn);
        turn.armQueue();
        return turn;
    }

    /** 句柄 API 返回 InjectionItem；断言仍按纯文本比较。 */
    private static List<String> texts(List<InjectionItem> items) {
        return items.stream().map(InjectionItem::getText).toList();
    }

    // ---- run context ----

    @Test
    void runContextIsThreadLocalAndClearable() {
        assertNull(AgentRunContext.current());

        AgentRunContext.set(new AgentRunContext("chat:main", "run-1"));
        assertNotNull(AgentRunContext.current());
        assertEquals("chat:main", AgentRunContext.current().getSessionKey());
        assertEquals("run-1", AgentRunContext.current().getRunId());

        AgentRunContext.clear();
        assertNull(AgentRunContext.current(), "must clear — carrier threads are pooled");
    }

    @Test
    void runContextDoesNotLeakToOtherThreads() throws Exception {
        AgentRunContext.set(new AgentRunContext("chat:main", "run-1"));
        try {
            AtomicReference<AgentRunContext> seen = new AtomicReference<>();
            Thread other = new Thread(() -> seen.set(AgentRunContext.current()));
            other.start();
            other.join();

            assertNull(seen.get(), "context must not be inherited by unrelated threads");
        } finally {
            AgentRunContext.clear();
        }
    }

    // ---- status ----

    @Test
    void statusSnapshotsAreImmutable() {
        SubagentStatus status = new SubagentStatus(
            "id1", "label", "task", "chat:main", java.time.Instant.now());

        status.setToolEvents(new java.util.ArrayList<>(List.of()));
        assertThrows(UnsupportedOperationException.class,
            () -> status.getToolEvents().add(null),
            "readers must not be able to mutate a published snapshot");

        assertFalse(status.isTerminal());
        status.markFinished("done", "ok");
        assertTrue(status.isTerminal());
        assertEquals(SubagentStatus.Phase.DONE, status.getPhase());
        assertEquals("done", status.getResult());
        assertNotNull(status.getFinishedAt());
    }

    @Test
    void statusMarkErrorIsTerminal() {
        SubagentStatus status = new SubagentStatus(
            "id1", "label", "task", "chat:main", java.time.Instant.now());

        status.markError("boom");

        assertTrue(status.isTerminal());
        assertEquals(SubagentStatus.Phase.ERROR, status.getPhase());
        assertEquals("boom", status.getError());
    }
}
