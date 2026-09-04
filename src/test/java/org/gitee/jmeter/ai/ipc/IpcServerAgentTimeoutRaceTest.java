package org.gitee.jmeter.ai.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.testsupport.RecordingSubscriber;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.protocol.IpcRequest;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /agent 超时分支的 TOCTOU 对抗测试（wire 镜头）[缺陷19]。
 *
 * <p>被攻击的交错：回合恰在 handler 的 {@code future.get(timeout)} 抛出 TimeoutException
 * 之后、{@code cancelActiveTask} 内部 {@code signalCancel} 读表之前自然完成——生产环境该窗口
 * 只有微秒级，本测试用「子类覆写 {@code cancelActiveTask} 入口挂闩」把它撑开成可编排的窗口
 * （super() 语义分毫未动，只是晚一点执行），从而确定性复现：取消空转（signalCancel 摘表
 * 扑空返回 false、无 TURN_CANCELLED，终态已由回合体 try 尾认领为 TURN_COMPLETED），而
 * handler 不复查 future 即回 504『turn cancelled』——正是 IpcServer.java:301-302 注释声称
 * 要消灭的『报错却已生效』状态错位。
 *
 * <p>钉住的 spec.md 契约（openspec/changes/unify-turn-event-display/specs/agent-turn-events/）：
 * <ul>
 *   <li>「Requirement: 结果通道语义不变」→ Scenario「IPC 响应信封不变」——完成的回合必须以
 *       成功信封（200 + 完整内容、非 cancelled）回报，信封须与回合实际终态一致；</li>
 *   <li>「Requirement: 终态恰好一次」——事件流恰一条终态 TURN_COMPLETED、零 TURN_CANCELLED，
 *       作为 wire 断言的 ground truth 对照。</li>
 * </ul>
 *
 * <p>构造配方与 AgentLoopTurnEventTest 相同（Mockito MemoryStore + ToolRegistry(Runnable::run)
 * + new AgentLoop(...)），wire 半边走真实 HTTP（自建 loopback HttpServer 反射调用私有
 * handleAgent），不 start() 单例 IpcServer（不占端口/不写端口文件/不挂 shutdown hook）。
 */
class IpcServerAgentTimeoutRaceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "unit-test-ipc-token";
    private static final long TIMEOUT_SECONDS = 10;

    /** /agent 同步等待压到 300ms：future.get 的 TimeoutException 在测试时间尺度内出现。 */
    private static final long IPC_TIMEOUT_MS = 300;
    private static final String TIMEOUT_PROP_KEY = "jmeter.ai.ipc.agent.timeout.ms";

    @TempDir
    Path tempDir;

    Properties jmeterProps;
    String prevTimeoutProp;
    HttpServer server;
    LatchedCancelAgentLoop loop;
    GatedScriptAiService aiService;
    RecordingSubscriber recorder;

    @BeforeEach
    void setUp() throws Exception {
        // 1) JMeter 属性：纯单测里 JMeterUtils.appProperties 为 null（见项目记忆），反射初始化
        //    后才能写 jmeter.ai.ipc.agent.timeout.ms（保存旧值供 @AfterEach 恢复，防污染同
        //    JVM 后续测试读到的 120s 默认）
        jmeterProps = ensureJMeterProperties();
        prevTimeoutProp = jmeterProps.getProperty(TIMEOUT_PROP_KEY);
        jmeterProps.setProperty(TIMEOUT_PROP_KEY, String.valueOf(IPC_TIMEOUT_MS));

        // 2) AgentLoopTurnEventTest 同配方的 loop + 两个确定性钩子（见 LatchedCancelAgentLoop）
        aiService = new GatedScriptAiService();
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        loop = new LatchedCancelAgentLoop(registry, memoryStore,
                Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), aiService);
        recorder = new RecordingSubscriber();
        loop.addTurnSubscriber(recorder);

        // 3) 单例 IpcServer：仅反射写入 expectedToken 供 tokenOk 校验；不 start()
        Field tokenField = IpcServer.class.getDeclaredField("expectedToken");
        tokenField.setAccessible(true);
        tokenField.set(IpcServer.getInstance(), TOKEN);

        // 4) 真实 HTTP：测试自建 loopback HttpServer，/agent 路由反射调用私有 handleAgent。
        //    HttpExchange/信封序列化/token 鉴权全部为真；executor 模拟 ipc-worker 单线程
        Method handleAgent = IpcServer.class.getDeclaredMethod("handleAgent", HttpExchange.class);
        handleAgent.setAccessible(true);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/agent", ex -> {
            try {
                handleAgent.invoke(IpcServer.getInstance(), ex);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-ipc-worker");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        // 防御性放行：任何断言失败也不把 handler 线程留在闩上（否则 server.stop 被拖住）
        if (loop != null) {
            loop.cancelProceed.countDown();
            loop.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
        if (jmeterProps != null) {
            if (prevTimeoutProp == null) {
                jmeterProps.remove(TIMEOUT_PROP_KEY);
            } else {
                jmeterProps.setProperty(TIMEOUT_PROP_KEY, prevTimeoutProp);
            }
        }
    }

    // ---- 被攻击的缺陷[19]：超时分支 TOCTOU——回合在取消生效前已完整完成，wire 仍谎报 504 cancelled ----

    /**
     * 回合在 future.get 超时抛出之后、signalCancel 读表之前自然完整完成时：
     * 事件流 ground truth = TURN_COMPLETED 恰一次、零 TURN_CANCELLED、future 正常完成、
     * 取消空转（cancelActiveTask 返回 false）；wire 信封必须与之一致——
     * 200 + success + 完整内容 + 不得声称 cancelled（spec「结果通道语义不变/IPC 响应信封不变」）。
     * 当前缺陷代码无视取消空转结果仍回 504『turn cancelled』，丢弃已完整生效的 AgentResponse。
     */
    @Test
    void timeoutBranchMustNotClaimCancelledWhenTurnCompletedInCancelWindow() throws Exception {
        String session = InstanceContext.currentSessionKey();
        Object previousFactoryInstance = swapFactoryInstance(loop);
        try {
            // 门控脚本：本回合唯一一次 LLM 调用挂住——回合在测试放行前不可能完成，
            // handler 的 future.get(300ms) 因此必然走 TimeoutException 分支（非竞速，是钉死）
            GatedCall llmCall = aiService.scriptGated(LLMResponse.text("TIMEOUT-RACE-FINAL"));

            // CLI 直连请求（delegated=false）异步发出；handler 在 fake-ipc-worker 上走真实路径
            CompletableFuture<HttpResponse<byte[]>> httpFuture = sendAgentRequest(session);

            // 锚点 1（确定性）：回合已开跑、LLM 调用在飞——future.get 正在超时倒计时
            assertTrue(llmCall.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "turn must be in flight before the handler's get() can time out");

            // 锚点 2（确定性）：handler 已抛 TimeoutException 并进入超时分支的 cancelActiveTask。
            // 此刻回合尚未完成 = 生产环境『get() 抛出 → signalCancel 读表』TOCTOU 窗口内
            assertTrue(loop.cancelEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "handler must have taken the TimeoutException branch and entered cancelActiveTask");
            CompletableFuture<AgentResponse> turnFuture = loop.lastTurnFuture;
            assertNotNull(turnFuture, "handler-side processMessage future must have been captured");
            assertFalse(turnFuture.isDone(),
                    "precondition: turn must still be in flight inside the race window");

            // 窗口内让回合自然完整完成（生产对应：最后一次 LLM 返回恰落在该窗口——
            // emitTerminal 发 TURN_COMPLETED、jsonl 落盘、future.complete、收尾 finally 摘表）
            llmCall.release.countDown();

            // 锚点 3（确定性）：future 正常完成 + 收尾 finally 完毕——
            // waitForCancellation 等的 completionLatch 在任务体外层 finally 里
            // 先按值摘表再倒数，返回 true 即「摘表/倒数均已完成」，此后 signalCancel 必然空转
            AwaitUtil.awaitUntil(
                    () -> turnFuture.isDone() && !turnFuture.isCompletedExceptionally(),
                    "the turn must complete normally inside the race window");
            assertTrue(loop.waitForCancellation(session, TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "whenComplete teardown must be finished before the cancel proceeds");

            // ---- 事实半边（ground truth；缺陷与修复后都必须成立）----
            // spec「终态恰好一次」：终态恰一条且为 TURN_COMPLETED；零 TURN_CANCELLED
            long turnId = recorder.lastCompleted().turn().id();
            assertEquals(1, recorder.terminalCountFor(turnId),
                    "terminal must be emitted exactly once (spec: 终态恰好一次)");
            assertEquals(0, recorder.count(TurnEvent.Kind.TURN_CANCELLED),
                    "the no-op cancel must not raise TURN_CANCELLED for a completed turn");
            assertEquals("TIMEOUT-RACE-FINAL", recorder.lastCompleted().response().getContent());

            // 放行取消：super.cancelActiveTask 空转（注册表条目已被收尾 finally 的
            // removeIfCurrent 按值摘除 → signalCancel 返回 false、无 TURN_CANCELLED）
            loop.cancelProceed.countDown();

            HttpResponse<byte[]> raw = httpFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // ---- wire 半边（红点所在）——spec「结果通道语义不变」Scenario「IPC 响应信封不变」：
            // 完成的回合必须按成功信封回报，信封与状态字段须与回合实际终态一致 ----
            assertEquals(200, raw.statusCode(),
                    "a turn that completed fully inside the timeout race window must be answered 200, "
                            + "not a 504 'turn cancelled' — the '报错却已生效' mismatch the branch "
                            + "comment (IpcServer.java:301-302) exists to eliminate");
            IpcResponse body = MAPPER.readValue(raw.body(), IpcResponse.class);
            assertFalse(body.isCancelled(),
                    "wire must not claim cancelled for a fully-completed turn (spec: IPC 响应信封不变)");
            assertTrue(body.isSuccess(), "the completed turn's envelope must be a success envelope");
            assertEquals("TIMEOUT-RACE-FINAL", body.getContent(),
                    "the completed AgentResponse must not be discarded by the timeout branch");

            // 复现证据（非断言目标的行为契约，缺陷与修复后同为 false）：取消确为空转，并非真取消
            assertFalse(loop.superCancelReturned.get(),
                    "cancel must have been a no-op (session maps already drained by whenComplete)");
        } finally {
            loop.cancelProceed.countDown(); // 防御：断言失败也放行 handler 线程
            swapFactoryInstance(previousFactoryInstance);
        }
    }

    // ---- scaffolding ----

    /**
     * 与 setUp 同配方 + 两个确定性钩子（撑开 TOCTOU 窗口的唯一测试侧改动，super() 语义不变）：
     * <ul>
     *   <li>覆写 4 参 {@code processMessage}：捕获 handler 侧回合 future——
     *       「回合实际已完整生效」的断言事实来源；</li>
     *   <li>覆写 {@code cancelActiveTask}（IpcServer 超时分支调的正是它）：进入即计数
     *       {@code cancelEntered}（= handler 已走 TimeoutException 分支的确定性证据），
     *       再挂 {@code cancelProceed}——窗口宽度由测试编排，窗口内放行 LLM 即命中缺陷交错。</li>
     * </ul>
     */
    private static final class LatchedCancelAgentLoop extends AgentLoop {
        /** handler 进入超时分支 cancelActiveTask 的确定性信号（TOCTOU 窗口起点）。 */
        final CountDownLatch cancelEntered = new CountDownLatch(1);
        /** 测试放行后 super.cancelActiveTask 才执行（窗口宽度=测试可控）。 */
        final CountDownLatch cancelProceed = new CountDownLatch(1);
        /** handler 侧最近一次 4 参 processMessage 返回的回合 future。 */
        final AtomicBoolean superCancelReturned = new AtomicBoolean(true);
        volatile CompletableFuture<AgentResponse> lastTurnFuture;

        LatchedCancelAgentLoop(ToolRegistry toolRegistry, MemoryStore memoryStore,
                               MemoryConsolidator memoryConsolidator, ContextBuilder contextBuilder,
                               SessionManager sessionManager, AiService aiService) {
            super(toolRegistry, memoryStore, memoryConsolidator, contextBuilder,
                    sessionManager, aiService);
        }

        @Override
        public CompletableFuture<AgentResponse> processMessage(String message, String sessionKey,
                                                               AgentLoop.ProgressCallback callback,
                                                               org.gitee.jmeter.ai.agent.presenter.TurnOrigin origin) {
            CompletableFuture<AgentResponse> future =
                    super.processMessage(message, sessionKey, callback, origin);
            lastTurnFuture = future;
            return future;
        }

        @Override
        public boolean cancelActiveTask(String sessionKey, CancelCause cause) {
            cancelEntered.countDown();
            try {
                // 有界等待：即使测试侧失手不放行也不挂死 handler 线程
                //noinspection ResultOfMethodCallIgnored
                cancelProceed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean result = super.cancelActiveTask(sessionKey, cause);
            superCancelReturned.set(result);
            return result;
        }
    }

    /** CLI 直连 /agent 请求（真实 HttpClient → 真实 HttpServer → 反射 handleAgent）。 */
    private CompletableFuture<HttpResponse<byte[]>> sendAgentRequest(String session) throws Exception {
        IpcRequest req = new IpcRequest();
        req.setOp("agent");
        req.setMessage("race the timeout boundary");
        req.setDelegated(false); // CLI 直连：TurnOrigin.IPC_CLI + 消息加 [from cli] 前缀
        req.setSession(session); // 显式当前实例会话键：事件按 spec headless 边界派发给 recorder
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/agent"))
                .header("X-IPC-Token", TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(req)))
                .build();
        return HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 反射确保 JMeterUtils.appProperties 非空（AiConfigTest 同款配方），返回其引用供写/恢复。 */
    private static Properties ensureJMeterProperties() throws Exception {
        Field f = JMeterUtils.class.getDeclaredField("appProperties");
        f.setAccessible(true);
        Properties props = (Properties) f.get(null);
        if (props == null) {
            props = new Properties();
            f.set(null, props);
        }
        return props;
    }

    /** 反射替换 AgentLoopFactory 私有静态单例（AgentLoopTurnEventTest.swapFactoryInstance 同款）。 */
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
