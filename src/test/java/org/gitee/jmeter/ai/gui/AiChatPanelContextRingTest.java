package org.gitee.jmeter.ai.gui;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnHandle;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.JComboBox;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextUsageRing} 的面板接线单测（context-usage-indicator P2）：USAGE 进度
 * 更新指示器且不进文本渲染域（不清 loading、转录无新增行）；会话重置复位指示器；
 * 旧会话迟到 USAGE 不渗入；IPC 委派回合同口径刷新。事件由测试线程直发
 * {@code panel.onTurnEvent}（与生产事件同构，接线模式同
 * {@code AiChatPanelIpcTurnPresenterTest}）。
 */
class AiChatPanelContextRingTest {

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
        loop = new AgentLoop(new ToolRegistry(Runnable::run),
                memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), new IdleAiService());

        previousJMeterHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tempDir.toString());

        panel = new AiChatPanel();
        JComboBox<?> selector = field(panel, "modelSelector");
        AwaitUtil.awaitUntil(() -> selector.getSelectedItem() != null,
                "loadModelsInBackground.done() landed (model item selected)");
        SwingUtilities.invokeAndWait(() -> { });
        setField(panel, "agentLoop", loop);
        loop.addTurnSubscriber(panel);
    }

    @AfterEach
    void tearDown() throws Exception {
        loop.removeTurnSubscriber(panel);
        AgentLoopFactory.removeTurnSubscriber(panel);
        AwaitUtil.awaitUntil(() -> loop.activeTurn(sessionKey).isEmpty(),
                "active turn drained before teardown");
        loop.shutdown();
        AgentLoopFactory.reset();
        if (previousJMeterHome != null) {
            JMeterUtils.setJMeterHome(previousJMeterHome);
        }
    }

    // ---- ① USAGE 事件：更新 ring、不清 loading、转录无新增行 ----

    @Test
    void usageProgressUpdatesRingWithoutTouchingChatOrLoading() throws Exception {
        JTextPane chatArea = field(panel, "chatArea");
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, loadingIndicatorCount(chatArea), "前置：回合武装后恰一个 loading 指示");
        String htmlBefore = chatTextOnEdt(chatArea);

        panel.onTurnEvent(TurnEvent.progress(turn,
                ProgressUpdate.usage(Map.of("prompt_tokens", 12_345, "completion_tokens", 30))));
        SwingUtilities.invokeAndWait(() -> { });

        ContextUsageRing ring = field(panel, "contextRing");
        int total = AiConfig.getContextWindowTokens();
        int pct = (int) (12_345 / (double) total * 100);
        assertEquals("Context: 12k / " + (total / 1024) + "k (" + pct + "%)", ring.getToolTipText(),
                "tooltip 反映最近一次调用的输入 tokens 与窗口配置（百分比随分母动态推导，防配置默认值变更假红）");
        assertEquals(1, loadingIndicatorCount(chatArea),
                "USAGE 进度不得清除回合的 loading 指示（loading 只由回合生命周期规则管理）");
        assertEquals(htmlBefore, chatTextOnEdt(chatArea),
                "USAGE 进度不得在聊天转录渲染任何文本行");
    }

    // ---- ② 会话重置（"+" 路径）：指示器随转录清空一并复位 ----

    @Test
    void newConversationResetsRing() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        panel.onTurnEvent(TurnEvent.progress(turn,
                ProgressUpdate.usage(Map.of("prompt_tokens", 12_345, "completion_tokens", 30))));
        SwingUtilities.invokeAndWait(() -> { });
        ContextUsageRing ring = field(panel, "contextRing");
        assertTrue(ring.getToolTipText().contains("12k"), "前置：ring 已带用量");

        SwingUtilities.invokeAndWait(() -> invoke(panel, "startNewConversation"));

        assertEquals(0, ring.ratio(), "会话重置后指示器归零");
        assertNull(ring.getToolTipText(), "会话重置后悬浮明细清空");
    }

    // ---- ③ 旧会话迟到 USAGE（活回合集合已清）：不渗入复位后的指示器 ----

    @Test
    void lateUsageAfterResetDoesNotLeakIntoRing() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_CLI, "[from cli] hello", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        SwingUtilities.invokeAndWait(() -> invoke(panel, "startNewConversation"));
        ContextUsageRing ring = field(panel, "contextRing");
        assertEquals(0, ring.ratio(), "前置：重置后指示器为空");

        panel.onTurnEvent(TurnEvent.progress(turn,
                ProgressUpdate.usage(Map.of("prompt_tokens", 99_999, "completion_tokens", 1))));
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(0, ring.ratio(), "重置后旧回合的迟到 USAGE 不得渗入指示器");
        assertNull(ring.getToolTipText());
    }

    // ---- ④ IPC 委派回合：USAGE 同口径刷新（显示域跟随事件流）----

    @Test
    void delegatedTurnUsageUpdatesRing() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.IPC_DELEGATED,
                "[delegated-from peer] analyze", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        panel.onTurnEvent(TurnEvent.progress(turn,
                ProgressUpdate.usage(Map.of("prompt_tokens", 32_000, "completion_tokens", 100))));
        SwingUtilities.invokeAndWait(() -> { });

        ContextUsageRing ring = field(panel, "contextRing");
        int total = AiConfig.getContextWindowTokens();
        assertEquals(32_000 / (double) total, ring.ratio(), 1e-9,
                "委派回合的 USAGE 照常刷新指示器（与本地回合同口径）");
    }

    // ---- helpers（接线模式同 AiChatPanelIpcTurnPresenterTest）----

    private static int loadingIndicatorCount(JTextPane chatArea) {
        return chatTextOnEdt(chatArea).split("AI is thinking", -1).length - 1;
    }

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

    /** 立即应答的 fake（AiChatPanelIpcTurnPresenterTest 同款）。 */
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
}
