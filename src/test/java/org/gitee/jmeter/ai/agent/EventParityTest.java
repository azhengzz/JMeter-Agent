package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.presenter.TurnSubscriber;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.testsupport.RecordingSubscriber;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件流载荷/时序断言 + 两条竞态钉子（P1a 起）：旧 notify* 通道的生产驱动腿已删
 * （IpcServer 瘦身、republishListener 删除），呈现的唯一权威是
 * {@link TurnSubscriber} 事件流。本测试原为双通道影子等价护栏（P0 并行期），换轨
 * 完成后转型为单流断言：
 * <ul>
 *   <li>A 正常 IPC 回合：Kind 序列 + echoText + 进度同实例（回调 vs 事件）+ 完成
 *       同实例（future.get 所得即 completed 载荷）；</li>
 *   <li>B Phase 1 命令：仅 COMMAND_RESULT（命令回合无 STARTED/终态，面板不武装）；</li>
 *   <li>C 委派撞会话忙：REJECTED_BUSY 恰一次；</li>
 *   <li>D 注入 ack：IPC/LOCAL 双源各一次 INJECTED（新通道无条件发射——LOCAL 源的
 *       面板渲染门控在 P1b 3.2 删除，发射本身不受门控）；</li>
 *   <li>E Stop 取消：signalCancel 自身发射 TURN_CANCELLED(USER_STOP)，无需外部
 *       转发；取消 reason 串 → {@link CancelCause} 映射；</li>
 *   <li>F 失败终态：loop catch(Throwable) 发射 {@code "agent failed: " + 根因}
 *       （对齐旧 IpcServer ExecutionException 分支的载荷归一）；</li>
 *   <li>钉子 1：Enter 在途横跨自然完成 → 注入周期超限残留 re-publish 成孤儿回合，
 *       「垂死回复 + 孤儿回复」两条都呈现 = 今日基线；</li>
 *   <li>钉子 2：cleanup→孤儿 register 间隙 → 垂死回合摘槽后到达的消息开独立回合，
 *       两个回合的回复都完整呈现 = 今日基线。</li>
 * </ul>
 */
class EventParityTest {

    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

    AgentLoop loop;
    GatedScriptAiService aiService;
    RecordingSubscriber newChannel;

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
        newChannel = new RecordingSubscriber();
        loop.addTurnSubscriber(newChannel);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    // ---- A. 正常 IPC 回合：Kind 序列 + 载荷逐一断言（进度/完成同实例）----

    @Test
    void normalIpcTurnEventSequenceAndPayloads() throws Exception {
        String current = InstanceContext.currentSessionKey();
        String message = "[from cli] hello";
        GatedCall first = aiService.scriptGated(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", Map.of())), "thinking"));
        aiService.script(LLMResponse.text("IPC-FINAL"));
        List<ProgressUpdate> callbackUpdates = new CopyOnWriteArrayList<>();

        // IpcServer handleAgent 形态：turnCallback 只喂累积器；呈现走事件流
        CompletableFuture<AgentResponse> future = loop.processMessage(
                message, current, callbackUpdates::add, TurnOrigin.IPC_CLI);
        assertTrue(!future.isDone(), "gated body parks the turn: events must fire while it is live");
        first.release.countDown(); // 门控放行：提交时回合体必未完成
        AgentResponse ar = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of("started", "progress", "progress", "completed"),
                newChannel.turnKindsFor(TurnOrigin.IPC_CLI));
        assertEquals(message, newChannel.startedMessages.get(0),
                "TURN_STARTED echoText is the submitted message");
        assertSame(ar, newChannel.completedResponses.get(0),
                "completed payload is the very instance the initiator future resolved with");
        assertEquals(callbackUpdates.size(), newChannel.progressUpdates.size(),
                "PROGRESS events pair 1:1 with the turn callback the initiator supplied");
        for (int i = 0; i < callbackUpdates.size(); i++) {
            assertSame(callbackUpdates.get(i), newChannel.progressUpdates.get(i),
                    "PROGRESS events carry the same instances the turn callback observed");
        }
    }

    // ---- B. Phase 1 命令：仅 COMMAND_RESULT（无回合武装）----

