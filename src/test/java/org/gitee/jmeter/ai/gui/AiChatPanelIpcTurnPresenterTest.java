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
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.model.ToolEvent;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnHandle;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.InterruptStrategy;
import org.gitee.jmeter.ai.agent.testsupport.RecordingSubscriber;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.provider.AiServiceFactory;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiChatPanel} 的 {@link org.gitee.jmeter.ai.agent.presenter.TurnSubscriber}
 * 实现单测：IPC 回合（委派/CLI）事件呈现——"You:" 行与按钮状态（TURN_STARTED）、
 * 进度/终结接入既有渲染链、生命周期系统提示行（TURN_CANCELLED/REJECTED_BUSY/
 * INJECTED）、活回合集合过滤（武装前丢弃、/new 后迟到丢弃）。
 *
 * <p>事件由测试线程直发 {@code panel.onTurnEvent}（生产由 AgentLoop 事件派发，
 * 线程不保证 EDT；面板实现只依赖"自投 EDT + 代数快照"这一契约）。句柄为真实
 * {@link TurnHandle}，与生产事件同构。
 */
class AiChatPanelIpcTurnPresenterTest {

    @TempDir
    Path tempDir;

    AgentLoop loop;
    AiChatPanel panel;
    String sessionKey;
    String previousJMeterHome;

