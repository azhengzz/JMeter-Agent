package org.gitee.jmeter.ai.gui;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
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
import org.gitee.jmeter.ai.agent.model.ToolResult;import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「+ 新会话按钮」与输入框 {@code /new} 的取消语义一致性回归测试。
 *
 * <p>缺陷（2026-08-22 生产日志定位，jmeter.log 23:02:37→23:03:59）：标题栏 "+"
 * 按钮直连 {@code AiChatPanel.startNewConversation()}，只做归档/清空/欢迎语，
 * 漏了 {@code signalCancel}——在跑回合（典型：Stop 后 re-publish 的孤儿回合）
 * 继续跑完，最终回复经 republishListener 渲染进刚清空的新会话聊天区，且回合
 * 持续向已清空 session 回写。输入框 /new 走 cmdNew（先 signalCancel 再清空）
 * 没有此问题；本类同时钉死该参照路径。
 *
 * <p>线程模型与 AgentLoopRepublishTest 一致：AgentLoop 单线程 executor 跑回合，
 * AgentRunner 在 commonPool 载体上等脚本化 LLM 调用；interrupt 被脚本吞掉，
 * 时序由 entered/release 门控确定。
 */
class AiChatPanelNewConversationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

    AgentLoop loop;
    ScriptedAiService aiService;
    MemoryConsolidator consolidator;
    String sessionKey;
    /** 懒构造：仅 GUI 用例需要面板（构造重，且会触碰 AgentLoopFactory 静态态）。 */
    AiChatPanel panel;

    String previousJMeterHome;

    @BeforeEach
    void setUp() {
        sessionKey = InstanceContext.currentSessionKey();
        aiService = new ScriptedAiService();

        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        consolidator = Mockito.mock(MemoryConsolidator.class);

        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());

        ContextBuilder contextBuilder = new ContextBuilder(memoryStore, tempDir);
        SessionManager sessionManager = new SessionManager(tempDir, sessionKey);

        loop = new AgentLoop(registry, memoryStore, consolidator, contextBuilder,
                sessionManager, aiService);

        // 面板构造会经 AgentLoopFactory 创建真实 loop（SessionManager 落在
        // JMeter home 下）——钉到 tempDir 防止单测污染仓库目录
        previousJMeterHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (panel != null) { // 面板构造挂了工厂级订阅表 + 注入 loop 的订阅，一并退役
            loop.removeTurnSubscriber(panel);
            AgentLoopFactory.removeTurnSubscriber(panel);
        }
        // 让仍卡在 LLM 门上的（被取消）回合尽快收尾，减少 tempDir 清理竞态
        aiService.releaseAllPending();
        // 后台回合的会话 jsonl 迟写与 @TempDir 清理竞态：Windows 下文件锁顶住
        // sessions 目录删除 → DirectoryNotEmptyException。句柄在 future 完成后
        //（含 re-publish 后继回合注册）才摘——排空即写盘必已结束，有界等待后再 shutdown
        awaitUntil(() -> loop.activeTurn(sessionKey).isEmpty(),
                "active turn drained before teardown");
        loop.shutdown();
        AgentLoopFactory.reset(); // 面板构造可能经工厂缓存了真实 loop，退役之
        if (previousJMeterHome != null) {
            JMeterUtils.setJMeterHome(previousJMeterHome);
        }
    }

    // ------------------------------------------------------------------
    // 主缺陷：+ 按钮在回合运行中点击，必须与 /new 等价地先取消在跑回合
    // ------------------------------------------------------------------

    @Test
    void plusButton_duringActiveTurn_cancelsRun_clearsChat_andResetsUi() throws Exception {
        AiChatPanel p = panel();
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));

        CompletableFuture<AgentResponse> turn = loop.processMessage("分析Code线程组", sessionKey);
        await(call1.entered, "turn parked in LLM call");

        // 复位前先布置在跑回合留下的 UI 状态（Stop 模式）与旧聊天内容
        JTextPane chatArea = field(p, "chatArea");
        JButton stopButton = field(p, "stopButton");
        JButton sendButton = field(p, "sendButton");
        SwingUtilities.invokeAndWait(() -> {
            invoke(p, "setButtonToStopMode");
            chatArea.setText("OLD-CONTENT");
        });
        assertTrue(stopButton.isVisible(), "前置：Stop 模式已就位");

        JButton plus = findNewChatButton(p);
        assertNotNull(plus, "未找到标题栏 \"+\" 新会话按钮");
        SwingUtilities.invokeAndWait(plus::doClick);

        // 核心回归点 1：必须摘路由槽中止在跑回合（对齐 cmdNew）
        assertFalse(loop.hasActiveRun(sessionKey),
                "点击 + 必须中止在跑回合；缺陷：回合继续跑完，其结论会渲染进刚清空的新会话");
        assertTrue(turn.isCancelled(), "在跑回合的 future 必须被取消");

        // 核心回归点 2：新会话主功能不受影响——旧内容清空 + 欢迎语显示
        assertTrue(chatArea.getText().contains("Welcome to Gitee Ai"),
                "新会话欢迎语必须显示");
        assertFalse(chatArea.getText().contains("OLD-CONTENT"),
                "旧会话聊天内容必须被清空");

        // 核心回归点 3：UI 复位——被取消回合不再有回调兜底（SwingWorker 静默
        // 结束、republishListener 跳过 CancellationException），按钮模式必须
        // 在此显式复位，否则 Stop 按钮常驻、Send 停留在注入模式
        assertFalse(stopButton.isVisible(), "Stop 按钮必须隐藏复位");
        assertNull(sendButton.getToolTipText(), "Send 按钮必须退出注入模式复位");

        // 收尾：放行垂死回合的 LLM 门并等其任务收尾（不落盘、不渲染）
        complete(call1);
        awaitTurnSettled();
    }

    // ------------------------------------------------------------------
    // 守护：空闲时点击 + 不受修复影响（signalCancel 对无回合会话必须无害）
    // ------------------------------------------------------------------

    @Test
    void plusButton_whenIdle_startsFreshConversationSafely() throws Exception {
        AiChatPanel p = panel();

        JTextPane chatArea = field(p, "chatArea");
        SwingUtilities.invokeAndWait(() -> chatArea.setText("OLD-CONTENT"));

        JButton plus = findNewChatButton(p);
        assertNotNull(plus, "未找到标题栏 \"+\" 新会话按钮");
        SwingUtilities.invokeAndWait(plus::doClick);

        assertTrue(chatArea.getText().contains("Welcome to Gitee Ai"),
                "空闲点击 + 应正常开启新会话");
        assertFalse(chatArea.getText().contains("OLD-CONTENT"), "旧内容必须被清空");
        assertFalse(loop.hasActiveRun(sessionKey), "空闲时点击 + 不应产生在跑回合");
    }

    // ------------------------------------------------------------------
    // 参照路径：输入框 /new（cmdNew）在回合运行中必须取消——钉死对齐目标
    // ------------------------------------------------------------------

    @Test
    void typedSlashNew_duringActiveTurn_cancelsRun() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));

        CompletableFuture<AgentResponse> turn = loop.processMessage("M1", sessionKey);
        await(call1.entered, "turn parked in LLM call");

        // 输入框 /new：Phase 2 内联派发 cmdNew（BuiltinCommands.signalCancel）
        AgentResponse resp = loop.processMessage("/new", sessionKey)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("New session started.", resp.getContent());
        assertTrue(turn.isCancelled(), "cmdNew 必须 signalCancel 在跑回合");
        assertFalse(loop.hasActiveRun(sessionKey), "cmdNew 后路由槽必须已摘除");

        complete(call1);
        awaitTurnSettled();
    }

    // ------------------------------------------------------------------
    // 会话重置代数：已 ack 未消费的注入残留不得 re-publish 进新会话
    // （signalCancel 触发的 re-publish 会把旧会话残留变成
    //  新会话首个回合——写入新 session 文件并渲染进刚清空的聊天区）
    // ------------------------------------------------------------------

    @Test
    void plusButton_withInjectedLeftover_discardsIt_insteadOfRepublishingIntoNewSession() throws Exception {
        AiChatPanel p = panel();
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        ScriptedCall call2 = aiService.scriptImmediate(LLMResponse.text("ORPHAN-R"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "turn parked in LLM call");

        // 中途注入（ack 入队，尚未被消费），随后直接点 "+" 重置会话（无 Stop）
        AgentResponse ack = loop.processMessage("M2", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ack.getContent().startsWith("Message injected"),
                "前置：M2 已作为注入 ack 入队");

        JButton plus = findNewChatButton(p);
        SwingUtilities.invokeAndWait(plus::doClick);
        assertFalse(loop.hasActiveRun(sessionKey), "重置必须取消在跑回合");

        complete(call1); // 垂死回合 abort → finally 抽干队列残留

        // 残留 M2 属于被放弃的旧会话：不得 re-publish 成新会话的首个回合
        assertFalse(call2.entered.await(2, TimeUnit.SECONDS),
                "会话重置后注入残留必须被丢弃，不得作为新回合跑进新会话");
        awaitTurnSettled();

        // 数据层：新会话不得包含被丢弃的旧会话注入消息
        org.gitee.jmeter.ai.agent.session.Session session = loop.getSessionManager().get(sessionKey);
        if (session != null) {
            for (Message msg : session.getMessages()) {
                assertFalse(String.valueOf(msg.getContent()).contains("M2"),
                        "新会话不得包含旧会话被丢弃的注入消息");
            }
        }
    }

    @Test
    void typedSlashNew_withInjectedLeftover_discardsIt() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        ScriptedCall call2 = aiService.scriptImmediate(LLMResponse.text("ORPHAN-R"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "turn parked in LLM call");

        AgentResponse ack = loop.processMessage("M2", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ack.getContent().startsWith("Message injected"),
                "前置：M2 已作为注入 ack 入队");

        AgentResponse resp = loop.processMessage("/new", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("New session started.", resp.getContent());

        complete(call1);
        assertFalse(call2.entered.await(2, TimeUnit.SECONDS),
                "cmdNew 重置会话后注入残留必须被丢弃（与 \"+\" 按钮同语义）");
        awaitTurnSettled();
    }

    // ------------------------------------------------------------------
    // 渲染代数守卫：重置后迟到的旧会话渲染不得污染新聊天区
    // ------------------------------------------------------------------

    /**
     * 「点击影子」窗口：回合恰在点击前后完成（signalCancel 对已完成 future
     * no-op），其投递任务排在重置之后——必须按代数丢弃，不得渲染进新会话。
     * 确定性构造：冻结 EDT → 入队 [blocker][click] → 放行回合 LLM → 回合完成后的
     * render 任务必然排在 click 之后（入队序）。走真实发送路径（sendMessage →
     * submitToLoop → 回合事件流），订阅时的渲染代数即生产消费的那份。
     * （2026-08-23 契约修订前用 Stop→重发布孤儿作载体；Stop 已不产生孤儿，改用
     * 完成回合本身——要验证的属性不变。）
     */
    @Test
    void completedTurnDeliveryAfterReset_doesNotRenderIntoFreshChat() throws Exception {
        AiChatPanel p = panel();
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));

        JTextArea messageField = field(p, "messageField");
        SwingUtilities.invokeAndWait(() -> {
            messageField.setText("M1");
            invoke(p, "sendMessage");
        });
        await(call1.entered, "worker-driven turn parked in LLM call");

        // 冻结 EDT 并预排队 click；回合完成后的 render 任务将排在 click 之后
        CountDownLatch edtRelease = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                edtRelease.await();
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        });
        JButton plus = findNewChatButton(p);
        SwingUtilities.invokeLater(plus::doClick);

        complete(call1); // 回合正常完成（click 尚未执行，signalCancel 将 no-op）
        awaitTurnSettled();

        edtRelease.countDown(); // EDT 依次执行：blocker → click(重置+代数+1) → render(过期→丢弃)
        awaitEdtDrained();

        JTextPane chatArea = field(p, "chatArea");
        assertTrue(chatArea.getText().contains("Welcome to Gitee Ai"), "重置本身不受影响");
        assertFalse(chatArea.getText().contains("R1"),
                "重置后才投递的旧回合结论必须按代数丢弃，不得渲染进新会话");
    }

    /**
     * 工具批进度污染：重置时工具批在跑（join() 不响应 interrupt），工具执行完
     * 毕发布的 TOOL_CALL 进度在重置之后投递——必须按代数丢弃。
     * 走真实发送路径（sendMessage → submitToLoop → 回合事件流）。
     */
    @Test
    void inFlightToolEventAfterReset_doesNotRenderIntoFreshChat() throws Exception {
        AiChatPanel p = panel();
        // 第一轮 LLM 返回工具调用 → NoopTool 内联执行 → afterExecuteTools 发布 TOOL_CALL
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.withToolCalls(
                List.of(new ToolCall("t1", "noop_tool", Map.of())), null));
        ScriptedCall call2 = aiService.scriptGated(LLMResponse.text("R-FINAL"));

        JTextArea messageField = field(p, "messageField");
        SwingUtilities.invokeAndWait(() -> {
            messageField.setText("M1");
            invoke(p, "sendMessage");
        });
        await(call1.entered, "worker-driven turn parked in LLM call");

        // 冻结 EDT 并预排队 click（重置在其后执行，signalCancel 取消 call2 中的回合）
        CountDownLatch edtRelease = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                edtRelease.await();
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        });
        JButton plus = findNewChatButton(p);
        SwingUtilities.invokeLater(plus::doClick);

        complete(call1); // 工具执行 → TOOL_CALL 进度入队（排 click 后）→ 迭代2 进 call2
        await(call2.entered, "tool executed, second LLM call parked");

        edtRelease.countDown(); // EDT：blocker → click(重置+代数+1) → TOOL_CALL render(丢弃)
        // 宽限等待：SwingWorker 进度经 ~33ms Timer 派发，留足时间让迟到渲染要么发生
        // （守卫丢弃后无痕）要么确定缺席——恒假条件即纯定时等待
        softWait(() -> false, 500);

        JTextPane chatArea = field(p, "chatArea");
        assertTrue(chatArea.getText().contains("Welcome to Gitee Ai"), "重置本身不受影响");
        assertFalse(chatArea.getText().contains("noop_tool"),
                "重置后迟到的工具事件必须按代数丢弃，不得渲染进新会话");

        complete(call2);
        awaitTurnSettled();
    }

    // ------------------------------------------------------------------
    // 第 3 轮：/new 作为 Phase 3 回合排队时的两个洞
    // ------------------------------------------------------------------

    /**
     * /new 回合自身排队期间（executor 被占用），用户输入的消息经 Phase 2
     * ack 进它自己的队列——该消息在 /new 之后输入，属于新会话，/new 执行后必须
     * re-publish 成新会话回合被回答。缺陷：回合代数在提交时捕获、早于自身重置的
     * 代数翻转，finally 的代数检查把 ack 过的消息当旧会话残留静默丢弃。
     */
    @Test
    void messageInjectedIntoQueuedNewTurn_isRepublishedIntoNewConversation() throws Exception {
        // 占住共享单线程 executor：别会话的门控回合，把 /new 回合的执行无限期推迟
        ScriptedCall blocker = aiService.scriptGated(LLMResponse.text("BLOCKER-R"));
        ScriptedCall taskCall = aiService.scriptGated(LLMResponse.text("TASK-R"));
        loop.processMessage("blocker-msg", "other-session");
        await(blocker.entered, "blocker turn occupies the executor");

        // /new 作为 Phase 3 回合排队（路由槽在提交时同步注册——注入窗口确定开放）
        CompletableFuture<AgentResponse> newAck = loop.processMessage("/new", sessionKey);
        assertTrue(loop.hasActiveRun(sessionKey), "前置：/new 回合的路由槽已注册");

        // /new 排队期间输入任务消息：Phase 2 → ack 进 /new 回合自身队列
        AgentResponse ack = loop.processMessage("TASK", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ack.getContent().startsWith("Message injected"),
                "前置：TASK 已 ack 进 /new 回合队列");

        complete(blocker); // /new 回合执行（重置）→ 其 finally 须 re-publish TASK

        AgentResponse newResp = newAck.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("New session started.", newResp.getContent(), "/new 确认不受影响");

        await(taskCall.entered, "TASK 必须 re-publish 成新会话回合并执行，不得静默丢失");
        complete(taskCall);
        awaitTurnSettled();

        // 数据层：TASK 的问答落进新会话
        org.gitee.jmeter.ai.agent.session.Session session =
                loop.getSessionManager().getOrCreate(sessionKey);
        boolean persisted = session.getMessages().stream()
                .anyMatch(m -> "TASK".equals(m.getContent()) || "TASK-R".equals(m.getContent()));
        assertTrue(persisted, "TASK 及其回复必须落盘到新会话");
    }

    /**
     * Stop→/new 序列（契约修订 2026-08-23 后语义）——Stop 时未消费的注入 M2
     * 由垂死回合 finally 作废（不再重发布成孤儿）；/new 重置后新会话保持干净。
     * 原缺陷（/new 自身豁免早退放走旧会话孤儿）随 Stop 不再产生孤儿而消失，
     * 本用例钉死新契约：作废的残留不得被重发布跑进任何回合。
     */
    @Test
    void stopThenSlashNew_leftoverDiscarded_newSessionClean() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        ScriptedCall orphanCall = aiService.scriptImmediate(LLMResponse.text("ORPHAN-R"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "turn parked in LLM call");
        loop.processMessage("M2", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // 旧会话残留（ack）

        loop.signalCancel(sessionKey); // Stop：T1 垂死（仍占 executor，槽已摘）
        CompletableFuture<AgentResponse> newAck = loop.processMessage("/new", sessionKey); // T2 排队
        complete(call1); // T1 abort → finally 抽干 M2 → 作废（契约修订：不重发布孤儿）

        AgentResponse resp = newAck.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("New session started.", resp.getContent(), "/new 确认不受影响");
        awaitTurnSettled();

        assertFalse(orphanCall.entered.await(2, TimeUnit.SECONDS),
                "Stop 作废的残留不得被重发布跑进任何回合");
        assertNull(loop.getSessionManager().get(sessionKey),
                "新会话不得包含旧会话残留的问答（重置后缓存应保持失效）");
    }

    /**
     * 契约面：{@code resetConversation(key, archive=false)}（关闭期深度提炼成功后
     * 的清空路径）必须保留完整重置栅栏——取消在跑回合 + 丢弃注入残留——但不再
     * 二次归档（消息刚被提炼进 MEMORY.md）。
     */
    @Test
    void resetConversation_noArchiveVariant_fencesButSkipsArchiving() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));

        CompletableFuture<AgentResponse> turn = loop.processMessage("M1", sessionKey);
        await(call1.entered, "turn parked in LLM call");
        loop.processMessage("M2", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // 注入残留

        List<Message> snapshot = loop.resetConversation(sessionKey, false);

        assertTrue(turn.isCancelled(), "no-archive 变体同样必须取消在跑回合");
        assertFalse(loop.hasActiveRun(sessionKey), "路由槽必须摘除");
        complete(call1);
        awaitTurnSettled();
        // 栅栏完整：M2 残留被丢弃（不 re-publish——可通过脚本未消费验证）
        assertTrue(aiService.script.isEmpty(), "残留不得被 re-publish 成回合");
        // 不二次归档：consolidator 不被调用（快照本身为空——回合运行中消息尚未落
        // session，持久化发生在回合结束时，被中止的回合不落盘——abort 不落盘语义）
        Mockito.verify(consolidator, Mockito.never())
                .archiveMessagesAsync(Mockito.anyList());
        assertTrue(snapshot.isEmpty(), "回合运行中重置：消息尚未持久化进 session");
    }

    /**
     * 排队中的回合被重置取消（pre-pickup）时，其队列消息全部 ack 于
     * 重置之前（closeRouting 与 offer 在 CHM bin 锁下互斥，翻转后无新 offer）——
     * 死任务的 guard 善后必须按<b>提交时</b>代数比对丢弃。缺陷：guard 在 pickup 时
     * 读 currentEpoch（已被重置翻转），自己跟自己比恒过，旧会话残留被 re-publish
     * 成新会话回合（渲染+落盘）。
     */
    @Test
    void resetCancelledQueuedTurn_preResetLeftovers_discardedNotRepublished() throws Exception {
        // 占住共享单线程 executor：/new 回合排队且未 pickup
        ScriptedCall blocker = aiService.scriptGated(LLMResponse.text("BLOCKER-R"));
        ScriptedCall ghost = aiService.scriptGated(LLMResponse.text("OLDMSG-R")); // 若残留被洗白将进入此门
        loop.processMessage("blocker-msg", "other-session");
        await(blocker.entered, "blocker turn occupies the executor");

        loop.processMessage("/new", sessionKey); // 排队（槽已注册，注入窗口开放）
        AgentResponse ack = loop.processMessage("OLDMSG", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ack.getContent().startsWith("Message injected"),
                "前置：OLDMSG 已 ack 进排队回合队列（重置之前）");

        // 重置取消排队的 /new 回合（pre-pickup）并翻转代数
        loop.resetConversation(sessionKey, false);
        complete(blocker); // 死任务的 guard 被取出执行

        // OLDMSG 属于被放弃的旧会话：不得 re-publish 成新会话回合
        assertFalse(ghost.entered.await(2, TimeUnit.SECONDS),
                "重置取消的排队回合：旧会话残留必须按提交时代数丢弃，不得洗白进新会话");
        awaitTurnSettled();

        // 数据层终局：新会话不含被丢弃的消息
        org.gitee.jmeter.ai.agent.session.Session session = loop.getSessionManager().get(sessionKey);
        if (session != null) {
            for (Message msg : session.getMessages()) {
                assertFalse(String.valueOf(msg.getContent()).contains("OLDMSG"),
                        "重置取消的排队回合的旧会话残留必须丢弃，不得进入新会话");
            }
        }
    }

    // ------------------------------------------------------------------
    // 测试基础设施
    // ------------------------------------------------------------------

    /**
     * 懒构造测试面板并注入测试 loop。
     *
     * <p>竞态警示：{@code loadModelsInBackground} 的 {@code done()} 在 EDT 上
     * <b>直接</b>调用 {@code switchAiService()} 覆盖 {@code agentLoop} 字段（不经
     * 下拉监听器，摘监听防不住），与本注入竞态——热 JVM 里构造→注入间隔可小于其
     * ~33ms Timer 延迟，注入会被覆盖，点击落空。构造时下拉 selectedItem 为 null
     * 占位；变非 null 说明 done() 已执行到 setSelectedIndex（同一 EDT 派发内
     * switchAiService 紧随其后），再排空 EDT 即可保证注入为最终写入。
     */
    private AiChatPanel panel() throws Exception {
        if (panel != null) {
            return panel;
        }
        AiChatPanel p = new AiChatPanel();
        JComboBox<?> selector = field(p, "modelSelector");
        awaitUntil(() -> selector.getSelectedItem() != null,
                "loadModelsInBackground.done() landed (model item selected)");
        SwingUtilities.invokeAndWait(() -> { }); // 排空 EDT，确保 done() 完整跑完
        setField(p, "agentLoop", loop);
        loop.addTurnSubscriber(p); // 生产对等：回合事件订阅挂到注入的 loop 上
        panel = p;
        return p;
    }

    /** 排空 EDT：invokeAndWait 的任务入队并执行完毕后，之前入队的事件必然已执行。 */
    private static void awaitEdtDrained() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** 软等待：cond 在 ms 内变真返回 true，否则到时返回 false（用于等待"不发生"的反证宽限）。 */
    private static boolean softWait(BooleanSupplier cond, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    /** 在组件树中找标题栏 "+ 新会话" 按钮（tooltip 是稳定标识）。 */
    private static JButton findNewChatButton(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b && "Start a new conversation".equals(b.getToolTipText())) {
                return b;
            }
            if (c instanceof Container cont) {
                JButton found = findNewChatButton(cont);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field f = reachableField(target.getClass(), name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read field " + name, e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = reachableField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set field " + name, e);
        }
    }

    private static Field reachableField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(Object target, String name) {
        try {
            Method m = null;
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod(name);
                    break;
                } catch (NoSuchMethodException ignore) {
                    // walk up
                }
            }
            if (m == null) {
                throw new NoSuchMethodException(name);
            }
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot invoke " + name, e);
        }
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

    /** 等被取消回合的任务真正收尾：turnTeardown 的 finally 会清掉 activeTurnTokens。 */
    @SuppressWarnings("unchecked")
    private void awaitTurnSettled() throws Exception {
        ConcurrentHashMap<String, Object> tokens = field(loop, "activeTurnTokens");
        awaitUntil(tokens::isEmpty, "cancelled turn task settled (activeTurnTokens drained)");
    }

    private void complete(ScriptedCall call) {
        call.release.complete(null);
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
     * 按脚本逐调用应答的 fake AiService（对齐 AgentLoopRepublishTest：调用先在
     * entered 上打点，再吞中断等待 release，最后返回脚本应答；脚本耗尽返回固定文本）。
     */
    private static final class ScriptedAiService implements AiService {
        final ConcurrentLinkedQueue<ScriptedCall> script = new ConcurrentLinkedQueue<>();
        final List<ScriptedCall> allCalls = new CopyOnWriteArrayList<>();

        ScriptedCall scriptGated(LLMResponse response) {
            ScriptedCall call = new ScriptedCall(response);
            script.add(call);
            allCalls.add(call);
            return call;
        }

        ScriptedCall scriptImmediate(LLMResponse response) {
            ScriptedCall call = new ScriptedCall(response);
            call.release.complete(null);
            script.add(call);
            allCalls.add(call);
            return call;
        }

        void releaseAllPending() {
            for (ScriptedCall c : allCalls) {
                if (!c.release.isDone()) {
                    c.release.complete(null);
                }
            }
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
                    // 吞中断继续等待测试放行（Stop 的 interrupt 打在载体线程上）
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
        @Override
        public String getName() {
            return "noop_tool";
        }

        @Override
        public String getDescription() {
            return "test noop tool";
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{}}";
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }
}
