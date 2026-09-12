package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.testsupport.RecordingSubscriber;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.TurnContentAccumulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * USAGE 进度通知单测（context-usage-indicator P0）：每次 LLM 调用返回且 usage 非空
 * → PROGRESS(USAGE) 事件（载荷 = usage map、message 空）；多迭代回合逐次通知、末次
 * 反映最终调用；无 usage 响应零通知；USAGE 先于回合终态。脚手架同
 * {@code AgentLoopTurnEventTest}（GatedScriptAiService + RecordingSubscriber + NoopTool）。
 */
class UsageProgressEventTest {

    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

    AgentLoop loop;
    GatedScriptAiService aiService;
    RecordingSubscriber recorder;

    @BeforeEach
    void setUp() {
        aiService = new GatedScriptAiService();
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "usage-test-session"), aiService);
        recorder = new RecordingSubscriber();
        loop.addTurnSubscriber(recorder);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    private static LLMResponse textWithUsage(String content, int promptTokens, int completionTokens) {
        return LLMResponse.builder()
                .content(content)
                .finishReason("stop")
                .usage(Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens))
                .build();
    }

    // ---- 1. 带 usage 的调用 → 恰一枚 PROGRESS(USAGE)，载荷为 usage map ----

    @Test
    void usageEventCarriesPayload() throws Exception {
        String current = InstanceContext.currentSessionKey();
        aiService.script(textWithUsage("R1", 1200, 30));

        AgentResponse response = loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("R1", response.getContent());

        List<ProgressUpdate> usages = usageUpdates();
        assertEquals(1, usages.size());
        assertEquals(Map.of("prompt_tokens", 1200, "completion_tokens", 30), usages.get(0).getPayload());
        assertEquals("", usages.get(0).getMessage());
    }

    // ---- 2. 多迭代回合逐次通知，末次反映最终调用 ----

    @Test
    void multiIterationTurnNotifiesPerCall() throws Exception {
        String current = InstanceContext.currentSessionKey();
        aiService.script(LLMResponse.builder()
                .toolCalls(List.of(new ToolCall("noop_tool", Map.of())))
                .finishReason("tool_calls")
                .usage(Map.of("prompt_tokens", 1000, "completion_tokens", 10))
                .build());
        aiService.script(textWithUsage("FINAL", 3000, 40));

        loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<ProgressUpdate> usages = usageUpdates();
        assertEquals(2, usages.size());
        assertEquals(1000, ((Map<?, ?>) usages.get(0).getPayload()).get("prompt_tokens"));
        assertEquals(3000, ((Map<?, ?>) usages.get(1).getPayload()).get("prompt_tokens"));
    }

    // ---- 3. 无 usage 响应 → 零 USAGE 事件 ----

    @Test
    void noUsageResponseEmitsNothing() throws Exception {
        String current = InstanceContext.currentSessionKey();
        aiService.script(LLMResponse.text("R1"));

        loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(usageUpdates().isEmpty(), "responses without usage must not emit USAGE events");
    }

    // ---- 4. USAGE 先于回合终态（本回合无工具迭代，唯一 PROGRESS 即 USAGE）----

    @Test
    void usagePrecedesTerminal() throws Exception {
        String current = InstanceContext.currentSessionKey();
        aiService.script(textWithUsage("R1", 500, 5));

        loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, usageUpdates().size());
        long turnId = recorder.startedTurnId();
        assertTrue(recorder.indexOf(TurnEvent.Kind.PROGRESS, turnId)
                        < recorder.indexOf(TurnEvent.Kind.TURN_COMPLETED, turnId),
                "USAGE must be delivered before the turn terminal event");
    }

    // ---- 5. IPC 累积器隔离：USAGE 不是可累积的助手内容 ----

    @Test
    void ipcAccumulatorIgnoresUsageUpdates() {
        TurnContentAccumulator accumulator = new TurnContentAccumulator();
        accumulator.onProgress(ProgressUpdate.usage(Map.of("prompt_tokens", 100, "completion_tokens", 5)));
        assertEquals("", accumulator.snapshotTruncated(),
                "USAGE must not pollute the IPC partialContent accumulator");
    }

    private List<ProgressUpdate> usageUpdates() {
        return recorder.progressUpdates.stream()
                .filter(u -> u.getType() == ProgressUpdate.Type.USAGE)
                .toList();
    }
}
