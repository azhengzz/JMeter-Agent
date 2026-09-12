package org.gitee.jmeter.ai.agent.hooks;

import org.gitee.jmeter.ai.agent.model.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * Hook interface for extending AgentRunner behavior.
 * Inspired by Nanobot's hook system for lifecycle extension and streaming.
 */
public interface AgentHook {

    /**
     * Check if this hook wants streaming output.
     * When true, onStream() will be called with partial content during generation.
     */
    default boolean wantsStreaming() {
        return false;
    }

    /**
     * Called when streaming content is available.
     * Only invoked if wantsStreaming() returns true.
     *
     * @param content Partial content from LLM
     * @param isFinal True if this is the final content
     */
    default void onStream(String content, boolean isFinal) {
        // Default: do nothing
    }

    /**
     * Called when streaming completes.
     *
     * @param finalContent The complete final content
     */
    default void onStreamEnd(String finalContent) {
        // Default: do nothing
    }

    /**
     * Called when streaming completes, with indication of what happens next.
     * This is more granular than onStreamEnd(finalContent).
     *
     * @param context The hook context
     * @param resuming If true, streaming ended because there are tool calls to execute.
     *                 If false, this is the final completion (no more tool calls).
     */
    default void onStreamEnd(AgentHookContext context, boolean resuming) {
        // Default: delegate to the simpler version for backward compatibility
        if (!resuming && context.getLastLlmResponse() != null) {
            onStreamEnd(context.getLastLlmResponse().getContent());
        }
    }

    /**
     * Called before each agent iteration.
     *
     * @param context The hook context with iteration state
     */
    default void beforeIteration(AgentHookContext context) {
        // Default: do nothing
    }

    /**
     * Called after each agent iteration.
     *
     * @param context The hook context with iteration results
     */
    default void afterIteration(AgentHookContext context) {
        // Default: do nothing
    }

    /**
     * Called before executing tools.
     *
     * @param toolCalls The tool calls about to be executed
     * @param context The hook context
     */
    default void beforeExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        // Default: do nothing
    }

    /**
     * Called after executing tools.
     *
     * @param toolCalls The tool calls that were executed
     * @param context The hook context with execution results
     */
    default void afterExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        // Default: do nothing
    }

    /**
     * Called to finalize content before returning.
     * Allows hooks to modify or post-process the final content.
     *
     * @param content The raw content from LLM
     * @param context The hook context
     * @return The finalized content (can be modified)
     */
    default String finalizeContent(String content, AgentHookContext context) {
        return content; // Default: return as-is
    }

    /**
     * Called when an error occurs.
     *
     * @param error The error that occurred
     * @param context The hook context
     */
    default void onError(Throwable error, AgentHookContext context) {
        // Default: do nothing
    }

    /**
     * Called when an intermediate response is generated during mid-turn injection.
     * The agent loop will continue after this — the response is not the final one.
     *
     * @param content The intermediate response text
     * @param context The hook context
     */
    default void onIntermediateResponse(String content, AgentHookContext context) {
        // Default: do nothing
    }

    /**
     * Called after each LLM call that returns a non-empty token usage.
     * Fires per iteration (not only at turn end), so live consumers can track the
     * context window usage as the turn grows.
     *
     * @param usage Token usage from the LLM response (prompt_tokens / completion_tokens)
     * @param context The hook context
     */
    default void onUsage(Map<String, Integer> usage, AgentHookContext context) {
        // Default: do nothing
    }
}
