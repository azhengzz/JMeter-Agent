package org.gitee.jmeter.ai.service.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Unified OpenAI-compatible provider for all Chinese LLM providers.
 * Uses the openai-java SDK with custom base URLs.
 */
public class OpenAICompatibleProvider implements AiService {
    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleProvider.class);

    // Maps thinking_style -> extra_body builder (mirrors Nanobot's _THINKING_STYLE_MAP).
    // Each builder takes (modelName, thinkingEnabled) and returns the dict to merge into extra_body.
    // Most styles ignore the model name; "minimax_thinking" uses it to pick M3 (adaptive) vs M2.x (enabled).
    private static final Map<String, java.util.function.BiFunction<String, Boolean, Map<String, Object>>> THINKING_STYLE_MAP = Map.of(
            "thinking_type", (model, on) -> Map.of("thinking", Map.of("type", on ? "enabled" : "disabled")),
            "enable_thinking", (model, on) -> Map.of("enable_thinking", on),
            "minimax_thinking", (model, on) -> buildMinimaxThinkingExtraBody(model, on)
    );

    /**
     * MiniMax thinking extra_body. The on/off toggle is {@code thinking.type} (NOT
     * {@code reasoning_split}, which is only an output-format toggle). The "on" value is
     * model-family dependent: M3 accepts only {@code adaptive} (sending {@code enabled} → HTTP 400);
     * M2.x accepts {@code enabled} (its {@code disabled} is silently ignored by the server — a known
     * limitation). When thinking is on, {@code reasoning_split:true} is also sent so reasoning routes
     * to {@code reasoning_content} (consumed by the display pipeline) instead of inline {@code <think>}
     * tags polluting {@code content}.
     *
     * @see <a href="https://platform.minimaxi.com/docs/api-reference/text-openai-api#thinking-控制">MiniMax thinking 控制</a>
     */
    static Map<String, Object> buildMinimaxThinkingExtraBody(String model, boolean on) {
        if (on) {
            String onType = isM3Family(model) ? "adaptive" : "enabled";
            return Map.of(
                    "thinking", Map.of("type", onType),
                    "reasoning_split", true);
        }
        return Map.of("thinking", Map.of("type", "disabled"));
    }

    /**
     * Whether a MiniMax model is in the M3 family, which requires {@code thinking.type=adaptive}
     * to enable thinking (it rejects {@code enabled} with HTTP 400). Detection is by substring on
     * the lowercased model id (e.g. "MiniMax-M3", "minimax:MiniMax-M3-Pro"). Substring (not prefix)
     * so renamed M3 ids on third-party aggregators (e.g. "acme-minimax-m3-pro") still match, and a
     * provider prefix does not affect the match (it cannot compose "minimax-m3" across the colon).
     */
    static boolean isM3Family(String model) {
        if (model == null) return false;
        return model.toLowerCase().contains("minimax-m3");
    }

    private final String providerName;
    private final OpenAIClient client;
    private final Map<String, Map<String, Object>> modelOverrides;
    private final ProviderSpec spec;
    private final String apiKey;
    private final String baseUrl;

    private String currentModelId;
    private String systemPrompt;
    private GenerationSettings generationSettings;
    private boolean systemPromptInitialized = false;

    public OpenAICompatibleProvider(ProviderSpec spec) {
        this.providerName = spec.getName();
        this.spec = spec;
        this.modelOverrides = spec.getModelOverrides();

        // Get API key from properties
        this.apiKey = AiConfig.getProperty(spec.getEnvKey(), "");
        if (apiKey.isEmpty()) {
            log.warn("No API key configured for provider: {}", spec.getEnvKey());
        }

        // Build the client with provider-specific base URL
        this.baseUrl = AiConfig.getProperty(spec.getName() + ".api.base.url", spec.getDefaultApiBase());
        log.info("Creating OpenAI-compatible provider: {} with base URL: {}",
                providerName, baseUrl);

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.isEmpty() ? "no-key" : apiKey);

        if (!baseUrl.isEmpty()) {
            clientBuilder.baseUrl(baseUrl);
        }

        this.client = clientBuilder.build();

        this.currentModelId = AiConfig.getDefaultModel();
        this.generationSettings = GenerationSettings.fromConfig();
        // Load system prompt using centralized utility
        this.systemPrompt = SystemPrompt.get();

        log.info("Initialized {} provider with model: {}", providerName, currentModelId);
    }

    @Override
    public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
        return doGenerateWithTools(messages, tools, null, null);
    }

    @Override
    public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
        return doGenerateWithTools(messages, tools, null, options);
    }

    @Override
    public LLMResponse generateResponseWithForcedTool(List<Message> messages, List<ToolDefinition> tools, String forcedToolName) {
        log.info("Forced tool calling for {}: {}", providerName, forcedToolName);
        try {
            LLMResponse response = doGenerateWithTools(messages, tools, forcedToolName, null);
            if (response.isError() && isToolChoiceUnsupported(response.getErrorMessage())) {
                log.warn("Forced tool_choice unsupported by {}", providerName);
                return response;
            }
            return response;
        } catch (Exception e) {
            if (isToolChoiceUnsupported(e)) {
                log.warn("Forced tool_choice unsupported by {}", providerName);
                return LLMResponse.error(e.getMessage());
            }
            return LLMResponse.error("Error in forced tool calling: " + e.getMessage());
        }
    }

    private boolean isToolChoiceUnsupported(Throwable e) {
        if (e == null) return false;
        String msg = (e.getMessage() != null ? e.getMessage() : "").toLowerCase();
        return msg.contains("tool_choice") || msg.contains("does not support")
                || msg.contains("should be [\"none\", \"auto\"]");
    }

    /**
     * Check if an exception was caused by thread interruption (e.g. OkHttp InterruptedIOException).
     */
    private boolean isCausedByInterrupt(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.io.InterruptedIOException
                    || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isToolChoiceUnsupported(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("tool_choice") || lower.contains("does not support")
                || lower.contains("should be [\"none\", \"auto\"]");
    }

    /**
     * Core implementation: generates response with optional forced tool choice.
     * @param forcedToolName if non-null, forces the LLM to call this specific tool
     */
    private LLMResponse doGenerateWithTools(List<Message> messages, List<ToolDefinition> tools, String forcedToolName, LlmCallOptions options) {
        log.info("Generating response for {}: {} tools, forcedTool={}", providerName,
                tools != null ? tools.size() : 0, forcedToolName);

        // Resolve per-call overrides (fallback to generation settings when null)
        String effectiveModel = (options != null && options.getModel() != null) ? options.getModel() : this.currentModelId;
        double effectiveTemperature = (options != null && options.getTemperature() != null) ? options.getTemperature() : this.generationSettings.getTemperature();
        long effectiveMaxTokens = (options != null && options.getMaxTokens() != null) ? options.getMaxTokens().longValue() : this.generationSettings.getMaxTokens();
        String effectiveReasoningEffort = (options != null && options.getReasoningEffort() != null) ? options.getReasoningEffort() : this.generationSettings.getReasoningEffort();

        String modelName = stripProviderPrefix(effectiveModel);
        boolean modelSupportsThinking = spec != null && spec.supportsThinking(modelName);

        // Apply model-specific overrides (e.g. kimi-k2.5/k2.6 require temperature=1.0)
        Map<String, Object> overrides = modelOverrides.get(modelName);
        if (overrides != null) {
            Object tempOverride = overrides.get("temperature");
            if (tempOverride instanceof Number) {
                effectiveTemperature = ((Number) tempOverride).doubleValue();
            }
        }

        try {
            // 使用 SDK 的 Builder 构建 request
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(modelName)
                    .maxCompletionTokens(effectiveMaxTokens)
                    .temperature(effectiveTemperature);

            // Provider-wide thinking style (injected into extra_body for models like K2.6/K2.7).
            String effectiveStyle = spec != null ? spec.getThinkingStyle() : "";
            boolean alwaysOn = spec != null && spec.isThinkingAlwaysOn(modelName);

            // Apply reasoning effort via the SDK enum (includes MAX). Skip for models without
            // thinking support. Values a model doesn't accept (e.g. K3/GLM-5.3 with
            // minimal/medium/xhigh) pass through unchanged — the provider rejects them,
            // surfacing the misconfiguration rather than silently clamping it. Always-on
            // models only force thinking.type=enabled below; a configured "none" simply omits
            // reasoning_effort and lands on the server default (deepest, e.g. GLM-5.3 max) —
            // the README documents "none" as unsupported for these models.
            ReasoningEffort effort = toReasoningEffort(effectiveReasoningEffort);
            if (effort != null && modelSupportsThinking) {
                paramsBuilder.reasoningEffort(effort);
            }

            // Determine if thinking mode is active (mirrors Nanobot's thinking_active logic).
            // Always-on models (e.g. kimi-k2.7-code, kimi-k3) force this true so reasoning_content
            // backfill triggers correctly for multi-turn tool calls.
            boolean thinkingActive = alwaysOn || (spec != null
                    && !effectiveStyle.isEmpty()
                    && modelSupportsThinking
                    && effectiveReasoningEffort != null
                    && !"none".equalsIgnoreCase(effectiveReasoningEffort));

            // Provider-specific thinking extra_body (mirrors Nanobot's _THINKING_STYLE_MAP).
            // Only sent when reasoning_effort is explicitly configured, so the provider default
            // is preserved otherwise.
            if (spec != null && !effectiveStyle.isEmpty()
                    && effectiveReasoningEffort != null && modelSupportsThinking) {
                // Always-on models cannot receive disabled (API rejects); force enabled.
                boolean thinkingEnabled = alwaysOn || !"none".equalsIgnoreCase(effectiveReasoningEffort);
                java.util.function.BiFunction<String, Boolean, Map<String, Object>> styleBuilder =
                        THINKING_STYLE_MAP.get(effectiveStyle);
                if (styleBuilder != null) {
                    Map<String, Object> extra = styleBuilder.apply(modelName, thinkingEnabled);
                    if (extra != null && !extra.isEmpty()) {
                        for (Map.Entry<String, Object> entry : extra.entrySet()) {
                            paramsBuilder.putAdditionalBodyProperty(entry.getKey(),
                                    com.openai.core.JsonValue.from(entry.getValue()));
                        }
                    }
                }
            }

            // 添加系统提示词
            boolean systemPromptAdded = false;
            for (Message msg : messages) {
                if (msg.getRole() == Message.Role.SYSTEM && msg.getContent() != null && !msg.getContent().isEmpty()) {
                    paramsBuilder.addSystemMessage(msg.getContent());
                    systemPromptAdded = true;
                }
            }

            // 如果没有系统提示词，添加默认系统提示词
            if (!systemPromptAdded && !systemPromptInitialized && systemPrompt != null && !systemPrompt.isEmpty()) {
                paramsBuilder.addSystemMessage(systemPrompt);
                systemPromptInitialized = true;
            }

            // 转换消息格式
            for (Message msg : messages) {
                switch (msg.getRole()) {
                    case SYSTEM -> {
                        // Already handled above, skip
                        continue;
                    }
                    case USER -> {
                        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                            paramsBuilder.addUserMessage(msg.getContent());
                        }
                        break;
                    }
                    case ASSISTANT -> {
                        // Build assistant message param (unified for both tool-call and text cases)
                        ChatCompletionAssistantMessageParam.Builder assistantBuilder =
                                ChatCompletionAssistantMessageParam.builder();

                        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                            assistantBuilder.content(msg.getContent());
                        }

                        // Pass back reasoning_content for thinking-mode providers (e.g., DeepSeek).
                        // If the message already has reasoning_content from a previous turn, pass it
                        // back intact. Otherwise, when thinking is active, backfill an empty string
                        // to satisfy providers that require the field on all assistant messages.
                        if (msg.hasReasoningContent()) {
                            assistantBuilder.putAdditionalProperty("reasoning_content",
                                    com.openai.core.JsonValue.from(msg.getReasoningContent()));
                        } else if (thinkingActive) {
                            assistantBuilder.putAdditionalProperty("reasoning_content",
                                    com.openai.core.JsonValue.from(""));
                        }

                        if (msg.hasToolCalls()) {
                            for (ToolCall tc : msg.getToolCalls()) {
                                ChatCompletionMessageFunctionToolCall toolCall =
                                        ChatCompletionMessageFunctionToolCall.builder()
                                                .id(tc.getId())
                                                .function(
                                                        com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall.Function
                                                                .builder()
                                                                .name(tc.getName())
                                                                .arguments(new ObjectMapper().writeValueAsString(tc.getArguments()))
                                                                .build()
                                                )
                                                .build();
                                assistantBuilder.addToolCall(toolCall);
                            }
                        }

                        paramsBuilder.addMessage(assistantBuilder.build());
                        break;
                    }
                    case TOOL -> {
                        // Tool result message
                        ChatCompletionToolMessageParam toolMessage =
                                ChatCompletionToolMessageParam.builder()
                                        .toolCallId(msg.getToolCallId())
                                        .content(msg.getContent() != null ? msg.getContent() : "")
                                        .build();
                        paramsBuilder.addMessage(toolMessage);
                        break;
                    }
                }
            }

            // 添加 tools - 使用 SDK 的 addFunctionTool 方法
            if (tools != null && !tools.isEmpty()) {
                for (ToolDefinition tool : tools) {
                    // Build FunctionParameters from the Map
                    FunctionParameters.Builder functionParamsBuilder = FunctionParameters.builder();
                    if (tool.getParameters() != null) {
                        Map<String, Object> paramMap = tool.getParameters();
                        for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
                            functionParamsBuilder.putAdditionalProperty(entry.getKey(),
                                    convertToJsonValue(entry.getValue()));
                        }
                    }

                    // Build FunctionDefinition
                    FunctionDefinition functionDef = FunctionDefinition.builder()
                            .name(tool.getName())
                            .description(tool.getDescription() != null ? tool.getDescription() : "")
                            .parameters(functionParamsBuilder.build())
                            .build();

                    // Add the tool using SDK method
                    paramsBuilder.addFunctionTool(functionDef);
                }
            }

            // 构建并发送请求
            if (forcedToolName != null) {
                paramsBuilder.toolChoice(
                    com.openai.models.chat.completions.ChatCompletionNamedToolChoice.builder()
                        .type(com.openai.core.JsonValue.from("function"))
                        .function(com.openai.models.chat.completions.ChatCompletionNamedToolChoice.Function.builder()
                            .name(forcedToolName)
                            .build())
                        .build()
                );
            }
            ChatCompletionCreateParams params = paramsBuilder.build();
            log.info("[{}] Request params: {}, thinkingActive={}, thinkingStyle={}",
                    providerName, summarizeParams(params), thinkingActive, effectiveStyle);

            ChatCompletion chatCompletion = client.chat().completions().create(params);
            log.info("Received response from {}, chatCompletion object type: {}",
                    providerName, chatCompletion.getClass().getName());

            // Extract usage directly from ChatCompletion for LLMResponse
            long extractedPTokens = 0;
            long extractedCTokens = 0;
            try {
                var usageOpt = chatCompletion.usage();
                if (usageOpt != null && usageOpt.isPresent()) {
                    var usage = usageOpt.get();
                    extractedPTokens = usage.promptTokens();
                    extractedCTokens = usage.completionTokens();
                    log.info("Usage from {} API: promptTokens={}, completionTokens={}, totalTokens={}",
                            providerName, extractedPTokens, extractedCTokens, usage.totalTokens());
                } else {
                    log.debug("No usage data in {} ChatCompletion response", providerName);
                }
            } catch (Exception e) {
                log.warn("Could not extract usage from {} ChatCompletion: {}", providerName, e.getMessage());
            }

            java.util.Map<String, Integer> usageMap;
            if (extractedPTokens > 0 || extractedCTokens > 0) {
                usageMap = java.util.Map.of("prompt_tokens", (int) extractedPTokens,
                        "completion_tokens", (int) extractedCTokens);
            } else {
                usageMap = java.util.Map.of();
                log.debug("Usage extraction returned 0/0 for provider: {}", providerName);
            }

            // 解析响应
            // 检查 choices 是否为 null 或空 - 需要捕获异常因为 openai-java SDK 的 choices() 方法在字段为 null 时会抛出异常
            List<ChatCompletion.Choice> choices;
            try {
                choices = chatCompletion.choices();
            } catch (com.openai.errors.OpenAIInvalidDataException e) {
                log.error("API returned null choices field for {}. This usually indicates an API error or invalid response.", providerName, e);
                // 尝试读取原始响应以获取更多信息
                try {
                    String rawResponse = chatCompletion.toString();
                    log.error("Raw API response from {}: {}", providerName, rawResponse);
                    return LLMResponse.error("API returned invalid response (null choices). Response: " +
                            rawResponse.substring(0, Math.min(200, rawResponse.length())) +
                            ". Check API key, quota, or service status.");
                } catch (Exception ex) {
                    log.error("Failed to read raw response", ex);
                    return LLMResponse.error("API returned invalid response (null choices). Check API key, quota, or service status.");
                }
            }

            if (choices.isEmpty()) {
                log.warn("API returned empty choices for {}", providerName);
                return LLMResponse.error("API returned empty choices. Check API status and configuration.");
            }

            ChatCompletion.Choice choice = choices.get(0);
            String content = choice.message().content().orElse(null);
            String finishReason = choice.finishReason() != null ? choice.finishReason().toString() : "unknown";

            // Extract reasoning_content from additional properties (DeepSeek reasoning models)
            String reasoningContent = null;
            Map<String, com.openai.core.JsonValue> additionalProps = choice.message()._additionalProperties();
            if (additionalProps != null && additionalProps.containsKey("reasoning_content")) {
                com.openai.core.JsonValue reasoningValue = additionalProps.get("reasoning_content");
                if (reasoningValue != null) {
                    try {
                        reasoningContent = reasoningValue.convert(String.class);
                        log.debug("Extracted reasoning_content from {} response (length: {})", providerName, reasoningContent.length());
                    } catch (Exception e) {
                        log.debug("Could not convert reasoning_content to String: {}", e.getMessage());
                    }
                }
            }

            // 提取 tool calls
            List<ToolCall> toolCalls = new ArrayList<>();
            var toolCallsOpt = choice.message().toolCalls();
            if (toolCallsOpt.isPresent() && !toolCallsOpt.get().isEmpty()) {
                for (var toolCall : toolCallsOpt.get()) {
                    // toolCall.function() returns Optional<ChatCompletionMessageFunctionToolCall>
                    var functionToolCallOpt = toolCall.function();
                    if (functionToolCallOpt.isPresent()) {
                        var functionToolCall = functionToolCallOpt.get();
                        // functionToolCall.function() returns the Function object
                        var function = functionToolCall.function();
                        try {
                            Map<String, Object> arguments = new ObjectMapper().readValue(
                                    function.arguments(),
                                    new TypeReference<Map<String, Object>>() {}
                            );
                            // Get ID from the functionToolCall
                            toolCalls.add(new ToolCall(functionToolCall.id(), function.name(), arguments));
                        } catch (JsonProcessingException e) {
                            log.error("Failed to parse tool arguments for {}: rawArguments=[{}]", function.name(), function.arguments(), e);
                        }
                    }
                }
            }

            // 构建响应
            LLMResponse.Builder responseBuilder = LLMResponse.builder()
                    .content(content)
                    .finishReason(finishReason)
                    .reasoningContent(reasoningContent)
                    .usage(usageMap);

            if (!toolCalls.isEmpty()) {
                responseBuilder.toolCalls(toolCalls);
            }

            return responseBuilder.build();

        } catch (Exception e) {
            // Thread interrupt can surface as InterruptedException or wrapped in OkHttp's InterruptedIOException
            if (isCausedByInterrupt(e)) {
                Thread.currentThread().interrupt();
                log.info("LLM request interrupted for {} (agent stopped)", providerName);
                return LLMResponse.error("Interrupted");
            }
            // tool_choice unsupported is expected for some models (e.g. deepseek-reasoner), log as WARN
            if (isToolChoiceUnsupported(e)) {
                log.warn("tool_choice unsupported by {}: {}", providerName, e.getMessage());
            } else {
                log.error("Error in generateResponseWithTools for {}", providerName, e);
            }
            return LLMResponse.error("Error calling LLM: " + e.getMessage());
        }
    }

    /**
     * Convert a Java object to a JsonValue for the OpenAI SDK.
     * Simply delegates to JsonValue.from() which handles all conversions.
     */
    private com.openai.core.JsonValue convertToJsonValue(Object value) {
        return com.openai.core.JsonValue.from(value);
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public boolean supportsForcedToolChoice() {
        return true;
    }

    @Override
    public String getName() {
        return providerName;
    }

    public void setModel(String modelId) {
        this.currentModelId = modelId;
        log.info("Model set to: {}", modelId);
    }

    @Override
    public GenerationSettings getGenerationSettings() {
        return generationSettings;
    }

    @Override
    public void setGenerationSettings(GenerationSettings settings) {
        this.generationSettings = settings;
        log.info("Generation settings updated for {}: {}", providerName, settings);
    }

    public void setTemperature(float temperature) {
        generationSettings.setTemperature(temperature);
        log.info("Temperature set to: {}", temperature);
    }

    public float getTemperature() {
        return (float) generationSettings.getTemperature();
    }

    public void setMaxTokens(long maxTokens) {
        generationSettings.setMaxTokens((int) maxTokens);
    }

    public long getMaxTokens() {
        return generationSettings.getMaxTokens();
    }

    /**
     * Build a log-friendly summary of request params (excludes message content,
     * includes counts and serialized extraBody for debugging).
     */
    private String summarizeParams(ChatCompletionCreateParams p) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("model", String.valueOf(p.model()));
        m.put("temperature", opt(p.temperature()));
        m.put("maxCompletionTokens", opt(p.maxCompletionTokens()));
        m.put("topP", opt(p.topP()));
        m.put("frequencyPenalty", opt(p.frequencyPenalty()));
        m.put("presencePenalty", opt(p.presencePenalty()));
        m.put("reasoningEffort", opt(p.reasoningEffort()));
        m.put("n", opt(p.n()));
        m.put("seed", opt(p.seed()));
        m.put("stop", opt(p.stop()));
        m.put("toolChoice", opt(p.toolChoice()));
        m.put("parallelToolCalls", opt(p.parallelToolCalls()));
        m.put("responseFormat", opt(p.responseFormat()));
        m.put("serviceTier", opt(p.serviceTier()));
        m.put("user", opt(p.user()));
        m.put("store", opt(p.store()));
        m.put("logprobs", opt(p.logprobs()));
        m.put("topLogprobs", opt(p.topLogprobs()));
        m.put("messages", p.messages().size() + " items");
        p.tools().ifPresentOrElse(v -> m.put("tools", v.size() + " items"), () -> m.put("tools", ""));
        Map<String, com.openai.core.JsonValue> extraBody = p._additionalBodyProperties();
        if (extraBody != null && !extraBody.isEmpty()) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, com.openai.core.JsonValue> e : extraBody.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            }
            json.append("}");
            m.put("extraBody", json.toString());
        }
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(", ");
            sb.append(k).append("=").append(v);
        });
        sb.append("}");
        return sb.toString();
    }

    private String opt(java.util.Optional<?> o) {
        return o.map(Object::toString).orElse("");
    }

    /**
     * Strip the current provider's own "provider:" prefix from a model ID.
     * e.g. for an ollama provider: "ollama:qwen3.5:2b" -> "qwen3.5:2b".
     * A bare colon that is NOT the current provider's prefix is left intact —
     * Ollama model tags themselves use ":" (e.g. "qwen3.5:2b"), which previously
     * got truncated to "2b". No global prefix whitelist is needed: each instance
     * already knows its own provider name.
     */
    private String stripProviderPrefix(String modelId) {
        if (modelId != null && modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            if (parts.length == 2 && parts[0].equals(this.providerName)) {
                return parts[1];
            }
        }
        return modelId;
    }

    private static ReasoningEffort toReasoningEffort(String effort) {
        if (effort == null || effort.equalsIgnoreCase("none") || effort.equalsIgnoreCase("null")) {
            return null;
        }
        return switch (effort.toLowerCase()) {
            case "minimal", "minimum" -> ReasoningEffort.MINIMAL;
            case "low" -> ReasoningEffort.LOW;
            case "medium" -> ReasoningEffort.MEDIUM;
            case "high" -> ReasoningEffort.HIGH;
            case "xhigh" -> ReasoningEffort.XHIGH;
            case "max" -> ReasoningEffort.MAX;
            default -> ReasoningEffort.HIGH;
        };
    }
}
