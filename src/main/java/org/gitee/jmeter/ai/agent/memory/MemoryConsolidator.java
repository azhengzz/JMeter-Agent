package org.gitee.jmeter.ai.agent.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.model.*;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * AI-powered memory consolidation aligned with Nanobot's MemoryStore.consolidate().
 * Uses forced tool calling (save_memory) for structured output,
 * with auto retry and raw-archive degradation.
 */
public class MemoryConsolidator {
    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int MAX_RETRIES = 3;
    private static final int MAX_CONSOLIDATION_ROUNDS = 5;
    private static final int SAFETY_BUFFER = 1024;

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private static final ToolDefinition SAVE_MEMORY_TOOL_DEF = ToolDefinition.builder()
            .name("save_memory")
            .description("Save the memory consolidation result to persistent storage.")
            .parameters(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "history_entry", Map.of(
                                    "type", "string",
                                    "description", "A paragraph summarizing key events/decisions/topics. Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search."
                            ),
                            "memory_update", Map.of(
                                    "type", "string",
                                    "description", "Full updated long-term memory as markdown. Include all existing facts plus new ones. Return unchanged if nothing new."
                            )
                    ),
                    "required", List.of("history_entry", "memory_update")
            ))
            .build();

    private final MemoryStore memoryStore;
    private final AiService aiService;
    private final SessionManager sessionManager;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry toolRegistry;
    private final int contextWindowTokens;
    private final int maxCompletionTokens;
    private int consecutiveFailures = 0;

    public MemoryConsolidator(MemoryStore memoryStore, AiService aiService, SessionManager sessionManager,
                              ContextBuilder contextBuilder, ToolRegistry toolRegistry) {
        this.memoryStore = memoryStore;
        this.aiService = aiService;
        this.sessionManager = sessionManager;
        this.contextBuilder = contextBuilder;
        this.toolRegistry = toolRegistry;
        this.contextWindowTokens = AiConfig.getContextWindowTokens();
        this.maxCompletionTokens = AiConfig.getMaxTokens();
    }

    /**
     * Consolidate a session when needed — multi-round support (Nanobot: maybe_consolidate_by_tokens).
     * Entry guard: skip if memory store disabled, or estimated tokens within budget.
     * Loop: archive old messages until estimated tokens <= target (budget / 2).
     *
     * <p>同步方法,内联跑在 AgentRunner 的 run 执行线程上——两个调用方(前置/后置整合)都需要
     * 在回合推进前拿到结果:前置推进 {@code lastConsolidatedIndex} 决定本轮上下文,后置必须
     * 先于回合 future 完成落地(防僵尸回合写盘;run() 同步直调,回合 future 在 run() 返回后
     * 才 complete)。取消事实来源是共享 abort flag:
     * {@code signalCancel} 先置 flag 再 interrupt,故 interrupt 落在任何阶段(等锁 sleep /
     * LLM 调用)都收敛到与 flag 相同的"不落盘"结局。每轮开始前轮询 {@code aborted},配合
     * {@link #consolidateWithAi(List, BooleanSupplier)} 写盘前检查,让被取消的回合不再写
     * HISTORY/MEMORY/session,避免与关闭对话框的深度提炼 + 清会话竞态。
     *
     * @param aborted 为 true 时本轮立即停止;调用方传 {@code () -> isAborted(spec)}
     *                 (flag + 中断位,均在 run 执行线程上求值)
     */
    public void maybeConsolidate(Session session, BooleanSupplier aborted) {
        if (!memoryStore.isEnabled()) {
            return;
        }

        int budget = contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
        int target = budget / 2;

        for (int round = 0; round < MAX_CONSOLIDATION_ROUNDS; round++) {
            if (aborted.getAsBoolean()) {
                log.info("Memory consolidation aborted before round {} for session {}",
                        round, session.getKey());
                break;
            }
            int estimated = estimateSessionTokens(session);
            if (estimated <= 0) {
                break;
            }
            if (estimated < budget) {
                log.info("Token consolidation idle {}: {}/{} tokens",
                        session.getKey(), estimated, contextWindowTokens);
                break;
            }
            if (estimated <= target) {
                log.info("Consolidation target reached: {} <= {} tokens", estimated, target);
                break;
            }

            int boundary = pickConsolidationBoundary(session, Math.max(1, estimated - target));
            if (boundary < 0) {
                log.info("No safe consolidation boundary found (round {})", round);
                break;
            }

            List<Message> chunk = session.getMessagesInRange(
                    session.getLastConsolidatedIndex(), boundary);
            if (chunk.isEmpty()) {
                break;
            }

            log.info("Consolidation round {} for session {}: estimated={}/{} tokens, chunk={} msgs",
                    round, session.getKey(), estimated, contextWindowTokens, chunk.size());

            if (!consolidateWithAi(chunk, aborted)) {
                log.warn("Consolidation round {} stopped (failed or aborted)", round);
                break;
            }

            session.setLastConsolidatedIndex(boundary);
            if (sessionManager != null) {
                sessionManager.saveSession(session);
            }
        }
    }

    /**
     * Archive messages with guaranteed persistence (Nanobot's archive_messages).
     * Retries AI consolidation up to MAX_RETRIES times, then falls back to raw archive.
     */
    public CompletableFuture<Boolean> archiveMessagesAsync(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            log.info("Archiving {} messages with AI consolidation", messages.size());

            for (int i = 0; i < MAX_RETRIES; i++) {
                if (consolidateWithAi(messages)) {
                    consecutiveFailures = 0;
                    return true;
                }
                log.warn("AI consolidation attempt {}/{} failed", i + 1, MAX_RETRIES);
            }

            // All retries failed — raw archive as last resort (Nanobot: _fail_or_raw_archive)
            log.warn("All AI consolidation attempts failed, falling back to raw archive");
            rawArchive(messages);
            return true;
        });
    }

    /**
     * 同步归档(无 LLM、无异步池):把 session 未整合消息原样追加进共享 {@code HISTORY.md},
     * 并把 {@code lastConsolidatedIndex} 推进到末尾。
     *
     * <p>幂等:重复调用时未整合集已空即 no-op(不会重复写)。供关闭期"始终静默归档"路径
     * (跨实例共享日志桥)使用;调用方负责随后 {@code saveSession} 持久化推进后的索引。
     *
     * @return 实际归档的消息条数(0 = 无可归档或记忆关闭)。
     */
    public int archiveSync(Session session) {
        if (session == null || !memoryStore.isEnabled()) {
            return 0;
        }
        List<Message> unconsolidated = session.getUnconsolidatedMessages();
        if (unconsolidated.isEmpty()) {
            return 0;
        }
        rawArchive(unconsolidated);
        session.setLastConsolidatedIndex(session.getMessageCount());
        return unconsolidated.size();
    }

    /**
     * 同步深度提炼(写 {@code MEMORY.md},复用 {@link #consolidateWithAi},有界超时)。
     * 在调用方线程阻塞,供关闭整合对话框的 {@code SwingWorker}(非 EDT)调用。
     * 超时/异常按 best-effort 处理,返回 {@code false};不向上抛。
     *
     * <p>预算含等锁 + LLM 全程;超时置共享 {@code timedOut} flag——等锁轮询与写盘前检查
     * 立即放弃,而非把 commonPool 载体线程留在阻塞式 {@code channel.lock()} 上、随 JVM
     * 退出被杀(确认的丢写路径)。超时后深度提炼不落盘(会话不清、
     * HISTORY.md 仍由关闭归档兜底),用户在对话框看到"incomplete"而非静默丢失。
     *
     * @param messages  待提炼的消息(通常为关闭前捕获的未整合快照)
     * @param timeoutMs 有界超时;超时则置 abort flag、取消并返回 {@code false}
     * @return 提炼是否成功完成
     */
    public boolean distillSync(List<Message> messages, long timeoutMs) {
        if (messages == null || messages.isEmpty() || !memoryStore.isEnabled()) {
            return true;
        }
        AtomicBoolean timedOut = new AtomicBoolean(false);
        CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> consolidateWithAi(messages, timedOut::get));
        try {
            boolean ok = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (ok) {
                consecutiveFailures = 0;
            }
            return ok;
        } catch (TimeoutException te) {
            // 先置共享 flag(等锁轮询 / 写盘前检查立即放弃),再取消任务本身。
            // CompletableFuture.cancel 不打断运行线程,flag 才是真正的中止信号。
            timedOut.set(true);
            f.cancel(true);
            log.warn("Close-time distillation timed out after {}ms (best-effort, proceeding)", timeoutMs);
            return false;
        } catch (Exception e) {
            log.warn("Close-time distillation failed (best-effort): {}", e.toString());
            return false;
        }
    }

    /**
     * Core AI consolidation — aligned with Nanobot's MemoryStore.consolidate().
     * Uses forced tool_choice → auto retry → failure tracking → raw-archive.
     */
    private boolean consolidateWithAi(List<Message> messages) {
        return consolidateWithAi(messages, () -> false);
    }

    /**
     * {@link #consolidateWithAi(List)} 的取消感知变体。LLM 调用所在线程随调用路径而异:
     * {@code distillSync}/{@code archiveMessagesAsync} 跑在 commonPool 载体上,关闭期
     * cancelActiveTask 只能置 abort flag、无法打断该调用;{@code maybeConsolidate} 路径
     * 内联在 run 执行线程上,interrupt 可达——但 signalCancel 先置 flag 再 interrupt,
     * 两者收敛到同一"不落盘"结局。因此本方法只在
     * <b>写盘前</b>检查 {@code aborted}——被取消的僵尸回合在 LLM 返回后、写 HISTORY/MEMORY 前
     * 直接放弃落盘(返回 false),不覆盖用户等待的关闭提炼结果。
     *
     * <p>全程持有 {@link MemoryStore#lockLongTermMemory(BooleanSupplier)} 跨进程写锁
     * (读→LLM→写),共享默认 workspace 的双实例并发深度提炼不会互相覆盖。
     * 等锁为 abort 感知轮询:被中止/中断时返回 {@code null} = 未执行,不降级写盘(降级会
     * 重新打开 lost-update 敞口);仅真实 IO 故障才按 best-effort 降级为无锁执行。
     */
    private boolean consolidateWithAi(List<Message> messages, BooleanSupplier aborted) {
        MemoryStore.MemoryWriteLock lock;
        try {
            lock = memoryStore.lockLongTermMemory(aborted);
        } catch (IOException e) {
            log.error("Failed to acquire MEMORY.md write lock, proceeding unlocked (best-effort)", e);
            return consolidateWithAiUnderLock(messages, aborted);
        }
        if (lock == null) {
            // 等锁期间被中止(Stop / 关闭超时)或中断 → 视为未执行、不降级写盘
            log.info("Memory consolidation aborted while waiting for MEMORY.md write lock");
            return false;
        }
        try (MemoryStore.MemoryWriteLock ignored = lock) {
            return consolidateWithAiUnderLock(messages, aborted);
        }
    }

    /** {@link #consolidateWithAi(List, BooleanSupplier)} 的锁内主体。 */
    private boolean consolidateWithAiUnderLock(List<Message> messages, BooleanSupplier aborted) {
        String currentMemory = memoryStore.readLongTermMemory();
        if (aborted.getAsBoolean()) {
            log.info("Memory consolidation aborted before LLM call ({} messages)", messages.size());
            return false;
        }
        String messagesText = formatMessages(messages);

        List<Message> chatMessages = List.of(
                Message.system("You are a memory consolidation agent. Call the save_memory tool with your consolidation of the conversation."),
                Message.user(String.format("""
                        Process this conversation and call the save_memory tool with your consolidation.

                        ## Current Long-term Memory
                        %s

                        ## Conversation to Process
                        %s
                        """,
                        currentMemory.isEmpty() ? "(empty)" : currentMemory,
                        messagesText))
        );

        try {
            // Step 1: try forced tool_choice (Nanobot line 139-145)
            LLMResponse response = aiService.generateResponseWithForcedTool(
                    chatMessages, List.of(SAVE_MEMORY_TOOL_DEF), "save_memory");

            // Step 2: if tool_choice unsupported, retry with auto (Nanobot line 147-156)
            if (response.isError() && isToolChoiceUnsupported(response.getErrorMessage())) {
                log.warn("Forced tool_choice unsupported, retrying with auto");
                response = aiService.generateResponseWithTools(chatMessages, List.of(SAVE_MEMORY_TOOL_DEF));
            }

            // Step 3: if no tool calls, track failure (Nanobot line 158-166)
            if (response.isError()) {
                log.warn("Tool-call consolidation failed: {}", response.getErrorMessage());
                return handleConsolidationFailure(messages, aborted);
            }

            if (!response.hasToolCalls()) {
                log.warn("LLM did not call save_memory tool (finishReason={}, content_len={})",
                        response.getFinishReason(),
                        response.getContent() != null ? response.getContent().length() : 0);
                return handleConsolidationFailure(messages, aborted);
            }

            // Step 4: extract and save (Nanobot line 168-196)
            return extractAndSaveToolCallResult(response, currentMemory, messages, aborted);

        } catch (Exception e) {
            // Step 5: if forced tool_choice caused the exception, try auto
            if (isToolChoiceUnsupported(e.getMessage())) {
                log.warn("Forced tool_choice unsupported (exception), retrying with auto");
                try {
                    LLMResponse response = aiService.generateResponseWithTools(chatMessages, List.of(SAVE_MEMORY_TOOL_DEF));
                    if (!response.isError() && response.hasToolCalls()) {
                        return extractAndSaveToolCallResult(response, currentMemory, messages, aborted);
                    }
                } catch (Exception e2) {
                    log.warn("Auto tool-call also failed: {}", e2.getMessage());
                }
            } else {
                log.error("Memory consolidation failed", e);
            }
            return handleConsolidationFailure(messages, aborted);
        }
    }

    /**
     * Extract save_memory tool call result and persist (Nanobot line 168-196).
     * 写盘前检查 {@code aborted}:被关闭期 cancelActiveTask 取消的僵尸回合在此放弃落盘,
     * 避免覆盖关闭对话框深度提炼刚写好的 MEMORY.md / HISTORY.md。
     */
    private boolean extractAndSaveToolCallResult(LLMResponse response, String currentMemory,
                                                 List<Message> originalMessages, BooleanSupplier aborted) {
        ToolCall saveCall = response.getToolCalls().stream()
                .filter(tc -> "save_memory".equals(tc.getName()))
                .findFirst().orElse(null);

        if (saveCall == null) {
            log.warn("No save_memory tool call in response");
            return handleConsolidationFailure(originalMessages, aborted);
        }

        Map<String, Object> args = saveCall.getArguments();
        String historyEntry = normalizeToString(args.get("history_entry"));
        String memoryUpdate = normalizeToString(args.get("memory_update"));

        if (historyEntry.isEmpty()) {
            log.warn("history_entry is empty after normalization");
            return handleConsolidationFailure(originalMessages, aborted);
        }

        if (aborted.getAsBoolean()) {
            log.info("Memory consolidation aborted before persist ({} messages)", originalMessages.size());
            return false;
        }

        // 两扇门:先写 MEMORY.md,成功后仅追加 HISTORY.md,任一失败返回 false。
        // 顺序关键——先 append 再写 MEMORY,写失败会留下已提交的 history 条目,同一批
        // 消息在下一次重试/关闭时被再次追加(重复条目无限累积);
        // 先写 MEMORY 则重试时 memoryUpdate==currentMemory 跳过 MEMORY 写、仅补 history,幂等。
        if (!memoryUpdate.equals(currentMemory) && !memoryStore.writeLongTermMemory(memoryUpdate)) {
            // 写 MEMORY.md 失败(MEMORY.md 只读/盘满)不能报成功——否则关闭对话框显示
            // "整合完成"并清会话,而内容实际未落盘。返回 false → 调用方视为失败(会话保留,
            // HISTORY.md 仍由关闭归档兜底;失败的内容本就无法落盘,无数据丢失)。
            log.error("Failed to write MEMORY.md (memory_update not persisted) — reporting consolidation failure");
            return false;
        }
        if (!memoryStore.appendHistory(historyEntry)) {
            // 历史侧:history 追加失败(MEMORY.md 已更新)同样不得报成功——否则关闭路径
            // 清会话而 HISTORY.md 无记录,唯一可检索的跨实例日志静默丢失。
            log.error("Failed to append HISTORY.md (history_entry not persisted) — reporting consolidation failure");
            return false;
        }

        log.info("Memory consolidation done for {} messages", originalMessages.size());
        return true;
    }

    private boolean isToolChoiceUnsupported(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("tool_choice") || lower.contains("does not support")
                || lower.contains("should be [\"none\", \"auto\"]");
    }

    /**
     * Handle consolidation failure — track consecutive failures, raw-archive if threshold reached.
     * Aligned with Nanobot's _fail_or_raw_archive.
     */
    private boolean handleConsolidationFailure(List<Message> messages, BooleanSupplier aborted) {
        if (aborted.getAsBoolean()) {
            // 被取消不是失败:不计数、不 rawArchive(避免再写 HISTORY.md)。
            log.info("Memory consolidation aborted, skipping failure handling");
            return false;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_RETRIES) {
            rawArchive(messages);
            consecutiveFailures = 0;
        }
        return false;
    }

    /**
     * Pick a user-turn boundary that removes enough old prompt tokens.
     * Ported from Nanobot's MemoryConsolidator.pick_consolidation_boundary().
     * Returns the index of the user-turn boundary, or -1 if none found.
     */
    private int pickConsolidationBoundary(Session session, int tokensToRemove) {
        List<Message> all = session.getMessages();
        int start = session.getLastConsolidatedIndex();
        if (start >= all.size() || tokensToRemove <= 0) {
            return -1;
        }

        int removedTokens = 0;
        int lastBoundary = -1;

        for (int i = start; i < all.size(); i++) {
            Message msg = all.get(i);
            // Check user-turn boundary BEFORE accumulating (Nanobot order)
            if (i > start && msg.getRole() == Message.Role.USER) {
                lastBoundary = i;
                if (removedTokens >= tokensToRemove) {
                    return lastBoundary;
                }
            }
            removedTokens += estimateMessageTokens(msg);
        }

        return lastBoundary;
    }

    // --- Formatting utilities ---

    private String formatMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            String timestamp = message.getTimestamp().format(TIMESTAMP_FORMAT);
            String role = message.getRole().toString().toUpperCase();
            String content = message.getContent();
            if (content == null) continue;

            String toolsInfo = message.hasToolCalls() ?
                    " [tools: " + message.getToolCalls().size() + "]" : "";

            sb.append("[").append(timestamp).append("] ")
                    .append(role).append(toolsInfo).append(": ")
                    .append(truncate(content, 200)).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...(truncated)";
    }

    private String normalizeToString(Object value) {
        if (value == null) return "";
        if (value instanceof String) return ((String) value).trim();
        String str = value.toString();
        if (str.startsWith("\"") && str.endsWith("\"")) {
            return str.substring(1, str.length() - 1).trim();
        }
        return str.trim();
    }

    /**
     * Fallback: dump raw messages to HISTORY.md without LLM summarization.
     * Aligned with Nanobot's _raw_archive.
     */
    private boolean rawArchive(List<Message> messages) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            String entry = "[" + timestamp + "] [RAW] " + messages.size() + " messages\n" +
                    formatMessages(messages);
            memoryStore.appendHistory(entry);
            log.warn("Memory consolidation degraded: raw-archived {} messages", messages.size());
            return true;
        } catch (Exception e) {
            log.error("Raw archive also failed", e);
            return false;
        }
    }

    /**
     * Estimate token count for a session by building a simulated prompt.
     */
    /**
     * Estimate current prompt size for the normal session history view.
     * Ported from Nanobot's MemoryConsolidator.estimate_session_prompt_tokens().
     * Builds a simulated prompt with "[token-probe]" as placeholder, then counts tokens.
     */
    public int estimateSessionTokens(Session session) {
        List<Message> history = session.getHistory(0);
        List<Message> probeMessages;
        if (contextBuilder != null) {
            List<Map<String, Object>> toolDefs = toolRegistry != null ? toolRegistry.getToolDefinitions() : null;
            probeMessages = contextBuilder.buildMessages(history, "[token-probe]", toolDefs);
        } else {
            probeMessages = new ArrayList<>(history);
        }

        List<String> parts = new ArrayList<>();
        for (Message msg : probeMessages) {
            if (msg.getContent() != null) {
                parts.add(msg.getContent());
            }
            if (msg.hasToolCalls()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getName() != null) parts.add(tc.getName());
                    parts.add(tc.getArgumentsAsString());
                }
            }
            if (msg.getToolCallId() != null) {
                parts.add(msg.getToolCallId());
            }
            String toolName = msg.getToolName();
            if (toolName != null) {
                parts.add(toolName);
            }
            if (msg.getReasoningContent() != null && !msg.getReasoningContent().isEmpty()) {
                parts.add(msg.getReasoningContent());
            }
        }

        if (toolRegistry != null) {
            for (Map<String, Object> def : toolRegistry.getToolDefinitions()) {
                parts.add(def.toString());
            }
        }

        String joined = String.join("\n", parts);
        return ENCODING.countTokens(joined) + probeMessages.size() * 4;
    }

    /**
     * Estimate prompt tokens contributed by one message.
     * Ported from Nanobot's helpers.estimate_message_tokens().
     * Covers: content, name, tool_call_id, tool_calls (JSON), reasoning_content.
     * Minimum return: 4 tokens (framing overhead).
     *
     * <p>Public so the in-loop context governor (ContextWindowManager.govern) can reuse
     * this class as the single source of truth for token estimation.
     */
    public int estimateMessageTokens(Message msg) {
        List<String> parts = new ArrayList<>();
        // content
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            parts.add(msg.getContent());
        }
        // name, tool_call_id
        if (msg.getToolName() != null && !msg.getToolName().isEmpty()) {
            parts.add(msg.getToolName());
        }
        if (msg.getToolCallId() != null && !msg.getToolCallId().isEmpty()) {
            parts.add(msg.getToolCallId());
        }
        // tool_calls (serialized as JSON, includes id + name + arguments)
        if (msg.hasToolCalls()) {
            parts.add(formatToolCalls(msg.getToolCalls()));
        }
        // reasoning_content
        if (msg.getReasoningContent() != null && !msg.getReasoningContent().isEmpty()) {
            parts.add(msg.getReasoningContent());
        }

        if (parts.isEmpty()) return 4;
        return Math.max(4, ENCODING.countTokens(String.join("\n", parts)) + 4);
    }

    /**
     * Sum of per-message token estimates for an arbitrary message list.
     * Used by the in-loop context governor (ContextWindowManager.govern).
     *
     * <p>Unlike {@link #estimateSessionTokens(Session)}, this operates on a raw list
     * WITHOUT rebuilding a probe prompt (no re-added system placeholder, no current-user
     * placeholder, no tool-definition tokens) — so it does not double-count when the
     * caller already holds the full prompt-bound message list.
     */
    public int estimateMessagesTokens(List<Message> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (Message msg : msgs) {
            sum += estimateMessageTokens(msg);
        }
        return sum;
    }

    /**
     * Serialize tool calls to JSON string for token estimation.
     * Matches Nanobot's: json.dumps(message["tool_calls"], ensure_ascii=False).
     */
    private static String formatToolCalls(List<ToolCall> toolCalls) {
        List<Map<String, Object>> list = toolCalls.stream().map(tc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", tc.getId() != null ? tc.getId() : "");
            m.put("name", tc.getName() != null ? tc.getName() : "");
            m.put("arguments", tc.getArguments());
            return m;
        }).collect(Collectors.toList());
        return list.toString();
    }
}
