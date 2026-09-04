package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.presenter.TurnSubscriber;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.InterruptStrategy;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.testsupport.RecordingSubscriber;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnSubscriber} 事件流单测（P0 加法层）：回合内严格序、跨回合终态先于下回合
 * STARTED、垂死终态先于 re-publish 孤儿 STARTED、终态恰好一次（signalCancel vs 回合体
 * 双发射点）、派发三守卫、IPC 命令回合只发终态、COMMAND_RESULT Phase1/Phase2 两点、
 * 工厂级晚挂接（先有 loop 后有订阅者）。
 *
 * <p>脚手架收编于 {@code agent.testsupport}：GatedScriptAiService（脚本化/门控 fake，
 * Stop/Reset 钉子经 {@link InterruptStrategy#HANG_UNTIL_RELEASED}）+ RecordingSubscriber
 * + NoopTool（工具迭代产生 THINKING/TOOL_CALL 进度事件）+ Mockito 记忆组件。
 */
class AgentLoopTurnEventTest {

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
                new SessionManager(tempDir, "test-session"), aiService);
        recorder = new RecordingSubscriber();
        loop.addTurnSubscriber(recorder);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    // ---- 1. 回合内严格序：STARTED → PROGRESS* → COMPLETED，同一回合 id ----

    @Test
    void turnLifecycleStrictOrder() throws Exception {
        String current = InstanceContext.currentSessionKey();
        aiService.script(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", Map.of())), "thinking"));
        aiService.script(LLMResponse.text("FINAL"));

        AgentResponse response = loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("FINAL", response.getContent());

        List<TurnEvent.Kind> kinds = recorder.kindsFor(recorder.startedTurnId());
        // STARTED → THINKING → TOOL_CALL → COMPLETED（进度至少一枚，序严格）
        assertEquals(TurnEvent.Kind.TURN_STARTED, kinds.get(0));
        assertEquals(TurnEvent.Kind.TURN_COMPLETED, kinds.get(kinds.size() - 1));
        assertTrue(kinds.contains(TurnEvent.Kind.PROGRESS), "tool iteration must emit PROGRESS");
        assertTrue(kinds.indexOf(TurnEvent.Kind.TURN_STARTED) < kinds.indexOf(TurnEvent.Kind.PROGRESS));
        assertTrue(kinds.indexOf(TurnEvent.Kind.PROGRESS) < kinds.indexOf(TurnEvent.Kind.TURN_COMPLETED));
        assertEquals(1, recorder.count(TurnEvent.Kind.TURN_STARTED));
        assertEquals(1, recorder.count(TurnEvent.Kind.TURN_COMPLETED));
        assertEquals(TurnOrigin.LOCAL_PANEL, recorder.lastStarted().turn().origin());
        // E 钉子：回合系事件 origin() 访问器解析自 turn（独立字段为 null）
        assertEquals(TurnOrigin.LOCAL_PANEL, recorder.lastStarted().origin());
        assertEquals("M1", recorder.lastStarted().turn().echoText());
        assertEquals("FINAL", recorder.lastCompleted().response().getContent());
    }

    // ---- 2. 跨回合：终态(N) 先于 STARTED(N+1)；回合 id 进程唯一递增 ----

    @Test
    void crossTurnTerminalPrecedesNextStart() throws Exception {
        String current = InstanceContext.currentSessionKey();
        aiService.script(LLMResponse.text("R1"));
        aiService.script(LLMResponse.text("R2"));

        loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long firstId = recorder.lastCompleted().turn().id();
        loop.processMessage("M2", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long secondId = recorder.lastStarted().turn().id();

        assertTrue(secondId > firstId, "turn ids must be process-unique and increasing");
        assertTrue(recorder.indexOf(TurnEvent.Kind.TURN_COMPLETED, firstId)
                        < recorder.indexOf(TurnEvent.Kind.TURN_STARTED, secondId),
                "terminal of turn N must precede STARTED of turn N+1");
        assertEquals(2, recorder.count(TurnEvent.Kind.TURN_COMPLETED));
    }

    // ---- 3. 孤儿：垂死（自然完成）终态先于 re-publish 孤儿的 STARTED ----

    @Test
    void orphanStartComesAfterDyingTerminal() throws Exception {
        String current = InstanceContext.currentSessionKey();
        // 注入周期超限（MAX_INJECTION_CYCLES=5）制造确定性残留：6 轮注入窗口各注入 1 条，
        // 第 6 条溢出成 leftover，自然完成后 re-publish 成孤儿回合（RepublishTest 同款配方）
        List<GatedCall> calls = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(aiService.scriptGated(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "step " + i)));
        }
        GatedCall finalCall = aiService.scriptGated(LLMResponse.text("R-FINAL"));
        aiService.script(LLMResponse.text("ORPHAN-FINAL"));

        CompletableFuture<AgentResponse> first = loop.processMessage("M1", current);
        for (int i = 0; i < 6; i++) {
            GatedCall call = calls.get(i);
            assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "LLM call " + (i + 1));
            assertTrue(loop.processMessage("FOLLOWUP-" + (i + 1), current)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
            call.release.countDown();
        }
        assertTrue(finalCall.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "final LLM call");
        finalCall.release.countDown();
        assertEquals("R-FINAL", first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());

        // 孤儿回合由垂死回合 finally 的 re-publish 启动（无外部通道可等待），
        // 以事件流的 REPUBLISH 源 STARTED 为同步锚点
        AwaitUtil.awaitUntil(() -> recorder.events.stream().anyMatch(e ->
                        e.kind() == TurnEvent.Kind.TURN_STARTED
                                && e.turn().origin() == TurnOrigin.REPUBLISH),
                "the overflow leftover must be re-published as a REPUBLISH turn");
        TurnEvent orphanStart = recorder.lastStarted();
        assertEquals(TurnOrigin.REPUBLISH, orphanStart.turn().origin());
        assertNull(orphanStart.turn().echoText(),
                "REPUBLISH echoText is null — the You echo was already given by the INJECTED event");
        long dyingId = recorder.completedTurnIds().get(0);
        assertTrue(recorder.indexOf(TurnEvent.Kind.TURN_COMPLETED, dyingId)
                        < recorder.indexOf(TurnEvent.Kind.TURN_STARTED, orphanStart.turn().id()),
                "dying turn's terminal must precede the republished orphan's STARTED");
        AwaitUtil.awaitUntil(() -> recorder.terminalCountFor(orphanStart.turn().id()) == 1,
                "orphan turn must get exactly one terminal too");
        assertEquals("ORPHAN-FINAL",
                recorder.eventOf(TurnEvent.Kind.TURN_COMPLETED, orphanStart.turn().id())
                        .response().getContent());
    }

    // ---- 4. Stop：signalCancel 认领终态，回合体尾部静默——恰好一次 ----

    @Test
    void stopCancelEmitsCancelledExactlyOnce() throws Exception {
        String current = InstanceContext.currentSessionKey();
        GatedScriptAiService service = swapService(
                new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED));
        GatedCall call = service.scriptGated(LLMResponse.text("interrupted-final"));
        CompletableFuture<AgentResponse> future = loop.processMessage("stop me", current);
        assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(loop.signalCancel(current), "an active turn must be cancellable");
        assertTrue(call.interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // signalCancel 已认领并发射 TURN_CANCELLED；此刻回合体还挂在 hang 上（确定性）
        assertEquals(CancelCause.USER_STOP, recorder.lastCancelled().cause());
        assertEquals(1, recorder.terminalCountFor(recorder.lastCancelled().turn().id()));

        call.hang.countDown();
        assertTrue(loop.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(future.isCompletedExceptionally(), "cancelled future must not complete normally");
        // 回合体走到 try 尾时 claim 已被认领：不得再发第二个终态
        assertEquals(1, recorder.terminalCountFor(recorder.lastCancelled().turn().id()),
                "terminal must be emitted exactly once across both emission points");
    }

    // ---- 5. Reset：resetFenceLock 内发射 TURN_CANCELLED(RESET)，订阅者早退不挂死 ----

    @Test
    void resetCancelEmitsResetCauseAndReturns() throws Exception {
        String current = InstanceContext.currentSessionKey();
        GatedScriptAiService service = swapService(
                new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED));
        GatedCall call = service.scriptGated(LLMResponse.text("interrupted-final"));
        CompletableFuture<AgentResponse> future = loop.processMessage("reset me", current);
        assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // resetConversation 在 resetFenceLock 持有期内调 signalCancel(RESET)：发射点在锁内，
        // 订阅者须 O(μs) 早退——本调用在测试超时内返回即证明未挂死
        loop.resetConversation(current);
        assertEquals(CancelCause.RESET, recorder.lastCancelled().cause());
        assertEquals(1, recorder.terminalCountFor(recorder.lastCancelled().turn().id()));

        call.hang.countDown();
        assertTrue(loop.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, recorder.terminalCountFor(recorder.lastCancelled().turn().id()),
                "turn body's tail emission must stay silent once the claim is taken");
        assertTrue(future.isCompletedExceptionally());
    }

    // ---- 5b. cause 谱系补全：TIMEOUT（/agent 超时自取消）与 SILENT（关闭整合静默取消）----

    @Test
    void timeoutAndSilentCancelsCarryTypedCauses() throws Exception {
        String current = InstanceContext.currentSessionKey();
        // TIMEOUT：IpcServer /agent 超时路径的 loop 半边（cancelActiveTask(session, TIMEOUT)
        // 即 signalCancel(TIMEOUT)）——取消原因字段可区分（spec「取消原因可区分」）
        GatedScriptAiService timeoutService = swapService(
                new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED));
        GatedCall timeoutCall = timeoutService.scriptGated(LLMResponse.text("interrupted-final"));
        CompletableFuture<AgentResponse> timeoutFuture = loop.processMessage("timeout me", current);
        assertTrue(timeoutCall.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(loop.signalCancel(current, CancelCause.TIMEOUT));
        assertEquals(CancelCause.TIMEOUT, recorder.lastCancelled().cause());
        assertEquals(1, recorder.terminalCountFor(recorder.lastCancelled().turn().id()));
        timeoutCall.hang.countDown();
        assertTrue(loop.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // SILENT：关闭整合对话框路径（CloseConsolidationDialog 经两参 cancelActiveTask）。
        // 渲染显示域（LOCAL 源静默 / IPC 源保留回执行）由面板测试钉住，此处钉载荷
        GatedScriptAiService silentService = swapService(
                new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED));
        GatedCall silentCall = silentService.scriptGated(LLMResponse.text("interrupted-final"));
        CompletableFuture<AgentResponse> silentFuture = loop.processMessage("silent me", current);
        assertTrue(silentCall.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(loop.signalCancel(current, CancelCause.SILENT));
        assertEquals(CancelCause.SILENT, recorder.lastCancelled().cause());
        assertEquals(1, recorder.terminalCountFor(recorder.lastCancelled().turn().id()));
        silentCall.hang.countDown();
        assertTrue(loop.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(silentFuture.isCompletedExceptionally());
    }

    // ---- 6. 三守卫：headless 会话不派发 / 订阅者异常隔离 ----

    @Test
    void headlessSessionKeyIsNotDispatched() throws Exception {
        loop.processMessage("M1", "headless-other-session").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(recorder.events.isEmpty(),
                "events for a non-current-instance session key must not reach subscribers");
    }

    @Test
    void subscriberExceptionIsolatedFromOthers() throws Exception {
        String current = InstanceContext.currentSessionKey();
        RecordingSubscriber second = new RecordingSubscriber();
        loop.addTurnSubscriber(new TurnSubscriber() {
            @Override public void onTurnEvent(TurnEvent event) {
                throw new IllegalStateException("boom");
            }
        });
        loop.addTurnSubscriber(second);

        AgentResponse response = loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(response.isSuccess(), "a throwing subscriber must not break the turn itself");
        assertEquals(1, second.count(TurnEvent.Kind.TURN_COMPLETED),
                "an isolated failure must not starve the other subscriber");
    }

    /** C1 钉子：订阅者抛 Error（类路径错位 = LinkageError）同样被隔离，不得杀回合。 */
    @Test
    void subscriberErrorIsolatedFromOthers() throws Exception {
        String current = InstanceContext.currentSessionKey();
        RecordingSubscriber second = new RecordingSubscriber();
        loop.addTurnSubscriber(new TurnSubscriber() {
            @Override public void onTurnEvent(TurnEvent event) {
                throw new LinkageError("stale-jar class mismatch");
            }
        });
        loop.addTurnSubscriber(second);

        AgentResponse response = loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(response.isSuccess(), "an Error-throwing subscriber must not break the turn itself");
        assertEquals(1, second.count(TurnEvent.Kind.TURN_COMPLETED),
                "a LinkageError must be contained like any other subscriber failure");
    }

    // ---- 7. IPC 命令回合只发终态（commandTurn + 非 LOCAL 源 = 不可见）----

    @Test
    void ipcCommandTurnEmitsOnlyTerminal() throws Exception {
        String current = InstanceContext.currentSessionKey();
        AgentResponse response = loop.processMessage("/help", current, null, TurnOrigin.IPC_CLI)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(response.isSuccess());

        List<TurnEvent> terminal = recorder.events.stream()
                .filter(e -> e.kind() == TurnEvent.Kind.TURN_COMPLETED).toList();
        assertEquals(1, terminal.size(), "the command turn must still get its terminal event");
        assertTrue(terminal.get(0).turn().commandTurn(), "/help is a dispatchable command turn");
        assertEquals(TurnOrigin.IPC_CLI, terminal.get(0).turn().origin());
        assertEquals(0, recorder.count(TurnEvent.Kind.TURN_STARTED),
                "invisible turn (IPC command) must not raise STARTED");
        assertEquals(0, recorder.count(TurnEvent.Kind.PROGRESS));
        assertTrue(terminal.get(0).turn().id() > 0);
    }

    @Test
    void localCommandTurnIsVisible() throws Exception {
        String current = InstanceContext.currentSessionKey();
        loop.processMessage("/help", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, recorder.count(TurnEvent.Kind.TURN_STARTED),
                "LOCAL_PANEL command turn is visible (STARTED emitted)");
        assertEquals(1, recorder.count(TurnEvent.Kind.TURN_COMPLETED));
        assertEquals(TurnOrigin.LOCAL_PANEL, recorder.lastStarted().turn().origin());
    }

    // ---- 8. COMMAND_RESULT：Phase 1（priority）与 Phase 2（忙时 dispatchable）两点 ----

    @Test
    void commandResultPhase1PriorityCommand() throws Exception {
        String current = InstanceContext.currentSessionKey();
        AgentResponse response = loop.processMessage("/status", current, null, TurnOrigin.IPC_CLI)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(response.isSuccess());

        assertEquals(1, recorder.events.size(), "a priority command raises exactly one event");
        TurnEvent event = recorder.events.get(0);
        assertEquals(TurnEvent.Kind.COMMAND_RESULT, event.kind());
        assertEquals(TurnOrigin.IPC_CLI, event.origin());
        assertEquals("/status", event.message());
        assertNull(event.turn(), "COMMAND_RESULT is session-level: no turn identity");
    }

    @Test
    void commandResultPhase2WhileBusy() throws Exception {
        String current = InstanceContext.currentSessionKey();
        CountDownLatch llmEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        aiService.override = () -> {
            llmEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LLMResponse.text("busy turn done");
        };
        CompletableFuture<AgentResponse> busy = loop.processMessage("busy", current);
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        AgentResponse response = loop.processMessage("/help", current, null, TurnOrigin.IPC_CLI)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(response.isSuccess());
        TurnEvent cmd = recorder.lastOf(TurnEvent.Kind.COMMAND_RESULT);
        assertEquals(TurnOrigin.IPC_CLI, cmd.origin(), "busy-time command result carries its origin");
        assertEquals("/help", cmd.message());

        release.countDown();
        busy.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- 9. INJECTED / REJECTED_BUSY：来源经 origin 携带（事件通道无条件）----

    @Test
    void injectedEventCarriesOriginForBothSources() throws Exception {
        String current = InstanceContext.currentSessionKey();
        CountDownLatch llmEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        aiService.override = () -> {
            llmEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LLMResponse.text("done");
        };
        CompletableFuture<AgentResponse> busy = loop.processMessage("busy", current);
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(loop.processMessage("[from cli] extra", current, null, TurnOrigin.IPC_CLI)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess());
        assertTrue(loop.processMessage("local extra", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess());
        TurnEvent ipcInjected = recorder.events.stream()
                .filter(e -> e.kind() == TurnEvent.Kind.INJECTED && e.origin() == TurnOrigin.IPC_CLI)
                .findFirst().orElseThrow();
        assertEquals("[from cli] extra", ipcInjected.message());

        // 本地注入也发事件（载荷 origin=LOCAL_PANEL，渲染与否由订阅端决定——P1a 面板过滤）
        long localInjected = recorder.events.stream()
                .filter(e -> e.kind() == TurnEvent.Kind.INJECTED && e.origin() == TurnOrigin.LOCAL_PANEL)
                .count();
        assertEquals(1, localInjected);

        release.countDown();
        busy.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void delegatedBusyRejectionRaisesRejectedBusy() throws Exception {
        String current = InstanceContext.currentSessionKey();
        CountDownLatch llmEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        aiService.override = () -> {
            llmEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LLMResponse.text("done");
        };
        CompletableFuture<AgentResponse> busy = loop.processMessage("busy", current);
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        AgentResponse rejected = loop.processMessage("[delegated-from A] task", current, null,
                TurnOrigin.IPC_DELEGATED)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(rejected.isSuccess());
        assertEquals(1, recorder.count(TurnEvent.Kind.REJECTED_BUSY));
        assertEquals(1, recorder.count(TurnEvent.Kind.TURN_STARTED),
                "only the pre-existing busy turn started; the rejected delegation must not raise another");

        release.countDown();
        busy.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- 10. 工厂级晚挂接：先有 loop、后有订阅者（面板懒创建链路）----

    @Test
    void factorySubscriberAttachesToLiveLoop() throws Exception {
        String current = InstanceContext.currentSessionKey();
        Object original = swapFactoryInstance(loop);
        RecordingSubscriber late = new RecordingSubscriber();
        try {
            AgentLoopFactory.addTurnSubscriber(late);
            aiService.script(LLMResponse.text("LATE"));
            loop.processMessage("M1", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, late.count(TurnEvent.Kind.TURN_COMPLETED),
                    "a subscriber registered after the loop exists must receive subsequent turns");

            AgentLoopFactory.removeTurnSubscriber(late);
            aiService.script(LLMResponse.text("AFTER-REMOVE"));
            loop.processMessage("M2", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, late.count(TurnEvent.Kind.TURN_COMPLETED),
                    "removed subscriber must receive nothing further");
        } finally {
            AgentLoopFactory.clearTurnSubscribersForTest();
            swapFactoryInstance(original == loop ? null : original);
        }
    }

    // ---- 10b. 回合中途挂接的订阅者照收 PROGRESS（门控不得在回合开始时冻结订阅者快照）----

    /**
     * A 钉子：开跑时零订阅者的可见回合（面板懒创建、CLI/委派先行），订阅者中途挂上后
     * 仍须收到在跑回合的 PROGRESS——领养承诺「后续 PROGRESS/终态照常渲染」。
     */
    @Test
    void midTurnSubscriberStillReceivesProgress() throws Exception {
        String current = InstanceContext.currentSessionKey();
        loop.removeTurnSubscriber(recorder); // 开跑时零订阅者
        GatedCall first = aiService.scriptGated(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", Map.of())), "step 1"));
        aiService.script(LLMResponse.text("MID-FINAL"));

        CompletableFuture<AgentResponse> future = loop.processMessage("M1", current);
        assertTrue(first.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "first LLM call must be in flight before the late subscriber attaches");

        RecordingSubscriber late = new RecordingSubscriber();
        loop.addTurnSubscriber(late);
        first.release.countDown();
        assertTrue(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess());

        assertTrue(late.count(TurnEvent.Kind.PROGRESS) >= 1,
                "a subscriber attached mid-turn must receive PROGRESS of the running turn");
        assertEquals(1, late.count(TurnEvent.Kind.TURN_COMPLETED));
    }

    // ---- 11. C3：换血后 Stop 经工厂路由到退役 loop 的在跑回合 ----

    /**
     * 模型切换换血后，面板只持新 loop 引用——旧 loop 上仍在跑的回合（shutdown 不打断
     * 在跑任务）须经 {@code AgentLoopFactory.signalCancelAny} 路由终止；排空的退役条目
     * 顺手剪除。
     */
    @Test
    void signalCancelAnyReachesRetiredLoopTurn() throws Exception {
        String current = InstanceContext.currentSessionKey();
        Object original = swapFactoryInstance(loop); // 当前单例 = setUp 的 loop（无在跑回合）
        GatedScriptAiService service = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        GatedCall call = service.scriptGated(LLMResponse.text("interrupted-final"));
        AgentLoop retired = newLoop(service);
        List<AgentLoop> retiredList = retiredLoopsForTest();
        try {
            retiredList.add(retired);
            CompletableFuture<AgentResponse> future = retired.processMessage("strand me", current);
            assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "retired loop's turn must be in flight");

            assertTrue(AgentLoopFactory.signalCancelAny(current),
                    "the retired loop's active turn must be reachable via factory routing");
            assertTrue(call.interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the routed cancel must interrupt the retired loop's LLM call");
            call.hang.countDown();
            assertTrue(retired.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(future.isCompletedExceptionally(), "cancelled future must not complete normally");
            assertFalse(retiredList.contains(retired),
                    "a drained retired loop must be pruned on the routing pass");
        } finally {
            retiredList.remove(retired);
            retired.shutdown();
            AgentLoopFactory.clearTurnSubscribersForTest();
            swapFactoryInstance(original == loop ? null : original);
        }
    }

    // ---- 11b. 关闭整合的 SILENT 取消必须覆盖退役 loop 的在跑回合（lens=factory）[缺陷4/9/17] ----

    /** 关闭整合取消窗：对齐对话框 cancelActiveTask 内部 waitForCancellation 的 5s 有界收尾。 */
    private static final long CANCEL_BOUND_SECONDS = 5;

    /**
     * 场景（缺陷 attackScenario ①②③）：L1 为当前单例、本地回合 T1 在跑（首个 LLM 调用
     * 门控挂起）→ 模型切换换血（L1 进 retiredLoops、L2 成为当前单例）→ 用户关闭 JMeter、
     * 关闭整合对话框选「是」，doInBackground 执行取消步骤
     * （CloseConsolidationDialog.java:113-117 的逐行同款路径，见 {@link #dialogCancelStep}）。
     *
     * 钉住的契约（openspec/changes/unify-turn-event-display/specs/agent-turn-events/spec.md）：
     * 1. 「事件种类与载荷完整 → 取消原因可区分且渲染有别」：静默取消的 TURN_CANCELLED
     *    须携带类型化 SILENT 原因——渲染侧抑制是订阅端职责，事件本身必须照发；
     * 2. 「可插拔订阅与工厂级存活」：AgentLoop 重建后「旧实例在跑回合的迟到事件仍可达
     *    订阅者」——此处被送达的迟到终态正是关闭整合取消产生的 TURN_CANCELLED；
     * 3. 「终态恰好一次」：signalCancel 认领与回合体 try 尾双发射点合计恰一条终态。
     *
     * 被证伪的生产承诺：CloseConsolidationDialog.java:107-111 注释「先取消本会话尚在跑的
     * Agent 回合……否则提炼/清空与并发回合写会话相互竞态」——该承诺必须覆盖退役 loop 上
     * 的回合（Stop 按钮已因同类问题改走 AgentLoopFactory.signalCancelAny，见 11 号 C3 测试）。
     */
    @Test
    void closeConsolidationSilentCancelReachesRetiredLoopTurn() throws Exception {
        String current = InstanceContext.currentSessionKey();
        // L1 = 换血前的 loop：HANG 策略 fake——被 interrupt 后改挂 hang，使「signalCancel
        // 先认领终态、回合体后收尾」可确定性断言（Stop/Reset 钉子同款，见 4/5 号测试）
        GatedScriptAiService service = swapService(
                new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED));
        GatedCall call = service.scriptGated(LLMResponse.text("stale-final"));
        Object original = swapFactoryInstance(loop); // 先让 L1 当当前单例（11 号 C3 同款起手）
        List<AgentLoop> retiredList = retiredLoopsForTest();
        AgentLoop l2 = null;
        try {
            // ① L1 为当前单例，T1 在跑。entered latch 同时保证 T1 的 getOrCreate 已把会话
            //    文件落盘（L2 构造期才能像生产 createAgentLoop 那样从磁盘载入「换血时刻快照」）
            CompletableFuture<AgentResponse> t1 = loop.processMessage("consolidate me", current);
            assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "T1 必须已进入首个 LLM 调用");

            // ② 换血：L1 收进退役表（生产 getAgentLoop(newService) 里 retireLoop 的净效果），
            //    L2 成为当前单例——面板/对话框此后只拿得到 L2
            l2 = newLoopWithManagerFocus(new GatedScriptAiService(), current);
            retiredList.add(loop);
            swapFactoryInstance(l2);

            // ③ 关闭整合对话框 doInBackground 的取消步骤（:113-117 同款路径）
            dialogCancelStep();

            // ===== 缺陷红点 =====
            // 当前链路 getAgentLoop() 返回 L2：L2.signalCancel 对该会话空转（无 abort flag、
            // 无 future、无注入槽），T1 在 L1 上永不被打断，await 超时返回 false。
            // 修复后（取消经工厂路由覆盖退役 loop，如 signalCancelAny(session, SILENT)）：
            // interrupt() 打在 L1 回合线程的 release.await() 上，latch 立即计数。
            assertTrue(call.interrupted.await(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS),
                    "关闭整合的 SILENT 取消必须到达退役 loop 上仍在跑的回合：当前只取消了"
                            + "换血后的当前单例（其上无该会话回合，signalCancel 空转），T1 继续烧、"
                            + "提炼快照漏尾、提炼期并发写会话");

            // —— 以下断言仅修复后可达（TURN_CANCELLED 在 dialogCancelStep 返回前已同步派发
            //    于测试线程上，无跨线程可见性问题）——

            // 契约 1「取消原因可区分」：关闭整合的取消事件携带类型化 SILENT（非 USER_STOP/
            // TIMEOUT/RESET）；非空同时钉住契约 2「旧实例在跑回合的迟到终态仍可达订阅者」
            assertEquals(CancelCause.SILENT, recorder.lastCancelled().cause());
            long turnId = recorder.lastCancelled().turn().id();
            assertEquals(1, recorder.terminalCountFor(turnId),
                    "认领发生在 signalCancel 内，此刻回合体仍挂在 hang 上——终态恰一条");

            call.hang.countDown();
            // 收尾屏障（确定性锚）：/help 是可分发非优先命令，走 Phase 3 排上单线程 executor，
            // 其 future 完成即证明 T1 载体线程已走完 try 尾 emitTerminal（第二个终态发射点
            // 已执行且必须静默）。命令回合不做 LLM 调用、不落盘会话，无副作用。
            loop.processMessage("/help", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // 契约 3「终态恰好一次」：claim 已被 signalCancel 认领，回合体尾部不得双发
            assertEquals(1, recorder.terminalCountFor(turnId),
                    "terminal must be emitted exactly once across both emission points");
            assertTrue(t1.isCompletedExceptionally(), "被取消回合的 future 不得正常完成");
        } finally {
            call.release.countDown();
            call.hang.countDown();
            AwaitUtil.awaitUntil(() -> loop.activeTurn(current).isEmpty(),
                    "T1 回合排空后再 shutdown（@TempDir 文件锁防护）");
            retiredList.remove(loop);
            if (l2 != null) {
                l2.shutdown();
            }
            AgentLoopFactory.clearTurnSubscribersForTest();
            swapFactoryInstance(original);
        }
    }

    // ---- 11c. 换血两步窗口（instance==null）：ISE 被「必无活动回合」吞掉，同样漏取消 ----

    /**
     * 场景（缺陷 actualSuspected 尾段）：AiChatPanel.switchAiService 的两步窗口
     * （AiChatPanel.java:885-886）——AgentLoopFactory.reset() 已把 L1 收进退役表并置
     * instance=null，getAgentLoop(newService) 尚未落位。此刻用户关闭 JMeter 触发关闭整合：
     * getAgentLoop() 抛 IllegalStateException，被 CloseConsolidationDialog.java:115-117
     * 以「agent 未初始化则必无活动回合」吞掉。该假设仅在退役表为空时成立：窗口期
     * retiredLoops 正挂着 L1 的在跑回合 T1，取消同样必须路由到达。
     *
     * <p>钉住契约同 11b（SILENT 类型化原因 / 旧实例迟到终态可达 / 终态恰好一次）；
     * 额外钉住 signalCancelAny 的既有不变式——工厂路由不得依赖「当前单例非空」
     * （currentLoopSnapshot 为 null 时退役表路由照常工作，这是 Stop 在同一窗口不丢取消的原因）。
     */
    @Test
    void closeConsolidationCancelSurvivesFactoryRebuildWindowInstanceNull() throws Exception {
        String current = InstanceContext.currentSessionKey();
        GatedScriptAiService service = swapService(
                new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED));
        GatedCall call = service.scriptGated(LLMResponse.text("stale-final"));
        Object original = swapFactoryInstance(loop);
        List<AgentLoop> retiredList = retiredLoopsForTest();
        try {
            // ① L1 为当前单例，T1 在跑
            CompletableFuture<AgentResponse> t1 = loop.processMessage("consolidate me", current);
            assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "T1 必须已进入首个 LLM 调用");

            // ② 复刻重建窗口：reset() 已把 L1 退役（进表）、instance 已置 null、
            //    新单例尚未创建——不构造 L2，窗口态本就没有当前单例
            retiredList.add(loop);
            swapFactoryInstance(null);

            // ③ 窗口期关闭整合触发（:113-117 同款路径）：当前实现 getAgentLoop() 抛
            //    IllegalStateException 被 catch 吞掉，什么都没取消
            dialogCancelStep();

            // ===== 缺陷红点 =====
            assertTrue(call.interrupted.await(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS),
                    "重建窗口（instance==null）期间关闭整合的取消仍须覆盖退役 loop 的在跑回合："
                            + "IllegalStateException 不等于「必无活动回合」——退役表里正挂着 T1；"
                            + "Stop 在同一窗口经 signalCancelAny 不丢取消，关闭整合不得更弱");

            // —— 修复后可达：SILENT 终态照常派发（工厂路由 null-safe）——
            assertEquals(CancelCause.SILENT, recorder.lastCancelled().cause());
            long turnId = recorder.lastCancelled().turn().id();
            assertEquals(1, recorder.terminalCountFor(turnId));

            call.hang.countDown();
            loop.processMessage("/help", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, recorder.terminalCountFor(turnId),
                    "terminal must be emitted exactly once across both emission points");
            assertTrue(t1.isCompletedExceptionally(), "被取消回合的 future 不得正常完成");
        } finally {
            call.release.countDown();
            call.hang.countDown();
            AwaitUtil.awaitUntil(() -> loop.activeTurn(current).isEmpty(),
                    "T1 回合排空后再 shutdown（@TempDir 文件锁防护）");
            retiredList.remove(loop);
            AgentLoopFactory.clearTurnSubscribersForTest();
            swapFactoryInstance(original);
        }
    }

    // ---- 11d. clearCurrentSession 截断的 jsonl 不得被未取消的退役回合复活（缺陷 ⑤） ----

    /**
     * 场景（attackScenario 全链 ①→⑤）：换血后关闭整合按生产序执行——doInBackground
     * 先取消（CloseConsolidationDialog.java:113-117），done() 提炼成功后
     * clearCurrentSession（:142 → 真生产入口 CloseConsolidationCoordinator.
     * clearCurrentSession，本测试直调真方法、非复制品）。
     *
     * 缺陷下取消漏掉 T1 → clearCurrentSession 只重置当前单例 L2 的会话副本并截断 jsonl
     * → T1 跑完照常落盘（AgentRunner 的 isAborted=false → saveMessagesToSession 把 L1
     * 缓存的完整历史写回同一文件——L1/L2 的 SessionManager 按会话键解析到同一 jsonl
     * 路径），截断文件被「复活」为含旧 lastConsolidatedIndex 的完整历史。
     *
     * 被证伪的生产承诺（非 spec.md，为被测权威文档）：
     *  - CloseConsolidationCoordinator.clearCurrentSession javadoc（:124-126）：重置栅栏
     *    「中止在跑回合……含关闭对话框取消上个回合后、其垂死收尾 re-publish 的孤儿——
     *    并翻转会话代数，使其后迟到的旧会话渲染/落盘被各层守卫丢弃」；
     *  - CloseConsolidationDialog.java:107-111：取消在先是为杜绝「提炼/清空与并发回合
     *    写会话相互竞态」。
     * 两者都须覆盖退役 loop 上的回合：L1 的会话代数与 abort flag 都在 L1 上，L2 的
     * resetConversation 对它们不可见，「各层守卫」全部失效。
     */
    @Test
    void clearCurrentSessionTruncationSurvivesRetiredLoopTurnTeardown() throws Exception {
        String current = InstanceContext.currentSessionKey();
        GatedScriptAiService service = swapService(
                new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED));
        GatedCall call = service.scriptGated(LLMResponse.text("stale-final"));
        Path sessionFile = sessionFileOf(current);
        Object original = swapFactoryInstance(loop);
        List<AgentLoop> retiredList = retiredLoopsForTest();
        AgentLoop l2 = null;
        try {
            // ① T1 在 L1 上挂起（entered 后会话文件已由 getOrCreate 落盘：仅元数据行）
            CompletableFuture<AgentResponse> t1 = loop.processMessage("consolidate me", current);
            assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "T1 必须已进入首个 LLM 调用");

            // ② 换血：L2 以当前会话键为 focus 构造（磁盘载入换血时刻快照），成为当前单例
            l2 = newLoopWithManagerFocus(new GatedScriptAiService(), current);
            retiredList.add(loop);
            swapFactoryInstance(l2);

            // ③ 生产序执行关闭整合两步：先取消（:113-117 复制品），后清空（真生产入口：
            //    L2.resetConversation(key,false) → signalCancel(RESET) 仅达 L2 →
            //    session.clear() + saveSession 截断 jsonl + invalidate L2 缓存）
            dialogCancelStep();
            org.gitee.jmeter.ai.agent.memory.CloseConsolidationCoordinator.clearCurrentSession();

            // 截断即刻可验：saveSession 同步关闭写句柄，返回即落盘——只剩元数据行
            assertEquals(1, countSessionFileLines(sessionFile),
                    "clearCurrentSession 后会话文件应只剩元数据行");

            // ④ 放行 T1（两条世界通用：未被取消→release.await() 生效；已被取消挂
            //    hang→hang.await() 生效；两 latch 都放，红绿世界都有限时间内收尾）
            call.release.countDown();
            call.hang.countDown();
            // 收尾屏障：T1 的 future 在其 executor lambda 的最后一条语句补完成——
            // 完成即证明 saveMessagesToSession 的落盘决策（写或不写）已成定局。
            // 不用「/help 排队作串行屏障」：缺陷世界里 T1 的路由槽未被摘除，
            // /help 会走 Phase 2 在调用线程同步分发、拿不到屏障效果。
            AwaitUtil.awaitUntil(t1::isDone, "T1 收尾决策必须落定");

            // ===== 缺陷红点 =====
            assertEquals(1, countSessionFileLines(sessionFile),
                    "截断后的会话文件不得被退役 loop 上未被取消的回合复活：T1 收尾"
                            + "saveMessagesToSession 把 L1 缓存的完整历史（连旧"
                            + "lastConsolidatedIndex）写回同一 jsonl——违背 clearCurrentSession"
                            + " javadoc「使其后迟到的旧会话落盘被各层守卫丢弃」");
            // 顺带钉死因果链：复活当且仅当 T1 未被取消（红世界此处 false；绿世界 true）
            assertTrue(t1.isCompletedExceptionally() || countSessionFileLines(sessionFile) == 1,
                    "若 T1 被正常取消则 future 异常完成且落盘被跳过；正常完成且文件仍截断"
                            + "（如未来落盘策略变化）亦接受——唯独「正常完成 + 文件复活」不可");
        } finally {
            call.release.countDown();
            call.hang.countDown();
            AwaitUtil.awaitUntil(() -> loop.activeTurn(current).isEmpty(),
                    "T1 回合排空后再 shutdown（@TempDir 文件锁防护）");
            retiredList.remove(loop);
            if (l2 != null) {
                l2.shutdown();
            }
            AgentLoopFactory.clearTurnSubscribersForTest();
            swapFactoryInstance(original);
        }
    }

    // ---- 11e. R6 AND 子句：换血后退役 loop 在跑回合的迟到终态仍达订阅者（守卫，保持 enabled） ----

    /**
     * spec「可插拔订阅与工厂级存活」→ Scenario「模型切换后订阅存活」的 AND 子句
     * （openspec/changes/unify-turn-event-display/specs/agent-turn-events/spec.md:89）：
     * 「旧 loop 在跑回合的迟到终态也被接收（按回合身份/会话代数过滤）」——此前零测试
     * 覆盖（唯一触及退役 loop 的 11 号测试只断言 interrupt 与剪枝，不断言事件送达）。
     *
     * <p>全真实换血链路（对齐 AiChatPanel.switchAiService 的两步：reset → getAgentLoop）：
     * <ol>
     *   <li>订阅者经 {@code AgentLoopFactory.addTurnSubscriber} 挂到工厂当前 loop L1
     *       （全局表 + L1 实例表——实例表快照是迟到事件可达的唯一机制，工厂只对新单例重挂）；</li>
     *   <li>委派回合 T 在 L1 上门控开跑（STARTED 已发、未终态）；</li>
     *   <li>{@code AgentLoopFactory.reset()} 把 L1 收容进 retiredLoops 并 shutdown
     *       （executorService.shutdown() 不打断在跑任务）→
     *       {@code getAgentLoop(newService)} 重建 L2 并全量重挂全局表；</li>
     *   <li>放行门控 → T 自然完成 → emitTerminal 经 L1 <b>自己的</b> turnSubscribers 快照派发
     *       TURN_COMPLETED（dispatchTurnEvent 的会话键守卫只按 InstanceContext 比较，
     *       不看 loop 退役状态）→ 订阅者照收，回合身份与退役前 STARTED 一致。</li>
     * </ol>
     *
     * <p>回归锚点：任何砍断该通道的重构——shutdown 时清 turnSubscribers、retireLoop 时
     * 摘订阅或重建实例、dispatchTurnEvent 增加「loop 已退役即拒发」拦截——本测试红。
     */
    @Test
    void retiredLoopLateTerminalStillReachesFactorySubscriber() throws Exception {
        String current = InstanceContext.currentSessionKey();
        Object original = swapFactoryInstance(null); // 摘走工厂单例，防 reset() 误伤无关 loop
        // createAgentLoop 的 workspace 解析钉进 tempDir（防止单测污染仓库/用户目录，
        // 对齐 AiChatPanelIpcTurnPresenterTest 的 JMeter home 钉扎配方）
        String previousJMeterHome = org.apache.jmeter.util.JMeterUtils.getJMeterHome();
        org.apache.jmeter.util.JMeterUtils.setJMeterHome(tempDir.toString());
        List<AgentLoop> retiredList = retiredLoopsForTest();
        AgentLoop l1 = null;
        AgentLoop l2 = null;
        try {
            // ① L1 = 工厂当前存活 loop（真实 getAgentLoop 创建，生产对等），订阅者经工厂 API 挂上
            GatedScriptAiService l1Service = new GatedScriptAiService();
            l1 = AgentLoopFactory.getAgentLoop(l1Service);
            org.junit.jupiter.api.Assumptions.assumeTrue(l1 != null, "agent 禁用时跳过（默认启用）");
            AgentLoopFactory.addTurnSubscriber(recorder); // 全局表 + L1 实例表

            // ② 委派回合 T 在 L1 上门控开跑。STARTED 在 executor 提交前、于 processMessage
            //    调用线程（= 测试线程）同步内联派发——方法返回即已在 recorder，无需轮询
            GatedCall call = l1Service.scriptGated(LLMResponse.text("R-LATE"));
            CompletableFuture<AgentResponse> future = l1.processMessage(
                    "[delegated-from peer] late terminal probe", current, null, TurnOrigin.IPC_DELEGATED);
            assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "L1 回合必须已进入 LLM 调用（STARTED 已发、未终态）");
            long startedId = recorder.startedTurnId(); // 退役前 STARTED 的回合身份（活回合集合成员资格的事实来源）
            assertEquals(TurnOrigin.IPC_DELEGATED, recorder.lastStarted().turn().origin());

            // ③ 模型切换换血两步：reset（收容 L1 + shutdown，不打断在跑回合）→ 重建 L2 全量重挂
            AgentLoopFactory.reset();
            GatedScriptAiService l2Service = new GatedScriptAiService();
            l2 = AgentLoopFactory.getAgentLoop(l2Service);
            org.junit.jupiter.api.Assumptions.assumeTrue(l2 != null, "agent 禁用时跳过");
            assertFalse(l1 == l2, "换血必须重建出新 loop 实例");
            assertTrue(retiredList.contains(l1),
                    "仍在跑回合的旧 loop 必须被收容进 retiredLoops（signalCancelAny 路由与迟到事件链路的前提）");

            // ④ 放行门控 → L1 回合在退役 loop 上自然完成。emitTerminal 先于 future.complete
            //    （AgentLoop 顺序契约），future.get 返回即迟到 TURN_COMPLETED 已派发——确定性同步锚
            call.release.countDown();
            assertEquals("R-LATE", future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                    "退役 loop 上的在跑回合必须照常自然完成（shutdown 不打断在跑任务）");

            // ---- spec.md:89 AND 子句钉子：旧 loop 在跑回合的迟到终态也被接收 ----
            assertEquals(1, recorder.terminalCountFor(startedId),
                    "spec R6 AND 子句（spec.md:89）：换血后旧 loop 在跑回合的迟到终态必须仍达订阅者");
            TurnEvent terminal = recorder.eventOf(TurnEvent.Kind.TURN_COMPLETED, startedId);
            assertEquals(startedId, terminal.turn().id(),
                    "迟到终态的回合身份必须与退役前 STARTED 一致（订阅者按回合身份过滤、面板 liveTurnIds 的前提）");
            assertEquals(TurnOrigin.IPC_DELEGATED, terminal.turn().origin(),
                    "迟到终态载荷的来源标记不得被换血洗掉");
            assertEquals("R-LATE", terminal.response().getContent(),
                    "迟到终态必须携带真实回复（自然完成路径，非 error 终态）");
        } finally {
            // 卫生：摘订阅 → 清工厂全局表 → 排空退役表 → 复原工厂单例与 JMeter home。
            // jsonl 写盘先于 future.complete（持久化在 AgentRunner.run 内、emitTerminal 之前），
            // future.get 已返回 ⇒ 写盘必已结束，@TempDir 清理无 Windows 文件锁竞态
            if (l1 != null) {
                l1.removeTurnSubscriber(recorder);
            }
            if (l2 != null) {
                l2.removeTurnSubscriber(recorder);
            }
            AgentLoopFactory.clearTurnSubscribersForTest();
            retiredList.remove(l1);
            retiredList.remove(l2);
            AgentLoopFactory.reset(); // 退役并 shutdown 当前工厂单例（L2）
            swapFactoryInstance(original);
            org.apache.jmeter.util.JMeterUtils.setJMeterHome(previousJMeterHome); // null 时还原为 null（静态字段纯赋值，安全）
        }
    }

    // ---- 12. 重置取消跨 loop 路由[缺陷3/5]：/new 与 "+" 的取消必须触达退役 loop 的在跑回合 ----

    /**
     * 攻击场景（缺陷）：L1 为换血前单例，其上挂着长回合 T1（HANG_UNTIL_RELEASED 钉在 LLM 调用里，
     * 用户视角 = 数分钟的 run_test）；模型切换把 L1 退役进 retiredLoops（shutdown 不打断在跑任务）；
     * 用户点 "+"（resetConversation 是 /new 与 "+" 共用的唯一实现，/new 命令路由见下一测试）——
     * resetConversation 在 L2 内执行 signalCancel(RESET)（AgentLoop.java:1060），只查 L2 自己的
     * activeTurnTokens（TurnRegistry 单表）；T1 活在 L1 的同名注册表里，完全未被触碰；放行后 T1
     * 收尾落盘（AgentRunner.saveMessagesToSession 的 abort 复查读 L1 的 flag=false），经 L1 自己的
     * SessionManager 缓存（invalidate 只清了 L2 缓存）全量重写刚被截断的 jsonl，旧对话复活。
     *
     * 契约钉子：
     * 1) spec.md「事件种类与载荷完整 → Scenario: 取消原因可区分且渲染有别」——"回合…因会话重置…
     *    而终止"：会话重置必须终止在跑回合且取消终态带 RESET；模型切换不改变该语义
     *    （AgentLoopFactory javadoc：退役 loop 在跑回合的终止信号此后经路由可达）。
     * 2) spec.md「终态恰好一次」——T1 的终态在取消路径与回合体收尾路径竞态下恰好一条。
     * 3) spec.md「可插拔订阅与工厂级存活 → Scenario: 模型切换后订阅存活」——旧 loop 在跑回合的
     *    迟到终态仍可达订阅者（按回合身份过滤，本测试以 t1Id 过滤）。
     * 4) resetConversation javadoc（AgentLoop.java:1026）"中止在跑回合（含子代理）" +
     *    AgentRunner.java:727-734 落盘守卫注释"不覆盖重置线程刚写的空文件"——会话被重置后，
     *    在跑回合必须被置 abort 并放弃落盘。interrupted 与"文件仍截断"两条断言联合钉住
     *    "L1 的 abort flag 为 true"：落盘守卫读的正是该 flag（flag 在 whenComplete 后即从 map
     *    摘除，反射直读反而测不到，故用行为代理）。
     */
    @Test
    void resetAfterModelSwitchCancelsRetiredLoopTurnAndKeepsSessionTruncated() throws Exception {
        String current = InstanceContext.currentSessionKey();

        // ---- L1：换血前单例。HANG_UNTIL_RELEASED：被 interrupt 后计数 interrupted、改挂 hang——
        // 给"终态恰好一次"留出确定性的认领窗口（既有 Stop/Reset 钉子配方）
        GatedScriptAiService l1Service = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        // SM1 与 SM2：同盘（tempDir/sessions）同键的两个独立缓存——缺陷的核心即两缓存互不知情
        SessionManager sm1 = new SessionManager(tempDir, current);
        AgentLoop l1 = newLoop(l1Service, sm1);
        l1.addTurnSubscriber(recorder);

        // ---- 种旧历史：先跑一条完整回合，让 jsonl 上有可被"复活"的内容
        l1Service.script(LLMResponse.text("OLD-REPLY"));
        l1.processMessage("OLD-QUESTION", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path sessionFile = sessionFileOf(current);
        assertTrue(java.nio.file.Files.readString(sessionFile).contains("OLD-REPLY"),
                "种子回合必须先落盘，否则后续\"复活\"断言无的放矢");

        // ---- T1：挂在 LLM 调用上的长回合
        GatedCall t1 = l1Service.scriptGated(LLMResponse.text("T1-FINAL"));
        CompletableFuture<AgentResponse> t1Future = l1.processMessage("T1-QUESTION", current);
        assertTrue(t1.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "T1 必须已进入 LLM 调用");
        long t1Id = recorder.lastStarted().turn().id(); // STARTED 在提交线程同步派发，此刻必在

        // ---- 模型切换换血（AgentLoopFactory.getAgentLoop 生产序）：L1 退役入表 + shutdown
        // （executor 放行在跑任务收尾、SM1 落盘同内容无害），L2 成为面板当前 loop
        List<AgentLoop> retiredList = retiredLoopsForTest();
        retiredList.add(l1);
        l1.shutdown();
        // L2：独立 SessionManager——构造时从盘重载旧历史（与生产 createAgentLoop new 一个 SM 一致）
        SessionManager sm2 = new SessionManager(tempDir, current);
        AgentLoop l2 = newLoop(new GatedScriptAiService(), sm2);
        Object originalFactory = swapFactoryInstance(l2);
        try {
            // 换血本身不得取消 T1（工厂 javadoc：shutdown 不打断在跑任务，终止信号此后经路由可达）。
            // 若修复改为"换血时杀残留回合"，此处红——那是另一种语义变更，不应静默通过
            assertEquals(1, t1.interrupted.getCount(), "model switch must not cancel the stranded turn");

            // ---- 用户点 "+"：重置核心（/new 与 "+" 共用的工厂入口——面板 "+" 生产路径
            // AiChatPanel.startNewConversation 现走 AgentLoopFactory.resetConversationAny(self, key)）
            AgentLoopFactory.resetConversationAny(l2, current);

            // 锚点 0（缺陷/修复两界通用，确定性）：resetConversation 同步返回即已截断落盘——
            // 后续对文件的任何写入只能来自放行后的 T1，把红因隔离到 T1 的迟到落盘
            assertEquals(1, java.nio.file.Files.readAllLines(sessionFile).size(),
                    "重置后 jsonl 必须只剩 metadata 一行");

            // ==== 红线 1（缺陷存在时在此超时红）：RESET 取消必须路由到退役 loop 的在跑回合 ====
            // signalCancel 五步中的 interrupt（第 2 步）→ GatedCall.interrupted 计数；
            // 当前实现里 L2.signalCancel 只查 L2 自己的 map，T1 在 L1 的 map 里——永远等不到
            assertTrue(t1.interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "reset 必须中止退役 loop 上的在跑回合（resetConversation 契约：中止在跑回合；"
                            + "spec「取消原因可区分」：会话重置须终止在跑回合）");
            // 订阅者契约（回调线程不保证）下的确定性同步：终态可能晚于 interrupted 计数片刻到达，
            // 以事件流为锚轮询（非竞速）——T1 未放行前不可能自然完成，终态只能来自取消路径
            AwaitUtil.awaitUntil(() -> recorder.terminalCountFor(t1Id) == 1,
                    "T1 必须收到恰好一条取消终态");
            // spec「取消原因可区分且渲染有别」：因会话重置终止 → TURN_CANCELLED(RESET)、回合身份为 T1
            assertEquals(CancelCause.RESET, recorder.eventOf(TurnEvent.Kind.TURN_CANCELLED, t1Id).cause());
            assertEquals(t1Id, recorder.eventOf(TurnEvent.Kind.TURN_CANCELLED, t1Id).turn().id());

            // ---- 放行：修复路径挂 hang、（若红线 1 被单独绕过的）缺陷路径挂 release——两把都开
            t1.hang.countDown();
            t1.release.countDown();
            assertTrue(l1.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));
            AwaitUtil.awaitUntil(t1Future::isDone, "放行后 T1 必须到达终态");
            assertTrue(t1Future.isCancelled(), "被 RESET 取消的回合 future 必须是 cancelled（回合终止、不再烧 token）");
            // spec「终态恰好一次」：取消路径已认领，回合体尾部不得补发第二条终态
            assertEquals(1, recorder.terminalCountFor(t1Id));

            // ==== 红线 2（兜底）：放行收尾后，jsonl 不得被 L1 缓存的旧会话复活 ====
            // AgentRunner 落盘守卫（abort 复查）读的正是 signalCancel 第 1 步置位的 flag——
            // 未触达 L1 则 flag=false，T1 收尾经 SM1 全量重写 jsonl（旧历史 + T1 问答），此处红
            String persisted = java.nio.file.Files.readString(sessionFile);
            assertEquals(1, java.nio.file.Files.readAllLines(sessionFile).size(),
                    "退役 loop 的迟到落盘不得复活刚重置的会话文件（AgentRunner.java:727-734 落盘守卫契约）");
            assertFalse(persisted.contains("OLD-"), "旧会话内容不得在重置后幸存");
            assertFalse(persisted.contains("T1-"), "被放弃回合的问答不得写进新会话");
        } finally {
            // 注：teardown 的 l1.shutdown() 会经 SM1 再落盘旧历史——发生在全部断言之后、
            // @TempDir 即弃，不污染断言窗口
            t1.release.countDown();
            t1.hang.countDown();
            AwaitUtil.awaitUntil(t1Future::isDone, "T1 排空后再收尾（@TempDir 文件锁防护）");
            retiredList.remove(l1);
            l2.shutdown();
            l1.shutdown();
            swapFactoryInstance(originalFactory);
        }
    }

    // ---- 12b. /new 命令路由半边[缺陷3/5]：BuiltinCommands.ctx.getLoop() 的重置取消同样必须触达退役 loop ----

    /**
     * 同一缺陷的 /new 命令路由半边（与上一测试互补的入口攻击）：换血后用户输入 /new——
     * /new 非 priority 命令，走 Phase 3 命令回合跑在 L2 的 executor 上，BuiltinCommands.cmdNew
     * （BuiltinCommands.java:21）经 ctx.getLoop()=L2 调 resetConversation。命令回合自身按身份豁免
     * （AgentLoop.signalCancel 的 self 豁免：取消自身 = "New session started." 确认永远无法返回），
     * 但重置取消仍必须路由到退役 loop 的 T1。
     *
     * 与上一测试的差异化攻击点：
     * - 取消触发的线程语境不同——resetConversation 在 L2 的 loop 线程（命令回合载体）上执行，
     *   T1 的 TURN_CANCELLED 将在 L2 的 loop 线程上经 L1 的订阅表派发，钉 spec「订阅者契约与
     *   隔离」的"回调线程 MUST 视为不可假设"；
     * - 钉命令回合自身存活（self 豁免按身份，修复不得把 /new 回合也取消掉）；
     * - .get() 是更强的同步锚：命令 future 完成 = resetConversation 已同步执行完毕（截断 + 路由取消）。
     *
     * 契约钉子：同上一测试（spec「取消原因可区分且渲染有别」/「终态恰好一次」/
     * 「可插拔订阅与工厂级存活 → 模型切换后订阅存活」+ resetConversation javadoc 与
     * AgentRunner 落盘守卫注释）。
     */
    @Test
    void newCommandAfterModelSwitchRoutesResetCancelToRetiredLoop() throws Exception {
        String current = InstanceContext.currentSessionKey();

        // ---- 前置装配与上一测试相同（完整展开以求独立可编译）----
        GatedScriptAiService l1Service = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        SessionManager sm1 = new SessionManager(tempDir, current);
        AgentLoop l1 = newLoop(l1Service, sm1);
        l1.addTurnSubscriber(recorder);

        l1Service.script(LLMResponse.text("OLD-REPLY"));
        l1.processMessage("OLD-QUESTION", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path sessionFile = sessionFileOf(current);
        assertTrue(java.nio.file.Files.readString(sessionFile).contains("OLD-REPLY"),
                "种子回合必须先落盘");

        GatedCall t1 = l1Service.scriptGated(LLMResponse.text("T1-FINAL"));
        CompletableFuture<AgentResponse> t1Future = l1.processMessage("T1-QUESTION", current);
        assertTrue(t1.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "T1 必须已进入 LLM 调用");
        long t1Id = recorder.lastStarted().turn().id();

        List<AgentLoop> retiredList = retiredLoopsForTest();
        retiredList.add(l1);
        l1.shutdown();
        SessionManager sm2 = new SessionManager(tempDir, current);
        AgentLoop l2 = newLoop(new GatedScriptAiService(), sm2);
        Object originalFactory = swapFactoryInstance(l2);
        try {
            // 换血本身不得取消 T1（工厂 javadoc：shutdown 不打断在跑任务）
            assertEquals(1, t1.interrupted.getCount(), "model switch must not cancel the stranded turn");

            // ---- 用户输入 /new：经命令路由（Phase 3 命令回合 → BuiltinCommands.cmdNew →
            // ctx.getLoop()=L2 的 resetConversation）。
            // .get() 返回 = 命令回合已同步执行完 resetConversation（截断落盘 + 修复后的路由取消）
            AgentResponse resp = l2.processMessage("/new", current)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("New session started.", resp.getContent(),
                    "/new 命令回合自身必须按身份豁免、正常返回确认（修复不得取消命令回合自己）");

            // 锚点 0：命令回合完成即重置已落盘——jsonl 只剩 metadata 一行
            assertEquals(1, java.nio.file.Files.readAllLines(sessionFile).size(),
                    "重置后 jsonl 必须只剩 metadata 一行");

            // ==== 红线 1（缺陷存在时在此超时红）：/new 的 RESET 取消必须路由到退役 loop 的 T1 ====
            assertTrue(t1.interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "/new 经 BuiltinCommands.ctx.getLoop() 调 resetConversation，其取消必须触达"
                            + "退役 loop 的在跑回合（spec「取消原因可区分」：会话重置须终止在跑回合）");
            // 事件可能晚于 interrupted 计数片刻、且派发线程是 L2 的 loop 线程（回调线程不保证）——
            // 以事件流为锚轮询；T1 未放行前不可能自然完成，终态只能来自取消路径
            AwaitUtil.awaitUntil(() -> recorder.terminalCountFor(t1Id) == 1,
                    "T1 必须收到恰好一条取消终态");
            assertEquals(CancelCause.RESET, recorder.eventOf(TurnEvent.Kind.TURN_CANCELLED, t1Id).cause());

            // ---- 放行（修复路径挂 hang、缺陷路径挂 release——两把都开）
            t1.hang.countDown();
            t1.release.countDown();
            assertTrue(l1.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));
            AwaitUtil.awaitUntil(t1Future::isDone, "放行后 T1 必须到达终态");
            assertTrue(t1Future.isCancelled(), "被 RESET 取消的回合 future 必须是 cancelled");
            // spec「终态恰好一次」：取消路径认领后，回合体尾部不得补发第二条终态
            assertEquals(1, recorder.terminalCountFor(t1Id));

            // ==== 红线 2（兜底）：/new 后新会话文件不得被退役 loop 的迟到落盘复活 ====
            String persisted = java.nio.file.Files.readString(sessionFile);
            assertEquals(1, java.nio.file.Files.readAllLines(sessionFile).size(),
                    "退役 loop 的迟到落盘不得复活刚被 /new 截断的会话文件");
            assertFalse(persisted.contains("OLD-"), "旧会话内容不得在 /new 后幸存");
            assertFalse(persisted.contains("T1-"), "被放弃回合的问答不得写进新会话");
        } finally {
            t1.release.countDown();
            t1.hang.countDown();
            AwaitUtil.awaitUntil(t1Future::isDone, "T1 排空后再收尾（@TempDir 文件锁防护）");
            retiredList.remove(l1);
            l2.shutdown();
            l1.shutdown();
            swapFactoryInstance(originalFactory);
        }
    }

    // ---- scaffolding ----

    /** 与 setUp 同配方的 loop 构造（换血/C3 测试需要第二个独立实例）。 */
    private AgentLoop newLoop(AiService service) {
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        return new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), service);
    }

    /**
     * 与 setUp 同配方、但 SessionManager 可指定：换血的两个 loop 须是同盘同键的独立缓存
     * （重置路由缺陷的核心即两缓存互不知情——L2 的 invalidate 触不到 L1 的缓存）。
     */
    private AgentLoop newLoop(AiService service, SessionManager sessionManager) {
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        return new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir), sessionManager, service);
    }

    /**
     * 与 {@link #newLoop(AiService)} 同配方，仅 SessionManager 的 focus 键参数化：换血后的
     * 新单例 L2 须以当前实例会话键为 focus 构造，才能复刻生产 {@code createAgentLoop}
     * 在构造期从磁盘载入当前会话（「换血时刻磁盘快照」），{@code clearCurrentSession}
     * 的 {@code sessions.get(...)} 才命中缓存（focus 只影响加载，落盘路径由会话键决定，
     * L1/L2 恒写同一 jsonl——这正是复活的物理通道）。
     */
    private AgentLoop newLoopWithManagerFocus(AiService service, String focusSessionKey) {
        return newLoop(service, new SessionManager(tempDir, focusSessionKey));
    }

    /** 与 {@code SessionManager.safeFileName} 同一规范化：会话 jsonl 的实际落盘路径。 */
    private Path sessionFileOf(String sessionKey) {
        return tempDir.resolve("sessions")
                .resolve(sessionKey.replaceAll("[^a-zA-Z0-9-_]", "_") + ".jsonl");
    }

    /** 会话文件的非空行数（saveSession 逐行写、try-with-resources 关闭即落盘，读时无缓冲竞态）。 */
    private static long countSessionFileLines(Path file) throws java.io.IOException {
        if (!java.nio.file.Files.exists(file)) {
            return 0;
        }
        long n = 0;
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(file)) {
            while (reader.readLine() != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * CloseConsolidationDialog.doInBackground 取消步骤的生产入口（原为 :113-117 的
     * 逐行复制品，按其内嵌「修复耦合提示」在修复落地时替换为直调真实现）：
     * {@code CloseConsolidationCoordinator.cancelActiveTurnsSilently()} =
     * 工厂跨实例路由 {@code cancelActiveTaskAny(currentSessionKey, SILENT)}——取消
     * 触达当前+退役 loop 上该会话的在跑回合并等收尾（合计 ≤5s），重建窗口
     * （instance==null）null-safe，不再有被「必无活动回合」假设吞掉的路径。
     */
    private static void dialogCancelStep() {
        org.gitee.jmeter.ai.agent.memory.CloseConsolidationCoordinator.cancelActiveTurnsSilently();
    }

    /** 反射读取工厂的退役 loop 表（仅 C3 路由测试用）。 */
    @SuppressWarnings("unchecked")
    private static List<AgentLoop> retiredLoopsForTest() {
        try {
            Field field = AgentLoopFactory.class.getDeclaredField("retiredLoops");
            field.setAccessible(true);
            return (List<AgentLoop>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 换上指定 fake service 的同配方 loop（Stop/Reset 钉子用 HANG 策略 service）。 */
    private <S extends AiService> S swapService(S service) {
        loop.shutdown();
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), service);
        loop.addTurnSubscriber(recorder);
        return service;
    }

    /** 反射替换 {@link AgentLoopFactory} 的私有静态单例（仅工厂挂接链路测试用）。 */
    private static Object swapFactoryInstance(Object value) {
        try {
            Field field = AgentLoopFactory.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object previous = field.get(null);
            field.set(null, value);
            return previous;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
