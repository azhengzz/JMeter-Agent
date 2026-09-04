package org.gitee.jmeter.ai.agent.tools;

import org.gitee.jmeter.ai.agent.model.ToolResult;

import java.util.Map;
import java.util.Set;

/**
 * Interface for Agent tools.
 * Tools are callable functions that the LLM can invoke to perform actions.
 */
public interface Tool {

    /** Scope every tool belongs to: callable by the main agent. */
    String SCOPE_CORE = "core";

    /** Scope for tools a subagent may call (read-only analysis tools). */
    String SCOPE_SUBAGENT = "subagent";

    /**
     * Unique tool name (used for registration and calling)
     */
    String getName();

    /**
     * Tool description for the LLM
     * Should explain what the tool does and when to use it
     */
    String getDescription();

    /**
     * Tool parameter schema in JSON Schema format
     * Defines the expected parameters for the tool
     */
    String getParameterSchema();

    /**
     * Execute the tool with given parameters
     * @param parameters Tool parameters (validated if provided)
     * @return Tool execution result
     */
    ToolResult execute(Map<String, Object> parameters);

    /**
     * Validate parameters before execution
     * @param parameters Parameters to validate
     * @return Validation result
     */
    default ValidationResult validateParameters(Map<String, Object> parameters) {
        return ValidationResult.valid();
    }

    /**
     * Check if this tool requires specific parameters
     * @return true if tool has required parameters
     */
    default boolean hasRequiredParameters() {
        return false;
    }

    /**
     * Get tool execution priority.
     * Higher values = higher priority. Default is 0.
     * Tools with higher priority are executed first when using concurrent execution.
     *
     * @return Tool priority (0-100, where 100 is highest priority)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Get tool execution timeout in milliseconds.
     * Returns 0 to use the default timeout from ToolRegistry.
     *
     * @return Timeout in milliseconds, or 0 for default
     */
    default long getTimeoutMs() {
        return 0;
    }

    /**
     * Whether this tool is read-only and side-effect free, and may therefore run
     * in parallel with other concurrency-safe tools in the same batch (Nanobot's
     * {@code concurrency_safe}; default-off whitelist admission).
     *
     * <p>Tools returning false always execute alone, inline on the run thread
     * — never dispatched concurrently with other tools. Overriding to
     * true is a declaration that the tool only reads shared state and is safe
     * to overlap with other such readers.
     *
     * @return true only if the tool is read-only and safe to parallelize
     */
    default boolean isConcurrencySafe() {
        return false;
    }

    /**
     * Get the scopes this tool belongs to.
     *
     * <p>The subagent toolset is built with INCLUDE semantics: only tools whose
     * scopes contain {@code "subagent"} are visible to a subagent. Tools keeping
     * the default {@code {"core"}} are therefore main-agent only — which is what
     * keeps {@code spawn} out of the subagent toolset and prevents unbounded
     * recursion (a subagent cannot spawn another subagent).
     *
     * @return Set of scope names; defaults to {@code {"core"}}
     */
    default Set<String> getScopes() {
        return Set.of("core");
    }
}