    @Test
    void commandResultIsNewOnlyChannel() throws Exception {
        String current = InstanceContext.currentSessionKey();
        AgentResponse ar = loop.processMessage("/status", current, null, TurnOrigin.IPC_CLI)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ar.isSuccess());
        assertEquals(List.of(TurnEvent.Kind.COMMAND_RESULT.name()), newChannel.rawKinds(),
                "command turns raise COMMAND_RESULT only: no STARTED/terminal, the panel stays idle");
    }

    // ---- C. 委派撞会话忙：loop 内联发射 REJECTED_BUSY 恰一次 ----

    @Test
    void delegatedBusyRejectionRaisedExactlyOnce() throws Exception {
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
            return LLMResponse.text("busy done");
        };
        CompletableFuture<AgentResponse> busy = loop.processMessage("busy", current);
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        AgentResponse rejected = loop.processMessage("[delegated-from A] t", current, null,
                TurnOrigin.IPC_DELEGATED)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(rejected.isSuccess());

        release.countDown();
        busy.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        long newRejected = newChannel.events.stream()
                .filter(e -> e.kind() == TurnEvent.Kind.REJECTED_BUSY).count();
        assertEquals(1, newRejected, "REJECTED_BUSY must be raised exactly once");
    }

    // ---- D. 注入 ack：双源各一次 INJECTED（发射无条件；面板 LOCAL 源门控另论）----

    @Test
    void injectedAckRaisedForBothSources() throws Exception {
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
            return LLMResponse.text("busy done");
        };
        CompletableFuture<AgentResponse> busy = loop.processMessage("busy", current);
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(loop.processMessage("[from cli] extra", current, null, TurnOrigin.IPC_CLI)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess());
        assertEquals(List.of("[from cli] extra"), newChannel.injectedMessages,
                "IPC source raises INJECTED once");

        // LOCAL 源：新通道无条件发射（旧 fromIpc 门控只作用于旧通道）
        assertTrue(loop.processMessage("local extra", current).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess());
        assertEquals(List.of("[from cli] extra", "local extra"), newChannel.injectedMessages,
                "the event stream emits INJECTED for both sources");

        release.countDown();
        busy.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- E. Stop 取消：signalCancel 即发射终态；reason 串 ↔ CancelCause 映射 ----

    @Test
    void userStopCancelEmitsCancelledTerminalEvent() throws Exception {
        String current = InstanceContext.currentSessionKey();
        CountDownLatch llmEntered = new CountDownLatch(1);
        aiService.override = () -> {
            llmEntered.countDown();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // signalCancel 第 2 步的 interrupt 先于第 3 步 cancel：单次停等被 interrupt
            // 唤醒后整回合可在两步间隙自然完成并认领终态（[started, completed]，负载
            // 相关偶然）。此处再停一门——回合只能被 cancel(true) 的第二次 interrupt
            // 放行，[interrupt → cancel] 窗口内 future 恒未完成，cancel 必胜、终态必为
            // TURN_CANCELLED（断言不变，仅消除 fake 的调度竞态）。
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LLMResponse.text("cancelled-final");
        };
        CompletableFuture<AgentResponse> future = loop.processMessage(
                "[from cli] stop me", current, null, TurnOrigin.IPC_CLI);
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // signalCancel 自身发射 TURN_CANCELLED(USER_STOP)：无需外部转发腿
        assertTrue(loop.signalCancel(current));

        assertEquals(List.of("started", "cancelled"), newChannel.turnKindsFor(TurnOrigin.IPC_CLI));
        assertEquals(CancelCause.USER_STOP, newChannel.lastCancelledCause,
                "cancelled_by_target_user maps to USER_STOP");
        assertTrue(future.isCompletedExceptionally(), "cancelled future must not complete normally");
    }

    // ---- F. 失败终态：loop catch(Throwable) 发射 "agent failed: " 前缀 + 根因 ----
    // 注：AgentRunner 吞 Exception（返回 error result，future 正常完成），能逃逸到
    // loop catch(Throwable) → future.completeExceptionally 的是 Error/Throwable——
    // 故用 AssertionError 触发该发射点。

    @Test
    void failureTurnEmitsAgentFailedTerminal() throws Exception {
        String current = InstanceContext.currentSessionKey();
        CountDownLatch failEntered = new CountDownLatch(1);
        CountDownLatch failRelease = new CountDownLatch(1);
        aiService.override = () -> {
            failEntered.countDown();
            try {
                failRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("llm exploded");
        };
        CompletableFuture<AgentResponse> future = loop.processMessage(
                "[from cli] fail", current, null, TurnOrigin.IPC_CLI);
        assertTrue(failEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        failRelease.countDown();

        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            throw new IllegalStateException("turn should have failed");
        } catch (ExecutionException ee) {
            // 期望路径：catch(Throwable) 发射失败终态后 completeExceptionally
        }

        assertEquals(List.of("started", "completed"), newChannel.turnKindsFor(TurnOrigin.IPC_CLI));
        assertTrue(newChannel.completedResponses.get(0).getErrorMessage()
                        .startsWith("agent failed: "),
                "failure payload keeps the 'agent failed: ' normalization");
        assertTrue(newChannel.completedResponses.get(0).getErrorMessage()
                        .contains("llm exploded"),
                "the root cause message is preserved");
    }

    // ---- 钉子 1. Enter 在途横跨自然完成：注入周期超限残留 re-publish 成孤儿回合，
    //         「垂死回复 + 孤儿回复」两条都呈现 = 今日基线（垂死终态先于孤儿 STARTED，
    //         孤儿是 REPUBLISH 源回合、echoText 为 null——You 回显已由 INJECTED 给过）----

    @Test
    void raceNailEnterInFlightAcrossNaturalCompletionBothRepliesRender() throws Exception {
        String current = InstanceContext.currentSessionKey();
        List<GatedCall> calls = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(aiService.scriptGated(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "step " + i)));
        }
        GatedCall finalCall = aiService.scriptGated(LLMResponse.text("R-FINAL"));
        aiService.script(LLMResponse.text("ORPHAN-FINAL"));

        CompletableFuture<AgentResponse> first = loop.processMessage(
                "[from cli] M1", current, null, TurnOrigin.IPC_CLI);
        assertEquals("[from cli] M1", newChannel.startedMessages.get(0),
                "TURN_STARTED echoText carries the submitted message");
        for (int i = 0; i < 6; i++) {
            GatedCall call = calls.get(i);
            assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(loop.processMessage("FOLLOWUP-" + (i + 1), current)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
            call.release.countDown();
        }
        assertTrue(finalCall.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        finalCall.release.countDown();
        AgentResponse dyingReply = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 孤儿回合由垂死回合 finally 的 re-publish 启动（无外部通道可等待），
        // 以事件流终态为完成信号
        AwaitUtil.awaitUntil(() -> newChannel.completedResponses.size() == 2,
                "orphan turn must reach its terminal event");

        // 两条回复都在（同一流、垂死先于孤儿），孤儿内容经事件载荷断言
        assertSame(dyingReply, newChannel.completedResponses.get(0));
        assertEquals("ORPHAN-FINAL", newChannel.completedResponses.get(1).getContent());
        TurnEvent orphanStart = newChannel.events.stream()
                .filter(e -> e.kind() == TurnEvent.Kind.TURN_STARTED
                        && e.turn().origin() == TurnOrigin.REPUBLISH)
                .findFirst().orElseThrow();
        assertNull(orphanStart.turn().echoText(),
                "REPUBLISH echoText is null — the You echo was already given by the INJECTED event");
        int dyingTerminalIdx = -1;
        for (int i = 0; i < newChannel.events.size(); i++) {
            TurnEvent e = newChannel.events.get(i);
            if (e.kind() == TurnEvent.Kind.TURN_COMPLETED && e.response() == dyingReply) {
                dyingTerminalIdx = i;
                break;
            }
        }
        assertTrue(dyingTerminalIdx >= 0 && newChannel.events.indexOf(orphanStart) > dyingTerminalIdx,
                "the dying turn's terminal precedes the orphan's STARTED");
    }

    // ---- 钉子 2. cleanup→孤儿 register 间隙：垂死回合摘槽后到达的消息走 Phase 3
    //         开独立回合，两个回合的回复都完整呈现 = 今日基线 ----

    @Test
    void raceNailMessageAfterSlotRemovalStartsOwnTurnBothRepliesRender() throws Exception {
        String current = InstanceContext.currentSessionKey();
        CountDownLatch llmEntered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch hang = new CountDownLatch(1);
        aiService.override = () -> {
            llmEntered.countDown();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                interrupted.countDown();
                try {
                    hang.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            return LLMResponse.text("interrupted-final");
        };
        CompletableFuture<AgentResponse> first = loop.processMessage(
                "[from cli] one", current, null, TurnOrigin.IPC_CLI);
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        loop.signalCancel(current); // 摘槽 + TURN_CANCELLED(USER_STOP) 终态
        assertTrue(interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        hang.countDown();
        assertTrue(loop.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // 间隙后到达的消息：hasActiveRun=false → 独立回合（门控保证其终态前回合体在跑）
        CountDownLatch t2Entered = new CountDownLatch(1);
        CountDownLatch t2Release = new CountDownLatch(1);
        aiService.override = () -> {
            t2Entered.countDown();
            try {
                t2Release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LLMResponse.text("turn two");
        };
        CompletableFuture<AgentResponse> second = loop.processMessage(
                "[from cli] two", current, null, TurnOrigin.IPC_CLI);
        assertTrue(t2Entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        t2Release.countDown();
        AgentResponse secondReply = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 第一回合取消 + 第二回合完整：两个回合的回复都有交代
        assertEquals(List.of("started", "cancelled", "started", "completed"),
                newChannel.turnKindsFor(TurnOrigin.IPC_CLI));
        assertEquals(1, newChannel.events.stream()
                .filter(e -> e.kind() == TurnEvent.Kind.TURN_CANCELLED).count());
        assertEquals(List.of(secondReply), newChannel.completedResponses);
        assertTrue(first.isCompletedExceptionally(), "turn one stays cancelled: no late completion");
    }

    // ---- scaffolding：收编于 org.gitee.jmeter.ai.agent.testsupport ----
}
