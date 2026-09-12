package org.gitee.jmeter.ai.agent.command;

import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.VersionUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Built-in slash command handlers.
 * Java equivalent of Nanobot's builtin.py.
 */
public class BuiltinCommands {

    /** Start a fresh session. Exact command. */
    public static String cmdNew(CommandContext ctx) {
        // 重置核心（唯一实现，与 GUI "+" 按钮共用）：中止在跑回合与子代理、代数 +1
        // （垂死回合的注入残留不 re-publish 进新会话）、归档/清空/落盘/失效缓存。
        // 走工厂跨实例路由：RESET 先触达当前+退役 loop 上该会话的在跑回合（模型
        // 切换换血后，旧 loop 上的 IPC/委派回合不再漏取消），重置核心在 self（=
        // ctx.getLoop()，直构 loop 亦正确重置）上执行——命令回合自身的 ThreadLocal
        // 身份豁免在 self 腿内原样生效（/new 不自杀）
        AgentLoopFactory.resetConversationAny(ctx.getLoop(), ctx.getSessionOrCreate().getKey());
        return "New session started.";
    }

    /** Build a status snapshot for the session. Registered as both priority and exact. */
    public static String cmdStatus(CommandContext ctx) {
        String version = VersionUtils.getVersion();
        String provider = AiConfig.getDefaultProvider();
        String model = AiConfig.getDefaultModel();
        int contextWindowTokens = AiConfig.getContextWindowTokens();
        int maxTokens = AiConfig.getMaxTokens();

        Map<String, Integer> lastUsage = ctx.getLoop().getLastUsage();
        int lastIn = lastUsage.getOrDefault("prompt_tokens", 0);
        int lastOut = lastUsage.getOrDefault("completion_tokens", 0);

        int ctxTotal = contextWindowTokens;
        // 口径对齐用量环：优先 API 实报 prompt_tokens（本地 tokenizer 估算系统性偏低
        // ~5%）；无实报（冷会话）才回落 estimateSessionTokens
        int ctxEst = lastIn;
        if (ctxEst <= 0) {
            try {
                ctxEst = ctx.getLoop().getMemoryConsolidator().estimateSessionTokens(ctx.getSessionOrCreate());
            } catch (Exception ignored) {
                // best-effort：估算不可用时按无数据继续
            }
        }
        int ctxPct = ctxTotal > 0 ? (int) ((ctxEst / (double) ctxTotal) * 100) : 0;
        String ctxUsedStr = ctxEst >= 1000 ? (ctxEst / 1000) + "k" : String.valueOf(ctxEst);
        String ctxTotalStr = ctxTotal > 0 ? (ctxTotal / 1024) + "k" : "n/a";

        Session session = ctx.getSessionOrCreate();
        int sessionMsgCount = session.getMessageCount();

        Instant start = ctx.getLoop().getStartTime();
        long uptimeS = Duration.between(start, Instant.now()).getSeconds();
        String uptime = uptimeS >= 3600
                ? (uptimeS / 3600) + "h " + ((uptimeS % 3600) / 60) + "m"
                : (uptimeS / 60) + "m " + (uptimeS % 60) + "s";

        return "Gitee Ai - JMeter Agent v" + version + "\n" +
               "Provider: " + provider + "\n" +
               "Model: " + model + "\n" +
               "Context Window: " + ctxTotalStr + "\n" +
               "Max Tokens: " + maxTokens + "\n" +
               "Last Tokens: " + lastIn + " in / " + lastOut + " out\n" +
               "Current Context: " + ctxUsedStr + "/" + ctxTotalStr + " (" + ctxPct + "%)\n" +
               "Session: " + sessionMsgCount + " messages\n" +
               "Uptime: " + uptime;
    }

    /** Return available slash commands. Exact command. */
    public static String cmdHelp(CommandContext ctx) {
        return String.join("\n",
                "Gitee Ai commands:",
                "/new — Start a new conversation",
                "/status — Show bot status",
                "/help — Show available commands"
        );
    }

    /** Register the default set of slash commands. */
    public static void registerBuiltinCommands(CommandRouter router) {
        router.priority("/status", BuiltinCommands::cmdStatus);
        router.exact("/new", BuiltinCommands::cmdNew);
        router.exact("/status", BuiltinCommands::cmdStatus);
        router.exact("/help", BuiltinCommands::cmdHelp);
    }
}
