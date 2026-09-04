package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.presenter.TurnSubscriber;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.Tool;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「Stop 取消收尾窗口」缺陷族的回归测试（队列归回合所有 + 路由槽）。
 *
 * <p>核心机制：注入队列是回合私有的（句柄穿进回合任务），map 条目只作路由槽
 * （队列存在 = 可注入）。signalCancel 摘路由槽——垂死会话立即不可注入，新消息
 * 只能开新回合；垂死回合按句柄抽干残留并 re-publish。缺陷族：
 * <ol>
 *   <li>Stop 后残留注入消息被 re-publish 成无人持有 future 的孤儿回合，最终回复
 *       静默丢失（GUI 不渲染）——孤儿回合现为 REPUBLISH 源事件回合，订阅者
 *       （面板）据活回合集合领养呈现；</li>
 *   <li>pre-pickup 被取消的回合（AsyncSupply 见 result 已置即跳过 lambda）无人
 *       善后其队列——由死任务的 guard 分支抽干 re-publish 修复；</li>
 *   <li>re-publish 期间旧回合 whenComplete 无条件 remove 摘掉新回合表项——孤儿
 *       变成 Stop 不可达——由条件删除修复；</li>
 *   <li>executor 已退役（模型切换换血）时 re-publish 提交抛 RejectedExecutionException
 *       顶掉回合返回值——由 REE 防护修复。</li>
 * </ol>
 *
 * <p><b>契约修订（2026-08-23，Stop=硬边界）：</b>取消（Stop/重置）语义下未消费的
 * 注入队列残留<b>一律作废</b>，不再重发布——消费进上下文的消息本就随回合作废，
 * 队列残留保持同命运，消除「点得快被复活、点得慢被作废」的时序差异。重发布仅存于
 * <b>自然完成</b>路径（回合正常结束时队列仍有残留，如注入周期 5/5 封顶后入队的消息）。
 * 作废仅记日志，不经回合回调渲染进聊天区（2026-08-23 拍板：不打扰页面操作）。
 *
 * <p>会话键用 {@code InstanceContext.currentSessionKey()}：事件派发守卫只放行
 * 当前实例键的回合事件（「other-session」跨会话锚点的照旧）。孤儿回合观察走
 * REPUBLISH 源事件记录器（republishListener 通道已删除）。
 *
 * <p>线程模型与生产一致：AgentLoop 用内部单线程 executor 跑回合任务，
 * AgentRunner 在 commonPool 上执行 LLM 调用与工具。脚本化 fake AiService 以
 * 「entered latch + release future」逐调用门控，保证跨线程时序确定可复现。
 */
class AgentLoopRepublishTest {

    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

    AgentLoop loop;
    ScriptedAiService aiService;
    SessionManager sessionManager;
    String sessionKey;

    /** 孤儿回合（REPUBLISH 源）事件记录器：STARTED 计数喂 latch、终态载荷按事件序分桶。 */
    OrphanRecorder orphans;

    @BeforeEach
    void setUp() {
        aiService = new ScriptedAiService();
        // 事件派发守卫只放行当前实例键：会话键与其保持一致，孤儿事件才可达
        sessionKey = InstanceContext.currentSessionKey();

        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        MemoryConsolidator consolidator = Mockito.mock(MemoryConsolidator.class);

        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());

        ContextBuilder contextBuilder = new ContextBuilder(memoryStore, tempDir);
        sessionManager = new SessionManager(tempDir, sessionKey);

        loop = new AgentLoop(registry, memoryStore, consolidator, contextBuilder,
                sessionManager, aiService);

        orphans = new OrphanRecorder();
        loop.addTurnSubscriber(orphans);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    /** @param expected 本测试期望观察到的 REPUBLISH 源回合 STARTED 个数（latch 计数） */
    private void expectOrphans(int expected) {
        orphans.latch.set(new CountDownLatch(expected));
    }

    // ------------------------------------------------------------------
    // 取消后新消息只能开新回合（用户原则：signalCancel 即摘路由槽）
    // ------------------------------------------------------------------

    @Test
    void stopThenSend_duringDyingWindow_startsNormalTurnInsteadOfInjection() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        aiService.scriptImmediate(LLMResponse.text("R2-FINAL"));

        // turn 1 的 future 会被 signalCancel 取消，无需等待（避免误判为失败）
        loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");

        // Stop：路由槽立即摘除，垂死会话不可注入
        loop.signalCancel(sessionKey);
        assertFalse(loop.hasActiveRun(sessionKey),
                "取消后路由槽必须立即摘除，否则新消息会被吸入注入队列");

        // 垂死窗口内的新消息：必须走 Phase 3 起独立回合（future 归调用者所有），
        // 而不是被路由进垂死回合的注入队列
        CompletableFuture<AgentResponse> f2 = loop.processMessage("M2", sessionKey);

