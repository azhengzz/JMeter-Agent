package org.gitee.jmeter.ai.service.provider;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central registry for LLM provider specifications.
 * Single source of truth for all provider metadata.
 */
public class ProviderRegistry {
    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private static final List<ProviderSpec> PROVIDERS = new ArrayList<>();

    static {
        // =====================================================
        // Chinese LLM Providers
        // =====================================================

        // DeepSeek
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("deepseek")
                .displayName("DeepSeek")
                .defaultApiBase("https://api.deepseek.com")
                .envKey("deepseek.api.key")
                .keywords("deepseek")
                .thinkingStyle("thinking_type")
                .build());

        // Zhipu AI (GLM): GLM-4.5+ 支持 thinking.type=enabled/disabled。
        // GLM-4.5/4.6 为混合推理（动态决定），GLM-4.7/5/5.1 默认开启思考。
        // GLM-5.3/5.3-flash 强制思考：thinking.type 仅支持 enabled（传 disabled 直接报错），
        //   思考程度改由顶层 reasoning_effort（low/high/max，默认 max）控制。沿 Kimi K3 先例
        //   注册 thinkingAlwaysOnModels（仅强制 thinking.type=enabled；reasoning.effort=none 时
        //   省略 reasoning_effort，服务端按默认 max 深度思考——这些模型不支持 none，误配不报错
        //   但也不省 token，README 已提示用户直接配 low/high/max）。
        //   https://docs.bigmodel.cn/cn/guide/start/migrate-to-glm-new
        // 偏离 Nanobot：通过 reasoning_effort 显式控制（none→disabled，medium/high→enabled）。
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("zhipu")
                .displayName("Zhipu AI")
                .defaultApiBase("https://open.bigmodel.cn/api/paas/v4")
                .envKey("zhipu.api.key")
                .keywords("zhipu", "glm", "zai")
                .thinkingStyle("thinking_type")
                // .thinkingModels(
                //         "glm-4.5", "glm-4.5-air", "glm-4.5-flash",
                //         "glm-4.6", "glm-4.7",
                //         "glm-5", "glm-5.1")
                .thinkingAlwaysOnModels("glm-5.3", "glm-5.3-flash")
                .build());

        // Moonshot (Kimi). base_url follows the official Kimi K3 quickstart (api.moonshot.cn/v1).
        //   K2.5/K2.6/K2.7: temperature fixed at 1.0, thinking via the `thinking.type` object
        //     (thinking_type). K2.7 Code 思考不可关闭：reasoning_effort=none 时强制 enabled。
        //   K3 (kimi-k3): thinking 始终开启且无法关闭，由请求顶层 `reasoning_effort`（low/high/max，
        //     默认 max）控制；temperature 固定 1.0。与其他 moonshot 模型一视同仁：复用标准
        //     toReasoningEffort（含 MAX），继承 provider 的 thinking_type（K3 实际由 reasoning_effort
        //     决定思考；thinking.type 即使被忽略也无害）。reasoning.effort 配成 minimal/medium/xhigh
        //     时由 K3 服务端报错，不做静默 clamp。
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("moonshot")
                .displayName("Moonshot")
                .defaultApiBase("https://api.moonshot.cn/v1")
                .envKey("moonshot.api.key")
                .keywords("moonshot", "kimi")
                .thinkingStyle("thinking_type")
                .thinkingModels("kimi-k2.5", "kimi-k2.6", "kimi-k2.7-code", "k2.6-code-preview", "kimi-k3")
                .thinkingAlwaysOnModels("kimi-k2.7-code", "kimi-k3")
                .addModelOverride("kimi-k2.5", "temperature", 1.0)
                .addModelOverride("kimi-k2.6", "temperature", 1.0)
                .addModelOverride("kimi-k2.7-code", "temperature", 1.0)
                .addModelOverride("kimi-k3", "temperature", 1.0)
                .build());

        // MiniMax. 思考开关是 thinking.type（不是 reasoning_split——后者只是输出格式开关）：
        //   M3 系列：开=adaptive / 关=disabled（传 enabled 直接 HTTP 400）；
        //   M2.x 系列：开=enabled / 关=disabled（服务端忽略 disabled，思考关不掉，已知限制）。
        //   思考开时一并发送 reasoning_split:true，使推理落到 reasoning_content 字段。
        //   详见 minimax_thinking 样式与
        //   https://platform.minimaxi.com/docs/api-reference/text-openai-api#thinking-控制
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("minimax")
                .displayName("MiniMax")
                .defaultApiBase("https://api.minimaxi.com/v1")
                .envKey("minimax.api.key")
                .keywords("minimax")
                .thinkingStyle("minimax_thinking")
                .build());

        // LangCat (OpenAI 兼容, thinking_type: thinking={"type":"enabled"|"disabled"})
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("langcat")
                .displayName("LangCat")
                .defaultApiBase("https://api.longcat.chat/openai/v1")
                .envKey("langcat.api.key")
                .keywords("langcat", "longcat")
                .thinkingStyle("thinking_type")
                .build());

        // =====================================================
        // Existing Providers (for backward compatibility)
        // =====================================================

        // OpenAI
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("openai")
                .displayName("OpenAI")
                .defaultApiBase("https://api.openai.com/v1")
                .envKey("openai.api.key")
                .keywords("openai", "gpt")
                .backend("openai_compat")
                .build());

        // Anthropic (Claude) - uses different SDK
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("anthropic")
                .displayName("Anthropic")
                .defaultApiBase("https://api.anthropic.com")
                .envKey("anthropic.api.key")
                .keywords("anthropic", "claude")
                .backend("anthropic")
                .build());

        // Ollama (local models)
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("ollama")
                .displayName("Ollama")
                .defaultApiBase("http://localhost:11434/v1")
                .envKey("ollama.api.key")
                .keywords("ollama", "llama", "mistral", "codellama")
                .backend("openai_compat")
                .build());

        log.info("Loaded {} provider specifications", PROVIDERS.size());
    }

    /**
     * Get all registered providers.
     *
     * @return Unmodifiable list of all providers
     */
    public static List<ProviderSpec> getAllProviders() {
        return Collections.unmodifiableList(PROVIDERS);
    }

    /**
     * Find a provider by name.
     *
     * @param name The provider name
     * @return The provider spec, or null if not found
     */
    public static ProviderSpec findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        String normalizedName = name.toLowerCase();
        for (ProviderSpec spec : PROVIDERS) {
            if (spec.getName().equals(normalizedName)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * Find a provider by model name.
     * Checks if the model name contains any provider keywords.
     *
     * @param model The model name (e.g., "glm-4-plus", "deepseek-chat")
     * @return The provider spec, or null if not found
     */
    public static ProviderSpec findByModel(String model) {
        if (model == null || model.isEmpty()) {
            return null;
        }

        String lowerModel = model.toLowerCase();

        // Check each provider's keywords
        for (ProviderSpec spec : PROVIDERS) {
            for (String keyword : spec.getKeywords()) {
                if (lowerModel.contains(keyword)) {
                    log.debug("Detected provider '{}' from model '{}'", spec.getName(), model);
                    return spec;
                }
            }
        }

        return null;
    }

    /**
     * Detect provider from model ID or API key.
     * This is the main entry point for provider detection.
     *
     * @param modelIdOrKey Model ID or API key string
     * @return The detected provider spec, or null if not found
     */
    public static ProviderSpec detectProvider(String modelIdOrKey) {
        if (modelIdOrKey == null || modelIdOrKey.isEmpty()) {
            return findByName(AiConfig.getDefaultProvider());
        }

        // first try to find by model name
        ProviderSpec spec = findByModel(modelIdOrKey);
        if (spec != null) {
            return spec;
        }

        // If no match, use global default provider
        String defaultProvider = AiConfig.getDefaultProvider();
        log.debug("No provider detected for '{}', using default provider: {}", modelIdOrKey, defaultProvider);
        return findByName(defaultProvider);
    }

    /**
     * Get models for a specific provider.
     * Reads from properties file, with fallback to default models.
     *
     * @param providerName The provider name
     * @return List of model names, or empty list if provider not found
     */
    public static List<String> getModelsForProvider(String providerName) {
        ProviderSpec spec = findByName(providerName);
        if (spec == null) {
            return Collections.emptyList();
        }

        // Always use the global default model
        String defaultModel = AiConfig.getDefaultModel();
        log.info("Using global default model for provider {}: {}", providerName, defaultModel);
        return List.of(defaultModel);
    }
}
