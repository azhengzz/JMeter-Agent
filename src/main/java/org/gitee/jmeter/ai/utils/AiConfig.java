package org.gitee.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class AiConfig {
    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    public static String getProperty(String key, String defaultValue) {
        return JMeterUtils.getPropDefault(key, defaultValue);
    }

    /**
     * Typed accessors. Unset (null/empty) values return {@code defaultValue}; a
     * non-numeric configured value logs an ERROR naming the bad value and falls back
     * to {@code defaultValue} instead of throwing.
     */
    public static int getInt(String key, int defaultValue) {
        String v = JMeterUtils.getPropDefault(key, null);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            log.error("Config {} expects an integer but was '{}'; using default {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    public static long getLong(String key, long defaultValue) {
        String v = JMeterUtils.getPropDefault(key, null);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            log.error("Config {} expects a long but was '{}'; using default {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String v = JMeterUtils.getPropDefault(key, null);
        return (v == null || v.isEmpty()) ? defaultValue : Boolean.parseBoolean(v);
    }

    public static double getDouble(String key, double defaultValue) {
        String v = JMeterUtils.getPropDefault(key, null);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            log.error("Config {} expects a double but was '{}'; using default {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Get the default model from global configuration.
     */
    public static String getDefaultModel() {
        return JMeterUtils.getPropDefault("jmeter.ai.default.model", "deepseek-v4-flash");
    }

    /**
     * Get the global default provider.
     */
    public static String getDefaultProvider() {
        return JMeterUtils.getPropDefault("jmeter.ai.default.provider", "deepseek");
    }

    // ---- IPC server (CLI 驱动运行中 GUI) ----

    /**
     * IPC server 是否启用。默认开启(仅 loopback + token 鉴权)。
     */
    public static boolean isIpcEnabled() {
        return getBoolean("jmeter.ai.ipc.enabled", true);
    }

    /**
     * IPC server 监听端口,0 = 自动分配(推荐)。
     */
    public static int getIpcPort() {
        return getInt("jmeter.ai.ipc.port", 0);
    }

    /**
     * IPC server 绑定地址,仅接受 loopback。默认 127.0.0.1。
     */
    public static String getIpcBind() {
        return JMeterUtils.getPropDefault("jmeter.ai.ipc.bind", "127.0.0.1");
    }

    /**
     * IPC 鉴权 token,空 = 启动时随机生成并写入端口文件。
     */
    public static String getIpcToken() {
        return JMeterUtils.getPropDefault("jmeter.ai.ipc.token", "");
    }

    /**
     * Agent 路由同步等待超时(毫秒),默认 120s。
     */
    public static long getIpcAgentTimeoutMs() {
        return getLong("jmeter.ai.ipc.agent.timeout.ms", 120000);
    }

    // ---- Run result capture (GUI-initiated runs) ----

    /**
     * Whether to capture results of user-initiated (GUI Run button) test runs via a global
     * Start.class pre-action listener. Default true. Only gates Start-listener registration;
     * the Save.class strip-listener (anti-jmx-leak) and RunTestTool's own injection are
     * unaffected, so {@code run_test} always captures regardless of this toggle.
     */
    public static boolean isRunCaptureEnabled() {
        return getBoolean("agent.runcapture.enabled", true);
    }

    // ---- Multi-instance session / coordination ----

    /**
     * 每实例独立会话文件(按启动 instanceId)是否启用。默认 true;false 回退到全局 legacy session key。
     */
    public static boolean isSessionPerInstance() {
        return getBoolean("agent.session.per-instance", true);
    }

    /**
     * 关闭整合"深度提炼"(写 MEMORY.md)的有界超时(毫秒)。默认 120s;超时保留已整合部分并继续退出。
     */
    public static long getConsolidateOnExitTimeoutMs() {
        return getLong("agent.memory.consolidate-on-exit.timeout.ms", 120000L);
    }

    /**
     * 孤立会话文件回收的存活 TTL(毫秒):启动期扫描 {@code sessions/} 时,仅回收注册表确认已失活
     * 且最后修改时间超过本 TTL 的 {@code {pid}-{startedAtMs}.jsonl}。读自 {@code agent.session.reap.ttl.days},
     * 默认 7 天。
     */
    public static long getSessionReapTtlMs() {
        int days = getInt("agent.session.reap.ttl.days", 7);
        return days * 24L * 60L * 60L * 1000L;
    }

    // ---- Agent 核心循环 ----

    /**
     * Agent Loop 是否启用。默认 true。
     */
    public static boolean isAgentEnabled() {
        return getBoolean("agent.enabled", true);
    }

    /** 单次 Agent 回合最大工具迭代次数。默认 50。 */
    public static int getMaxToolIterations() {
        return getInt("jmeter.ai.max.tool.iterations", 50);
    }

    /** 上下文窗口 token 上限。默认 65536。 */
    public static int getContextWindowTokens() {
        return getInt("jmeter.ai.context.window.tokens", 65536);
    }

    /** 生成最大 token 数。默认 4096。 */
    public static int getMaxTokens() {
        return getInt("jmeter.ai.max.tokens", 4096);
    }

    /** 工具结果最大字符数(超过截断)。默认 16000。 */
    public static int getToolResultMaxChars() {
        return getInt("agent.tool.result.max.chars", 16000);
    }

    /** 单次工具调用超时(毫秒)。默认 30000。 */
    public static long getToolTimeoutMs() {
        return getLong("agent.tools.timeout.ms", 30000);
    }

    /** 对话历史消息条数上限。默认 120。 */
    public static int getMaxHistorySize() {
        return getInt("jmeter.ai.max.history.size", 120);
    }

    /** 工具输出字符串最大长度。默认 2048。 */
    public static int getMaxStringLength() {
        return getInt("jmeter.ai.tool.max.string.length", 2048);
    }

    /** 生成温度。默认 0.7。 */
    public static double getTemperature() {
        return getDouble("jmeter.ai.temperature", 0.7);
    }

    /** 推理强度(reasoning effort)。默认 high。 */
    public static String getReasoningEffort() {
        return getProperty("jmeter.ai.reasoning.effort", "high");
    }

    /** 自定义系统提示词。默认空串。 */
    public static String getSystemPrompt() {
        return getProperty("jmeter.ai.system.prompt", "");
    }

    /** 注入队列容量。默认 20。 */
    public static int getInjectionQueueSize() {
        return getInt("jmeter.ai.injection.queue.size", 20);
    }

    /** 每回合注入条数上限。默认 3。 */
    public static int getInjectionMaxPerTurn() {
        return getInt("jmeter.ai.injection.max.per.turn", 3);
    }

    // ---- 工具开关与参数 ----

    /** JMeter 工具是否启用。默认 true。 */
    public static boolean isJmeterToolsEnabled() {
        return getBoolean("agent.tools.jmeter.enabled", true);
    }

    /** 文件系统工具是否启用。默认 true。 */
    public static boolean isFilesystemToolsEnabled() {
        return getBoolean("agent.tools.filesystem.enabled", true);
    }

    /** web 工具是否启用。默认 true。 */
    public static boolean isWebsearchToolsEnabled() {
        return getBoolean("agent.tools.websearch.enabled", true);
    }

    /** exec 工具是否启用。默认 true。 */
    public static boolean isExecToolsEnabled() {
        return getBoolean("agent.tools.exec.enabled", true);
    }

    /** 文件系统工具允许的目录(逗号分隔)。默认空串。 */
    public static String getFilesystemAllowedDirs() {
        return getProperty("agent.tools.filesystem.allowed.dirs", "");
    }

    /** 文件系统工具拒绝的目录(逗号分隔)。默认空串。 */
    public static String getFilesystemDeniedDirs() {
        return getProperty("agent.tools.filesystem.denied.dirs", "");
    }

    /** exec 工具超时(秒)。默认 60。 */
    public static int getExecTimeout() {
        return getInt("agent.tools.exec.timeout", 60);
    }

    /** exec 工具工作目录。默认空串。 */
    public static String getExecWorkingDir() {
        return getProperty("agent.tools.exec.working.dir", "");
    }

    /** exec 工具 PATH 追加。默认空串。 */
    public static String getExecPathAppend() {
        return getProperty("agent.tools.exec.path.append", "");
    }

    /** exec 工具拒绝的命令模式。默认空串。 */
    public static String getExecDenyPatterns() {
        return getProperty("agent.tools.exec.deny.patterns", "");
    }

    // ---- web 工具 ----

    /** web 工具 SSRF 防护是否启用。默认 true。 */
    public static boolean isWebSsrfProtection() {
        return getBoolean("agent.tools.web.ssrf.protection", true);
    }

    /** web 工具最大重定向次数。默认 5。 */
    public static int getWebMaxRedirects() {
        return getInt("agent.tools.web.max.redirects", 5);
    }

    /** webfetch 超时(秒)。默认 30。 */
    public static int getWebfetchTimeout() {
        return getInt("agent.tools.webfetch.timeout", 30);
    }

    /** websearch provider。默认 jina。 */
    public static String getWebsearchProvider() {
        return getProperty("agent.tools.websearch.provider", "jina");
    }

    /** websearch 最大结果数。默认 10。 */
    public static int getWebsearchMaxResults() {
        return getInt("agent.tools.websearch.max.results", 10);
    }

    /** websearch 超时(秒)。默认 30。 */
    public static int getWebsearchTimeout() {
        return getInt("agent.tools.websearch.timeout", 30);
    }

    /** websearch Jina API key。默认空串。 */
    public static String getWebsearchJinaApiKey() {
        return getProperty("agent.tools.websearch.jina.api.key", "");
    }

    /** websearch Brave API key。默认空串。 */
    public static String getWebsearchBraveApiKey() {
        return getProperty("agent.tools.websearch.brave.api.key", "");
    }

    /** websearch Tavily API key。默认空串。 */
    public static String getWebsearchTavilyApiKey() {
        return getProperty("agent.tools.websearch.tavily.api.key", "");
    }

    // ---- 子代理 ----

    /** 异步子代理是否启用。默认 false。 */
    public static boolean isSubagentEnabled() {
        return getBoolean("agent.subagent.enabled", false);
    }

    /** 每主会话并发子代理上限。默认 1。 */
    public static int getSubagentMaxConcurrent() {
        return getInt("agent.subagent.max.concurrent", 1);
    }

    /** 单次子代理工具迭代上限。默认 50。 */
    public static int getSubagentMaxIterations() {
        return getInt("agent.subagent.max.iterations", 50);
    }

    /** 主回合等待子代理结果的阻塞时长(秒)。默认 120。 */
    public static long getSubagentDrainTimeoutSeconds() {
        return getLong("agent.subagent.drain.timeout.seconds", 120);
    }

    /** 完成态子代理状态的可查询保留时长(秒)。默认 60。 */
    public static long getSubagentStatusRetentionSeconds() {
        return getLong("agent.subagent.status.retention.seconds", 60);
    }

    /** 每会话保留的完成态子代理状态上限(超出按最旧淘汰)。默认 10。 */
    public static int getSubagentStatusMaxCompleted() {
        return getInt("agent.subagent.status.max.completed", 10);
    }

    // ---- 记忆 / 会话 ----

    /** Agent 记忆是否启用。默认 true。 */
    public static boolean isMemoryEnabled() {
        return getBoolean("agent.memory.enabled", true);
    }

    // ---- Anthropic / OpenAI 服务键 ----

    /** Anthropic API key。默认空串(未配置)。 */
    public static String getAnthropicApiKey() {
        return getProperty("anthropic.api.key", "");
    }

    /** Anthropic 日志级别。默认空串。 */
    public static String getAnthropicLogLevel() {
        return getProperty("anthropic.log.level", "");
    }

    /** Anthropic API base URL。默认空串(走 SDK 默认端点)。 */
    public static String getAnthropicApiBaseUrl() {
        return getProperty("anthropic.api.base.url", "");
    }

    /** OpenAI API key。默认空串。 */
    public static String getOpenAiApiKey() {
        return getProperty("openai.api.key", "");
    }

    // ---- LangSmith 链路追踪 ----

    /** LangSmith 追踪是否启用。默认 false。 */
    public static boolean isLangsmithEnabled() {
        return getBoolean("langsmith.enabled", false);
    }

    /** LangSmith 采样率 (0.0-1.0)。默认 1.0 = 全量追踪。 */
    public static double getLangsmithSampleRate() {
        return getDouble("langsmith.sample.rate", 1.0);
    }

    /** LangSmith 项目名。默认 jmeter-ai。 */
    public static String getLangsmithProjectName() {
        return getProperty("langsmith.project.name", "jmeter-ai");
    }

    /** LangSmith API 端点。默认官方云；可覆盖为自托管/EU 区域。 */
    public static String getLangsmithEndpoint() {
        return getProperty("langsmith.endpoint", "https://api.smith.langchain.com");
    }

    /** LangSmith API key。默认空串。 */
    public static String getLangsmithApiKey() {
        return getProperty("langsmith.api.key", "");
    }

    // ---- GUI / 展示 ----

    /** 聊天输入框字体大小(0=默认)。默认 0。 */
    public static int getChatFontSize() {
        return getInt("ai.chat.font.size", 0);
    }

    /** 是否显示工具调用。默认 true。 */
    public static boolean isChatShowToolCalls() {
        return getBoolean("ai.chat.show.tool.calls", true);
    }

    /** 是否显示思考内容。默认 false。 */
    public static boolean isChatShowThinking() {
        return getBoolean("ai.chat.show.thinking", false);
    }

    /** 聊天工具结果最大展示长度。默认 500。 */
    public static int getChatToolResultMaxLength() {
        return getInt("ai.chat.tool.result.max.length", 500);
    }

    /** JMeter LoggerPanel 最大行数(仅用于 get_log_panel_content 的容量提示)。默认 1000。 */
    public static int getLoggerPanelMaxLength() {
        return getInt("jmeter.loggerpanel.maxlength", 1000);
    }

    // ---- workspace ----

    /**
     * workspace 路径。解析委托 {@link WorkspacePaths#resolveWorkspace()}(单一事实来源,
     * 含 {@code agent.workspace.path} 属性 / JMETER_HOME / user.dir 三档回退)。
     */
    public static Path getWorkspacePath() {
        return WorkspacePaths.resolveWorkspace();
    }

    /**
     * 打印当前配置快照(仅存活配置项)。供启动路径调用一次。
     */
    public static void logConfiguration() {
        log.info("Agent Configuration:");
        log.info("  enabled: {}", isAgentEnabled());
        log.info("  maxToolIterations: {}", getMaxToolIterations());
        log.info("  contextWindowTokens: {}", getContextWindowTokens());
        log.info("  maxHistorySize: {}", getMaxHistorySize());
        log.info("  maxStringLength: {}", getMaxStringLength());
        log.info("  memoryEnabled: {}", isMemoryEnabled());
        log.info("  workspacePath: {}", getWorkspacePath());
        log.info("  jmeterToolsEnabled: {}", isJmeterToolsEnabled());
        log.info("  toolTimeoutMs: {}", getToolTimeoutMs());
    }
}