        complete(call1); // 垂死回合的 LLM 调用返回 → abort → 不落盘 → 收尾

        AgentResponse r2 = f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("R2-FINAL", r2.getContent(),
                "新消息必须作为正常回合执行；当前缺陷：被吞成 'Message injected into current conversation.'");
        assertTrue(assistantContents().contains("R2-FINAL"), "最终回复应落盘到 session");
        assertTrue(orphans.started.isEmpty(), "M2 未入队时不应产生 re-publish 孤儿");
    }

    // ------------------------------------------------------------------
    // 契约修订（2026-08-23）主用例：Stop 前已 ack、尚未消费的注入残留
    // 一律作废且不产生聊天区提示（仅记日志），不得重发布成孤儿回合
    // ------------------------------------------------------------------

    @Test
    void leftoverInjectedBeforeStop_discarded_notRepublished_noChatNotice() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        ScriptedCall callNext = aiService.scriptGated(LLMResponse.text("R-NEXT"));
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-MUST-NOT-RUN"));

        List<ProgressUpdate> notes = new CopyOnWriteArrayList<>();
        loop.processMessage("M1", sessionKey, notes::add, TurnOrigin.LOCAL_PANEL);
        await(call1.entered, "first LLM call started");

        // Stop 之前的合法中途注入（进入垂死回合的私有队列，回合持句柄）
        AgentResponse ack = loop.processMessage("M2", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ack.getContent().startsWith("Message injected"),
                "非垂死回合的中途注入语义不受影响");

        loop.signalCancel(sessionKey);
        complete(call1); // 垂死回合 abort → finally 按句柄抽干残留 → 作废（不重发布）

        // 后继回合的 LLM entered = 垂死收尾（含作废）已执行的确定性锚点（executor 串行）
        CompletableFuture<AgentResponse> fNext = loop.processMessage("M-next", sessionKey);
        await(callNext.entered, "follow-up turn started（隐含垂死收尾已执行）");
        complete(callNext);
        assertEquals("R-NEXT", fNext.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());

        assertTrue(notes.stream().noneMatch(u -> u.getMessage().contains("已作废")),
                "作废仅记日志，不得经回合回调渲染进聊天区");
        assertTrue(orphans.started.isEmpty(), "Stop 后的队列残留不得被重发布成孤儿回合");
        assertFalse(assistantContents().contains("ORPHAN-MUST-NOT-RUN"),
                "被作废的消息不得被处理落盘");
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "turn settles");
    }

    // 注：原「Stop 触发的孤儿回合必须可被 cancelActiveTask 二次取消」用例随契约修订删除——
    // Stop 不再产生孤儿；「重发布回合可取消」属性由 overflowRepublishedTurn_remainsCancellable
    // （自然完成触发）钉死。

    // ------------------------------------------------------------------
    // 注入周期超限（MAX_INJECTION_CYCLES=5）的残留消息走同一 re-publish 出口
    // ------------------------------------------------------------------

    @Test
    void injectionCycleOverflow_leftoverMessage_republishedTurnReachesEventStream() throws Exception {
        expectOrphans(1);
        // 调用 1..6：带工具调用（每轮一个注入检查点）；调用 7：原回合最终回复
        List<ScriptedCall> calls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(aiService.scriptGated(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "step " + i)));
        }
        ScriptedCall finalCall = aiService.scriptGated(LLMResponse.text("R7-FINAL"));
        // 孤儿回合的调用：立即返回
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-FINAL"));

        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);

        // 每轮 LLM 调用窗口内注入 1 条：前 5 条被 drain（周期 5/5 封顶），第 6 条成为残留
        for (int i = 0; i < 6; i++) {
            await(calls.get(i).entered, "LLM call " + (i + 1));
            AgentResponse ack = loop.processMessage("FOLLOWUP-" + (i + 1), sessionKey)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(ack.getContent().startsWith("Message injected"));
            complete(calls.get(i));
        }
        await(finalCall.entered, "final LLM call");
        complete(finalCall);

        assertEquals("R7-FINAL", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "原回合自身的最终回复不受注入周期超限影响");

        assertTrue(orphans.latch.get().await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "周期超限残留必须经 re-publish 成 REPUBLISH 源回合（STARTED 事件）");
        awaitUntil(() -> !orphans.completedResponses().isEmpty(), "orphan turn terminal event");
        assertEquals("ORPHAN-FINAL", orphans.completedResponses().get(0).getContent(),
                "残留消息的孤儿回合最终回复应可达");
    }

    /**
     * 溢出路径的孤儿回合同样必须可取消：原回合的 whenComplete 在其 finally 的
     * re-publish（已 put 新回合的 future/abortFlag/latch）之后才执行——无条件
     * remove(key) 会当场摘掉孤儿回合的表项，使其 Stop 不可达。条件删除修复此缺陷。
     */
    @Test
    void overflowRepublishedTurn_remainsCancellable() throws Exception {
        expectOrphans(1);
        List<ScriptedCall> calls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(aiService.scriptGated(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "step " + i)));
        }
        ScriptedCall finalCall = aiService.scriptGated(LLMResponse.text("R7-FINAL"));
        ScriptedCall orphanCall = aiService.scriptGated(LLMResponse.text("ORPHAN-RUNNING"));

        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);
        for (int i = 0; i < 6; i++) {
            await(calls.get(i).entered, "LLM call " + (i + 1));
            assertTrue(loop.processMessage("FOLLOWUP-" + (i + 1), sessionKey)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
            complete(calls.get(i));
        }
        await(finalCall.entered, "final LLM call");
        complete(finalCall);
        assertEquals("R7-FINAL", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());

        assertTrue(orphans.latch.get().await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "孤儿回合应发 REPUBLISH 源 STARTED 事件");
        await(orphanCall.entered, "orphan turn's LLM call started");

        loop.cancelActiveTask(sessionKey, CancelCause.USER_STOP);
        awaitUntil(() -> orphans.cancelledCount() == 1,
                "孤儿回合必须可被 cancelActiveTask 取消（TURN_CANCELLED 终态事件闭合；"
                        + "原回合 whenComplete 不得摘掉其 activeTasks 表项）");

        complete(orphanCall);
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "orphan turn winds down");
    }

    // ------------------------------------------------------------------
    // 回归保护：非垂死回合的中途注入语义不变
    // ------------------------------------------------------------------

    @Test
    void midTurnInjection_withoutStop_stillQueuesNormally() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("PARTIAL"));
        aiService.scriptImmediate(LLMResponse.text("DONE"));

        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");

        AgentResponse ack = loop.processMessage("FOLLOWUP", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ack.getContent().startsWith("Message injected"));
        assertTrue(loop.hasActiveRun(sessionKey), "正常回合必须继续报告 active（注入语义依赖）");

        complete(call1);
        // call1 返回 "PARTIAL"（final）→ 注入检查 2 drain 到 FOLLOWUP → continue → call2 "DONE"
        assertEquals("DONE", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());
        assertTrue(assistantContents().contains("DONE"));
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "turn settles");
        assertTrue(orphans.started.isEmpty(), "无残留消息时不应触发 re-publish 孤儿");
    }

    // ------------------------------------------------------------------
    // 对抗性测试：路由槽生命周期、订阅者健壮性、复合时序
    // ------------------------------------------------------------------

    /**
     * 路由槽必须在回合收尾后彻底恢复：Stop → 收尾 → 起新回合，新回合运行中
     * hasActiveRun 必须为 true。若取消状态泄漏，GUI 将永远走 startNormalSend
     * 而非注入。
     */
    @Test
    void adversarial_routingSlotRecovers_AfterCancelledTurnSettles() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        ScriptedCall call2 = aiService.scriptGated(LLMResponse.text("R2"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");

        loop.signalCancel(sessionKey);
        assertFalse(loop.hasActiveRun(sessionKey), "垂死窗口内应报 false");
        complete(call1);
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "cancelled turn settles");

        // 起新回合：若路由状态泄漏，此处 hasActiveRun 将永远 false
        CompletableFuture<AgentResponse> f2 = loop.processMessage("M2", sessionKey);
        await(call2.entered, "second turn LLM call started");
        assertTrue(loop.hasActiveRun(sessionKey),
                "新回合运行中 hasActiveRun 必须恢复 true——取消状态泄漏会使注入语义永久失效");

        complete(call2);
        assertEquals("R2", f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());
    }

    /**
     * 迟到/重复的 signalCancel（回合已彻底结束）必须是纯 no-op，
     * 不得给后续回合留下任何副作用。
     */
    @Test
    void adversarial_lateSignalCancel_AfterTurnSettled_IsNoOp() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        ScriptedCall call2 = aiService.scriptGated(LLMResponse.text("R2"));

        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");
        complete(call1);
        assertEquals("R1", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "first turn settles");

        // 回合已结束：迟到的取消（双击 Stop / 竞态迟到）应无副作用
        boolean signalled = loop.signalCancel(sessionKey);
        assertFalse(signalled, "无活回合时 signalCancel 应报告无可取消");

        CompletableFuture<AgentResponse> f2 = loop.processMessage("M2", sessionKey);
        await(call2.entered, "second turn LLM call started");
        assertTrue(loop.hasActiveRun(sessionKey), "迟到取消不得影响后续回合的 active 判定");
        complete(call2);
        assertEquals("R2", f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());
    }

    /**
     * 订阅者自身抛异常不得炸掉 finally 的 re-publish 循环——多条 leftover 时，
     * 第一条派发中的订阅者异常会让后续消息不再被 re-publish 而丢失。派发按
     * 订阅者逐个隔离（吞异常），重发布与其它订阅者不受影响。
     * 契约修订（2026-08-23）后 Stop 不再触发重发布，改用自然完成（周期超限）构造。
     */
    @Test
    void adversarial_throwingSubscriber_DoesNotBreakRepublishLoop() throws Exception {
        loop.addTurnSubscriber(new TurnSubscriber() {
            @Override public void onTurnEvent(TurnEvent event) {
                throw new RuntimeException("subscriber exploded");
            }
        });
        OverflowTurn t = scriptOverflowingTurn();
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-A"));
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-B"));

        driveOverflowingTurn(t, "LEFT-A", "LEFT-B"); // finally re-publish 两条；每次派发抛异常订阅者即炸

        awaitUntil(() -> assistantContents().contains("ORPHAN-A")
                        && assistantContents().contains("ORPHAN-B"),
                "both republished turns complete and persist");
        awaitUntil(() -> orphans.completedResponses().size() == 2,
                "健康订阅者照常收到两个孤儿回合的终态（逐订阅者异常隔离）");
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "all turns settle");
    }

    /**
     * 多条 leftover → 多个孤儿回合，终态逐一经事件流可达且落盘。
     * 契约修订（2026-08-23）后 Stop 不再触发重发布，改用自然完成（周期超限）构造。
     */
    @Test
    void adversarial_twoLeftovers_TwoOrphanTurns_BothReachEventStream() throws Exception {
        expectOrphans(2);
        OverflowTurn t = scriptOverflowingTurn();
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-A"));
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-B"));

        driveOverflowingTurn(t, "LEFT-A", "LEFT-B");

        assertTrue(orphans.latch.get().await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "两条残留应各产生一个 REPUBLISH 源回合 STARTED 事件");
        awaitUntil(() -> orphans.completedResponses().size() == 2, "both orphan terminals");
        assertEquals(2, orphans.started.size());
        assertEquals(List.of("ORPHAN-A", "ORPHAN-B"), orphans.completedResponses().stream()
                .map(AgentResponse::getContent).toList());
        assertTrue(assistantContents().contains("ORPHAN-A"));
        assertTrue(assistantContents().contains("ORPHAN-B"));
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "orphan turns settle");
    }

    /**
     * 复合时序：Stop 前队列已有残留 M2 + 垂死窗口内又发新消息 M3——M3 走 Phase 3
     * （future 归调用者）正常完成；M2 随 Stop 作废（契约修订：不重发布、明示）。
     */
    @Test
    void adversarial_leftoverDiscarded_newMessageDuringDying_runsToCompletion() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        aiService.scriptImmediate(LLMResponse.text("R3-FINAL")); // M3（唯一会跑的后续回合）

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");

        // M2 先入队（Stop 前的合法注入）
        assertTrue(loop.processMessage("M2", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getContent().startsWith("Message injected"));

        loop.signalCancel(sessionKey);

        // M3 在垂死窗口内发出：放行走 Phase 3，future 归测试持有
        CompletableFuture<AgentResponse> f3 = loop.processMessage("M3", sessionKey);

        complete(call1); // 垂死收尾：cleanup 拿到 M2 → 作废（不重发布）；M3 已在队列前列

        assertEquals("R3-FINAL", f3.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "垂死窗口内的新消息回合必须正常完成（f3 完成隐含垂死收尾已执行）");
        assertTrue(orphans.started.isEmpty(), "Stop 前的队列残留必须作废，不得重发布成孤儿");
        assertTrue(assistantContents().contains("R3-FINAL"));
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "all turns settle");
    }

    // ------------------------------------------------------------------
    // [提交→pickup] 窗口：caller 侧注册路由槽 + pre-pickup 取消的善后
    // ------------------------------------------------------------------

    /**
     * 回合提交后、执行器 pickup 前到达的普通消息，必须立即得到注入回执并入该回合；
     * 否则消息被拆成独立回合排到队尾，调用方在原回合收尾前拿不到回执
     * （DelegationGuardTest flaky 的根因）。本用例用「跨会话长回合占住单线程
     * executor」把窗口无限期撑开，将偶发竞态转为确定性复现。
     */
    @Test
    void busySessionFollowUpDuringPickupWindow_GetsInjectionAckImmediately() throws Exception {
        ScriptedCall blocker = aiService.scriptGated(LLMResponse.text("BLOCKER-DONE"));
        aiService.scriptImmediate(LLMResponse.text("BUSY-DONE-1"));
        aiService.scriptImmediate(LLMResponse.text("BUSY-DONE-2"));

        // 别的会话的长回合占住 loop 级单线程 executor，把本会话回合的 pickup 无限期
        // 推迟——[提交→pickup] 窗口被测试稳定撑开，不依赖线程调度运气
        loop.processMessage("block", "other-session");
        await(blocker.entered, "blocker turn LLM call started");

        // busy 会话回合提交：其队列已在调用方线程注册（路由槽就位）
        CompletableFuture<AgentResponse> f1 = loop.processMessage("user message", sessionKey);

        // 窗口内到达的普通消息：必须立即得到注入回执（并入 busy 回合）
        AgentResponse ack = loop.processMessage("user follow-up", sessionKey).get(2, TimeUnit.SECONDS);
        assertTrue(ack.getContent().startsWith("Message injected"),
                "pickup 窗口内的普通消息必须立即得到注入回执；当前缺陷：穿透 Phase 3 排队，回执超时");

        complete(blocker); // blocker 收尾 → busy 回合 pickup → 窗口内并入的消息在回合内消化

        assertEquals("BUSY-DONE-2", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "窗口内并入的消息应在本回合内被消化（final 后注入检查 drain → continue → 再答），"
                        + "不得被拆成独立回合");
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "turn settles");
        assertTrue(orphans.started.isEmpty(), "窗口内并入的消息不得产生 re-publish 孤儿");
    }

    /**
     * pre-pickup 取消（双击 Stop 打中 [提交→pickup] 中的回合）：supplyAsync 语义下
     * 被取消任务的 lambda 永不执行，其队列里已 ack 的消息由死任务 guard 分支善后——
     * 契约修订（2026-08-23）：取消语义下一律立即抽干作废，不得悬挂到下一回合，
     * 也不得重发布成孤儿回合。
     */
    @Test
    void prePickupCancel_windowMessagesDiscardedImmediately() throws Exception {
        ScriptedCall blocker = aiService.scriptGated(LLMResponse.text("BLOCKER-DONE"));
        ScriptedCall call3 = aiService.scriptGated(LLMResponse.text("R3"));

        loop.processMessage("block", "other-session");
        await(blocker.entered, "blocker turn LLM call started");

        // 本会话回合提交（pre-pickup，排在 blocker 之后）
        CompletableFuture<AgentResponse> f2 = loop.processMessage("M2", sessionKey);

        // [提交→pickup] 窗口内的消息并入该回合的队列并拿到 ack
        assertTrue(loop.processMessage("M-ack", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getContent().startsWith("Message injected"));

        // Stop：取消 pre-pickup 的回合；路由槽立即摘除
        loop.signalCancel(sessionKey);
        assertFalse(loop.hasActiveRun(sessionKey), "取消即摘槽");
        assertThrows(CancellationException.class,
                () -> f2.get(1, TimeUnit.SECONDS), "pre-pickup 回合的 future 应已取消");

        complete(blocker); // blocker 收尾 → 死任务 guard 被取出 → 抽干队列 → 作废

        // 后续新回合 pickup 隐含 guard 已执行（executor 串行）
        CompletableFuture<AgentResponse> f3 = loop.processMessage("M3", sessionKey);
        await(call3.entered, "follow-up turn LLM call started（隐含 guard 已执行）");
        complete(call3);
        assertEquals("R3", f3.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());

        assertTrue(orphans.started.isEmpty(), "被取消排队回合的队列消息必须作废，不得重发布");
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "all turns settle");
    }

    /**
     * pre-pickup 取消后路由不得残留僵尸：后续消息必须正常起新回合并跑完，
     * 回合结束后 hasActiveRun 收敛为 false。
     */
    @Test
    void prePickupCancel_noZombieRouting_followUpStartsNewTurn() throws Exception {
        ScriptedCall blocker = aiService.scriptGated(LLMResponse.text("BLOCKER-DONE"));
        ScriptedCall call3 = aiService.scriptGated(LLMResponse.text("R3"));

        loop.processMessage("block", "other-session");
        await(blocker.entered, "blocker turn LLM call started");

        CompletableFuture<AgentResponse> f2 = loop.processMessage("M2", sessionKey);
        loop.signalCancel(sessionKey);
        assertFalse(loop.hasActiveRun(sessionKey), "取消即摘槽，无僵尸路由");

        complete(blocker);

        // M3 起新回合：排在死任务 guard 之后（executor 串行），guard 空抽干即过
        CompletableFuture<AgentResponse> f3 = loop.processMessage("M3", sessionKey);
        await(call3.entered, "follow-up turn LLM call started（隐含 guard 已执行）");
        assertTrue(loop.hasActiveRun(sessionKey), "新回合运行中应报 active");

        complete(call3);
        assertEquals("R3", f3.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "turn settles, no zombie slot");
        assertTrue(orphans.started.isEmpty(), "无残留消息不应产生孤儿");
    }

    /**
     * subagent 公告与用户消息同队列但来源可区分：自然完成的残留里公告不得被
     * re-publish 成伪造用户回合（结果可经 subagent_status 查询），用户消息正常恢复。
     * 契约修订（2026-08-23）后 Stop 不再触发重发布，改用周期超限构造。
     */
    @Test
    void announcementLeftover_dropped_notRepublishedAsUserTurn() throws Exception {
        expectOrphans(1);
        OverflowTurn t = scriptOverflowingTurn();
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-FINAL"));

        driveOverflowingTurn(t, () -> {
            var token = loop.currentTurnToken(sessionKey);
            assertNotNull(token, "运行中的回合应有 turn token");
            assertTrue(loop.offerInjection(sessionKey, token.identity(), "[subagent test] ANNOUNCE"),
                    "活回合的公告应正常入队");
        }, "M-user"); // cleanup：M-user re-publish，ANNOUNCE 丢弃

        assertTrue(orphans.latch.get().await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "用户消息的孤儿回合应发 REPUBLISH 源 STARTED 事件");
        assertEquals(1, orphans.started.size(),
                "subagent 公告不得被 re-publish 成第二个孤儿回合");
        awaitUntil(() -> !orphans.completedResponses().isEmpty(), "orphan terminal");
        assertEquals("ORPHAN-FINAL", orphans.completedResponses().get(0).getContent());
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "turn settles");
    }

    /**
     * REE 防护：executor 已退役（模型切换换血 AgentLoopFactory.reset）时，回合收尾
     * finally 里 re-publish 的提交会抛 RejectedExecutionException——不得让异常从
     * finally 冒出顶掉本回合的正常返回值（GUI 会显示错误且回复丢失）。
     *
     * <p>用注入周期超限（5/5 封顶）制造必然走 re-publish 的残留：第 6 条注入在
     * 回合检查点不再被消化，只能由 finally 重新发布。
     */
    @Test
    void reeGuard_leftoverRepublishOnRetiredExecutor_preservesTurnResult() throws Exception {
        expectOrphans(1);
        List<ScriptedCall> calls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(aiService.scriptGated(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "step " + i)));
        }
        ScriptedCall finalCall = aiService.scriptGated(LLMResponse.text("R7-FINAL"));

        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);
        for (int i = 0; i < 6; i++) {
            await(calls.get(i).entered, "LLM call " + (i + 1));
            assertTrue(loop.processMessage("FOLLOWUP-" + (i + 1), sessionKey)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
            complete(calls.get(i));
        }
        await(finalCall.entered, "final LLM call started");

        // 模拟模型切换：工厂换血会 shutdown 旧 loop 的 executor；回合任务已被取出、
        // 本体继续执行，但其 finally 的 re-publish 提交将 REE
        Field field = AgentLoop.class.getDeclaredField("executorService");
        field.setAccessible(true);
        ((ExecutorService) field.get(loop)).shutdown();

        // 周期已 5/5 封顶：FOLLOWUP-6 不会被检查点消化，只能走 finally re-publish → REE
        complete(finalCall);

        assertEquals("R7-FINAL", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "REE 不得顶掉回合自身的正常返回值");
        assertTrue(assistantContents().contains("R7-FINAL"), "回合回复应正常落盘");
        // REE 路径不静默：startTurn 发「消息未处理」错误终态事件（订阅者可渲染提示
        // 而非无声丢失，用户可重发）
        awaitUntil(() -> !orphans.completedResponses().isEmpty(),
                "REE 路径应发射孤儿错误终态事件而非静默丢弃");
        assertFalse(orphans.completedResponses().get(0).isSuccess(),
                "REE 路径的孤儿终态应为错误回执");
    }

    // ------------------------------------------------------------------
    // 重置栅栏（resetFenceLock）：「取消+代数翻转」与「代数检查+重发布」互斥
    // ------------------------------------------------------------------

    /**
     * 栅栏互斥的确定性钉法：孤儿回合的 TURN_STARTED 派发发生在 republishLeftovers
     * 持有 resetFenceLock 期间（startTurn 在锁内被调）——用在该事件上 park 的订阅者
     * 把 loop 线程钉死在栅栏临界区内，此时另一线程的 resetConversation 必须阻塞在
     * 锁外，不得穿插进「检查代数 → 重发布」之间（TOCTOU 缝隙会让旧代数孤儿漏网）。
     * 放行后 reset 才入栅栏执行，代数翻转必须真实发生（非 loop 线程路径）。
     */
    @Test
    void resetFence_mutuallyExcludesRepublish_andFlipsEpochOffLoopThread() throws Exception {
        CountDownLatch republishStarted = new CountDownLatch(1);
        CountDownLatch releaseRepublish = new CountDownLatch(1);
        loop.addTurnSubscriber(new TurnSubscriber() {
            @Override public void onTurnEvent(TurnEvent event) {
                if (event.turn() != null && event.turn().origin() == TurnOrigin.REPUBLISH
                        && event.kind() == TurnEvent.Kind.TURN_STARTED) {
                    republishStarted.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = releaseRepublish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            // 取消中断不得提前解锁 park：栅栏持有方必须确定性收尾
                        }
                    }
                    // 清掉取消路径可能落下的中断位，不带回 loop 线程后续流程
                    Thread.interrupted();
                }
            }
        });

        // 构造必然走 re-publish 的自然完成残留（周期 5/5 封顶后的 LEFT）
        OverflowTurn t = scriptOverflowingTurn();
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-FINAL"));

        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);
        for (int i = 0; i < 5; i++) {
            await(t.calls().get(i).entered, "LLM call " + (i + 1));
            assertTrue(loop.processMessage("FOLLOWUP-" + (i + 1), sessionKey)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
            complete(t.calls().get(i));
        }
        await(t.calls().get(5).entered, "LLM call 6（周期已封顶）");
        assertTrue(loop.processMessage("LEFT", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getContent().startsWith("Message injected"));
        complete(t.calls().get(5));
        await(t.finalCall().entered, "final LLM call");
        complete(t.finalCall());

        // loop 线程进入内层 finally → 栅栏 → re-publish → 孤儿 STARTED 派发 → park：
        // 此刻 loop 线程被钉在 resetFenceLock 临界区内
        assertTrue(republishStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "loop thread should reach the orphan STARTED dispatch inside the fence");
        assertFalse(f1.isDone(), "park 期间原回合 future 不得落定（complete 在内层 finally 之后）");

        // 非_loop 线程的重置必须阻塞在栅栏外
        AtomicBoolean resetDone = new AtomicBoolean(false);
        AtomicReference<Throwable> resetFailure = new AtomicReference<>();
        Thread resetter = new Thread(() -> {
            try {
                loop.resetConversation(sessionKey);
                resetDone.set(true);
            } catch (Throwable e) {
                resetFailure.set(e);
            }
        }, "fence-resetter");
        resetter.start();
        Thread.sleep(400);
        assertFalse(resetDone.get(), "resetConversation 必须阻塞在 resetFenceLock 外，不得穿插进重发布临界区");

        // 放行：重发布收尾，reset 才入栅栏执行「取消 + 代数翻转」
        releaseRepublish.countDown();
        resetter.join(5000);
        assertNull(resetFailure.get(), "resetter 不应抛异常");
        assertTrue(resetDone.get(), "放行后 reset 应完成");

        // 原回合 future：正常完成；或被放行后抢得栅栏的 reset 以 RESET 取消（终态事件
        // 与 re-publish 均已完成，两种交错都是合法产物，此处不约束）
        try {
            assertEquals("T1-FINAL", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());
        } catch (CancellationException postFenceRace) {
            // reset 恰在 future.complete 前入栅栏：合法竞态，终态事件此前已发
        }

        // 代数翻转真实发生（非 loop 线程路径；若翻转被 self 守卫吞掉此处回到 1）
        assertEquals(2L, loop.markConversationReset(sessionKey),
                "resetter 的 resetConversation 必须把代数从 0 翻到 1（本调用翻到 2）");
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "all turns settle");
    }

    // ------------------------------------------------------------------
    // 测试基础设施
    // ------------------------------------------------------------------

    /** 孤儿回合（REPUBLISH 源）事件记录器：STARTED 计数喂 latch、终态载荷按事件序分桶。 */
    private static final class OrphanRecorder implements TurnSubscriber {
        final List<TurnEvent> started = new CopyOnWriteArrayList<>();
        final List<TurnEvent> terminals = new CopyOnWriteArrayList<>();
        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));

        @Override public void onTurnEvent(TurnEvent event) {
            if (event.turn() == null || event.turn().origin() != TurnOrigin.REPUBLISH) {
                return;
            }
            switch (event.kind()) {
                case TURN_STARTED -> {
                    started.add(event);
                    latch.get().countDown();
                }
                case TURN_COMPLETED, TURN_CANCELLED -> terminals.add(event);
                default -> { }
            }
        }

        /** REPUBLISH 源回合的自然完成终态载荷（按事件序）。 */
        List<AgentResponse> completedResponses() {
            List<AgentResponse> responses = new ArrayList<>();
            for (TurnEvent e : terminals) {
                if (e.kind() == TurnEvent.Kind.TURN_COMPLETED) {
                    responses.add(e.response());
                }
            }
            return responses;
        }

        int cancelledCount() {
            int count = 0;
            for (TurnEvent e : terminals) {
                if (e.kind() == TurnEvent.Kind.TURN_CANCELLED) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 「自然完成 + 注入周期超限」脚本的句柄：calls 为 6 次工具调用（各触发一个注入
     * 检查点），finalCall 为回合最终回复。
     */
    private record OverflowTurn(List<ScriptedCall> calls, ScriptedCall finalCall) {}

    /**
     * 预置「自然完成 + 注入周期超限」脚本。契约修订（2026-08-23）后 Stop 不再触发
     * 重发布，这是唯一确定性的重发布残留构造：前 5 条注入被周期消化（5/5 封顶），
     * 封顶后入队的消息不再被消化、回合自然结束时留在队列，由 finally 走 re-publish。
     * 调用方应在 {@link #driveOverflowingTurn} 之前按消费顺序追加孤儿回合脚本。
     */
    private OverflowTurn scriptOverflowingTurn() {
        List<ScriptedCall> calls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(aiService.scriptGated(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "step " + i)));
        }
        ScriptedCall finalCall = aiService.scriptGated(LLMResponse.text("T1-FINAL"));
        return new OverflowTurn(calls, finalCall);
    }

    private void driveOverflowingTurn(OverflowTurn t, String... leftovers) throws Exception {
        driveOverflowingTurn(t, () -> {}, leftovers);
    }

    /**
     * 驱动 {@link #scriptOverflowingTurn} 预置的回合：前 5 次调用各消化 1 条注入，
     * 第 6 次调用窗口（周期已封顶）内注入 leftovers 并执行 duringCappedWindow，
     * 最终以 T1-FINAL 自然结束——leftovers 成为重发布残留。
     */
    private void driveOverflowingTurn(OverflowTurn t, Runnable duringCappedWindow, String... leftovers)
            throws Exception {
        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);
        for (int i = 0; i < 5; i++) {
            await(t.calls().get(i).entered, "LLM call " + (i + 1));
            assertTrue(loop.processMessage("FOLLOWUP-" + (i + 1), sessionKey)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
            complete(t.calls().get(i));
        }
        await(t.calls().get(5).entered, "LLM call 6"); // 周期已 5/5 封顶：此后入队的消息不再被消化
        for (String left : leftovers) {
            assertTrue(loop.processMessage(left, sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getContent().startsWith("Message injected"));
        }
        duringCappedWindow.run();
        complete(t.calls().get(5));
        await(t.finalCall().entered, "final LLM call");
        complete(t.finalCall());
        assertEquals("T1-FINAL", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "原回合必须自然完成（残留由此走 re-publish 出口）");
    }

    private void await(CountDownLatch latch, String what) throws InterruptedException {
        assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for: " + what);
    }

    private void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
            Thread.sleep(20);
        }
    }

    private void complete(ScriptedCall call) {
        call.release.complete(null);
    }

    private List<String> assistantContents() {
        Session session = sessionManager.get(sessionKey);
        if (session == null) {
            return List.of();
        }
        List<String> contents = new ArrayList<>();
        for (Message msg : session.getMessages()) {
            if (msg.getRole() == Message.Role.ASSISTANT && msg.getContent() != null) {
                contents.add(msg.getContent());
            }
        }
        return contents;
    }

    /** 一次脚本化 LLM 调用：entered 信号 + release 门 + 应答。 */
    private static final class ScriptedCall {
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final LLMResponse response;

        ScriptedCall(LLMResponse response) {
            this.response = response;
        }
    }

    /**
     * 按脚本逐调用应答的 fake AiService。调用先在 entered 上打点，再等待 release
     * （中断容忍：Stop 的 interrupt 打在 commonPool 载体上，等待必须吞掉中断继续等
     * 测试放行——与生产 HTTP 调用不被 interrupt 即时打断的行为一致），最后返回脚本应答。
     * 脚本耗尽后返回固定最终文本，避免非重点调用使测试意外失败。
     */
    private static final class ScriptedAiService implements AiService {
        final ConcurrentLinkedQueue<ScriptedCall> script = new ConcurrentLinkedQueue<>();

        ScriptedCall scriptGated(LLMResponse response) {
            ScriptedCall call = new ScriptedCall(response);
            script.add(call);
            return call;
        }

        void scriptImmediate(LLMResponse response) {
            ScriptedCall call = new ScriptedCall(response);
            call.release.complete(null);
            script.add(call);
        }

        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            ScriptedCall call = script.poll();
            if (call == null) {
                return LLMResponse.text("DEFAULT-FINAL");
            }
            call.entered.countDown();
            while (!call.release.isDone()) {
                try {
                    call.release.get();
                } catch (InterruptedException e) {
                    // 吞中断继续等待测试放行（见类注释）
                } catch (ExecutionException | CancellationException | CompletionException e) {
                    throw new IllegalStateException("release future completed unexpectedly", e);
                }
            }
            return call.response;
        }

        @Override
        public String getName() {
            return "scripted-fake";
        }

        @Override
        public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }

        @Override
        public void setGenerationSettings(GenerationSettings settings) {
            // no-op
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }

    /** 立即成功的空操作工具，用于驱动带工具调用的迭代与注入检查点。 */
    private static final class NoopTool implements Tool {
        @Override public String getName() {
            return "noop_tool";
        }

        @Override public String getDescription() {
            return "test noop tool";
        }

        @Override public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{}}";
        }

        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }
}
