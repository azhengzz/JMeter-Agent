package org.gitee.jmeter.ai.agent.hooks;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolEvent;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Adapter to convert legacy ProgressCallback to AgentHook.
 * Mirrors Nanobot's _LoopHook: pushes model thought and tool info
 * to the UI during the agent loop.
 */
public class ProgressCallbackHookAdapter implements AgentHook {
    private static final Logger log = LoggerFactory.getLogger(ProgressCallbackHookAdapter.class);

    private final AgentLoop.ProgressCallback callback;
    private final boolean showThinking;
    private int lastEventCount = 0;

    public ProgressCallbackHookAdapter(AgentLoop.ProgressCallback callback) {
        this.callback = callback;
        this.showThinking = AiConfig.isChatShowThinking();
    }

    @Override
    public void beforeExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        if (callback == null) return;

        if (context.getLastLlmResponse() != null) {
            String reasoningContent = context.getLastLlmResponse().getReasoningContent();
            String content = context.getLastLlmResponse().getContent();

            String display;
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                // Structured reasoning_content is separated from content. content may be null
                // (a thinking+tool_use iteration emits no text block) — concatenating it
                // verbatim would paint a literal "null"; append only real content.
                display = showThinking
                        ? "<think>" + reasoningContent + "</think>"
                          + (content != null && !content.isEmpty() ? "\n" + content : "")
                        : TextUtils.stripThink(content);
            } else {
                // No structured field — thinking may be embedded as <think/> tags in content
                display = showThinking ? content : TextUtils.stripThink(content);
            }

            if (display != null && !display.isEmpty()) {
                publish(ProgressUpdate.thinking(display));
            }
        }
    }

    @Override
    public void afterExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        if (callback == null) return;

        // Show tool execution results for this iteration
        List<ToolEvent> allEvents = context.getToolEvents();
        if (lastEventCount < allEvents.size()) {
            List<ToolEvent> newEvents = allEvents.subList(lastEventCount, allEvents.size());
            for (ToolEvent event : newEvents) {
                publish(ProgressUpdate.toolCall(event));
            }
            lastEventCount = allEvents.size();
        }
    }

    @Override
    public String finalizeContent(String content, AgentHookContext context) {
        return this.showThinking ? content : TextUtils.stripThink(content);
    }

    @Override
    public void onIntermediateResponse(String content, AgentHookContext context) {
        if (callback == null || content == null || content.isEmpty()) return;
        String display = this.showThinking ? content : TextUtils.stripThink(content);
        publish(ProgressUpdate.intermediateResponse(display));
    }

    @Override
    public void onUsage(Map<String, Integer> usage, AgentHookContext context) {
        if (callback == null) return;
        publish(ProgressUpdate.usage(usage));
    }

    @Override
    public void onError(Throwable error, AgentHookContext context) {
        if (callback != null) {
            publish(ProgressUpdate.error("Error: " + error.getMessage()));
        }
    }

    private void publish(ProgressUpdate update) {
        try {
            callback.onProgress(update);
        } catch (Exception e) {
            log.warn("Error in progress callback", e);
        }
    }
}
