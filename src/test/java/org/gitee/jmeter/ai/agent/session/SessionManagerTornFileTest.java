package org.gitee.jmeter.ai.agent.session;

import org.gitee.jmeter.ai.agent.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话文件持久化的撕裂容忍与原子写回归（2026-09-09 审计 P1 修复钉定）。
 * <ul>
 *   <li><b>写侧原子替换：</b>saveSession 先写完整临时文件再 ATOMIC_MOVE——写中途
 *       被杀（agent-loop daemon 线程随 JVM 退出）或盘满中断不留半截 jsonl，
 *       移动前的旧文件始终完整（对齐 MemoryStore.writeLongTermMemory 先例）。</li>
 *   <li><b>加载侧逐行容忍：</b>半截/损坏行只丢弃本行、完好前缀照常加载——旧实现
 *       readTree 在逐行容忍圈外，一行撕裂使整个文件失效，再被 getOrCreate 的
 *       空会话覆写把完好消息前缀不可逆销毁（进程内 loop 重建重载同 instanceId
 *       文件的路径真实可达）。</li>
 * </ul>
 */
class SessionManagerTornFileTest {

    private static final String META_LINE = "{\"_type\":\"metadata\",\"key\":\"%s\","
            + "\"created_at\":\"2026-09-10T10:00:00\",\"updated_at\":\"2026-09-10T10:00:00\","
            + "\"metadata\":{},\"last_consolidated\":0}";

    private static String msgLine(String content) {
        return "{\"role\":\"user\",\"content\":\"" + content + "\",\"timestamp\":\"2026-09-10T10:00:01\"}";
    }

    /** 半截/损坏行只丢弃本行，完好前后行照常加载——整文件不再因一行撕裂失效。 */
    @Test
    void tornLine_skippedOnlyBadLine_intactPrefixAndSuffixLoad(@TempDir Path tempDir) throws Exception {
        String key = "torn-file";
        Path sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Files.write(sessionsDir.resolve(key + ".jsonl"), (
                String.format(META_LINE, key) + "\n"
                        + msgLine("m1") + "\n"
                        + msgLine("m2") + "\n"
                        + msgLine("m3") + "\n"
                        // 半截 JSON：写中途被杀的撕裂形态（无闭合、无换行尾）
                        + "{\"role\":\"user\",\"content\":\"torn"
                        + "\n"
                        + msgLine("m4") + "\n"
                        + msgLine("m5") + "\n"
        ).getBytes(StandardCharsets.UTF_8));

        SessionManager manager = new SessionManager(tempDir, key);
        Session loaded = manager.getOrCreate(key);

        List<String> contents = new ArrayList<>();
        for (Message m : loaded.getMessages()) {
            contents.add(m.getContent());
        }
        assertEquals(List.of("m1", "m2", "m3", "m4", "m5"), contents,
                "撕裂行只丢弃本行，5 条完好消息（前缀 + 后缀）必须全部加载");
    }

    /**
     * 原子替换回归：再 save 更短的会话后，磁盘内容必须等于新会话——move 失败/
     * 旧内容残留类实现缺陷在此暴露（旧 TRUNCATE 语义等价性）。
     */
    @Test
    void atomicReplace_shorterResave_diskMatchesLatest(@TempDir Path tempDir) {
        String key = "atomic-replace";
        SessionManager manager = new SessionManager(tempDir, key);
        Session session = manager.getOrCreate(key);
        for (int i = 1; i <= 3; i++) {
            session.addMessage(Message.builder()
                    .role(Message.Role.USER).content("long-" + i).build());
        }
        manager.saveSession(session);

        manager.invalidate(key);                       // 模拟重置后的干净缓存
        Session shorter = manager.getOrCreate(key);    // 新会话（无消息）
        shorter.addMessage(Message.builder()
                .role(Message.Role.USER).content("only-one").build());
        manager.saveSession(shorter);

        SessionManager reloader = new SessionManager(tempDir, key);
        assertEquals(1, reloader.getOrCreate(key).getMessageCount(),
                "原子替换后磁盘必须是新会话内容，旧的长内容不得残留");
        assertEquals("only-one", reloader.getOrCreate(key).getMessages().get(0).getContent());
    }

    /** 临时文件不残留：saveSession 后 sessions 目录只有目标 jsonl，无 session-*.tmp。 */
    @Test
    void saveSession_leavesNoTempFiles(@TempDir Path tempDir) throws Exception {
        String key = "no-tmp";
        SessionManager manager = new SessionManager(tempDir, key);
        Session session = manager.getOrCreate(key);
        session.addMessage(Message.builder()
                .role(Message.Role.USER).content("hello").build());
        manager.saveSession(session);

        try (DirectoryStream<Path> files = Files.newDirectoryStream(
                tempDir.resolve("sessions"))) {
            for (Path p : files) {
                assertFalse(p.getFileName().toString().endsWith(".tmp"),
                        "saveSession 后不得残留临时文件：" + p.getFileName());
            }
        }
        assertTrue(Files.exists(tempDir.resolve("sessions").resolve(key + ".jsonl")),
                "目标 jsonl 必须就位");
    }
}
