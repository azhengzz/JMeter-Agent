package org.gitee.jmeter.ai.agent.subagent;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.agent.tools.subagent.SpawnTool;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a real {@link AgentLoop} end to end to cover the two behaviours that
 * cannot be seen from {@code SubagentManager} alone: turn-confluence and
 * graceful degradation when the drain times out.
 */
class SubagentTurnConfluenceIT {

    @BeforeAll
    static void initJMeterProperties() throws Exception {
        // JMeterUtils.setProperty NPEs until appProperties exists, and the drain
        // timeout is read once in the AgentLoop constructor — so seed before building.
        Path props = Files.createTempFile("jmeter-it", ".properties");
        Files.writeString(props, "# test\n");
        JMeterUtils.loadJMeterProperties(props.toString());
        JMeterUtils.setProperty("agent.subagent.drain.timeout.seconds", "3");
        JMeterUtils.setProperty("jmeter.ai.max.tool.iterations", "6");
    }

    /**
     * Scripted LLM: turn 1 calls spawn, then answers. Whatever it is handed after
     * that is recorded, so the test can prove the subagent result reached the model
     * inside the same turn.
     */
    private static class ScriptedAiService implements AiService {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<List<Message>> lastMessages = new AtomicReference<>();
        final CountDownLatch sawSubagentResult = new CountDownLatch(1);
        volatile boolean spawnRequested;

        @Override public String getName() { return "scripted"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }

        @Override
        public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            return generateResponseWithTools(messages, tools, null);
        }

