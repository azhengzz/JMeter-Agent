package org.gitee.jmeter.ai.agent.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages conversation sessions.
 * Provides session creation, retrieval, and persistence.
 * Session files are stored in JSONL format (Nanobot compatible).
 */
public class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Session> sessions;
    private final Path sessionStorage;
    /** 启动只加载该 session key 的 jsonl(每实例会话隔离),不解析历史遗留/其他实例文件。 */
    private final String focusSessionKey;

    /**
     * 便捷构造:聚焦全局遗留会话键 {@link InstanceContext#LEGACY_SESSION_KEY} 加载
     * (等价于 {@code agent.session.per-instance=false} 的回退行为)。
     */
    public SessionManager(Path workspace) {
        this(workspace, InstanceContext.LEGACY_SESSION_KEY);
    }

    /**
     * @param focusSessionKey 每实例会话模式下传入当前 {@code instanceId} session key,实例只需
     *                        解析自己的 jsonl——历史遗留 {@code jmeter-ai-chat.jsonl} 与失活
     *                        孤立实例文件不再被整文件载入内存(启动成本归零;遗留迁移与回收
     *                        {@code SessionReaper} 均直接读文件,不受影响)。始终非 null。
     */
    public SessionManager(Path workspace, String focusSessionKey) {
        this.sessionStorage = workspace.resolve("sessions");
        this.sessions = new ConcurrentHashMap<>();
        this.focusSessionKey = focusSessionKey;

        ensureDirectories();
        loadSessions();

        log.info("SessionManager initialized with workspace: {}", sessionStorage);
    }

    private void ensureDirectories() {
        try {
            if (!Files.exists(sessionStorage)) {
                Files.createDirectories(sessionStorage);
                log.info("Created session directory: {}", sessionStorage);
            }
        } catch (IOException e) {
            log.error("Failed to create session directory", e);
        }
    }

    /**
     * Get or create a session for the given key
     */
    public Session getOrCreate(String sessionKey) {
        return sessions.computeIfAbsent(sessionKey, key -> {
            Session session = new Session(key);
            saveSession(session);
            log.info("Created new session: {}", key);
            return session;
        });
    }

    /**
     * Get an existing session without creating
     */
    public Session get(String sessionKey) {
        return sessions.get(sessionKey);
    }

    /**
     * Get message history for a session
     */
    public java.util.List<Message> getHistory(String sessionKey, int maxMessages) {
        Session session = sessions.get(sessionKey);
        if (session == null) {
            return java.util.Collections.emptyList();
        }
        return session.getHistory(maxMessages);
    }

    /**
     * Clear and remove a session
     */
    public void clearSession(String sessionKey) {
        Session removed = sessions.remove(sessionKey);
        if (removed != null) {
            log.info("Cleared session: {}", sessionKey);
        }
    }

    /**
     * Invalidate cached session state (remove from cache, keep on disk).
     * Next getOrCreate() will reload from disk or create fresh.
     */
    public void invalidate(String sessionKey) {
        sessions.remove(sessionKey);
        log.debug("Invalidated session cache: {}", sessionKey);
    }

    /**
     * Save a session to disk in JSONL format (Nanobot compatible).
     * Line 1: metadata JSON
     * Lines 2+: one message JSON per line
     *
     * <p>文件写持 session 的 monitor（与 {@link Session} 各方法同一把锁）串行化：
     * 会话重置线程（EDT 清空落盘）与回合载体线程（追加落盘）并发写同一 jsonl 时，
     * 两个写路径按各自偏移交错会写出撕裂内容。
     *
     * <p><b>原子写（对齐 {@code MemoryStore.writeLongTermMemory}）：</b>先写完整
     * 临时文件再原子替换——写中途被杀（agent-loop 为 daemon 线程，JVM 退出不等
     * 写完）或盘满中断都不留半截 jsonl，移动前的旧文件始终完整；撕裂 jsonl 曾被
     * 加载侧整文件丢弃、再被 {@link #getOrCreate} 的空会话覆写不可逆销毁）。
     */
    public void saveSession(Session session) {
        Path sessionFile = getSessionFile(session.getKey());
        synchronized (session) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile(sessionStorage, "session-", ".tmp");
                try (BufferedWriter writer = Files.newBufferedWriter(tmp)) {
                    // Line 1: metadata
                    ObjectNode metadata = mapper.createObjectNode();
                    metadata.put("_type", "metadata");
                    metadata.put("key", session.getKey());
                    metadata.put("created_at", session.getCreatedAt().toString());
                    metadata.put("updated_at", session.getUpdatedAt().toString());
                    metadata.putObject("metadata");
                    metadata.put("last_consolidated", session.getLastConsolidatedIndex());
                    writer.write(mapper.writeValueAsString(metadata));
                    writer.newLine();

                    // Lines 2+: messages（getMessages 已是快照拷贝，迭代安全）
                    for (Message message : session.getMessages()) {
                        ObjectNode msgNode = messageToJson(message);
                        writer.write(mapper.writeValueAsString(msgNode));
                        writer.newLine();
                    }
                }
                try {
                    Files.move(tmp, sessionFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, sessionFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.error("Failed to save session: {}", session.getKey(), e);
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException e) {
                        // move 成功后 tmp 已不存在（清理无害）；move 失败时清掉半截
                        // tmp 防堆积——旧 jsonl 完好，本轮回退到上次成功的落盘
                        log.debug("Failed to delete temp session file {}", tmp, e);
                    }
                }
            }
        }
    }

    /**
     * Load sessions from disk
     */
    private void loadSessions() {
        try {
            if (!Files.exists(sessionStorage)) {
                return;
            }

            Files.list(sessionStorage)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .filter(p -> p.getFileName().toString().equals(safeFileName(focusSessionKey)))
                    .forEach(this::loadSessionFile);

            log.info("Loaded {} sessions from disk", sessions.size());

        } catch (IOException e) {
            log.error("Failed to load sessions", e);
        }
    }

    /**
     * Load a single session file in JSONL format.
     */
    private void loadSessionFile(Path sessionFile) {
        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String line;
            String sessionKey = null;
            LocalDateTime createdAt = null;
            LocalDateTime updatedAt = null;
            int lastConsolidated = 0;
            java.util.List<Message> messages = new java.util.ArrayList<>();

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // 逐行容忍（对齐下方 jsonToMessage 的既有语义）：半截/损坏行只丢弃
                    // 本行、完好前缀照常加载——写中途被杀/盘满留下的撕裂行不再让整
                    // 个会话文件失效、再被 getOrCreate 的空会话覆写不可逆销毁
                    // （2026-09-09 审计 P1 修复）
                    log.warn("Skipping corrupted session line in {}: {}",
                            sessionFile.getFileName(), e.getMessage());
                    continue;
                }

                if (node.has("_type") && "metadata".equals(node.get("_type").asText())) {
                    sessionKey = node.has("key") ? node.get("key").asText() : null;
                    createdAt = node.has("created_at") ? LocalDateTime.parse(node.get("created_at").asText()) : null;
                    updatedAt = node.has("updated_at") ? LocalDateTime.parse(node.get("updated_at").asText()) : null;
                    lastConsolidated = node.has("last_consolidated") ? node.get("last_consolidated").asInt() : 0;
                } else {
                    Message msg = jsonToMessage(node);
                    if (msg != null) {
                        messages.add(msg);
                    }
                }
            }

            if (sessionKey != null && !sessionKey.isEmpty()) {
                Session session = new Session(sessionKey);
                if (createdAt != null) {
                    session.setCreatedAt(createdAt);
                }
                if (updatedAt != null) {
                    session.setUpdatedAt(updatedAt);
                }
                session.setLastConsolidatedIndex(lastConsolidated);
                for (Message msg : messages) {
                    session.addMessage(msg);
                }
                sessions.put(sessionKey, session);
            }

        } catch (IOException e) {
            log.warn("Failed to load session file: {}", sessionFile, e);
        }
    }

    /**
     * Sanitize a session key into a safe filename ({@code {safeKey}.jsonl}).
     * 与 {@link #loadSessions} 的 focus 过滤共用同一规范化,保证读写命中同一文件。
     */
    private static String safeFileName(String sessionKey) {
        return sessionKey.replaceAll("[^a-zA-Z0-9-_]", "_") + ".jsonl";
    }

    private Path getSessionFile(String sessionKey) {
        return sessionStorage.resolve(safeFileName(sessionKey));
    }

    /**
     * Serialize a Message to JSON ObjectNode (Nanobot compatible format).
     */
    private ObjectNode messageToJson(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.getRole().name().toLowerCase());
        if (message.getContent() != null) {
            node.put("content", message.getContent());
        } else {
            node.putNull("content");
        }
        node.put("timestamp", message.getTimestamp().toString());

        // Tool calls for assistant messages
        if (message.hasToolCalls()) {
            ArrayNode toolCallsNode = mapper.createArrayNode();
            for (ToolCall tc : message.getToolCalls()) {
                ObjectNode tcNode = mapper.createObjectNode();
                tcNode.put("id", tc.getId());
                tcNode.put("type", "function");
                ObjectNode funcNode = mapper.createObjectNode();
                funcNode.put("name", tc.getName());
                // Serialize arguments as JSON string
                try {
                    funcNode.put("arguments", mapper.writeValueAsString(tc.getArguments()));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    funcNode.put("arguments", "{}");
                }
                tcNode.set("function", funcNode);
                toolCallsNode.add(tcNode);
            }
            node.set("tool_calls", toolCallsNode);
        }

        // Reasoning content for thinking-mode models
        if (message.getReasoningContent() != null) {
            node.put("reasoning_content", message.getReasoningContent());
        }

        // Tool result fields
        if (message.getRole() == Message.Role.TOOL) {
            if (message.getToolCallId() != null) {
                node.put("tool_call_id", message.getToolCallId());
            }
            if (message.getToolName() != null) {
                node.put("name", message.getToolName());
            }
        }

        return node;
    }

    /**
     * Deserialize a JsonNode to Message.
     */
    private Message jsonToMessage(JsonNode node) {
        try {
            String roleStr = node.has("role") ? node.get("role").asText() : "user";
            Message.Role role = Message.Role.valueOf(roleStr.toUpperCase());
            String content = node.has("content") && !node.get("content").isNull()
                    ? node.get("content").asText() : null;

            Message.Builder builder = Message.builder()
                    .role(role)
                    .content(content);

            // Parse tool calls
            if (node.has("tool_calls") && node.get("tool_calls").isArray()) {
                java.util.List<ToolCall> toolCalls = new java.util.ArrayList<>();
                for (JsonNode tcNode : node.get("tool_calls")) {
                    String id = tcNode.has("id") ? tcNode.get("id").asText() : null;
                    String name = null;
                    java.util.Map<String, Object> arguments = null;
                    if (tcNode.has("function")) {
                        JsonNode funcNode = tcNode.get("function");
                        name = funcNode.has("name") ? funcNode.get("name").asText() : null;
                        if (funcNode.has("arguments")) {
                            String argsStr = funcNode.get("arguments").asText();
                            arguments = mapper.readValue(argsStr, java.util.Map.class);
                        }
                    }
                    toolCalls.add(new ToolCall(id, name, arguments));
                }
                builder.toolCalls(toolCalls);
            }

            // Parse reasoning content
            if (node.has("reasoning_content") && !node.get("reasoning_content").isNull()) {
                builder.reasoningContent(node.get("reasoning_content").asText());
            }

            // Preserve the message's original timestamp (round-trip fidelity) instead of
            // resetting it to load time. Legacy lines without a parseable timestamp fall
            // back to load-time now(), matching the historical behavior.
            if (node.has("timestamp") && !node.get("timestamp").isNull()) {
                try {
                    builder.timestamp(LocalDateTime.parse(node.get("timestamp").asText()));
                } catch (DateTimeParseException ignore) {
                    // keep load-time default
                }
            }

            // Parse tool result fields
            if (role == Message.Role.TOOL) {
                if (node.has("tool_call_id")) {
                    builder.toolCallId(node.get("tool_call_id").asText());
                }
                if (node.has("name")) {
                    builder.metadata(java.util.Collections.singletonMap("toolName", node.get("name").asText()));
                }
            }

            return builder.build();
        } catch (Exception e) {
            log.warn("Failed to parse message from JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get active session count
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Shutdown the session manager
     */
    public void shutdown() {
        // Save all sessions
        for (Session session : sessions.values()) {
            saveSession(session);
        }

        log.info("SessionManager shutdown complete");
    }

    @Override
    public String toString() {
        return "SessionManager{" +
                "activeSessions=" + sessions.size() +
                ", storage=" + sessionStorage +
                '}';
    }
}
