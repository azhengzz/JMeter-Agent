package org.gitee.jmeter.ai.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.InterruptStrategy;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.testsupport.RecordingSubscriber;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.protocol.IpcRequest;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code IpcServer.handleAgent} 的 wire 级信封钉子[缺陷12]——spec R9「结果通道语义不变 →
 * Scenario: IPC 响应信封不变」（openspec/changes/unify-turn-event-display/specs/
 * agent-turn-events/spec.md:127-129）：委派回合完成 / 超时 / 被目标用户终止三态的
 * 响应信封与状态字段和事件化之前完全一致（MUST NOT change）。
 *
 * <p>此前该 Scenario 在整个测试树零覆盖（IpcServerCancelledResponseTest 只测静态
 * {@code cancelledResponse} 构造器，IpcClientTest 的 HttpServer 是测试自建假对端，
 * 未经 IpcServer），「双实例委派超时 504」只剩 tasks.md 5.1 的人工冒烟防线。本类把
 * 真实单例 {@link IpcServer} 起在 loopback 临时端口上，经真实 HTTP POST /agent 驱动
 * handleAgent 的 200/504/409 三条路径 + 忙期委派拒绝（200 + success=false），四个用例：
 * <ol>
 *   <li>成功态：200 + success + content + durationMs，成功信封不携带取消三元组
 *       （NON_DEFAULT/NON_NULL 省略——与事件化之前逐字节同形）；</li>
 *   <li>超时态：504 + cancelled=true + cancelReason=timeout，且超时分支的
 *       {@code cancelActiveTask(session, TIMEOUT)} 确已打断 in-flight 回合
 *       （丢掉自取消 = 「CLI 已报错、agent 却继续改测试计划」的状态错位回归）；</li>
 *   <li>目标用户终止态：409 + cancelled=true + cancelReason=cancelled_by_target_user
 *       + partialContent（spec.md:131-133「发起方进度回调保留」：per-turn 回调通道
 *       照常把注入续跑的中间回复喂给 TurnContentAccumulator——丢掉
 *       {@code accumulator::onProgress} 接线时 partialContent 消失）；同时钉
 *       delegated=true → TurnOrigin.IPC_DELEGATED 映射与「wire 409 / 事件流
 *       TURN_CANCELLED 恰一条」并存不干扰；</li>
 *   <li>忙期委派拒绝：200 + success=false + errorMessage 含 busy（事务成功、载荷报错，
 *       不得漂成 409/500；delegated 映射回归为 IPC_CLI 时会被并入注入队列变
 *       success=true——最强的 delegated 接线 oracle）。</li>
 * </ol>
 *
 * <p>本类为 GREEN 守卫（信封现状正确，钉住的是「MUST NOT change」契约的回归防线）。
 *
 * <p>脚手架：GatedScriptAiService（HANG_UNTIL_RELEASED 钉子）+ RecordingSubscriber
 * + NoopTool + Mockito 记忆组件（AgentLoopTurnEventTest 同配方）；loop 经反射注入
 * {@code AgentLoopFactory.instance} 单例，使 handleAgent 的 resolveAgentLoop()
 * 命中受控 loop（绕开 AiServiceFactory 预热）。并发等待全部以 CountDownLatch /
 * future.get 超时为确定性锚，无裸 sleep。
 */
class IpcServerAgentEnvelopeWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long WAIT_SECONDS = 10;
    /** /agent 服务端同步等待上限的属性键（handleAgent 每次请求现读，可按用例改）。 */
    private static final String TIMEOUT_KEY = "jmeter.ai.ipc.agent.timeout.ms";

    /** 类级共享：IPC server 的临时 jmeter home（端口文件落点，避免污染真实安装）。 */
    @TempDir
    static Path sharedDir;

    /** 每测试独立：本用例 loop 的会话/上下文 jsonl 落点。 */
    @TempDir
    Path tempDir;

    private static IpcClient client;
    private static String prevHome;

    AgentLoop loop;
    GatedScriptAiService aiService;
    RecordingSubscriber recorder;
    Object originalFactoryInstance;

    // ---- 类级装配：起真实单例 IpcServer（每测试 JVM 仅一次） ----

    @BeforeAll
    static void bootRealIpcServer() throws Exception {
        ensureJMeterProps();
        // 防御性显式开启 + 固定 token（默认本就 true/随机 token；固定 token 使断言独立于随机性）
        JMeterUtils.setProperty("jmeter.ai.ipc.enabled", "true");
        JMeterUtils.setProperty("jmeter.ai.ipc.token", "wire-test-token");
        // 端口文件写入落点指向临时 home
        prevHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(sharedDir.toString());
        // 预注入空 ToolRegistry：跳过 start() 尾部日志 registry() 触发的默认 JMeter 工具
        // 注册（/agent 路径不使用工具注册表；默认注册含大量 JMeter GUI 工具，headless 无必要）
        Field toolRegistryField = IpcServer.class.getDeclaredField("toolRegistry");
        toolRegistryField.setAccessible(true);
        toolRegistryField.set(IpcServer.getInstance(), new ToolRegistry());

        IpcServer.getInstance().start();
        // token 反射读自活实例：即使单例已被（未来其他用例）先启动也能拿到真值
        client = new IpcClient("127.0.0.1", IpcServer.getInstance().getPort(), readExpectedToken());
    }

    @AfterAll
    static void restoreGlobalState() {
        if (prevHome != null) {
            JMeterUtils.setJMeterHome(prevHome);
        }
        JMeterUtils.getJMeterProperties().remove("jmeter.ai.ipc.enabled");
        JMeterUtils.getJMeterProperties().remove("jmeter.ai.ipc.token");
        // 单例 HttpServer 不停（无公开 stop；executor 为 daemon 线程，不阻 fork 退出）。
        // 本套件仅本类启动它——若日后他类也要驱动 IpcServer，复用本类的反射读取即可。
    }

    // ---- 每测试装配：受控 loop 注入工厂单例（resolveAgentLoop() 直接命中） ----

    @BeforeEach
    void setUpLoop() throws Exception {
        // HANG_UNTIL_RELEASED：signalCancel 的 interrupt 落地后回合体改挂 hang——
        // 「504/409 已回包、垂死回合体仍挂起」的确定性钉子（信封不等回合体收尾）
        aiService = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), aiService);
        recorder = new RecordingSubscriber();
        loop.addTurnSubscriber(recorder);
        originalFactoryInstance = swapFactoryInstance(loop);
    }

    @AfterEach
    void tearDownLoop() throws Exception {
        swapFactoryInstance(originalFactoryInstance);
        loop.shutdown();
    }

    // ---- 1. 成功态信封：200 + content + durationMs，取消三元组整体缺席 ----

    /**
     * spec.md:127-129「IPC 响应信封不变」：完成态。委派方/CLI 收到的 200 信封
     * success/content/durationMs 映射不变，且成功响应不携带 cancelled/cancelReason/
     * partialContent（NON_DEFAULT/NON_NULL 省略——与事件化之前逐字节同形）。
     */
    @Test
    void successEnvelope200CarriesContentAndDuration() throws Exception {
        aiService.script(LLMResponse.text("WIRE-FINAL"));

        HttpResponse<String> http = postRaw("/agent", agentRequest("wire: finish fast", false), 10_000);

        assertEquals(200, http.statusCode(), "completed turn must stay HTTP 200");
        IpcResponse resp = MAPPER.readValue(http.body(), IpcResponse.class);
        assertTrue(resp.isSuccess(), "success envelope keeps success=true");
        assertEquals("WIRE-FINAL", resp.getContent(), "final content must map to the wire content field");
        assertTrue(http.body().contains("\"durationMs\""), "success envelope carries durationMs");
        assertFalse(http.body().contains("\"cancelled\""), "success must not ship the cancelled flag");
        assertFalse(http.body().contains("\"cancelReason\""), "success must not ship cancelReason");
        assertFalse(http.body().contains("\"partialContent\""), "success must not ship partialContent");
    }

    // ---- 2. 超时态信封：504 + typed reason + 自取消 in-flight 回合 ----

    /**
     * spec.md:127-129「IPC 响应信封不变」：超时态。504 + cancelled=true +
     * cancelReason=timeout（常量 {@link IpcResponse#CANCEL_REASON_TIMEOUT}="timeout"）；
     * 无注入续跑 → 累积器为空 → partialContent 字段整体省略（非空串上线）。
     * 同时钉 handleAgent 超时分支的副作用：cancelActiveTask(session, TIMEOUT) 必须
     * 打断 in-flight 调用——否则 agent 在 CLI 已收 504 后继续跑完并改测试计划树
     * （「报错却已生效」的状态错位回归）。
     */
    @Test
    void timeoutReturns504WithTypedReasonAndCancelsInFlightTurn() throws Exception {
        GatedCall held = aiService.scriptGated(LLMResponse.text("late-final"));
        // 服务端等待上限 4s：远大于回合 pickup（毫秒级），保证计时器到期时回合确定性地 in-flight
        JMeterUtils.setProperty(TIMEOUT_KEY, "4000");
        try {
            CompletableFuture<HttpResponse<String>> post = CompletableFuture.supplyAsync(
                    () -> postRaw("/agent", agentRequest("hold past the timer", false), 20_000));
            // 确定性锚：回合确已 pickup 并进入第一次 LLM 调用（此刻服务端计时器仍在倒数）
            assertTrue(held.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the gated LLM call must be in flight before the server-side timer expires");

            HttpResponse<String> http = post.get(15, TimeUnit.SECONDS);
            assertEquals(504, http.statusCode(),
                    "timeout envelope must stay HTTP 504 (not 500/409 drift)");
            IpcResponse resp = MAPPER.readValue(http.body(), IpcResponse.class);
            assertFalse(resp.isSuccess());
            assertTrue(resp.isCancelled(), "timeout body must carry cancelled=true");
            assertEquals(IpcResponse.CANCEL_REASON_TIMEOUT, resp.getCancelReason(),
                    "timeout self-cancel must map to cancelReason=timeout, "
                            + "not cancelled_by_target_user");
            assertNotNull(resp.getError());
            assertTrue(resp.getError().contains("timeout"), "error text names the timeout: " + resp.getError());
            assertFalse(http.body().contains("\"partialContent\""),
                    "empty accumulation must omit partialContent entirely: " + http.body());

            // 确定性锚 2：504 分支确已 interrupt in-flight 调用（cancelActiveTask 接线在场）。
            // interrupt 发生在响应回包之前，此 await 立即返回；若超时不再自取消则 10s 超时红
            assertTrue(held.interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the 504 branch must interrupt the in-flight LLM call "
                            + "(cancelActiveTask(session, TIMEOUT) wiring)");
        } finally {
            JMeterUtils.getJMeterProperties().remove(TIMEOUT_KEY);
            held.hang.countDown(); // HANG 钉子放行：垂死回合体收尾（信封此刻早已发出）
        }
    }

    // ---- 3. 目标用户终止态信封：409 + USER_STOP reason + 累积器 partialContent ----

    /**
     * spec.md:127-129「IPC 响应信封不变」：被目标用户终止态。409 + cancelled=true +
     * cancelReason=cancelled_by_target_user + partialContent。
     *
     * <p>partialContent 的产生链（spec.md:131-133「发起方进度回调保留」）：门控第一次
     * LLM 调用 → 测试忙期注入 → 注入检查点发布中间回复 PARTIAL-1（经 per-turn 回调
     * 喂给 TurnContentAccumulator）→ 第二次 LLM 调用 in-flight 时目标用户 Stop
     * （面板 stopActiveTask 同款 AgentLoopFactory.signalCancelAny 路由）→ 409 回执
     * 携带累积快照。丢掉 {@code accumulator::onProgress} 接线时 partialContent 为 null。
     *
     * <p>顺带钉：delegated=true → TurnOrigin.IPC_DELEGATED 映射（spec.md:16-19），
     * 以及 wire 收 409 的同一取消在事件流上恰出一条 TURN_CANCELLED（两通道并存不干扰）。
     */
    @Test
    void targetUserStopReturns409WithPartialContentFromPerTurnCallback() throws Exception {
        String session = InstanceContext.currentSessionKey();
        GatedCall call1 = aiService.scriptGated(LLMResponse.text("PARTIAL-1"));
        GatedCall call2 = aiService.scriptGated(LLMResponse.text("never-reaches-wire"));

        CompletableFuture<HttpResponse<String>> post = CompletableFuture.supplyAsync(
                () -> postRaw("/agent", agentRequest("[delegated-from wire-test] tune the plan", true), 20_000));
        assertTrue(call1.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the delegated turn must be in flight");
        // 忙期注入：直连 loop 的生产注入入口（processMessage 忙期走 Phase 2 offer，
        // 本地源拿注入 ack；委派消息并发 POST 会被硬拒绝，故不走 HTTP）
        AgentResponse injAck = loop.processMessage("mid-turn follow-up", session)
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(injAck.isSuccess() && injAck.getContent() != null
                        && injAck.getContent().startsWith("Message injected"),
                "injection must queue on the in-flight turn");
        call1.release.countDown();
        // 确定性锚：注入检查点已发布中间回复 PARTIAL-1 并进入第二次 LLM 调用
        assertTrue(call2.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the injection checkpoint must publish the intermediate response and continue");

        // 目标实例用户点 Stop（AiChatPanel.stopActiveTask 同款工厂路由，缺省 cause=USER_STOP）。
        // future.cancel(true) 同步解除 ipc-worker 的 future.get 阻塞 → 409 立即回包
        assertTrue(AgentLoopFactory.signalCancelAny(session), "the turn must be cancellable");
        assertTrue(call2.interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS));

        HttpResponse<String> http = post.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(409, http.statusCode(),
                "user-stop envelope must stay HTTP 409 "
                        + "(not 500 'server error: null' from the outer catch)");
        IpcResponse resp = MAPPER.readValue(http.body(), IpcResponse.class);
        assertFalse(resp.isSuccess());
        assertTrue(resp.isCancelled());
        assertEquals(IpcResponse.CANCEL_REASON_USER_STOP, resp.getCancelReason(),
                "target-user stop must map to cancelReason=cancelled_by_target_user");
        assertEquals("PARTIAL-1", resp.getPartialContent(),
                "partialContent must come from the accumulator wired through the per-turn "
                        + "callback (turnCallback wiring)");
        assertTrue(http.body().contains("\"partialContent\":\"PARTIAL-1\""),
                "raw JSON must ship the partial: " + http.body());

        // 姊妹钉：同一取消在事件流上恰出一条 TURN_CANCELLED（wire 与事件流互不干扰）
        assertEquals(1, recorder.count(TurnEvent.Kind.TURN_CANCELLED));
        assertEquals(TurnOrigin.IPC_DELEGATED, recorder.lastStarted().turn().origin(),
                "delegated=true must map to TurnOrigin.IPC_DELEGATED");

        call2.hang.countDown(); // 放行垂死回合体（信封已断言完毕）
    }

    // ---- 4. 忙期委派拒绝：200 + success=false + errorMessage（事务成功、载荷报错） ----

    /**
     * spec.md:127-129（完成态信封语义）：同会话忙期的委派请求是「HTTP 事务成功、载荷
     * 报错」——200 + success=false + errorMessage 含 busy，不得漂成 409（409 专属
     * 「被目标用户终止」）或 500。
     *
     * <p>这是 delegated→TurnOrigin 映射最强的 wire 级 oracle：若映射回归为 IPC_CLI
     * （delegated 语义丢失），忙期委派消息会被并入注入队列返回 success=true
     * （"Message injected..."），本用例的 success=false 断言即红；REJECTED_BUSY
     * 事件计数同步为 0 亦红。
     */
    @Test
    void delegatedBusyRejectionIsTransportSuccessWithTypedError() throws Exception {
        String session = InstanceContext.currentSessionKey();
        GatedCall busyCall = aiService.scriptGated(LLMResponse.text("busy turn final"));
        CompletableFuture<HttpResponse<String>> busyPost = CompletableFuture.supplyAsync(
                () -> postRaw("/agent", agentRequest("cli keeps this turn busy", false), 20_000));
        assertTrue(busyCall.entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the first turn must be in flight");

        // 同会话并发委派：Phase 2 对 delegated 源硬拒绝（不进注入队列）
        HttpResponse<String> http = postRaw("/agent",
                agentRequest("[delegated-from wire-test] task B", true), 10_000);

        assertEquals(200, http.statusCode(), "busy rejection is a successful transport transaction");
        IpcResponse resp = MAPPER.readValue(http.body(), IpcResponse.class);
        assertFalse(resp.isSuccess(), "busy rejection reports success=false in the envelope");
        assertNotNull(resp.getErrorMessage());
        assertTrue(resp.getErrorMessage().contains("busy"),
                "errorMessage names the busy state: " + resp.getErrorMessage());
        assertFalse(resp.isCancelled(), "busy rejection is not a cancellation");
        assertFalse(http.body().contains("\"cancelReason\""));
        assertFalse(http.body().contains("\"partialContent\""));
        assertEquals(1, recorder.count(TurnEvent.Kind.REJECTED_BUSY),
                "the delegated-origin busy rejection must raise REJECTED_BUSY on the event bus");

        // 收尾：停掉 busy 回合并放行 HANG；该 POST 以 409 收场（信封已由用例 3 钉死，此处仅排水）
        assertTrue(AgentLoopFactory.signalCancelAny(session));
        busyCall.hang.countDown();
        busyPost.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- helpers ----

    /** 组装 /agent 请求体；session 取当前实例会话键（事件可派发，wire 断言不受影响）。 */
    private static IpcRequest agentRequest(String message, boolean delegated) {
        IpcRequest req = new IpcRequest();
        req.setOp("agent");
        req.setMessage(message);
        req.setSession(InstanceContext.currentSessionKey());
        req.setDelegated(delegated);
        return req;
    }

    /** client.post 的异常包装（供 supplyAsync lambda 使用）。 */
    private static HttpResponse<String> postRaw(String endpoint, IpcRequest req, long timeoutMs) {
        try {
            return client.post(endpoint, req, timeoutMs);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    /** 反射确保 {@code JMeterUtils.appProperties} 非空（AiConfigTest 同款），否则 setProperty NPE。 */
    private static void ensureJMeterProps() throws Exception {
        Field f = JMeterUtils.class.getDeclaredField("appProperties");
        f.setAccessible(true);
        if (f.get(null) == null) {
            f.set(null, new Properties());
        }
    }

    /** 反射读取单例 server 实际生效的鉴权 token（已启动则非我们配置的值也能拿到）。 */
    private static String readExpectedToken() throws Exception {
        Field f = IpcServer.class.getDeclaredField("expectedToken");
        f.setAccessible(true);
        return (String) f.get(IpcServer.getInstance());
    }

    /** 反射替换 {@link AgentLoopFactory} 私有静态单例（AgentLoopTurnEventTest 同款），返回旧值。 */
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
