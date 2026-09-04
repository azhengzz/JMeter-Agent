package org.gitee.jmeter.ai.agent.hooks;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ProgressCallbackHookAdapter#beforeExecuteTools} 的 THINKING 载荷形态单测：
 * 结构化 reasoning_content 与 content 的拼接——content 可为 null（thinking+tool_use
 * 迭代无文本块），载荷不得出现字面 "null"（否则经面板 think 拆分渲染成正文段落）。
 */
class ProgressCallbackHookAdapterTest {

    private static final String SHOW_THINKING_KEY = "ai.chat.show.thinking";

    /** 反射确保 JMeterUtils.appProperties 非空，否则 setProperty NPE（AiConfigTest 同款）。 */
    @BeforeAll
    static void ensureJMeterProps() {
        try {
            Field f = JMeterUtils.class.getDeclaredField("appProperties");
            f.setAccessible(true);
            if (f.get(null) == null) {
                f.set(null, new Properties());
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void resetShowThinking() {
        JMeterUtils.setProperty(SHOW_THINKING_KEY, ""); // 空串解析回默认 false，防跨测试类串扰
    }

    @Test
    void structuredReasoningWithNullContentOmitsLiteralNull() {
        JMeterUtils.setProperty(SHOW_THINKING_KEY, "true");
        List<ProgressUpdate> updates = new ArrayList<>();
        AgentHookContext context = new AgentHookContext("run-1", null, "q");
        context.setLastLlmResponse(LLMResponse.builder()
                .reasoningContent("let me check").build()); // content 保持 null

        new ProgressCallbackHookAdapter(updates::add).beforeExecuteTools(List.of(), context);

        assertEquals(1, updates.size());
        assertEquals(ProgressUpdate.Type.THINKING, updates.get(0).getType());
        assertEquals("<think>let me check</think>", updates.get(0).getMessage());
    }

    @Test
    void structuredReasoningWithContentKeepsBothParts() {
        JMeterUtils.setProperty(SHOW_THINKING_KEY, "true");
        List<ProgressUpdate> updates = new ArrayList<>();
        AgentHookContext context = new AgentHookContext("run-1", null, "q");
        context.setLastLlmResponse(LLMResponse.builder()
                .reasoningContent("reasoning").content("answer").build());

        new ProgressCallbackHookAdapter(updates::add).beforeExecuteTools(List.of(), context);

        assertEquals(1, updates.size());
        assertEquals("<think>reasoning</think>\nanswer", updates.get(0).getMessage());
    }
}
