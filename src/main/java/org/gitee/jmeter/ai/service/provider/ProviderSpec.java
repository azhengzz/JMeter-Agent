package org.gitee.jmeter.ai.service.provider;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable metadata class for LLM provider specifications.
 * Uses Builder pattern for construction.
 */
public final class ProviderSpec {
    private final String name;
    private final String displayName;
    private final String defaultApiBase;
    private final String envKey;
    private final String[] keywords;
    private final String backend;
    private final Map<String, Map<String, Object>> modelOverrides;
    private final Set<String> thinkingModels;

    // How to inject the thinking on/off toggle into extra_body.
    // ""                — no extra_body needed (default)
    // "thinking_type"   — {"thinking": {"type": "enabled"/"disabled"}}  (DeepSeek, VolcEngine, BytePlus)
    // "enable_thinking" — {"enable_thinking": true/false}  (DashScope)
    // "minimax_thinking" — MiniMax 思考开关（thinking.type，非 reasoning_split）：
    //                      开 → {"thinking":{"type": adaptive(M3)/enabled(M2.x)}, "reasoning_split": true}；
    //                      关 → {"thinking":{"type":"disabled"}}
    private final String thinkingStyle;

    // Models whose thinking mode cannot be disabled (e.g. Moonshot kimi-k2.7-code, kimi-k3,
    // Zhipu glm-5.3/glm-5.3-flash).
    // Sending a disabled thinking.type for these models causes API rejection;
    // force enabled regardless of reasoning_effort.
    private final Set<String> thinkingAlwaysOnModels;

    private ProviderSpec(Builder builder) {
        this.name = builder.name;
        this.displayName = builder.displayName;
        this.defaultApiBase = builder.defaultApiBase;
        this.envKey = builder.envKey;
        this.keywords = builder.keywords;
        this.backend = builder.backend;
        this.modelOverrides = Collections.unmodifiableMap(new HashMap<>(builder.modelOverrides));
        this.thinkingModels = builder.thinkingModels != null
                ? Collections.unmodifiableSet(new HashSet<>(builder.thinkingModels))
                : Collections.emptySet();
        this.thinkingStyle = builder.thinkingStyle;
        this.thinkingAlwaysOnModels = builder.thinkingAlwaysOnModels != null
                ? Collections.unmodifiableSet(new HashSet<>(builder.thinkingAlwaysOnModels))
                : Collections.emptySet();
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultApiBase() {
        return defaultApiBase;
    }

    public String getEnvKey() {
        return envKey;
    }

    public String[] getKeywords() {
        return keywords;
    }

    public String getBackend() {
        return backend;
    }

    public Map<String, Map<String, Object>> getModelOverrides() {
        return modelOverrides;
    }

    public Map<String, Object> getOverridesForModel(String model) {
        return modelOverrides.getOrDefault(model, Collections.emptyMap());
    }

    public boolean supportsThinking(String model) {
        if (thinkingModels.isEmpty()) return true;
        return model != null && thinkingModels.contains(model.toLowerCase());
    }

    public String getThinkingStyle() {
        return thinkingStyle;
    }

    public boolean isThinkingAlwaysOn(String model) {
        if (thinkingAlwaysOnModels.isEmpty()) return false;
        return model != null && thinkingAlwaysOnModels.contains(model.toLowerCase());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProviderSpec that = (ProviderSpec) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "ProviderSpec{" +
                "name='" + name + '\'' +
                ", displayName='" + displayName + '\'' +
                ", defaultApiBase='" + defaultApiBase + '\'' +
                ", backend='" + backend + '\'' +
                '}';
    }

    /**
     * Builder for ProviderSpec.
     */
    public static class Builder {
        private String name;
        private String displayName;
        private String defaultApiBase;
        private String envKey;
        private String[] keywords = new String[0];
        private String backend = "openai_compat";
        private final Map<String, Map<String, Object>> modelOverrides = new HashMap<>();
        private Set<String> thinkingModels;
        private String thinkingStyle = "";
        private Set<String> thinkingAlwaysOnModels;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder defaultApiBase(String defaultApiBase) {
            this.defaultApiBase = defaultApiBase;
            return this;
        }

        public Builder envKey(String envKey) {
            this.envKey = envKey;
            return this;
        }

        public Builder keywords(String... keywords) {
            this.keywords = keywords;
            return this;
        }

        public Builder backend(String backend) {
            this.backend = backend;
            return this;
        }

        /**
         * Set the thinking style for this provider.
         * @param thinkingStyle one of "", "thinking_type", "enable_thinking", "minimax_thinking"
         * @return this builder
         */
        public Builder thinkingStyle(String thinkingStyle) {
            this.thinkingStyle = thinkingStyle != null ? thinkingStyle : "";
            return this;
        }

        /**
         * Mark models whose thinking mode cannot be disabled. For these models the
         * thinking extra_body is forced to enabled regardless of reasoning_effort.
         * @param models model names (case-insensitive)
         * @return this builder
         */
        public Builder thinkingAlwaysOnModels(String... models) {
            this.thinkingAlwaysOnModels = Arrays.stream(models)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            return this;
        }

        /**
         * Add a parameter override for a specific model.
         *
         * @param model The model name
         * @param param The parameter name (e.g., "temperature")
         * @param value The parameter value
         * @return this builder
         */
        public Builder addModelOverride(String model, String param, Object value) {
            modelOverrides.computeIfAbsent(model, k -> new HashMap<>()).put(param, value);
            return this;
        }

        public Builder thinkingModels(String... models) {
            this.thinkingModels = Arrays.stream(models)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            return this;
        }

        public ProviderSpec build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Provider name is required");
            }
            if (displayName == null || displayName.isEmpty()) {
                displayName = name;
            }
            if (defaultApiBase == null || defaultApiBase.isEmpty()) {
                throw new IllegalArgumentException("Default API base URL is required");
            }
            if (envKey == null || envKey.isEmpty()) {
                envKey = name.toUpperCase() + "_API_KEY";
            }
            return new ProviderSpec(this);
        }
    }
}
