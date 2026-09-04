package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 特征测试（characterization）：锁定 {@code runAgentLoop} 的 hook 发射时序、注入检查点
 * 异构路径与 drain6 行为的<b>现状</b>（refactor-agent-loop-turn-centric tasks 2.1）。
 *
 * <p>本组在巨方法分解（LoopState / handleToolCallsBranch / checkpoint）<b>之前</b>入仓，
 * 断言全部对照当前未分解实现手工推演——分解后这些断言一字不改必须仍然全绿；任何一条
 * 变红都说明分解改变了行为（如检查点位置移动、hook 差异变体被拉平），须回退或单独说明。
 *
 * <p>驱经公开入口 {@link AgentRunner#run}（同步直调，测试线程上跑完全程），脚本化
 * {@link GatedScriptAiService} 供 LLM 应答序列，真实 {@link ContextBuilder}（临时
 * workspace）+ 空注册表 + noop/boom 工具。会话走 {@code persistSession(false)}（非
 * subagent 前缀、免持久化），初始消息 {@code [user("hi")]} 使消息结构断言不依赖
 * ContextBuilder 的 system/runtime-context 包装细节。
 */
class AgentRunnerLoopSequencingTest {

    private static final String MAX_ITER_MSG =
        "I reached the maximum number of tool call iterations. Please try breaking the task into smaller steps.";

    /** 立即失败的固定工具（failOnToolError 路径脚手架）。 */
    private static final class BoomTool implements Tool {
        @Override public String getName() { return "boom_tool"; }
        @Override public String getDescription() { return "always fails"; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.error("disk on fire");
        }
    }

    /** 依序供数的注入回调：按调用次序返回构造时给定的各组消息，耗尽后返回空表。 */
    private static final class ScriptedInjections implements Function<Integer, List<String>> {
        private final List<List<String>> rounds;
        private int consumed;
        int invocations;

        @SafeVarargs
        ScriptedInjections(List<String>... rounds) {
            this.rounds = List.of(rounds);
        }

        @Override
        public List<String> apply(Integer maxMessages) {
            invocations++;
            int idx = consumed++;
            return idx < rounds.size() ? rounds.get(idx) : List.of();
        }
    }

    /** 记录 hook 发射序（finalizeContent 可选做前缀变换以锁定其变换时机）。 */
    private static final class RecordingHook implements AgentHook {
        final List<String> events = new ArrayList<>();
        private final String finalizePrefix; // null = 恒等返回

        RecordingHook() {
            this(null);
        }

        RecordingHook(String finalizePrefix) {
            this.finalizePrefix = finalizePrefix;
        }

        @Override public void beforeIteration(AgentHookContext ctx) {
            events.add("beforeIteration:" + ctx.getCurrentIteration());
        }
        @Override public void afterIteration(AgentHookContext ctx) {
            events.add("afterIteration:" + ctx.getCurrentIteration());
        }
        @Override public void beforeExecuteTools(List<ToolCall> toolCalls, AgentHookContext ctx) {
            events.add("beforeExecuteTools");
        }
        @Override public void afterExecuteTools(List<ToolCall> toolCalls, AgentHookContext ctx) {
            events.add("afterExecuteTools");
        }
        @Override public void onError(Throwable error, AgentHookContext ctx) {
            events.add("onError");
        }
        @Override public void onIntermediateResponse(String content, AgentHookContext ctx) {
            events.add("onIntermediateResponse:" + content);
        }
        @Override public String finalizeContent(String content, AgentHookContext ctx) {
            events.add("finalizeContent:" + content);
            return finalizePrefix == null ? content : finalizePrefix + content;
        }
    }

    // ---- 场景 A：工具迭代 → 终答（无注入基线） ----

    /**
     * 终答迭代的 break 路径<b>不发射</b> afterIteration（循环体末尾的 afterIteration 只在
     * 非继续、非 break 的自然落穿路径命中）——inj1 无注入时工具迭代正常落穿发射。
     */
    @Test
    void toolIterationThenFinalResponse_noInjection() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.withToolCalls(List.of(new ToolCall("noop_tool", Map.of())), "t1"));
        ai.script(LLMResponse.text("FINAL"));
        RecordingHook hook = new RecordingHook();

        AgentRunResult r = run(ai, hook, null, spec -> spec.maxIterations(5));

        assertEquals(List.of(
            "beforeIteration:1", "beforeExecuteTools", "afterExecuteTools", "afterIteration:1",
            "beforeIteration:2", "finalizeContent:FINAL"), hook.events);
        assertEquals("FINAL", r.getContent());
        assertEquals(2, r.getIterationCount());
        assertEquals(List.of("noop_tool"), r.getToolsUsed());
        assertFalse(r.hadInjections());
        assertTrue(r.isSuccess());
        assertEquals(List.of("USER", "ASSISTANT", "TOOL", "ASSISTANT"), roles(r));
        assertEquals(List.of("hi", "t1", "ok", "FINAL"), contents(r));
    }

    // ---- 场景 B：inj2（终答后注入）→ onIntermediateResponse + finalContent 清空 ----

    /**
     * inj2 继续路径独有：先 onIntermediateResponse(中间答)、再清 finalContent、再
     * afterIteration——下一迭代重新起 LLM 调用；中间答本身已 append（assistant → 注入
     * user 保持角色交替）。
     */
    @Test
    void finalResponseInjection_emitsIntermediateResponse_andClearsFinalContent() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.text("intermediate"));
        ai.script(LLMResponse.text("FINAL"));
        RecordingHook hook = new RecordingHook();
        ScriptedInjections inj = new ScriptedInjections(List.of("inj"));

        AgentRunResult r = run(ai, hook, inj, spec -> spec.maxIterations(5));

        assertEquals(List.of(
            "beforeIteration:1", "onIntermediateResponse:intermediate", "afterIteration:1",
            "beforeIteration:2", "finalizeContent:FINAL"), hook.events);
        assertEquals("FINAL", r.getContent());
        assertTrue(r.hadInjections());
        assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT"), roles(r));
        assertEquals(List.of("hi", "intermediate", "inj", "FINAL"), contents(r));
    }

    // ---- 场景 C：inj1（工具执行后注入）----

    /**
     * inj1 继续路径：完整工具仪式（before/afterExecuteTools）后注入 user 消息，无
     * onIntermediateResponse（hook 差异变体之二）。
     */
    @Test
    void afterToolExecutionInjection_appendsUserMessage_andContinues() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.withToolCalls(List.of(new ToolCall("noop_tool", Map.of())), "t1"));
        ai.script(LLMResponse.text("FINAL"));
        RecordingHook hook = new RecordingHook();
        ScriptedInjections inj = new ScriptedInjections(List.of("inj"));

        AgentRunResult r = run(ai, hook, inj, spec -> spec.maxIterations(5));

        assertEquals(List.of(
            "beforeIteration:1", "beforeExecuteTools", "afterExecuteTools", "afterIteration:1",
            "beforeIteration:2", "finalizeContent:FINAL"), hook.events);
        assertEquals("FINAL", r.getContent());
        assertEquals(List.of("noop_tool"), r.getToolsUsed());
        assertTrue(r.hadInjections());
        assertEquals(List.of("USER", "ASSISTANT", "TOOL", "USER", "ASSISTANT"), roles(r));
        assertEquals(List.of("hi", "t1", "ok", "inj", "FINAL"), contents(r));
    }

    // ---- 场景 D：inj5（空回复注入）——并入最后一条 user，无 assistant 占位 ----

    /**
     * 空回复的注入继续发生在 append assistant 之前：注入并入<b>最后一条 user</b>（"\n\n"
     * 合并保角色交替），该迭代不产生任何 assistant 消息，也不发射 onIntermediateResponse。
     */
    @Test
    void emptyResponseInjection_mergesIntoLastUserMessage_noAssistantAppended() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.text(""));
        ai.script(LLMResponse.text("FINAL"));
        RecordingHook hook = new RecordingHook();
        ScriptedInjections inj = new ScriptedInjections(List.of("inj"));

        AgentRunResult r = run(ai, hook, inj, spec -> spec.maxIterations(5));

        assertEquals(List.of(
            "beforeIteration:1", "afterIteration:1",
            "beforeIteration:2", "finalizeContent:FINAL"), hook.events);
        assertEquals("FINAL", r.getContent());
        assertTrue(r.hadInjections());
        assertEquals(List.of("USER", "ASSISTANT"), roles(r));
        assertEquals(List.of("hi\n\ninj", "FINAL"), contents(r));
    }

    // ---- 场景 E/E2：inj4（LLM 错误后注入）——错误串不 append、finalContent 不清空 ----

    /**
     * inj4 继续路径：错误回复不 append 任何消息（注入并入最后一条 user），finalContent
     * 保留错误串<b>不清空</b>（与 inj2 相反）——由下一迭代的正常回复覆盖；onError 每次
     * 错误恰发射一次。
     */
    @Test
    void llmErrorInjection_continuesWithoutClearingFinalContent_errorNotAppended() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.error("boom"));
        ai.script(LLMResponse.text("FINAL"));
        RecordingHook hook = new RecordingHook();
        ScriptedInjections inj = new ScriptedInjections(List.of("inj"));

        AgentRunResult r = run(ai, hook, inj, spec -> spec.maxIterations(5));

        assertEquals(List.of(
            "beforeIteration:1", "onError", "afterIteration:1",
            "beforeIteration:2", "finalizeContent:FINAL"), hook.events);
        assertEquals("FINAL", r.getContent());
        assertTrue(r.hadInjections());
        assertEquals(List.of("USER", "ASSISTANT"), roles(r));
        assertEquals(List.of("hi\n\ninj", "FINAL"), contents(r));
    }

    /** inj4 无注入直接 break：错误串本身经 finalizeContent 出结果，success 仍为 true。 */
    @Test
    void llmErrorWithoutInjection_breaksWithErrorContent() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.error("boom"));
        RecordingHook hook = new RecordingHook();

        AgentRunResult r = run(ai, hook, null, spec -> spec.maxIterations(5));

        assertEquals(List.of(
            "beforeIteration:1", "onError",
            "finalizeContent:I encountered an error: boom"), hook.events);
        assertEquals("I encountered an error: boom", r.getContent());
        assertEquals(1, r.getIterationCount());
        assertTrue(r.isSuccess());
        assertEquals(List.of("USER"), roles(r));
    }

    // ---- 场景 F/F2：inj3（failOnToolError 致命错误后注入）——afterIteration 先于检查点 ----

    /**
     * inj3 路径的 hook 时序异构：afterIteration 在<b>检查点之前</b>发射（致命错误块内），
     * afterExecuteTools 被跳过；工具名不计入 toolsUsed、工具结果不 append（assistant
     * 携带 toolCalls 悬挂）；stopReason=tool_error 跨迭代保留。
     */
    @Test
    void failOnToolErrorInjection_afterIterationEmittedBeforeCheckpoint_toolResultNotAppended() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.withToolCalls(List.of(new ToolCall("boom_tool", Map.of())), "t"));
        ai.script(LLMResponse.text("FINAL"));
        RecordingHook hook = new RecordingHook();
        ScriptedInjections inj = new ScriptedInjections(List.of("inj"));

        AgentRunResult r = run(ai, hook, inj, spec -> spec.maxIterations(5).failOnToolError(true));

        assertEquals(List.of(
            "beforeIteration:1", "beforeExecuteTools", "afterIteration:1",
            "beforeIteration:2", "finalizeContent:FINAL"), hook.events);
        assertEquals("FINAL", r.getContent());
        assertTrue(r.getToolsUsed().isEmpty(), "致命错误迭代的工具名不计入 toolsUsed");
        assertEquals("tool_error", r.getStopReason());
        assertTrue(r.hadInjections());
        assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT"), roles(r));
        assertTrue(r.getCurrentMessages().get(1).hasToolCalls(), "assistant 消息悬挂 toolCalls");
    }

    /** inj3 无注入 break：afterIteration（块内那次）仍发射，错误串经 finalizeContent 出结果。 */
    @Test
    void failOnToolErrorWithoutInjection_breaksAfterInBlockAfterIteration() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.withToolCalls(List.of(new ToolCall("boom_tool", Map.of())), "t"));
        RecordingHook hook = new RecordingHook();

        AgentRunResult r = run(ai, hook, null, spec -> spec.maxIterations(5).failOnToolError(true));

        assertEquals(List.of(
            "beforeIteration:1", "beforeExecuteTools", "afterIteration:1",
            "finalizeContent:Error: Tool execution failed: boom_tool: disk on fire"), hook.events);
        assertEquals("Error: Tool execution failed: boom_tool: disk on fire", r.getContent());
        assertEquals("tool_error", r.getStopReason());
    }

    // ---- 场景 G：drain6（maxIterations 后收尾抽干）——只 append 永不 continue ----

    /**
     * drain6 与 5 处检查点异构（design D4，保留手写不进 checkpoint）：绕过
     * MAX_INJECTION_CYCLES 上限、直接 append、永不 continue；抽干消息并入最后一条
     * user（"\n\n" 合并）；maxIterations 提示语随后经 finalizeContent 出结果。
     */
    @Test
    void maxIterations_drainsRemainingInjections_withoutContinuing() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.withToolCalls(List.of(new ToolCall("noop_tool", Map.of())), "t1"));
        RecordingHook hook = new RecordingHook();
        ScriptedInjections inj = new ScriptedInjections(List.of("inj-a"), List.of("inj-b"));

        AgentRunResult r = run(ai, hook, inj, spec -> spec.maxIterations(1));

        assertEquals(List.of(
            "beforeIteration:1", "beforeExecuteTools", "afterExecuteTools", "afterIteration:1",
            "finalizeContent:" + MAX_ITER_MSG), hook.events);
        assertEquals(MAX_ITER_MSG, r.getContent());
        assertEquals(1, r.getIterationCount());
        assertTrue(r.hadInjections());
        assertEquals(2, inj.invocations, "inj1 一次 + drain6 一次");
        assertEquals(List.of("USER", "ASSISTANT", "TOOL", "USER"), roles(r));
        assertEquals(List.of("hi", "t1", "ok", "inj-a\n\ninj-b"), contents(r));
    }

    // ---- 场景 H：MAX_INJECTION_CYCLES 上限——第 6 次尝试不再回调 ----

    /**
     * 注入周期达上限（5）后，第 6 个检查点在<b>调用回调之前</b>短路（回调恰被调 5 次），
     * 该迭代正常 break 出终答；onIntermediateResponse 恰发射 5 次。
     */
    @Test
    void injectionCycleCap_fifthCycleIsLast_callbackNotInvokedOnSixthAttempt() {
        GatedScriptAiService ai = new GatedScriptAiService();
        for (int i = 0; i < 5; i++) {
            ai.script(LLMResponse.text("mid"));
        }
        ai.script(LLMResponse.text("FINAL"));
        RecordingHook hook = new RecordingHook();
        // 5 个周期各供一批（第 6 次尝试被上限短路，根本不调回调）
        ScriptedInjections inj = new ScriptedInjections(
            List.of("inj"), List.of("inj"), List.of("inj"), List.of("inj"), List.of("inj"));

        AgentRunResult r = run(ai, hook, inj, spec -> spec.maxIterations(10));

        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            expected.add("beforeIteration:" + i);
            expected.add("onIntermediateResponse:mid");
            expected.add("afterIteration:" + i);
        }
        expected.add("beforeIteration:6");
        expected.add("finalizeContent:FINAL");
        assertEquals(expected, hook.events);
        assertEquals("FINAL", r.getContent());
        assertEquals(6, r.getIterationCount());
        assertTrue(r.hadInjections());
        assertEquals(5, inj.invocations, "上限检查先于回调：第 6 次尝试不调回调");
        assertEquals(12, r.getCurrentMessages().size(), "hi + 5×(mid, inj) + FINAL");
    }

    // ---- 场景 I：无注入回调的 maxIterations——自然落穿发射 afterIteration ----

    /**
     * inj1 无注入时不 continue、也不 break：自然落穿到循环体末尾的 afterIteration（每
     * 迭代恰一次）；maxIterations 提示语经 finalizeContent <b>变换后</b>出结果。
     */
    @Test
    void maxIterations_withoutCallback_fallsThroughToTrailingAfterIteration_finalizeTransforms() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.withToolCalls(List.of(new ToolCall("noop_tool", Map.of())), "t1"));
        ai.script(LLMResponse.withToolCalls(List.of(new ToolCall("noop_tool", Map.of())), "t2"));
        RecordingHook hook = new RecordingHook("[X] ");

        AgentRunResult r = run(ai, hook, null, spec -> spec.maxIterations(2));

        assertEquals(List.of(
            "beforeIteration:1", "beforeExecuteTools", "afterExecuteTools", "afterIteration:1",
            "beforeIteration:2", "beforeExecuteTools", "afterExecuteTools", "afterIteration:2",
            "finalizeContent:" + MAX_ITER_MSG), hook.events);
        assertEquals("[X] " + MAX_ITER_MSG, r.getContent());
        assertEquals(List.of("noop_tool", "noop_tool"), r.getToolsUsed());
        assertEquals(2, r.getIterationCount());
        assertFalse(r.hadInjections());
    }

    // ---- 场景 K：空终答无注入——空串原样出结果（无占位替换） ----

    /** 空回复 + 无注入：注释里的"append placeholder"实际不发生，空串 append 后原样终态。 */
    @Test
    void emptyFinalResponseWithoutInjection_staysEmpty() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.text(""));
        RecordingHook hook = new RecordingHook();

        AgentRunResult r = run(ai, hook, null, spec -> spec.maxIterations(5));

        assertEquals(List.of("beforeIteration:1", "finalizeContent:"), hook.events);
        assertEquals("", r.getContent());
        assertEquals(1, r.getIterationCount());
        assertTrue(r.isSuccess());
        assertEquals(List.of("USER", "ASSISTANT"), roles(r));
        assertEquals(List.of("hi", ""), contents(r));
    }

    // ---- 场景 L：入口中断位清扫（复用线程的残留中断） ----

    /**
     * 复用线程上残留的 preset 中断位必须在入口清一次：agent-loop 专用线程跨回合复用，
     * 上一回合被 Stop 取消时 signalCancel 的 runnerThread 中断可能晚于上一回合 finally
     * 的清扫才送达（读→interrupt TOCTOU）——不清扫会让本轮迭代 1 就因
     * {@code isInterrupted()} 直接 break 返回空回复。取消语义不受影响（abort flag 先于
     * interrupt 置位，flag 才是取消唯一事实来源）。
     */
    @Test
    void presetInterruptBit_sweptAtEntry_runNotAborted() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.text("FINAL"));

        Thread.currentThread().interrupt();  // 模拟复用线程上的残留中断位
        try {
            AgentRunResult r = run(ai, new RecordingHook(), null, spec -> spec.maxIterations(5));

            assertEquals("FINAL", r.getContent(),
                    "入口清扫必须清掉残留中断位——否则迭代 1 因 isInterrupted 直接 break 出空回复");
            assertTrue(r.isSuccess());
            assertEquals(List.of("USER", "ASSISTANT"), roles(r));
            assertFalse(Thread.interrupted(),
                    "run 返回后中断位应保持干净（finally 的退出清扫）；本断言顺带清位防泄漏");
        } finally {
            // 兜底清位（Thread.interrupted 兼具读取与清除）：断言失败路径也不把中断位泄漏给同 JVM 的后续测试
            Thread.interrupted();
        }
    }

    // ---- 脚手架 ----

    /** consumer 式 spec 微调器，避免每个场景重复 builder 样板。 */
    private interface SpecTweak {
        AgentRunSpec.Builder apply(AgentRunSpec.Builder builder);
    }

    private AgentRunResult run(GatedScriptAiService ai, RecordingHook hook,
            Function<Integer, List<String>> injections, SpecTweak tweak) {
        try {
            Path workspace = Files.createTempDirectory("loopseq-test-ws");
            workspace.toFile().deleteOnExit();
            ToolRegistry tools = new ToolRegistry();
            tools.register(new NoopTool());
            tools.register(new BoomTool());
            AgentRunner runner = new AgentRunner(
                tools, null,
                new ContextBuilder(new MemoryStore(workspace), workspace),
                null, ai, 40, 16000, 30000);

            AgentRunSpec.Builder builder = AgentRunSpec.builder()
                .sessionKey("loopseq-test")
                .initialMessages(List.of(Message.user("hi")))
                .persistSession(false)
                .hook(hook);
            if (injections != null) {
                builder.injectionCallback(injections);
            }
            return runner.run(tweak.apply(builder).build());
        } catch (Exception e) {
            throw new IllegalStateException("test setup failed", e);
        }
    }

    private static List<String> roles(AgentRunResult r) {
        return r.getCurrentMessages().stream().map(m -> m.getRole().name()).toList();
    }

    private static List<String> contents(AgentRunResult r) {
        return r.getCurrentMessages().stream().map(Message::getContent).toList();
    }
}