    @BeforeEach
    void setUp() throws Exception {
        sessionKey = InstanceContext.currentSessionKey();

        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), new IdleAiService());

        // 面板构造会经 AgentLoopFactory 创建真实 loop（SessionManager 落在
        // JMeter home 下）——钉到 tempDir 防止单测污染仓库目录
        previousJMeterHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tempDir.toString());

        panel = new AiChatPanel();
        JComboBox<?> selector = field(panel, "modelSelector");
        awaitUntil(() -> selector.getSelectedItem() != null,
                "loadModelsInBackground.done() landed (model item selected)");
        SwingUtilities.invokeAndWait(() -> { }); // 排空 EDT，确保 done() 完整跑完
        setField(panel, "agentLoop", loop);
        loop.addTurnSubscriber(panel); // 生产对等：回合事件订阅挂注入的 loop
    }

    @AfterEach
    void tearDown() throws Exception {
        loop.removeTurnSubscriber(panel); // 面板构造还挂了工厂级订阅表，一并退役
        AgentLoopFactory.removeTurnSubscriber(panel);
        // 后台回合（cmdNew/本地回合）的会话 jsonl 迟写与 @TempDir 清理竞态：Windows
        // 下文件锁顶住 sessions 目录删除 → DirectoryNotEmptyException。句柄在
        // future 完成后才摘（写盘必已结束），有界等待排空再 shutdown
        awaitUntil(() -> loop.activeTurn(sessionKey).isEmpty(),
                "active turn drained before teardown");
        loop.shutdown();
        AgentLoopFactory.reset(); // 面板构造可能经工厂缓存了真实 loop，退役之
        if (previousJMeterHome != null) {
            JMeterUtils.setJMeterHome(previousJMeterHome);
        }
    }

    @Test
    void turnStartRendersYouLineAndStopsMode_completionResets() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");
        JButton sendButton = field(panel, "sendButton");
        assertTrue(chatArea.getText().contains("You: [from cli] hello"),
                "来源消息（带前缀）必须以 You: 行渲染");
        assertTrue(stopButton.isVisible(), "回合运行中 Stop 按钮必须可见");
        assertNotNull(sendButton.getToolTipText(), "Send 必须切入注入模式");

        panel.onTurnEvent(TurnEvent.progress(turn, ProgressUpdate.thinking("THINKING-TRACE")));
        panel.onTurnEvent(TurnEvent.completed(turn, AgentResponse.success("FINAL-ANSWER")));
        // PROGRESS 的渲染比终态多一跳 EDT（dispatch 的 EDT 投递 + handleProgress 的
        // 内层跳）：invokeAndWait 排不干净，须轮询内容而非排空事件
        awaitUntil(() -> {
            String t = chatTextOnEdt(chatArea);
            return t.contains("THINKING-TRACE") && t.contains("FINAL-ANSWER");
        }, "progress and completion rendered");
        awaitEdtDrained();

        // 注：chatArea.getText() 是 HTML 源码，非 ASCII 会被转成数字字符实体——断言用 ASCII
        // 失败消息带代数与聊天区全文：诊断全量回归下偶发的渲染丢失（代数被外部翻转 vs 渲染被清除）
        String html = chatTextOnEdt(chatArea);
        String diag = "convGen=" + field(panel, "conversationGeneration")
                + " chat=" + html;
        assertTrue(html.contains("THINKING-TRACE"), "进度事件必须接入既有渲染链 " + diag);
        assertTrue(html.contains("FINAL-ANSWER"), "终结必须走 appendBotResponse 路径 " + diag);
        assertFalse(stopButton.isVisible(), "回合结束（无后续回合）Stop 按钮必须隐藏");
        assertNull(sendButton.getToolTipText(), "Send 必须退出注入模式");
    }

    @Test
    void thinkingProgressSplitsThinkTagFromReplyBodyStyling() throws Exception {
        // THINKING 进度载荷携带 <think>…</think> 包裹的思考 + 标签外正文（结构化
        // reasoning_content 的展示形态，ProgressCallbackHookAdapter:43）：思考段保持
        // 灰斜体并以 <think> 标签字面包裹，标签外的正文按回复正文样式渲染（非斜体、
        // 主题色）。注：getText() 的 HTML 序列化不回写 color/font-style（loading 指示
        // 同款丢样式）且会转义标签文本，样式断言走文档元素属性、标签断言走文档纯文本。
        JTextPane chatArea = field(panel, "chatArea");
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        awaitEdtDrained();

        panel.onTurnEvent(TurnEvent.progress(turn,
                ProgressUpdate.thinking("<think>REASON-TRACE</think>\nBODY-COMMENT")));
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("BODY-COMMENT"),
                "thinking progress rendered");

        String html = chatTextOnEdt(chatArea);
        String diag = "reason=" + attrsToString(styleAttrsAt(chatArea, "REASON-TRACE"))
                + " body=" + attrsToString(styleAttrsAt(chatArea, "BODY-COMMENT"));
        assertTrue(html.contains("REASON-TRACE"), "思考段必须渲染 " + diag);
        assertTrue(html.contains("BODY-COMMENT"), "标签外正文必须渲染 " + diag);
        assertTrue(isItalicAt(chatArea, "REASON-TRACE"),
                "think 内的思考段保持灰斜体样式 " + diag);
        assertTrue("#787878".equalsIgnoreCase(cssColorAt(chatArea, "REASON-TRACE")),
                "think 内的思考段保持思考灰（#787878）" + diag);
        assertFalse(isItalicAt(chatArea, "BODY-COMMENT"),
                "think 外的正文按正文样式渲染（非斜体），与思考可区分 " + diag);
        // 思考段以 <think> 标签字面包裹展示（appendStyled 转义后作为文本渲染）
        String docText = docTextOnEdt(chatArea);
        assertTrue(docText.contains("<think>REASON-TRACE</think>"),
                "思考段必须以 <think> 标签包裹展示 " + diag + " doc=" + docText);
        assertFalse(docText.contains("<think>BODY-COMMENT"),
                "正文段不得被 think 标签包裹 " + diag);
    }

    @Test
    void progressBeforeTurnStartIsDropped() throws Exception {
        // 未武装（回合 id 不在活回合集合）：早于 TURN_STARTED EDT 运行的投递丢弃
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.progress(turn, ProgressUpdate.thinking("EARLY-EVENT")));
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        assertFalse(chatArea.getText().contains("EARLY-EVENT"),
                "武装前到达的进度不得渲染（回合提交与首事件间的毫秒窗口）");
    }

    @Test
    void deliveryAfterSlashNewIsDropped() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        awaitEdtDrained();

        // /new：代数 +1 并清空聊天区 + 活回合集合清空（真实路径：handleNewCommand
        // 同步清空+代数翻转，cmdNew 作为 Phase 3 异步回合执行）
        JTextArea messageField = field(panel, "messageField");
        SwingUtilities.invokeAndWait(() -> {
            messageField.setText("/new");
            invoke(panel, "sendMessage");
        });

        panel.onTurnEvent(TurnEvent.progress(turn, ProgressUpdate.thinking("LATE-PROGRESS")));
        panel.onTurnEvent(TurnEvent.completed(turn, AgentResponse.success("LATE-FINAL")));
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        assertTrue(chatArea.getText().contains("You: /new"), "/new 自身的回显不受影响");
        assertFalse(chatArea.getText().contains("LATE-PROGRESS"),
                "重置后迟到的进度必须被活回合集合丢弃");
        assertFalse(chatArea.getText().contains("LATE-FINAL"),
                "重置后迟到的结论必须被活回合集合丢弃，不得渲染进新会话");
    }

    @Test
    void cancellationRendersReceiptLineAndResets_userStop() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        awaitEdtDrained();

        panel.onTurnEvent(TurnEvent.cancelled(turn, CancelCause.USER_STOP));
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");
        assertTrue(chatArea.getText().contains("Task cancelled"),
                "人工终止必须渲染系统提示行");
        assertTrue(chatArea.getText().contains("Partial results"),
                "人工终止文案必须含『部分结果已回传』回执");
        assertFalse(stopButton.isVisible(), "取消后（无后续回合）Stop 按钮必须隐藏");
    }

    @Test
    void cancellationRendersTimeoutLine() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_DELEGATED,
                "[delegated-from A] task", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        awaitEdtDrained();

        panel.onTurnEvent(TurnEvent.cancelled(turn, CancelCause.TIMEOUT));
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        assertTrue(chatArea.getText().contains("timed out"),
                "超时取消必须渲染超时文案");
    }

    @Test
    void silentAndResetCancelDisplayDomain() throws Exception {
        // SILENT 显示域（design D3 校验修复）：关闭整合静默取消——LOCAL_PANEL 源回合
        // 不渲染取消噪音行；IPC 源回合照旧渲染人工终止回执行（对端在等终止反馈）。
        // RESET 一律不渲染（/new 清屏后回执属旧会话噪音）——IPC 源亦然
        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");

        TurnHandle local = new TurnHandle(sessionKey, TurnOrigin.LOCAL_PANEL, "local q", false);
        panel.onTurnEvent(TurnEvent.started(local));
        awaitEdtDrained();
        panel.onTurnEvent(TurnEvent.cancelled(local, CancelCause.SILENT));
        awaitEdtDrained();
        assertFalse(chatArea.getText().contains("Task cancelled"),
                "SILENT 对 LOCAL 源回合必须静默（关闭整合无取消噪音行）");
        assertFalse(stopButton.isVisible(), "SILENT 取消后（无后续回合）Stop 按钮复位");

        TurnHandle ipc = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] q", false);
        panel.onTurnEvent(TurnEvent.started(ipc));
        awaitEdtDrained();
        panel.onTurnEvent(TurnEvent.cancelled(ipc, CancelCause.SILENT));
        awaitEdtDrained();
        assertTrue(chatArea.getText().contains("Task cancelled"),
                "SILENT 对 IPC 源回合必须保留终止回执行（目标面板反馈行不消失）");
        assertTrue(chatArea.getText().contains("Partial results"),
                "与 USER_STOP 共用人工终止回执文案");

        // SILENT + REPUBLISH 孤儿：孤儿无对端调用方，回执文案无的放矢——同不渲染。
        // 钉住谓词收敛：SILENT 不放宽 IPC 判据（旧式 origin != LOCAL_PANEL 写法会误渲染）
        TurnHandle orphan = new TurnHandle(sessionKey, TurnOrigin.REPUBLISH, null, false);
        panel.onTurnEvent(TurnEvent.started(orphan));
        awaitEdtDrained();
        panel.onTurnEvent(TurnEvent.cancelled(orphan, CancelCause.SILENT));
        awaitEdtDrained();
        assertEquals(1, chatTextOnEdt(chatArea).split("Task cancelled", -1).length - 1,
                "REPUBLISH 孤儿的 SILENT 取消不渲染回执（仍仅上文 IPC+SILENT 那一条）");

        TurnHandle reset = new TurnHandle(sessionKey, TurnOrigin.IPC_DELEGATED,
                "[delegated-from A] t", false);
        panel.onTurnEvent(TurnEvent.started(reset));
        awaitEdtDrained();
        panel.onTurnEvent(TurnEvent.cancelled(reset, CancelCause.RESET));
        awaitEdtDrained();
        assertEquals(1, chatTextOnEdt(chatArea).split("Task cancelled", -1).length - 1,
                "RESET 不新增取消行（即便 IPC 源）——仅上文 IPC+SILENT 那一条");
    }

    @Test
    void commandResultDisplayDomain_localRenders_peerLeftToEnvelope() throws Exception {
        // COMMAND_RESULT 显示域（值基规则）：本地面板命令补画 You 行并渲染结果（UX
        // 差异②）；CLI/委派命令的结果留给其对端界面（HTTP 信封），面板不渲染
        JTextPane chatArea = field(panel, "chatArea");

        panel.onTurnEvent(TurnEvent.commandResult(sessionKey, TurnOrigin.LOCAL_PANEL,
                "/help", AgentResponse.success("HELP-TEXT")));
        awaitEdtDrained();
        String html = chatTextOnEdt(chatArea);
        assertTrue(html.contains("You: /help"), "本地命令必须补画 You 行");
        assertTrue(html.contains("HELP-TEXT"), "本地命令结果必须渲染");

        panel.onTurnEvent(TurnEvent.commandResult(sessionKey, TurnOrigin.IPC_CLI,
                "/status", AgentResponse.success("CLI-STATUS")));
        awaitEdtDrained();
        html = chatTextOnEdt(chatArea);
        assertFalse(html.contains("You: /status"), "CLI 命令原文不渲染（发起方界面显示）");
        assertFalse(html.contains("CLI-STATUS"), "CLI 命令结果留给对端 HTTP 信封");
    }

    @Test
    void stopWithoutCancellableTargetStillResetsUnconditionally() throws Exception {
        // spec「Stop 无可取消对象时不死寂」：终态已发、取消事件尚未送达的毫秒窗口内
        // 点 Stop——复位（清 loading/恢复发送）无条件执行，不依赖取消事件到达
        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");
        JButton sendButton = field(panel, "sendButton");

        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.LOCAL_PANEL, "local q", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        awaitEdtDrained();
        assertTrue(stopButton.isVisible(), "武装后 Stop 按钮可见（前置）");
        assertTrue(chatTextOnEdt(chatArea).contains("AI is thinking"),
                "武装后 loading 指示在（前置）");

        // loop 上无在跑回合（IdleAiService，句柄只是合成武装）：signalCancel 无可取消
        // 对象返回 false——Stop 仍必须无条件复位
        SwingUtilities.invokeAndWait(() -> invoke(panel, "stopActiveTask"));
        awaitEdtDrained();

        assertFalse(chatTextOnEdt(chatArea).contains("AI is thinking"),
                "无条件清 loading——不依赖取消事件到达");
        assertFalse(stopButton.isVisible(), "无条件恢复发送模式");
        assertNull(sendButton.getToolTipText(), "Send 退出注入模式");
        assertTrue(chatTextOnEdt(chatArea).contains("Stopped"),
                "本地取消由 Stopped. 行交代");
    }

    @Test
    void cancelledTerminalArrivingAfterNextTurnStartStillRenders() throws Exception {
        // C2 取消路径倒序钉子（契约修订的容错面）：signalCancel 在摘槽后才发取消终态，
        // 槽已空窗口内新回合可开跑——订阅者可能先见 STARTED(N+1)、后见 CANCELLED(N)。
        // 面板按回合身份（活回合集合）过滤，两回合渲染互不吞没
        JTextPane chatArea = field(panel, "chatArea");

        TurnHandle turnA = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] A", false);
        panel.onTurnEvent(TurnEvent.started(turnA));
        awaitEdtDrained();
        assertTrue(chatTextOnEdt(chatArea).contains("You: [from cli] A"), "回合 A 回显（前置）");

        // 倒序窗口：A 的取消终态未发，新回合 B 的 STARTED 先到达
        TurnHandle turnB = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] B", false);
        panel.onTurnEvent(TurnEvent.started(turnB));
        awaitEdtDrained();

        // A 的取消终态此时才到（晚于 B 的 STARTED——契约允许的倒序）
        panel.onTurnEvent(TurnEvent.cancelled(turnA, CancelCause.USER_STOP));
        awaitEdtDrained();

        String html = chatTextOnEdt(chatArea);
        assertTrue(html.contains("You: [from cli] A"), "倒序取消不吞没回合 A 的回显");
        assertTrue(html.contains("You: [from cli] B"), "回合 B 的回显照常");
        assertEquals(1, html.split("Task cancelled", -1).length - 1,
                "A 的取消回执恰渲染一次（IPC 源 USER_STOP）");
    }

    @Test
    void busyRejectAndInjectedRenderNoticeLines() throws Exception {
        // 直调面板的事件分发（AgentLoop 侧的发射已由 AgentLoopTurnEventTest 覆盖；
        // 此处测面板渲染）
        panel.onTurnEvent(TurnEvent.rejectedBusy(sessionKey));
        panel.onTurnEvent(TurnEvent.injected(sessionKey, TurnOrigin.IPC_CLI, "[from cli] extra input"));
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        assertTrue(chatArea.getText().contains("Delegation rejected"),
                "busy 快拒必须渲染系统提示行");
        assertTrue(chatArea.getText().contains("[Injected] You: [from cli] extra input"),
                "注入消息必须以注入回显样式渲染（含来源前缀）");
    }

    @Test
    void turnStartQueuedBehindSlashNewIsDroppedByGenerationSnapshot() throws Exception {
        // F4 复现（对抗审查）：/new 的 EDT 事件先入队、回合启动事件排其后——
        // dispatch 的代数必须于<b>通知时</b>快照；若在 EDT 执行时才读，会按
        // /new 之后的新代数放行，幽灵 "You:" 行与取消回执渗入刚清空的新会话
        JTextPane chatArea = field(panel, "chatArea");
        java.util.concurrent.CountDownLatch edtHold = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                edtHold.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        JTextArea messageField = field(panel, "messageField");
        SwingUtilities.invokeLater(() -> {
            messageField.setText("/new");
            invoke(panel, "sendMessage");
        });
        // EDT 仍被钳制：/new 必然后于本事件执行 → 快照必然读到旧代数
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] race", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        edtHold.countDown();

        awaitUntil(() -> chatTextOnEdt(chatArea).contains("You: /new"), "/new echo rendered");
        awaitEdtDrained();
        String html = chatTextOnEdt(chatArea);
        assertFalse(html.contains("[from cli] race"),
                "旧会话的回合启动不得按 EDT 执行时的新代数放行并渲染进 /new 后的新会话");

        // 活回合集合已被 /new 清空、该回合 id 从未入集：迟到的取消回执同样丢弃
        panel.onTurnEvent(TurnEvent.cancelled(turn, CancelCause.USER_STOP));
        awaitEdtDrained();
        assertFalse(chatTextOnEdt(chatArea).contains("Task cancelled"),
                "已重置会话的迟到取消回执不得渲染");
    }

    @Test
    void lateIpcTerminalAfterLocalTurnStart_rendersUnderLiveTurnSet() throws Exception {
        // F3 语义修订（活回合集合模型）：垂死 IPC 回合的迟到终态对集合内任意 id 照常
        // 渲染（proposal：终态先于下回合 STARTED / 垂死迟到终态渲染=今日双渲染基线的
        // 推广）——旧单窗口「本地回合一开即关闭 IPC 呈现窗口」被集合模型取代，
        // 两个回合的输出互不清除
        JTextPane chatArea = field(panel, "chatArea");
        TurnHandle ipcTurn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.started(ipcTurn));
        awaitEdtDrained();

        JTextArea messageField = field(panel, "messageField");
        SwingUtilities.invokeAndWait(() -> {
            messageField.setText("local question");
            invoke(panel, "sendMessage");
        });
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("You: local question"),
                "local turn echo rendered");

        panel.onTurnEvent(TurnEvent.cancelled(ipcTurn, CancelCause.TIMEOUT));
        awaitEdtDrained();
        String html = chatTextOnEdt(chatArea);
        assertTrue(html.contains("You: [from cli] hello"),
                "同代数内先前的 IPC 回显不受影响（sanity）");
        assertTrue(html.contains("timed out"),
                "活回合集合内 IPC 回合的迟到超时终态照常渲染（集合不清除垂死回合）");
    }

    @Test
    void adoptsRunningIpcTurnWhenPanelJoinsMidTurn() throws Exception {
        // F2b 复现（对抗审查）：面板懒创建——委派/CLI 回合先于面板存在，其
        // TURN_STARTED 发给了零订阅者。构造完成时本实例会话上仍有活跃回合 →
        // 领养：提示行 + Stop 模式 + 活回合集合登记（后续事件照常渲染，
        // 错过的中途进度不补放——Q12 无缓冲决策）
        JTextPane chatArea = field(panel, "chatArea");
        java.util.concurrent.CountDownLatch llmEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseLlm = new java.util.concurrent.CountDownLatch(1);
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        AgentLoop runningLoop = new AgentLoop(new ToolRegistry(Runnable::run),
                memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey),
                new BlockingAiService(llmEntered, releaseLlm));
        CompletableFuture<AgentResponse> running = null;
        try {
            // 回合先开跑（面板此刻还不存在——生产上面板尚未订阅）
            running = runningLoop.processMessage("[from cli] long delegated task",
                    sessionKey, null, TurnOrigin.IPC_DELEGATED);
            assertTrue(llmEntered.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "delegated turn should reach its LLM call");

            // 面板此刻构造：绑定 loop（生产：构造器的 initializeAgentLoop + 工厂级
            // 订阅）+ 领养
            setField(panel, "agentLoop", runningLoop);
            runningLoop.addTurnSubscriber(panel);
            invoke(panel, "adoptRunningIpcTurnIfNeeded");

            JButton stopButton = field(panel, "stopButton");
            awaitUntil(stopButton::isVisible, "adoption switches to Stop mode");
            String html = chatTextOnEdt(chatArea);
            assertTrue(html.contains("An IPC turn"),
                    "领养必须渲染提示行（面板晚到，回合已在跑）");
            assertFalse(html.contains("You: [from cli] long delegated task"),
                    "错过的回合启动不补放 You: 行（Q12 无缓冲决策）");

            // 领养后的事件照常渲染：PROGRESS 用领养登记的真实句柄直发
            TurnHandle adopted = runningLoop.activeTurn(sessionKey).orElseThrow();
            panel.onTurnEvent(TurnEvent.progress(adopted, ProgressUpdate.thinking("ADOPTED-PROGRESS")));
            awaitUntil(() -> chatTextOnEdt(chatArea).contains("ADOPTED-PROGRESS"),
                    "post-adoption progress renders");

            // 终态经真实事件流（面板已订阅 runningLoop）：LLM 放行 → 回合完成
            releaseLlm.countDown();
            running.get(10, java.util.concurrent.TimeUnit.SECONDS);
            awaitUntil(() -> chatTextOnEdt(chatArea).contains("blocked-done"),
                    "post-adoption terminal renders via the real event stream");
        } finally {
            runningLoop.removeTurnSubscriber(panel);
            releaseLlm.countDown();
            if (running != null) {
                try {
                    running.get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 收尾形态无关紧要：确保回合结束即可
                }
            }
            runningLoop.shutdown();
            setField(panel, "agentLoop", loop); // 还原，tearDown 关的是原 loop
        }
    }

    @Test
    void panelConstructionSharesFactoryCachedLoopWithIpcWarmup() throws Exception {
        // F2a 复现（对抗审查）：IpcServer 预热与面板构造必须命中同一
        // AiServiceFactory 缓存实例 → 同一 AgentLoop 单例；面板懒创建中途打开
        // 不得 shutdown+recreate 换掉 IPC 在跑回合所在的 loop
        AiService warmed = null;
        try {
            warmed = AiServiceFactory.createService(AiConfig.getDefaultModel());
        } catch (Exception e) {
            // 测试环境未注册 provider：无共享可言，跳过
        }
        Assumptions.assumeTrue(warmed != null, "默认 provider 不可用时跳过（无共享可言）");
        AgentLoop shared = AgentLoopFactory.getAgentLoop(warmed);
        Assumptions.assumeTrue(shared != null, "agent 禁用时跳过");
        AiChatPanel second = null;
        try {
            second = new AiChatPanel();
            assertSame(shared, AgentLoopFactory.getAgentLoop(),
                    "面板构造不得换掉 IPC 预热的 AgentLoop 单例");

            // 模型加载完成（选择器落地默认模型）后也不得再换：选择器条目剥前缀后
            // 命中同一 cache key → switchAiService 判 newService == currentAiService
            JComboBox<?> selector2 = field(second, "modelSelector");
            awaitUntil(() -> selector2.getSelectedItem() != null, "second panel models loaded");
            SwingUtilities.invokeAndWait(() -> { });
            assertSame(shared, AgentLoopFactory.getAgentLoop(),
                    "模型加载完成后的 switchAiService 不得重建单例 loop");
        } finally {
            if (second != null) {
                AgentLoopFactory.removeTurnSubscriber(second);
            }
            AgentLoopFactory.reset();
        }
    }

    @Test
    void panelSubscriptionSurvivesFactoryRebuildAfterModelSwitch() throws Exception {
        // 模型切换路径（switchAiService 的换血两步）：AgentLoopFactory.reset() →
        // getAgentLoop(newService) 重建 loop——面板订阅挂在工厂级表（构造器注册），
        // 重建后由工厂自动重挂；IPC 回合显示不得因换 loop 断流（「重建后忘了再挂」
        // 一类 bug 的护栏，对齐 AiChatPanel.switchAiService 的注释契约）
        JTextPane chatArea = field(panel, "chatArea");
        AgentLoop rebuilt = null;
        try {
            AgentLoopFactory.reset();
            rebuilt = AgentLoopFactory.getAgentLoop(new IdleAiService());
            Assumptions.assumeTrue(rebuilt != null, "agent 禁用时跳过");
            setField(panel, "agentLoop", rebuilt); // switchAiService 的字段回写

            // 重建后的 loop 上跑 CLI 回合：面板未手动重挂订阅，仍必须照常渲染
            rebuilt.processMessage("[from cli] after rebuild", sessionKey, null,
                    TurnOrigin.IPC_CLI)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            awaitUntil(() -> chatTextOnEdt(chatArea).contains("You: [from cli] after rebuild"),
                    "post-rebuild IPC turn still renders on the panel");
        } finally {
            if (rebuilt != null) {
                setField(panel, "agentLoop", loop); // 还原，tearDown 关的是原 loop
            }
            AgentLoopFactory.reset(); // 退役重建的工厂缓存 loop
        }
    }

    // ==================================================================
    // 对抗性证伪测试（d:/tmp/adv-findings.md 缺陷 1/2/6/7/8/10/14/16）：
    // loading 指示生命周期 / 终态窗口按钮复位 / 跨回合标志串扰 / 领养守卫 /
    // 按钮复位判据 / CLI 远程 /new 清屏。合成事件直发 panel.onTurnEvent（与生产
    // 事件同构）或门控 loop 驱动，顺序确定性锚见各测试注释。
    // ==================================================================

    @Test
    void reverseOrderCancelThenNextStartLeavesNoResidualLoadingIndicator() throws Exception {
        // 对抗缺陷[1/18]：armActiveTurn 无武装守卫（AiChatPanel.java:736-745，armed 已置仍无条件
        // appendLoadingIndicator）；removeLoadingIndicator 的 armed 门（AiChatPanel.java:1330）+
        // MessageProcessor.lastIndexOf 单点删除（MessageProcessor.java:226）——倒序窗口内先武装的
        // 指示 I1 在两回合终态均处理后永久残留，直到 /new 清屏。
        //
        // 攻击序列（缺陷 attackScenario 的确定性复刻，非线程竞速）：
        // ① 回合 N（IPC/CLI 源）在跑：STARTED(N) 武装指示 I1（loadingIndicatorArmed=true）；
        // ② ipc-worker 上 /agent 超时触发 signalCancel(TIMEOUT)：摘槽后才发 TURN_CANCELLED(N)
        //   （步骤 5 tryClaimTerminal 后发射），经面板 onTurnEvent 非 EDT → invokeLater 入队（尚未出队）；
        // ③ EDT 出队用户此前已排队的 Enter → 本地回合 N+1 开跑，STARTED(N+1) 在 EDT 零跳直派
        //   （先于队列中的 CANCELLED(N) 执行）→ armActiveTurn 在 armed=true 时仍追加第二个指示 I2；
        // ④ EDT 出队 CANCELLED(N)：lastIndexOf 只删 I2，armed 置 false；
        // ⑤ TURN_COMPLETED(N+1) → handleAgentResponse → removeLoadingIndicator 因 armed=false
        //   直接 no-op——I1 永久残留。
        //
        // 顺序确定性锚（flakiness 关键）：不竞速。STARTED(N+1) 经 invokeLater 入队的 EDT 任务内、
        // 在 EDT 上调 panel.onTurnEvent——走 onTurnEvent 的零跳分支（isEventDispatchThread →
        // dispatch 直调），即生产中 EDT 提交路径的零跳直派（spec「订阅者契约」G1）；CANCELLED(N)
        // 由测试线程直发——走 invokeLater 分支，即生产 ipc-worker 路径。EDT 队列 FIFO + 测试线程
        // 程序序 ⇒ 执行序恒为 STARTED(N+1) → CANCELLED(N)：即使 EDT 抢先取走先入队的 STARTED
        // 任务，该任务也同步完整跑完后才轮到后入队的取消任务。
        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");

        // ① 回合 N 开跑（IPC 源：/agent 超时取消路径的攻击对象）
        TurnHandle turnN = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] N", false);
        panel.onTurnEvent(TurnEvent.started(turnN));
        awaitEdtDrained();
        assertEquals(1, loadingIndicatorCount(chatArea), "首次武装后文档恰一个 loading 指示（前置）");
        assertTrue(stopButton.isVisible(), "武装后 Stop 按钮可见（前置）");

        // ②+③ 倒序窗口：N 的取消终态先入队（测试线程 invokeLater）、
        // N+1 的 STARTED 零跳先执行（EDT 任务内直调）
        TurnHandle turnN1 = new TurnHandle(sessionKey, TurnOrigin.LOCAL_PANEL, "next question", false);
        SwingUtilities.invokeLater(() -> panel.onTurnEvent(TurnEvent.started(turnN1)));
        panel.onTurnEvent(TurnEvent.cancelled(turnN, CancelCause.TIMEOUT));
        awaitEdtDrained();

        // 活回合集合语义（spec「面板过滤为活回合集合」）：倒序交付下两回合渲染互不吞没。
        // 注意：此处刻意不断言指示计数——幂等守卫式修复（不再追加）与全删式修复（删除时
        // 清除全部）的中间态计数分别为 1 与 2，均合法；只钉契约级终态。
        String mid = chatTextOnEdt(chatArea);
        assertTrue(mid.contains("You: [from cli] N"), "回合 N 的回显不受倒序影响（sanity）");
        assertTrue(mid.contains("You: next question"), "回合 N+1 的回显照常（sanity）");
        assertTrue(mid.contains("timed out"), "IPC 源超时取消回执照常渲染（sanity）");

        // ⑤ N+1 自然完成——最后一个终态是清理指示的最后机会
        panel.onTurnEvent(TurnEvent.completed(turnN1, AgentResponse.success("REPLY-SECOND-TURN")));
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("REPLY-SECOND-TURN"),
                "second turn completion rendered");
        awaitEdtDrained();

        // 契约断言（缺陷存在时的红点）：spec「事件顺序保证 / 取消路径倒序容忍」——取消终态(N)
        // SHALL 允许晚于新回合开始(N+1) 到达，订阅者 MUST 按回合身份过滤而非依赖跨回合到达
        // 顺序，且两者均照常处理；「面板过滤为活回合集合」——终态对集合内任意标识渲染并收尾。
        // 两回合终态均已处理后，文档不得残留任何 "AI is thinking..." 指示（重复武装须幂等：
        // armed 已置时不再追加，或删除时清除全部）。现实现此处 residual=1（I1 永久滞留）。
        String html = chatTextOnEdt(chatArea);
        String diag = "convGen=" + field(panel, "conversationGeneration")
                + " residual=" + loadingIndicatorCount(chatArea) + " chat=" + html;
        assertEquals(0, loadingIndicatorCount(chatArea),
                "倒序窗口后两回合终态均已处理，不得残留 loading 指示"
                        + "（armActiveTurn 无武装守卫叠加第二指示 + lastIndexOf 单点删除 + armed 门 no-op）" + diag);
        assertTrue(html.contains("REPLY-SECOND-TURN"),
                "回合 N+1 的最终回复照常渲染（清理修复不得以吞内容为代价）");
        assertFalse(stopButton.isVisible(), "无活跃回合后 Stop 按钮必须复位");
    }

    @Test
    void reverseOrderCompletionThenNextStartLeavesNoResidualLoadingIndicator() throws Exception {
        // 同一缺陷[1/18]的自然完成路径变体（缺陷声明「COMPLETED(N) 同理」）：loop 线程发射的
        // TURN_COMPLETED(N) 经订阅者 invokeLater 入队（AgentLoop 的终态在 finally 摘槽后同步
        // 内联发射，回调线程为 loop 线程 → 面板 invokeLater），EDT 上用户 Enter 触发的
        // STARTED(N+1) 零跳直派先执行——发射序仍满足 spec「回合 N 的终态先于回合 N+1 的
        // 开始（自然完成路径）」，但订阅者编组到 EDT 后的执行序可被零跳路径越过（零跳
        // 分支跳过队列、排队终态后出队）。面板对该执行序同样不得残留指示：只特判取消
        // 倒序、不修 armActiveTurn 武装幂等（或终态全删）的实现会在此变体下漏出同样的
        // 永久残留（COMPLETED(N) 的 handleAgentResponse → removeLoadingIndicator 同受
        // armed 门 + lastIndexOf 单点删除约束，AiChatPanel.java:1068-1070 → 1329-1339）。
        //
        // 顺序确定性锚与主攻测试相同：不竞速。STARTED(N+1) 经先入队的 EDT 任务零跳直调
        // panel.onTurnEvent，COMPLETED(N) 由测试线程直发走 invokeLater 后入队——EDT FIFO +
        // 程序序 ⇒ 执行序恒为 STARTED(N+1) → COMPLETED(N)。标记文本互非前缀，防 contains
        // 假阳性（不用 REPLY-N / REPLY-N1 这类前缀对）。
        JTextPane chatArea = field(panel, "chatArea");

        // ① 回合 N（IPC 源）开跑并武装指示 I1
        TurnHandle turnN = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] N", false);
        panel.onTurnEvent(TurnEvent.started(turnN));
        awaitEdtDrained();
        assertEquals(1, loadingIndicatorCount(chatArea), "首次武装后文档恰一个 loading 指示（前置）");

        // ②+③ 倒序：STARTED(N+1) 零跳先执行、已入队的 COMPLETED(N) 后出队
        TurnHandle turnN1 = new TurnHandle(sessionKey, TurnOrigin.LOCAL_PANEL, "next question", false);
        SwingUtilities.invokeLater(() -> panel.onTurnEvent(TurnEvent.started(turnN1)));
        panel.onTurnEvent(TurnEvent.completed(turnN, AgentResponse.success("REPLY-FIRST-TURN")));
        awaitEdtDrained();

        // ⑤ N+1 自然完成——最后一个终态是清理指示的最后机会
        panel.onTurnEvent(TurnEvent.completed(turnN1, AgentResponse.success("REPLY-SECOND-TURN")));
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("REPLY-SECOND-TURN"),
                "second turn completion rendered");
        awaitEdtDrained();

        // 契约断言（缺陷存在时的红点）：spec「面板过滤为活回合集合」——垂死/已终回合与
        // 新回合交叠时两者的回复 SHALL 都渲染且终态对集合内任意标识收尾；两回合终态均已
        // 处理后文档不得残留任何 "AI is thinking..." 指示。现实现此处 residual=1。
        String html = chatTextOnEdt(chatArea);
        assertTrue(html.contains("REPLY-FIRST-TURN"), "回合 N 的最终回复照常渲染（sanity）");
        assertTrue(html.contains("REPLY-SECOND-TURN"), "回合 N+1 的最终回复照常渲染（sanity）");
        assertEquals(0, loadingIndicatorCount(chatArea),
                "自然完成路径的倒序交付同样不得残留 loading 指示"
                        + " residual=" + loadingIndicatorCount(chatArea) + " chat=" + html);
    }

    @Test
    void overlappingLiveTurnsDoNotDuplicateOrStrandLoadingIndicator() throws Exception {
        // 缺陷[6/11]复现（panel lens）：loading 指示与 armed 位是跨回合共享单槽——
        // armActiveTurn(:736-744) 对已武装（loadingIndicatorArmed=true）的面板无条件二次
        // appendLoadingIndicator，叠加 removeLoadingIndicator(:1329-1339) 的 armed 单槽门控与
        // MessageProcessor.removeLoadingIndicator(:226) 的 lastIndexOf 删除，契约许可的交叠
        // 活回合（spec「面板过滤为活回合集合」+「取消路径倒序容忍」）下出现双指示，且首个
        // 到达的终态删掉最后一个指示并清 armed 后，另一回合的终态收尾因 armed=false 直接
        // no-op，先前的指示永久残留。
        //
        // 契约锚（openspec/changes/unify-turn-event-display/specs/agent-turn-events/spec.md）：
        // - Requirement「面板过滤为活回合集合」：呈现过滤 MUST NOT 退化为单槽「当前回合」——
        //   垂死回合与新回合交叠时两者的呈现互不吞没；loading 指示的清理须遵循活回合集合
        //   判据，而非 armed 单槽；
        // - Requirement「事件顺序保证」→ Scenario「取消路径倒序容忍」：本交叠序列是契约
        //   明确许可的到达顺序，面板必须按回合身份过滤而非依赖跨回合到达顺序。
        JTextPane chatArea = field(panel, "chatArea");

        // 回合 A 武装（合成事件直发 panel.onTurnEvent，与生产事件同构——本类既有配方）
        TurnHandle turnA = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] A", false);
        panel.onTurnEvent(TurnEvent.started(turnA));
        awaitEdtDrained();
        // 前置 sanity：恰一个指示。同时钉住计数观测面非空——防止终态断言在
        // 「getText() 根本不含该文本」时空转绿（vacuous green）
        assertEquals(1, loadingIndicatorCount(chatArea),
                "前置 sanity：首个回合武装后恰一个 loading 指示");

        // 契约许可的交叠窗口：A 未终结，B 的 STARTED 先到达
        // （生产对应：退役 loop 在跑 A + 新 loop 跑 B，或取消路径倒序容忍窗口）
        TurnHandle turnB = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] B", false);
        panel.onTurnEvent(TurnEvent.started(turnB));
        awaitEdtDrained();
        // 缺陷现场①：armActiveTurn 不看 armed 已置 → 无条件二次 append（现行为 2 个）
        assertEquals(1, loadingIndicatorCount(chatArea),
                "交叠活回合下至多一个 loading 指示（活回合集合呈现不得单槽重复武装）");

        // B 先完成：liveTurnIds.remove(B) 成功 → handleAgentResponse(:1068) 首行即
        // removeLoadingIndicator(:1070)。removeLoadingIndicator 先于内容渲染执行，
        // 「B-DONE」可见即删除已被执行——计数读取无竞态
        panel.onTurnEvent(TurnEvent.completed(turnB, AgentResponse.success("B-DONE")));
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("B-DONE"), "B 终态渲染落地");
        awaitEdtDrained();
        // A 仍在跑（liveTurnIds 仍含 A），指示必须保留。此断言同时钉住「只给 append
        // 加 gate」的天真修复方向（删除仍单槽）：B 的终态会误删 A 的指示 → 0 个，同样红
        assertEquals(1, loadingIndicatorCount(chatArea),
                "B 的终态不得清掉仍在跑的 A 的指示（终态收尾以『仍有活回合』为判据，对齐按钮复位判据）");

        // A 最后完成：全部活回合结束
        panel.onTurnEvent(TurnEvent.completed(turnA, AgentResponse.success("A-DONE")));
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("A-DONE"), "A 终态渲染落地");
        awaitEdtDrained();
        // 缺陷现场②：B 的终态已 lastIndexOf 删掉最后一个指示（I2）并清 armed=false，
        // A 的终态 removeLoadingIndicator 因 armed=false 直接 no-op → I1 永久残留
        assertEquals(0, loadingIndicatorCount(chatArea),
                "全部活回合结束后文档不得残留 'AI is thinking...'（armed 单槽永久残留缺陷）");
    }

    /**
     * 对抗评审缺陷[2/10]（race lens）：终态先派发、注入槽后摘除的 [AgentLoop:509→534] 窗口。
     *
     * 回合体在 try 尾（AgentLoop.java:443）emitTerminal 同步派发 TURN_COMPLETED，直到
     * finally（:470）activeTurnTokens.cleanup 才摘除路由槽（TurnRegistry：槽 = 条目
     * 存在且未 closed，hasActiveRun 的事实来源）。面板 handleAgentResponse
     * （AiChatPanel.java:1098）以 hasActiveRun 判定是否复位按钮——EDT 若在窗口内出队
     * 处理该终态（生产触发：loop 线程被 turnTeardownLock 并发 offerInjection 短持、GC
     * 停顿或调度抢占停滞数毫秒），读到的是本回合自己尚未摘除的槽 → 误判「仍有回合
     * 在跑」跳过 setButtonToSendMode；随后槽被静默摘除，会话无后继回合（队列空 →
     * 无 re-publish 孤儿）→ 事件流再无任何事件兜底复位，Stop 按钮滞留。
     *
     * 确定性复现锚（无裸 sleep 竞速）：
     *  - 门控订阅者注册在面板之后（AgentLoop.turnSubscribers 为 CopyOnWriteArrayList，
     *    按插入序派发）——COMPLETED 派发时面板先收到（其 onTurnEvent 仅 invokeLater
     *    投 EDT，快速返回），门控订阅者再把 loop 线程挂起在 :509 派发点内部（程序序
     *    严格先于 finally :534 摘槽），等价于生产中 loop 线程在该窗口停滞；
     *  - 主线程 invokeAndWait 排空 EDT（EDT FIFO：STARTED 由提交线程在 executor.execute
     *    之前投递，必先于 COMPLETED 处理），保证面板确定在窗口内完成终态处理；
     *  - future.get(10s) 等回合完全收尾（摘槽落地、无孤儿）后再做终局断言。
     *
     * 契约钉（spec）：「面板过滤为活回合集合」Scenario「垂死回合迟到终态仍渲染」：
     * 「按钮复位以『无活跃回合』为判据」——终局 hasActiveRun=false 时 Stop 必须隐藏；
     * 本场景无后续回合（无注入残留、无 re-publish 孤儿），终态即该会话事件流的最后一件，
     * 窗口内的误判不可能被任何后续事件修复（无兜底，这正是缺陷的杀伤点）。
     */
    @Test
    void completedTerminalEdtReadDuringSlotTeardownWindowMustResetButton() throws Exception {
        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");
        JButton sendButton = field(panel, "sendButton");

        java.util.concurrent.CountDownLatch terminalDispatched = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseLoop = new java.util.concurrent.CountDownLatch(1);
        // 门控订阅者：仅对 TURN_COMPLETED 报数并挂起 loop 线程（STARTED/PROGRESS 等直接
        // 放行）；挂起点在 emitTerminal 的派发循环内部 → finally 摘槽（:534）必然未达。
        // 挂起不持任何 AgentLoop 内部锁（resetFenceLock/turnTeardownLock 均在派发点之外），
        // EDT 侧 hasActiveRun 只读 CHM，无死锁面（订阅者契约「锁内回调须快速返回」不违反）
        org.gitee.jmeter.ai.agent.presenter.TurnSubscriber teardownWindowGate = event -> {
            if (event.kind() == TurnEvent.Kind.TURN_COMPLETED) {
                terminalDispatched.countDown();
                try {
                    // 兜底 10s 自弃（防测试自身故障时永久挂死 loop 线程）；主路径由
                    // finally 的 countDown 放行
                    releaseLoop.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        loop.addTurnSubscriber(teardownWindowGate); // 插入序在 panel 之后 → 派发序：面板先收
        try {
            // 会话唯一在跑的本地回合自然完成（IdleAiService 立即应答；队列无注入 → 收尾
            // 无 re-publish 孤儿）。STARTED 在提交线程（本测试主线程）派发并武装 Stop 模式
            CompletableFuture<AgentResponse> turn =
                    loop.processMessage("final natural completion", sessionKey);

            // ① loop 线程已停在终态派发点：窗口开启，:534 摘槽未达（latch 计数先于挂起，
            // 面板的 EDT 投递也先于本计数——程序序保证）
            assertTrue(terminalDispatched.await(10, TimeUnit.SECONDS),
                    "回合应到达终态发射点（前置）");

            // ② 排空 EDT：面板已在窗口内处理 COMPLETED（EDT FIFO：STARTED 先投递先处理，
            // liveTurnIds.remove 命中 → handleAgentResponse 已执行）
            SwingUtilities.invokeAndWait(() -> { });
            // 前置（红/绿两界均须成立）：终态内容渲染已落地——handleAgentResponse 的内容
            // 渲染无条件先行于按钮判定，以此证明 EDT 确在窗口内执行了终态处理
            awaitUntil(() -> chatTextOnEdt(chatArea).contains("unused"),
                    "terminal content rendered inside the teardown window");

            // 窗口观测（仅诊断、随失败消息输出，不作断言：面板侧/补发事件侧修复的世界里
            // 此值仍为 true；若未来修复把摘槽提前到终态派发之前则为 false——两种修复下
            // 终局断言都应绿，故此值不进断言防 false-red）
            final boolean slotStillRoutedInsideWindow = loop.hasActiveRun(sessionKey);

            // ③ 放行 loop 线程：走完 finally（activeTurnTokens.cleanup 置 closed 摘槽；队列空 →
            // republishLeftovers 无孤儿）→ future 完成；再排空残余 EDT 事件（修复若走
            // 「收尾后补发复位事件」路线，其 EDT 落地由下方轮询窗口吸收）
            releaseLoop.countDown();
            turn.get(10, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> { });

            // 前置：契约判据的输入此刻必须为真——「无活跃回合」（槽已摘、无孤儿在跑）
            assertFalse(loop.hasActiveRun(sessionKey),
                    "前置：回合收尾后无活跃回合（路由槽已摘、无 re-publish 孤儿）");

            // ④ 终局钉子（缺陷红点）：无活跃回合时 Stop 必须已复位隐藏。
            // 缺陷存在：②中 handleAgentResponse 在窗口内读到本回合未摘的槽（AiChatPanel
            // :1098 hasActiveRun=true）→ 跳过 setButtonToSendMode → 摘槽后无任何后续事件
            // → 本轮询 10s 超时，红在本 awaitUntil 内部的 assertTrue 处。
            awaitUntil(() -> !stopButton.isVisible(),
                    "无活跃回合时 Stop 按钮必须复位（终态在 [509→534] 摘槽窗口内被 EDT 处理，"
                            + "hasActiveRun 把本回合未摘的槽计作在跑回合致误判滞留；"
                            + "窗口内槽未摘=" + slotStillRoutedInsideWindow + "）");
            assertNull(sendButton.getToolTipText(), "Send 必须退出注入模式（复位完成）");
        } finally {
            releaseLoop.countDown(); // 任意断言失败也放行，防 tearDown 的回合排空等待卡死
            loop.removeTurnSubscriber(teardownWindowGate); // 门控订阅者不外漏到同类其他用例
        }
    }

    /**
     * 统计文档中 "AI is thinking" 指示出现次数。chatArea.getText() 返回 HTML 源码，
     * 指示文本为 ASCII 可直读（对齐既有测试约定）；在 EDT 上同步读取（复用本类
     * chatTextOnEdt）。供本文件倒序/交叠残留测试共用。
     */
    private static int loadingIndicatorCount(JTextPane chatArea) {
        return chatTextOnEdt(chatArea).split("AI is thinking", -1).length - 1;
    }

    /**
     * 【缺陷7复现】领养缺 visibleToPanel 守卫：不可见 IPC 命令回合被领养后
     * 其命令回执经 TURN_COMPLETED 渲染进本地面板（双显示域泄漏）。
     *
     * <p>证伪的契约条目（openspec/changes/unify-turn-event-display/specs/agent-turn-events/spec.md）：
     * <ul>
     *   <li>「headless 与会话边界」（spec.md:97）：IPC 命令回合（无显示契约）SHALL 只发射
     *       终态事件，不发射开始与进度事件；订阅者 SHALL 能按“终态可无起点”编码——
     *       面板的编码即 dispatch TURN_COMPLETED 分支的 liveTurnIds.remove 守卫
     *       （AiChatPanel.java:675-680），领养把发射端刻意省略的起点手工塞进集合即击穿它；</li>
     *   <li>「面板过滤为活回合集合」（spec.md:109）：领养写入集合的是<b>有显示契约</b>的
     *       回合——TurnHandle.visibleToPanel()（TurnHandle.java:63-66：
     *       !commandTurn || origin.isLocalPanel()）是显示域判定，adoptRunningIpcTurnIfNeeded
     *       （AiChatPanel.java:789-803）只豁免 isLocalPanel 不查它，属实现与契约背离。</li>
     * </ul>
     *
     * <p>确定性设计（无裸 sleep 竞速）：
     * <ol>
     *   <li>占位回合（另一会话键的非命令消息）在 GatedCall 上挂起，钉死 runningLoop 的
     *       单线程 executor；/new 命令回合提交后停留在 [提交→pickup] 队列——
     *       Turn 构造+注册（句柄随构造就位，AgentLoop.java:344-345）先于 execute 同步完成，
     *       activeTurn(sessionKey) 无需轮询即可查（窗口被阻塞任务无限期撑开）；</li>
     *   <li>领养是 invokeLater，断言前 invokeAndWait 排空 EDT（FIFO 保证领养先执行）；</li>
     *   <li>终态负断言的锚：emitTerminal（AgentLoop.java:443）先于 future.complete
     *       （:477）同步执行 → cmdFuture.get() 返回即证明 TURN_COMPLETED 已派发到
     *       面板的 onTurnEvent（invokeLater 已入队），再排空 EDT 后断言“未渲染”是确定的。</li>
     * </ol>
     */
    @Test
    void adoptionSkipsInvisibleIpcCommandTurn_itsTerminalStaysDropped() throws Exception {
        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");

        // 门控脚本服务：首个（也是唯一一个）LLM 调用进入即挂起。本场景无 signalCancel、
        // 无 interrupt，故用默认 RETURN_SCRIPTED 策略即可（HANG_UNTIL_RELEASED 是
        // Stop/Reset 钉子，此处用不上）
        GatedScriptAiService gated = new GatedScriptAiService();
        GatedCall blocker = gated.scriptGated(LLMResponse.text("FILLER-DONE"));

        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        AgentLoop runningLoop = new AgentLoop(new ToolRegistry(Runnable::run),
                memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), gated);
        // 记录器：钉住发射端契约前提（IPC 命令回合只发终态、无 STARTED/PROGRESS），
        // 使面板侧“终态应被丢弃”的期望有据可依
        RecordingSubscriber recorder = new RecordingSubscriber();
        runningLoop.addTurnSubscriber(recorder);

        CompletableFuture<AgentResponse> filler = null;
        CompletableFuture<AgentResponse> cmdFuture = null;
        try {
            // ① 占位回合：另一会话键的非命令消息进入门控 LLM 并挂起，占住单线程 executor。
            //    其所有事件被 dispatchTurnEvent 的会话键守卫（AgentLoop.java:169，
            //    spec「事件 SHALL 只对当前实例会话键派发」）拦截，不污染面板
            String otherSession = sessionKey + "-other";
            filler = runningLoop.processMessage("block-the-single-thread-executor",
                    otherSession, null, TurnOrigin.IPC_CLI);
            AwaitUtil.awaitUntil(() -> blocker.entered.getCount() == 0,
                    "filler turn reached its gated LLM call");

            // ② IPC 命令回合：本会话空闲（无在跑回合）→ Phase 3 完整回合。
            //    /new 是 exact 命令（BuiltinCommands.java:82）非 priority →
            //    commandTurn=true、origin=IPC_CLI ⇒ visibleToPanel()=false：
            //    发射端按 spec「IPC 命令回合只发射终态」不发 STARTED（AgentLoop.java:422）
            cmdFuture = runningLoop.processMessage("/new", sessionKey, null, TurnOrigin.IPC_CLI);
            TurnHandle cmdHandle = runningLoop.activeTurn(sessionKey).orElseThrow();
            assertFalse(cmdFuture.isDone(), "命令回合应仍排在被阻塞的 executor 后（前置）");
            assertEquals(TurnOrigin.IPC_CLI, cmdHandle.origin(), "前置：IPC_CLI 源");
            assertFalse(cmdHandle.visibleToPanel(), "前置：IPC 命令回合无显示契约");

            // ③ 面板此刻加入（生产等价：面板懒创建，构造器 addTurnSubscriber 后
            //    invokeLater 领养）——绑定 loop + 订阅 + 领养
            setField(panel, "agentLoop", runningLoop);
            runningLoop.addTurnSubscriber(panel);
            invoke(panel, "adoptRunningIpcTurnIfNeeded");
            awaitEdtDrained(); // EDT FIFO：领养的 invokeLater 必已执行

            // ④ 缺陷红线（第一道）：不可见命令回合不得被领养——
            //    现行为三者全出现：id 入集合 + loading/Stop 武装 + 提示行
            assertTrue(liveTurnIdsOnEdt(panel).isEmpty(),
                    "不可见 IPC 命令回合（visibleToPanel()==false）不得被领养写入活回合集合"
                            + "——spec「订阅者 SHALL 能按『终态可无起点』编码」");
            assertFalse(stopButton.isVisible(), "不得武装 Stop 模式（命令回合无显示契约）");
            assertFalse(chatTextOnEdt(chatArea).contains("An IPC turn"),
                    "不得渲染领养提示行（发射端刻意省略的起点不得由领养手工补画）");

            // ⑤ 放行占位回合 → 命令回合真正执行 cmdNew→resetConversation 并收尾
            blocker.release.countDown();
            cmdFuture.get(10, TimeUnit.SECONDS);
            filler.get(10, TimeUnit.SECONDS);

            // 发射端契约钉子：该回合全程只发了一个终态（无 STARTED/PROGRESS），
            // 且确已派发——面板侧“丢弃”是面对真实终态的丢弃，不是事件没发
            assertEquals(List.of(TurnEvent.Kind.TURN_COMPLETED), recorder.kindsFor(cmdHandle.id()),
                    "IPC 命令回合只发射终态事件（spec.md:97 headless 条款）");

            // ⑥ 缺陷红线（第二道）：命令回执不得渲染进本地面板。
            //    emitTerminal 先于 future.complete + cmdFuture.get() 已返回 + EDT 已排空
            //    ⇒ 事件必已处理完毕，此负断言确定性成立
            awaitEdtDrained();
            assertFalse(chatTextOnEdt(chatArea).contains("New session started."),
                    "未被领养的命令回合终态必须被活回合集合丢弃（id 不在集合 → 不渲染）；"
                            + "命令回执属于对端 CLI 的 HTTP 信封显示域，本地面板双渲染即泄漏");
        } finally {
            runningLoop.removeTurnSubscriber(panel);
            runningLoop.removeTurnSubscriber(recorder);
            blocker.release.countDown(); // 任一断言红时也放行挂起的占位回合
            if (filler != null) {
                try {
                    filler.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 收尾形态无关紧要：确保回合结束、jsonl 写盘完毕（@TempDir 清理前提）
                }
            }
            if (cmdFuture != null) {
                try {
                    cmdFuture.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 同上
                }
            }
            runningLoop.shutdown();
            setField(panel, "agentLoop", loop); // 还原，tearDown 关的是原 loop
        }
    }

    /**
     * 在 EDT 上拷贝活回合集合快照（与 chatTextOnEdt 同理：Swing 状态读取须在 EDT，
     * 拷贝后离线断言，避免断言期间 EDT 并发修改）。
     */
    private static Set<Long> liveTurnIdsOnEdt(AiChatPanel target) {
        try {
            java.util.concurrent.atomic.AtomicReference<Set<Long>> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            SwingUtilities.invokeAndWait(() ->
                    ref.set(new java.util.HashSet<>(field(target, "liveTurnIds"))));
            return ref.get();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read liveTurnIds on EDT", e);
        }
    }

    @Test
    void concurrentLiveTurnsToolCallSummaryNotSwallowedNorDuplicatedBySiblingProgress() throws Exception {
        // 缺陷[8]复现（panel lens）：toolCallsDisplayedProgressively（AiChatPanel.java:93）是
        // 面板级单标志——任一活回合的 TOOL_CALL 进度在 handleProgressNow(:1117) 置位，
        // 任一回合的终态在 handleAgentResponse(:1082-1089) 按布尔消费并复位，无回合身份归属。
        // 旧单 worker 单回合串行下它等价于回合内标志；事件流的活回合集合（liveTurnIds
        // 多 id）允许多活回合并行渲染（换血后退役 loop 的回合与当前 loop 的回合交叠）后，
        // 该标志成为跨回合共享脏位：B 的渐进标志吞掉 A 的工具摘要，A 的终态复位又使
        // B 已渐进显示过的工具块被重复补显。
        //
        // 契约锚（spec.md）：
        // 1)「Requirement: 面板过滤为活回合集合」——"垂死回合与新回合交叠时两者的回复
        //    SHALL 都渲染（与既有基线一致）"：A 的工具摘要属 A 的回复呈现，不得被兄弟
        //    回合 B 的进度标志吞掉；
        // 2)「Requirement: 事件顺序保证」——"订阅者 MUST 按回合身份过滤而非依赖跨回合
        //    到达顺序"：回合内呈现状态（是否已渐进显示过 TOOL_CALL）同理必须按回合身份
        //    归属，面板级共享标志正是跨回合串扰；
        // 3)「Requirement: 统一回合事件交付」——迁移到事件流后渲染语义不变，
        //    旧单 worker 模型的"渐进显示过则不补显汇总/未显示过则补显"必须按回合保留。
        JTextPane chatArea = field(panel, "chatArea");

        // ① 两活回合并行：A = 换血后退役 loop 上仍在跑的委派回合（本面板未见其任何
        //    TOOL_CALL 进度——生产对应领养前进度已丢/进度早到被活回合集合丢弃/跨 loop
        //    交叠窗口），B = 当前 loop 的 CLI 回合。两回合同代数、同在活回合集合内
        TurnHandle turnA = new TurnHandle(sessionKey, TurnOrigin.IPC_DELEGATED,
                "[delegated-from old] cross-loop task", false);
        panel.onTurnEvent(TurnEvent.started(turnA));
        awaitEdtDrained();
        TurnHandle turnB = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] b", false);
        panel.onTurnEvent(TurnEvent.started(turnB));
        awaitEdtDrained();

        // ② B 渐进显示一条工具调用 → 面板级标志被置位（缺陷：置位无回合归属）。
        //    注：断言只用 ASCII 工具名——chatArea.getText() 是 HTML 源码，✓ 图标会被
        //    转成数字字符实体（既有测试同款注意事项）
        ToolEvent toolOfB = ToolEvent.success("TOOL-LIVE-B", "b detail", 34);
        panel.onTurnEvent(TurnEvent.progress(turnB, ProgressUpdate.toolCall(toolOfB)));
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("TOOL-LIVE-B"),
                "B 的渐进工具行渲染（前置）");
        assertEquals(1, chatTextOnEdt(chatArea).split("TOOL-LIVE-B", -1).length - 1,
                "前置：B 的工具行此刻恰一条（仅渐进行）");

        // ③ A 的终态先到：A 自身从未渐进显示过任何 TOOL_CALL，按旧模型语义（flag 为
        //    回合内私有、非渐进回退路径 displayToolCallInfo）其 toolEvents 摘要必须补显。
        //    displayToolCallInfo 先于 processAiResponse 执行：正文 FINAL-A 可见即 A 的
        //    工具块补显决策已完整执行，此时计数即终态
        ToolEvent toolOfA = ToolEvent.success("TOOL-SUMMARY-A", "a detail", 12);
        panel.onTurnEvent(TurnEvent.completed(turnA,
                AgentResponse.success("FINAL-A", List.of("TOOL-SUMMARY-A"), 1, List.of(toolOfA))));
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("FINAL-A"), "A 终态渲染完成");

        String html = chatTextOnEdt(chatArea);
        String diag = "convGen=" + field(panel, "conversationGeneration") + " chat=" + html;
        assertEquals(1, html.split("TOOL-SUMMARY-A", -1).length - 1,
                "A 的工具摘要必须补显：A 未渐进显示过任何 TOOL_CALL，兄弟回合 B 置位的"
                        + "面板级标志不得吞掉 A 的汇总（spec『面板过滤为活回合集合』："
                        + "交叠两回合的回复都渲染）" + diag);

        // ④ B 的终态后到：B 已渐进显示过同一工具块，摘要不得再重复渲染（旧模型语义：
        //    渐进显示过的回合不补显汇总）。缺陷下 A 的终态已把标志错误消费复位为
        //    false → B 的 displayToolCallInfo 再次渲染同一工具块 → 计数变 2
        panel.onTurnEvent(TurnEvent.completed(turnB,
                AgentResponse.success("FINAL-B", List.of("TOOL-LIVE-B"), 1, List.of(toolOfB))));
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("FINAL-B"), "B 终态渲染完成");

        html = chatTextOnEdt(chatArea);
        diag = "convGen=" + field(panel, "conversationGeneration") + " chat=" + html;
        assertEquals(1, html.split("TOOL-LIVE-B", -1).length - 1,
                "B 的工具块恰渲染一次（仅②的渐进行）：若 A 的终态错误消费复位了标志，"
                        + "B 的摘要会在已渐进显示后重复补显（spec『事件顺序保证』：按回合"
                        + "身份归属，不依赖跨回合状态）" + diag);
    }

    /**
     * 对抗复现（已确认缺陷[10] major：按钮复位判据只查当前 loop 单例，违反 R8）。
     *
     * <p>契约钉子（spec.md）：
     * <ul>
     * <li>R8「面板过滤为活回合集合」Scenario「垂死回合迟到终态仍渲染」：
     *     "按钮复位以『无活跃回合』为判据"——活回合集合（liveTurnIds）非空时不得复位；
     * <li>R6「可插拔订阅与工厂级存活」Scenario「模型切换后订阅存活」：AgentLoop 重建后
     *     "旧实例在跑回合的迟到事件仍可达订阅者"——换血不得终止退役 loop 上的在跑回合。</li>
     * </ul>
     *
     * <p>场景四拍（生产拓扑对等）：① IPC 委派回合 T 在 L1 上开跑并武装面板
     * （liveTurnIds={T}、Stop 可见）；② 模型切换换血两步（switchAiService :885-886
     * 的等价物）：AgentLoopFactory.reset() 把 L1 退役进 retiredLoops（shutdown 不打断
     * 在跑任务）→ L2 顶上并回写 panel.agentLoop；③ 本地快回合 U 在 L2 上跑完 →
     * TURN_COMPLETED(U) 进 handleAgentResponse（AiChatPanel.java:1098）——复位判据只查
     * agentLoop(=L2).hasActiveRun：U 的槽位已在 finally 摘除、T 的槽位在退役 L1 上
     * → 误判"无活跃回合" → setButtonToSendMode 隐藏 Stop，而 liveTurnIds 仍含 T、
     * L1 迟到事件照常进聊天区——面板呈现"无回合"假象，用户失去终止 T 的唯一 UI 入口
     * （Stop 仅 Stop 模式可见）。
     *
     * <p>红点断言：U 终态落地后 stopButton.isVisible() 必须仍为 true。其前一行的
     * liveTurnIds 守卫断言在缺陷下仍绿——隔离红点，证明活回合集合模型本身完好，
     * 错的只是 :1098 的复位判据（漏退役 loop）。Stop 腿（缺陷下也绿）钉住后果：
     * 该按钮是终止 T 的唯一 UI 入口，signalCancelAny 必须真能停掉退役回合。
     *
     * <p>确定性锚（防 EDT 抢先在 U 摘槽前处理终态造成假绿）：U 提交前先以 latch 任务
     * 钳制 EDT——U 的 STARTED/COMPLETED EDT 投递全部排队；turn.get() 返回（⟹ U 的
     * finally 摘槽已执行完）后才放行 EDT，COMPLETED 必然在「槽已摘」状态下被处理。
     */
    @Test
    void stopButtonStaysVisibleForRetiredLoopTurnAfterModelSwitch() throws Exception {
        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");

        // L1 的服务：HANG_UNTIL_RELEASED——Stop 路由的 interrupt 有 interrupted 锚，
        // 放行前回合体挂 hang（取消收尾确定性可控）
        GatedScriptAiService gated = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        GatedCall tCall = gated.scriptGated(LLMResponse.text("T-FINAL"));
        AgentLoop l1 = newPanelLoop(gated);
        // L2 的服务：立即应答的本地快回合 U
        GatedScriptAiService quick = new GatedScriptAiService();
        quick.script(LLMResponse.text("QUICK-FINAL"));

        AgentLoop l2 = null;
        Object originalFactoryInstance = null;
        CompletableFuture<AgentResponse> tFuture = null;
        try {
            // 生产拓扑：工厂当前单例 = L1（反射替换，AgentLoopTurnEventTest.swapFactoryInstance 同款）
            originalFactoryInstance = swapFactoryInstance(l1);
            l1.addTurnSubscriber(panel);

            // ---- ① IPC 委派回合 T 在 L1 上开跑（[delegated-from] 前缀对齐生产载荷）----
            tFuture = l1.processMessage("[delegated-from peer] long running task",
                    sessionKey, null, TurnOrigin.IPC_DELEGATED);
            assertTrue(tCall.entered.await(10, TimeUnit.SECONDS), "T 必须已进入 LLM 调用（确定性锚）");
            awaitUntil(() -> chatTextOnEdt(chatArea).contains("You: [delegated-from peer]"),
                    "T 的 TURN_STARTED 武装面板（You 行渲染）");
            assertTrue(stopButton.isVisible(), "前置：T 在跑，Stop 模式已武装");
            long tId = l1.activeTurn(sessionKey).orElseThrow().id();

            // ---- ② 模型切换换血：reset() 退役 L1（retireLoop 入表 + shutdown 不打断 T）----
            AgentLoopFactory.reset();
            assertTrue(retiredLoopsForTest().contains(l1),
                    "L1 必须被工厂退役收容（R6：旧 loop 在跑回合的终止信号/事件仍经此路由）");
            assertFalse(tFuture.isDone(), "换血不得打断退役 L1 上的在跑回合（R6 前提）");
            l2 = newPanelLoop(quick);
            swapFactoryInstance(l2);          // getAgentLoop 的 instance = 新 loop 效果
            l2.addTurnSubscriber(panel);      // 工厂级订阅表重挂的对等操作（createAgentLoop :202）
            setField(panel, "agentLoop", l2); // switchAiService 的字段回写（:886）

            // ---- EDT 钳制：U 的 STARTED/COMPLETED EDT 投递排队，turn.get() 返回（U 槽
            //      已摘）后才放行——COMPLETED 必在「L2 无活跃回合」状态下被处理，红点确定 ----
            java.util.concurrent.CountDownLatch edtHold = new java.util.concurrent.CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                try {
                    edtHold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // ---- ③ 本地快回合 U 在 L2 上跑完（两参 processMessage = LOCAL_PANEL 源）----
            CompletableFuture<AgentResponse> uFuture = l2.processMessage("quick local question", sessionKey);
            assertTrue(uFuture.get(10, TimeUnit.SECONDS).isSuccess());
            edtHold.countDown(); // 放行 EDT：STARTED(U) → COMPLETED(U) 按序执行
            // 确定性锚：handleAgentResponse 在同一个 EDT runnable 内先渲染内容（:1091）
            // 再评估复位判据（:1098-1100）——内容可见即判据已执行，无需竞速
            awaitUntil(() -> chatTextOnEdt(chatArea).contains("QUICK-FINAL"),
                    "U 的终态已进 handleAgentResponse（同 EDT runnable 内含复位判据）");
            awaitEdtDrained();

            // ---- 红点断言：T 仍是活跃回合，Stop 不得复位（R8 判据 = 活回合集合非空）----
            @SuppressWarnings("unchecked")
            Set<Long> liveIds = field(panel, "liveTurnIds");
            assertTrue(liveIds.contains(tId),
                    "守卫：T 仍在活回合集合（R8 活集合模型完好——缺陷下此行仍绿，"
                            + "隔离红点到下面的按钮判据；liveTurnIds=" + liveIds + "）");
            assertTrue(stopButton.isVisible(),
                    "R8『按钮复位以无活跃回合为判据』× R6『换血后旧 loop 在跑回合仍可达』："
                            + "退役 L1 的 T 仍在跑（liveTurnIds 非空、事件照常渲染），U 完成后 Stop "
                            + "必须保持可见——只查 panel.agentLoop(=L2).hasActiveRun 会漏掉退役 loop "
                            + "的在跑回合（AiChatPanel.java:1098）");

            // ---- 后果钉子（缺陷下也绿）：Stop 是终止 T 的唯一 UI 入口，路由必须真能停 ----
            // EDT 上直调 stopActiveTask（按钮点击的对等物；缺陷下按钮不可见正是用户失去
            // 该入口的表现）——signalCancelAny 经工厂路由当前(L2) + 退役(L1) loop
            SwingUtilities.invokeAndWait(() -> invoke(panel, "stopActiveTask"));
            assertTrue(tCall.interrupted.await(10, TimeUnit.SECONDS),
                    "signalCancelAny 须路由到退役 L1 的在跑回合（R6 终止信号可达）");
            tCall.hang.countDown();
            assertTrue(l1.waitForCancellation(sessionKey, 10, TimeUnit.SECONDS), "T 的取消收尾落地");
            awaitUntil(() -> chatTextOnEdt(chatArea).contains("Task cancelled"),
                    "T 的取消回执行渲染（IPC_DELEGATED 源 + USER_STOP）");
            awaitEdtDrained();
            assertFalse(stopButton.isVisible(),
                    "T 终结（活回合集合此刻真为空）后 Stop 复位——判据正确时的全链收束");
        } finally {
            // 未走到 Stop 腿的早退路径也要放行 T：正常路径挂 release、取消后挂 hang——双计数幂等
            tCall.release.countDown();
            tCall.hang.countDown();
            if (tFuture != null) {
                try {
                    tFuture.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 取消路径下 future 异常完成——收尾形态无关紧要，排空即可
                }
            }
            if (l1 != null) {
                awaitUntil(() -> l1.activeTurn(sessionKey).isEmpty(),
                        "L1 回合排空（Windows 下 @TempDir 删除前先排空 jsonl 迟写）");
                l1.removeTurnSubscriber(panel);
                l1.shutdown();
            }
            if (l2 != null) {
                final AgentLoop secondLoop = l2; // lambda 捕获需 effectively final（l2 后置赋值）
                awaitUntil(() -> secondLoop.activeTurn(sessionKey).isEmpty(), "L2 回合排空");
                l2.removeTurnSubscriber(panel);
                l2.shutdown();
            }
            retiredLoopsForTest().remove(l1); // 静态退役表跨测试存活——污染清理（幂等）
            swapFactoryInstance(originalFactoryInstance); // 还原工厂单例（类级 tearDown 的 reset() 负责退役它）
            setField(panel, "agentLoop", loop); // 还原字段，tearDown 关的是 setUp 的 loop
        }
    }

    /** 与 setUp 同配方的独立 loop 构造（换血拓扑需要 L1/L2 两个实例）。 */
    private AgentLoop newPanelLoop(AiService service) {
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        return new AgentLoop(new ToolRegistry(Runnable::run), memoryStore,
                Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), service);
    }

    /** 反射替换 AgentLoopFactory 的私有静态单例（AgentLoopTurnEventTest 同款配方）。 */
    private static Object swapFactoryInstance(Object value) {
        try {
            Field f = AgentLoopFactory.class.getDeclaredField("instance");
            f.setAccessible(true);
            Object previous = f.get(null);
            f.set(null, value);
            return previous;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 反射读取工厂的退役 loop 表（换血拓扑的测试卫生与断言用）。 */
    @SuppressWarnings("unchecked")
    private static List<AgentLoop> retiredLoopsForTest() {
        try {
            Field f = AgentLoopFactory.class.getDeclaredField("retiredLoops");
            f.setAccessible(true);
            return (List<AgentLoop>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 缺陷[14]钉子（忙期路径，完整攻击面①-⑤）：CLI 远程 /new 重置后面板不得滞留旧会话转录。
     *
     * <p>spec.md「事件种类与载荷完整 &gt; Scenario: 取消原因可区分且渲染有别」原文断言
     * 「重置一律不渲染（重置 UI 自管、面板即将清理）」——其括号前提是：重置发生后面板侧
     * 必有清理动作。本地 /new 走 handleNewCommand（清屏+翻代数）前提成立；但同实例
     * CLI 直连 jmeter-cli agent "/new"（IpcServer.applyCliProvenance 对斜杠命令豁免
     * [from cli] 前缀，/agent 处理器以 TurnOrigin.IPC_CLI 调 processMessage）会真的执行
     * BuiltinCommands.cmdNew → AgentLoop.resetConversation（归档/清空/落盘会话数据、
     * signalCancel(RESET) 中止武装回合），而事件流不携带任何会话级清理信号：面板只收到
     * TURN_CANCELLED(RESET)（appendCancelLine 对 RESET 早退，仅摘 loading+复位按钮）
     * 与 COMMAND_RESULT(IPC_CLI)（非本地源不渲染）——转录滞留、conversationGeneration
     * 不翻，显示态与被清空的 session jsonl 永久分叉，重启后旧内容消失。
     */
    @Test
    void remoteSlashNewDuringArmedLocalTurnMustClearStaleTranscript() throws Exception {
        JTextPane chatArea = field(panel, "chatArea");

        // 门控 fake（HANG_UNTIL_RELEASED）：武装回合被 RESET interrupt 后挂 hang——
        // signalCancel 得以先认领终态并派发 TURN_CANCELLED(RESET)，断言窗口内回合体
        // 确定性冻结（对齐 AgentLoopTurnEventTest 的 Stop/Reset 钉子配方）
        GatedScriptAiService gated = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        AgentLoop remoteLoop = new AgentLoop(new ToolRegistry(Runnable::run),
                memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), gated);
        GatedCall armedCall = null;
        CompletableFuture<AgentResponse> armed = null;
        try {
            setField(panel, "agentLoop", remoteLoop); // 对齐领养测试的换绑配方
            remoteLoop.addTurnSubscriber(panel);      // 生产对等：回合事件订阅挂注入的 loop

            // ① 旧会话转录：一轮真实本地回合渲染出可断言的 ASCII 标记
            //   （chatArea.getText() 是 HTML 源码，断言一律用 ASCII）
            gated.script(LLMResponse.text("OLD-SESSION-ANSWER"));
            remoteLoop.processMessage("OLD-SESSION-QUESTION", sessionKey)
                    .get(10, TimeUnit.SECONDS);
            AwaitUtil.awaitUntil(() -> chatTextOnEdt(chatArea).contains("OLD-SESSION-ANSWER"),
                    "precondition: prior transcript rendered");

            // ①b 武装中的本地回合：门控挂进 LLM 调用（entered 双锁为同步锚）
            armedCall = gated.scriptGated(LLMResponse.text("INTERRUPTED-FINAL"));
            armed = remoteLoop.processMessage("LOCAL-IN-FLIGHT", sessionKey);
            assertTrue(armedCall.entered.await(10, TimeUnit.SECONDS),
                    "precondition: armed local turn reached its LLM call");
            AwaitUtil.awaitUntil(() -> chatTextOnEdt(chatArea).contains("You: LOCAL-IN-FLIGHT"),
                    "precondition: armed turn echo rendered");
            int generationBefore = (Integer) field(panel, "conversationGeneration");

            // ②③ 同实例 CLI 直连 /new（与 IpcServer /agent 处理器同一入口：来源 IPC_CLI、
            //     斜杠命令免前缀原样投递）：忙期 Phase 2 可分发命令在调用方线程（生产为
            //     ipc-worker）同步执行 cmdNew → resetConversation
            AgentResponse cliResult = remoteLoop.processMessage("/new", sessionKey, null,
                    TurnOrigin.IPC_CLI).get(10, TimeUnit.SECONDS);
            assertTrue(cliResult.isSuccess(), "remote /new must execute the reset");
            // 确定性锚：RESET 取消确已打断武装回合（此刻回合体冻结在 hang 上，无法再写会话）
            assertTrue(armedCall.interrupted.await(10, TimeUnit.SECONDS),
                    "RESET cancel must have interrupted the armed local turn");
            awaitEdtDrained(); // TURN_CANCELLED(RESET) / COMMAND_RESULT(IPC_CLI) 的 EDT 投递排空

            // 数据侧钉住分叉前提：会话确实已被远程 /new 清空（显示态即将与之分叉之处）
            assertEquals(0, remoteLoop.getSessionManager().getOrCreate(sessionKey).getMessageCount(),
                    "precondition: session data cleared by the remote /new (source of the divergence)");

            // 【红线断言】spec「取消原因可区分且渲染有别」：「重置一律不渲染（重置 UI
            // 自管、面板即将清理）」的括号前提对远程重置同样必须成立——面板转录不得
            // 残留已被清空的旧会话内容。缺陷存在时旧转录是稳定终态（无任何路径
            // 清屏），本行 10s 有界轮询后超时红；修复后（面板收到清理信号/事件流补「重置」
            // 会话级事件触发清屏）轮询即绿。
            AwaitUtil.awaitUntil(() -> !chatTextOnEdt(chatArea).contains("OLD-SESSION-ANSWER"),
                    "remote /new must clear the stale transcript "
                            + "(spec R2 premise: 'the panel is about to clean up' must hold for remote resets)");

            // 【次线断言】「清屏+翻代数」：渲染代数必须翻——否则旧会话的迟到事件仍按
            // 旧代数放行，渗入已清屏的新会话（dispatch 的代数过滤失效）。
            // 清屏落地（上行已过）时翻代数同批发生，此处即时断言即确定。
            int generationAfter = (Integer) field(panel, "conversationGeneration");
            assertTrue(generationAfter > generationBefore,
                    "remote /new must advance the render generation alongside the clear");

            armedCall.hang.countDown(); // 常规路径放行垂死回合收尾（finally 有幂等兜底）
        } finally {
            remoteLoop.removeTurnSubscriber(panel);
            if (armedCall != null) {
                armedCall.hang.countDown(); // 幂等兜底：断言提前失败时也放行，防排空卡死
            }
            if (armed != null) {
                try {
                    armed.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 被 RESET 取消的 future 异常完成是预期收尾形态，仅确保回合结束
                }
            }
            // Windows 下 @TempDir 清理与迟写 jsonl 的文件锁竞态：有界排空再 shutdown
            AwaitUtil.awaitUntil(() -> remoteLoop.activeTurn(sessionKey).isEmpty(),
                    "remote loop active turn drained before shutdown");
            remoteLoop.shutdown();
            setField(panel, "agentLoop", loop); // 还原，tearDown 关的是 setUp 注入的 loop
        }
    }

    /**
     * 缺陷[14]钉子（空闲路径变体，缺陷描述③的 Phase 3 走向）：无在跑回合时，CLI 远程 /new
     * 经 Phase 3 命令回合在 loop 线程执行 cmdNew → resetConversation。IPC_CLI 命令回合
     * visibleToPanel()=false（不发 STARTED），其 TURN_COMPLETED 的回合 id 不在活回合
     * 集合 → dispatch 早退；COMMAND_RESULT 通道的非本地源事件不渲染——面板全程无感知，
     * 转录滞留、代数不翻，与已清空的会话数据分叉。spec 契约条目同忙期变体：
     * 「事件种类与载荷完整 &gt; 取消原因可区分且渲染有别」的「面板即将清理」前提。
     */
    @Test
    void remoteSlashNewAsIdleCommandTurnMustClearStaleTranscript() throws Exception {
        JTextPane chatArea = field(panel, "chatArea");

        // 空闲路径无需门控冻结：脚本化即答 fake 已够（保留 testsupport 配方）
        GatedScriptAiService gated = new GatedScriptAiService();
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        AgentLoop remoteLoop = new AgentLoop(new ToolRegistry(Runnable::run),
                memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), gated);
        try {
            setField(panel, "agentLoop", remoteLoop);
            remoteLoop.addTurnSubscriber(panel); // 生产对等：事件订阅挂注入的 loop

            // ① 旧会话转录：一轮真实本地回合渲染出可断言的 ASCII 标记
            gated.script(LLMResponse.text("OLD-SESSION-ANSWER"));
            remoteLoop.processMessage("OLD-SESSION-QUESTION", sessionKey)
                    .get(10, TimeUnit.SECONDS);
            AwaitUtil.awaitUntil(() -> chatTextOnEdt(chatArea).contains("OLD-SESSION-ANSWER"),
                    "precondition: prior transcript rendered");
            int generationBefore = (Integer) field(panel, "conversationGeneration");

            // ② 空闲期 CLI 直连 /new：Phase 3 命令回合（loop 线程 commandRouter.dispatch
            //    → cmdNew → resetConversation；自身豁免下无 TURN_CANCELLED，仅回合终态）。
            //    回执经 future 返还 CLI 调用方——spec「结果通道语义不变」的 HTTP 信封不受影响
            AgentResponse cliResult = remoteLoop.processMessage("/new", sessionKey, null,
                    TurnOrigin.IPC_CLI).get(10, TimeUnit.SECONDS);
            assertTrue(cliResult.isSuccess(), "remote /new must execute the reset");
            assertTrue(cliResult.getContent().contains("New session started"),
                    "cmdNew receipt must still reach the CLI caller (envelope semantics untouched)");
            awaitEdtDrained(); // 命令回合终态事件的 EDT 投递排空

            // 数据侧钉住分叉前提：会话确实已被远程 /new 清空
            assertEquals(0, remoteLoop.getSessionManager().getOrCreate(sessionKey).getMessageCount(),
                    "precondition: session data cleared by the remote /new (source of the divergence)");

            // 【红线断言】转录不得残留旧会话标记。缺陷存在时本行 10s 超时红
            // （IPC_CLI 命令回合不可见+活回合集合外终态早退，面板无任何清理路径）；
            // 修复后（清理信号/会话级重置事件 → 清屏+翻代数）即绿。
            AwaitUtil.awaitUntil(() -> !chatTextOnEdt(chatArea).contains("OLD-SESSION-ANSWER"),
                    "remote /new must clear the stale transcript "
                            + "(spec R2 premise: 'the panel is about to clean up' must hold for remote resets)");

            // 【次线断言】清屏必须伴随渲染代数翻转，否则旧会话迟到事件仍按旧代数放行
            // 渗入已清屏的新会话
            int generationAfter = (Integer) field(panel, "conversationGeneration");
            assertTrue(generationAfter > generationBefore,
                    "remote /new must advance the render generation alongside the clear");
        } finally {
            remoteLoop.removeTurnSubscriber(panel);
            // 两回合均已 future.get 完成，activeTurn 必已排空；保留有界排空兜底（Windows
            // 下 @TempDir 清理与迟写 jsonl 的文件锁竞态，对齐既有 tearDown 注释）
            AwaitUtil.awaitUntil(() -> remoteLoop.activeTurn(sessionKey).isEmpty(),
                    "remote loop active turn drained before shutdown");
            remoteLoop.shutdown();
            setField(panel, "agentLoop", loop); // 还原，tearDown 关的是 setUp 注入的 loop
        }
    }

    /**
     * 对抗测试[缺陷16]（fidelity）：领养无法甄别「终态已发射、句柄未摘」的死回合。
     *
     * <p>缺陷链路（AgentLoop 收尾序，行号按当前源码）：
     * <pre>
     *   emitTerminal(:509)          —— TURN_COMPLETED 派发给<b>当时</b>的订阅表
     *   finally(:515-535)           —— 首句 synchronized(turnTeardownLock)
     *   future.complete(:540)       —— 完成后才触发 whenComplete 摘句柄(:568)
     * </pre>
     * 面板若在此窗口内构造：addTurnSubscriber 挂上时终态已发给旧订阅表（事件流无缓冲，
     * Q12 决策），adoptRunningIpcTurnIfNeeded（AiChatPanel:789）仍凭 loop.activeTurn()
     * 命中句柄即领养并武装 loading+Stop——此后永无第二个终态（tryClaimTerminal 已认领，
     * 恰好一次契约禁止重发）来走 dispatch 的 TURN_COMPLETED/:676 remove(id) 分支，
     * 面板永久滞留武装态，仅手点 Stop 才复位。
     *
     * <p><b>确定性锚（无裸 sleep 竞速）</b>：测试线程预先反射持有 turnTeardownLock
     * （finally 首句的同一把锁），把 loop 线程钉死在「终态已发射、future.complete 未达」
     * 处——emitTerminal 在 try 尾、锁在 finally 首，JVM 语义保证先派发后 parked，窗口
     * 可任意拉宽且顺序无歧义。EDT 领养路径不获取任何 loop 内部锁（订阅者契约），
     * 持锁跨 awaitEdtDrained/awaitUntil 无死锁。
     */
    @Test
    void adoptedDeadTurnInEmissionWindowMustNotStayArmed() throws Exception {
        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");

        // 脚本化 fake（testsupport）：单次立即应答——回合自然完成，收尾即进入被钉死的 finally
        GatedScriptAiService scripted = new GatedScriptAiService();
        scripted.script(LLMResponse.text("dead-turn-final"));
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        AgentLoop runningLoop = new AgentLoop(new ToolRegistry(Runnable::run),
                memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), scripted);
        // recorder 充当「面板尚不存在的订阅表」：给测试一个「终态已发射」的确定性锚点
        //（缺陷场景里订阅表可以为空——空表派发 no-op，窗口结构不变）
        RecordingSubscriber recorder = new RecordingSubscriber();
        runningLoop.addTurnSubscriber(recorder);
        Object teardownLock = field(runningLoop, "turnTeardownLock");
        CompletableFuture<AgentResponse> running = null;
        try {
            synchronized (teardownLock) {
                // ① IPC 委派回合在面板存在之前开跑（生产对应：IpcServer /agent 直达、面板懒创建未订阅）
                running = runningLoop.processMessage("[delegated-from peer] dead window task",
                        sessionKey, null, TurnOrigin.IPC_DELEGATED);
                // ② 锚点：终态已发射给当时的订阅表（先于 finally park，happens-before 轮询可见）
                awaitUntil(() -> recorder.count(TurnEvent.Kind.TURN_COMPLETED) == 1,
                        "turn must dispatch TURN_COMPLETED while parked before future.complete");
                // ③ 窗口成立性锚（缺陷定义「句柄在场而终态已亡」）：future 未完成——钉住
                // AgentLoop 契约「订阅者见终态先于发起方 get() 返回」（emitTerminal
                // 先于 future.complete）；句柄未被 whenComplete 摘除（摘除点在 :568，晚于 park）
                assertFalse(running.isDone(), "loop thread must be parked before future.complete");
                assertTrue(runningLoop.activeTurn(sessionKey).isPresent(),
                        "handle must still be registered while parked in the finally window");

                // ④ 面板此刻才「构造」（生产：构造器 :138 addTurnSubscriber + :144 领养——
                //    终态已发射给旧订阅表，面板永收不到该回合的任何终态事件）。
                //    对齐 adoptsRunningIpcTurnWhenPanelJoinsMidTurn 的「生产对等」装配
                setField(panel, "agentLoop", runningLoop);
                runningLoop.addTurnSubscriber(panel);
                invoke(panel, "adoptRunningIpcTurnIfNeeded");
                awaitEdtDrained(); // 领养的 invokeLater 已在 EDT 执行完毕（武装与否由实现决定）
            } // ⑤ 放开窗口：loop 线程走出 finally → future.complete → whenComplete 摘句柄

            // ⑥ 回合彻底收尾：future 完成 + 句柄摘除——「是否滞留武装」的评判必须在窗口关闭之后
            assertEquals("dead-turn-final",
                    running.get(10, TimeUnit.SECONDS).getContent());
            awaitUntil(() -> runningLoop.activeTurn(sessionKey).isEmpty(),
                    "handle must be removed once the future completes");
            // ⑦ spec「终态恰好一次」Requirement：修复不得以「向晚到订阅者重发终态」实现本缺陷
            //（双发射点 claim 后静默；recorder 全程在场，若补发则计数变 2）
            assertEquals(1, recorder.terminalCountFor(recorder.lastCompleted().turn().id()),
                    "terminal must stay exactly-once; late subscribers must not trigger re-emission");

            // ⑧ 契约断言（缺陷红点）：spec「面板过滤为活回合集合」Requirement · Scenario
            // 「领养回合事件照常渲染」——"领养回合的进度与终态事件照常渲染<b>（含武装与终态
            // 收尾）</b>"：武装必须有配对的终态收尾。被领养回合已终局、句柄已摘后，面板
            // 不得滞留武装态（loading 指示已清 + Stop 已隐藏）。
            // 缺陷现实现：领养武装后永无终态复位 → 本行 10s 超时红（失败消息带判定起点的
            // 聊天区快照，可见 "AI is thinking" 与领养提示行俱在）；
            // 修复后：变体 a（TurnHandle 暴露 terminalEmitted / 领养双检——死回合不武装）
            //   → 条件立即成立；变体 b（武装后异步补查 future/hasActiveRun 自复位）
            //   → 句柄摘除后一拍内复位 → 条件成立。两变体均绿。
            String before = chatTextOnEdt(chatArea);
            awaitUntil(() -> !stopVisibleOnEdt(stopButton)
                            && !chatTextOnEdt(chatArea).contains("AI is thinking"),
                    "adopted dead turn must not leave the panel armed (loading cleared + Stop "
                            + "hidden) after the turn fully completed; chat-at-judgment=" + before);
        } finally {
            runningLoop.removeTurnSubscriber(panel);
            runningLoop.removeTurnSubscriber(recorder);
            runningLoop.shutdown();
            setField(panel, "agentLoop", loop); // 还原，tearDown 关的是原 loop
        }
    }

    /** 在 EDT 上读 Stop 按钮可见性（Swing 组件读取须在 EDT；轮询需同步取值）。 */
    private static boolean stopVisibleOnEdt(JButton stopButton) {
        java.util.concurrent.atomic.AtomicBoolean ref =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() -> ref.set(stopButton.isVisible()));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read stopButton visibility on EDT", e);
        }
        return ref.get();
    }

    // ------------------------------------------------------------------
    // 测试基础设施（对齐 AiChatPanelNewConversationTest 的面板脚手架）
    // ------------------------------------------------------------------

    /** 排空 EDT：invokeAndWait 的任务入队并执行完毕后，之前入队的事件必然已执行。 */
    private static void awaitEdtDrained() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /**
     * needle 起点处的生效样式：自根沿祖先链合并到字符元素（HTML 内联样式落在块级
     * 元素上，字符叶子自身可能不带）——视图解析样式的同一来源。EDT 上同步读取。
     */
    private static javax.swing.text.AttributeSet styleAttrsAt(JTextPane chatArea, String needle) {
        try {
            java.util.concurrent.atomic.AtomicReference<javax.swing.text.AttributeSet> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    javax.swing.text.StyledDocument doc = chatArea.getStyledDocument();
                    int idx = doc.getText(0, doc.getLength()).indexOf(needle);
                    if (idx < 0) {
                        return;
                    }
                    java.util.ArrayList<javax.swing.text.Element> chain = new java.util.ArrayList<>();
                    for (javax.swing.text.Element e = doc.getCharacterElement(idx);
                            e != null; e = e.getParentElement()) {
                        chain.add(e);
                    }
                    javax.swing.text.SimpleAttributeSet merged =
                            new javax.swing.text.SimpleAttributeSet();
                    for (int i = chain.size() - 1; i >= 0; i--) {
                        merged.addAttributes(chain.get(i).getAttributes());
                    }
                    ref.set(merged);
                } catch (javax.swing.text.BadLocationException ignored) {
                    // needle 不在文档内：返回 null，由调用方断言失败
                }
            });
            return ref.get();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read style attributes on EDT", e);
        }
    }

    /** styleAttrsAt 的斜体判定：HTML 文档把内联样式存为 CSS.Attribute 键（非 StyleConstants.Italic）。 */
    private static boolean isItalicAt(JTextPane chatArea, String needle) {
        javax.swing.text.AttributeSet attrs = styleAttrsAt(chatArea, needle);
        Object fontStyle = attrs == null ? null
                : attrs.getAttribute(javax.swing.text.html.CSS.Attribute.FONT_STYLE);
        return "italic".equalsIgnoreCase(String.valueOf(fontStyle));
    }

    /** styleAttrsAt 的取色（CSS.Attribute.COLOR 的字符串值；无内联色返回 null）。 */
    private static String cssColorAt(JTextPane chatArea, String needle) {
        javax.swing.text.AttributeSet attrs = styleAttrsAt(chatArea, needle);
        Object color = attrs == null ? null
                : attrs.getAttribute(javax.swing.text.html.CSS.Attribute.COLOR);
        return color == null ? null : String.valueOf(color);
    }

    /** AttributeSet 的诊断展开（失败消息用）。 */
    private static String attrsToString(javax.swing.text.AttributeSet attrs) {
        if (attrs == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (java.util.Enumeration<?> e = attrs.getAttributeNames(); e.hasMoreElements(); ) {
            Object k = e.nextElement();
            sb.append(k).append('=').append(attrs.getAttribute(k)).append(' ');
        }
        return sb.append(']').toString();
    }

    /** 在 EDT 上读文档纯文本（非 HTML 源码）：字面标签断言用（getText 会转义且丢样式）。 */
    private static String docTextOnEdt(JTextPane chatArea) {
        try {
            java.util.concurrent.atomic.AtomicReference<String> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    javax.swing.text.StyledDocument doc = chatArea.getStyledDocument();
                    ref.set(doc.getText(0, doc.getLength()));
                } catch (javax.swing.text.BadLocationException ignored) {
                    ref.set("");
                }
            });
            return ref.get();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read document text on EDT", e);
        }
    }

    /** 在 EDT 上读聊天区全文（HTML 源码）：Swing 组件读取须在 EDT，且轮询需同步取值。 */
    private static String chatTextOnEdt(JTextPane chatArea) {
        try {
            java.util.concurrent.atomic.AtomicReference<String> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(chatArea.getText()));
            return ref.get();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read chatArea on EDT", e);
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
            Thread.sleep(20);
        }
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

    /** 立即应答的假 AiService：本测试不跑真实回合，仅满足 AgentLoop 构造。 */
    private static final class IdleAiService implements AiService {
        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            return LLMResponse.text("unused");
        }

        @Override public String getName() {
            return "idle-fake";
        }

        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }

        @Override public void setGenerationSettings(GenerationSettings settings) { }

        @Override public boolean supportsToolCalling() {
            return true;
        }
    }

    /** 进入即报号、等待外部放行的假 AiService：模拟一次长时间阻塞的 LLM 调用（领养测试）。 */
    private static final class BlockingAiService implements AiService {
        private final java.util.concurrent.CountDownLatch entered;
        private final java.util.concurrent.CountDownLatch release;

        BlockingAiService(java.util.concurrent.CountDownLatch entered,
                java.util.concurrent.CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("LLM call interrupted", e);
            }
            return LLMResponse.text("blocked-done");
        }

        @Override public String getName() {
            return "blocking-fake";
        }

        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }

        @Override public void setGenerationSettings(GenerationSettings settings) { }

        @Override public boolean supportsToolCalling() {
            return true;
        }
    }
}