        @Override
        public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools,
                                                     LlmCallOptions options) {
            lastMessages.set(List.copyOf(messages));

            boolean carriesSubagentResult = messages.stream()
                .anyMatch(m -> m.getRole() == Message.Role.USER
                    && m.getContent() != null
                    && m.getContent().contains("SUBAGENT_FINDING"));
            if (carriesSubagentResult) {
                sawSubagentResult.countDown();
                return LLMResponse.text("Relaying: the subagent reported SUBAGENT_FINDING.");
            }

            if (calls.incrementAndGet() == 1) {
                spawnRequested = true;
                ToolCall call = new ToolCall(
                    "call-1", "spawn", Map.of("task", "audit the plan", "label", "audit"));
                return LLMResponse.withToolCalls(List.of(call), null);
            }
            return LLMResponse.text("Nothing further.");
        }
    }

    /** The subagent's own LLM: answers with the marker the main agent must relay. */
    private static class SubagentAiService implements AiService {
        private final long delayMs;
        SubagentAiService(long delayMs) { this.delayMs = delayMs; }

        @Override public String getName() { return "subagent-llm"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return LLMResponse.error("Interrupted");
                }
            }
            return LLMResponse.text("SUBAGENT_FINDING: 3 samplers lack assertions");
        }
    }

    private static class Harness implements AutoCloseable {
        final AgentLoop loop;
        final SubagentManager manager;
        final ScriptedAiService mainAi;

        Harness(long subagentDelayMs) throws Exception {
            Path workspace = Files.createTempDirectory("subagent-it");
            ToolRegistry registry = new ToolRegistry();
            MemoryStore memory = new MemoryStore(workspace);
            SessionManager sessions = new SessionManager(workspace);
            ContextBuilder context = new ContextBuilder(memory, workspace);
            mainAi = new ScriptedAiService();
            MemoryConsolidator consolidator =
                new MemoryConsolidator(memory, mainAi, sessions, context, registry);

            loop = new AgentLoop(registry, memory, consolidator, context, sessions, mainAi);
            manager = new SubagentManager(
                new SubagentAiService(subagentDelayMs), context, sessions, registry,
                loop::offerInjection);
            loop.setSubagentManager(manager);
            registry.register(new SpawnTool(manager,
                () -> {
                    var ctx = org.gitee.jmeter.ai.agent.run.AgentRunContext.current();
                    return ctx == null ? null : loop.currentTurnToken(ctx.getSessionKey());
                }));
        }

        @Override public void close() {
            manager.shutdown();
            loop.shutdown();
        }
    }

    /**
     * Turn confluence: the main agent spawns, blocks at an injection
     * checkpoint, absorbs the subagent's result, and relays it in the SAME turn.
     */
    @Test
    void subagentResultIsAbsorbedAndRelayedWithinTheSameTurn() throws Exception {
        try (Harness h = new Harness(300)) {
            CompletableFuture<AgentResponse> turn =
                h.loop.processMessage("audit my plan", "chat:main");

            AgentResponse response = turn.get(30, TimeUnit.SECONDS);

            assertTrue(h.mainAi.spawnRequested, "the scripted model should have called spawn");
            assertTrue(h.mainAi.sawSubagentResult.await(1, TimeUnit.SECONDS),
                "the subagent's result must be fed back to the model inside this turn");
            assertNotNull(response);
            assertTrue(response.isSuccess(), "turn should succeed: " + response.getContent());
            assertTrue(response.getContent().contains("SUBAGENT_FINDING"),
                "the turn's final answer must relay the subagent's finding: " + response.getContent());

            // The injected announcement reached the model as a user message.
            assertTrue(h.mainAi.lastMessages.get().stream()
                    .anyMatch(m -> m.getRole() == Message.Role.USER
                        && m.getContent() != null
                        && m.getContent().contains("SUBAGENT_FINDING")),
                "the announcement should be injected as a user message");
        }
    }

    /**
     * Degradation: when the subagent outlives the drain timeout, the
     * turn must finish anyway rather than hanging, and the late result must stay
     * retrievable instead of being dropped.
     */
    @Test
    void turnFinishesWhenTheSubagentOutlivesTheDrainTimeout() throws Exception {
        // Drain timeout is 3s (seeded above); the subagent takes ~6s.
        try (Harness h = new Harness(6_000)) {
            long start = System.currentTimeMillis();
            CompletableFuture<AgentResponse> turn =
                h.loop.processMessage("audit my plan", "chat:main");

            AgentResponse response = turn.get(30, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            assertNotNull(response);
            assertTrue(response.isSuccess(), "the turn must still complete: " + response.getContent());
            assertTrue(elapsed < 25_000,
                "the turn must give up on the slow subagent, not hang; took " + elapsed + "ms");

            // The subagent keeps running and its result remains queryable.
            long deadline = System.currentTimeMillis() + 20_000;
            SubagentStatus status = null;
            while (System.currentTimeMillis() < deadline) {
                List<SubagentStatus> all = h.manager.getStatuses("chat:main", true);
                if (!all.isEmpty() && all.get(0).isTerminal()) {
                    status = all.get(0);
                    break;
                }
                Thread.sleep(100);
            }
            assertNotNull(status, "the late subagent should still reach a terminal state");
            assertTrue(status.getResult() != null || status.getError() != null,
                "a late result must remain retrievable via subagent_status, not be discarded");
        }
    }

    /**
     * drain 超时闩锁（{@code Turn.markDrainTimedOut}）：首个空检查点阻塞等待超时后，
     * 本回合后续检查点不得再次阻塞等待——否则每个检查点都等满超时，回合时长随迭代数
     * 线性膨胀。mock 的 SubagentManager 恒报 1 个可等待子代理（实际无人回灌结果），
     * 4 个工具迭代 = 4 个空检查点：闩锁生效 ≈ 1×3s；闩锁失效 ≥ 4×3s=12s。
     */
    @Test
    void drainTimeoutLatches_subsequentCheckpointsDoNotBlockAgain() throws Exception {
        Path workspace = Files.createTempDirectory("drain-latch");
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new NoopTool());
        GatedScriptAiService ai = new GatedScriptAiService();

        AgentLoop latchLoop = new AgentLoop(registry, memoryStore,
            Mockito.mock(MemoryConsolidator.class),
            new ContextBuilder(memoryStore, workspace),
            new SessionManager(workspace, "chat:latch"), ai);
        SubagentManager manager = Mockito.mock(SubagentManager.class);
        Mockito.when(manager.getWaitableCountBySession("chat:latch")).thenReturn(1);
        latchLoop.setSubagentManager(manager);
        try {
            for (int i = 0; i < 4; i++) {
                ai.script(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "s" + i));
            }
            ai.script(LLMResponse.text("LATCH-FINAL"));

            long start = System.currentTimeMillis();
            AgentResponse response = latchLoop.processMessage("m", "chat:latch")
                .get(60, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(response.isSuccess(), "turn should succeed: " + response.getErrorMessage());
            assertEquals("LATCH-FINAL", response.getContent());
            assertTrue(elapsed < 7_000,
                "drain timeout must latch after the first blocking wait; "
                    + "4 checkpoints × 3s would take ≥12s, took " + elapsed + "ms");
        } finally {
            latchLoop.shutdown();
        }
    }
}
