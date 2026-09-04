package org.gitee.jmeter.ai.agent.memory;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;


/**
 * Two-layer memory storage for Agent.
 * - MEMORY.md: Long-term facts and knowledge
 * - HISTORY.md: Searchable conversation log
 */
public class MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyFile;

    /** 同 JVM 串行化:FileLock 是 JVM 级持有,同 JVM 内多个线程对同一区域重复 lock 会抛
     *  {@code OverlappingFileLockException}。两层互斥不可互替——
     *  <ul>
     *    <li>OS 锁{@code FileLock}保障的是规范确证的跨进程互斥;</li>
     *    <li>同 JVM 的互斥必须用本静态 {@code ReentrantLock} 补上:依赖
     *        {@code OverlappingFileLockException} 抛出当"同 JVM 冲突"信号,是押在未规范化的
     *        实现细节上(该异常抛不抛各 JVM/平台不一),正确性不能建立在此。
     *        它是 {@code lockLongTermMemory} 里 {@code channel.tryLock()} 抛异常时的一个
     *        潜在干扰源,故从不指望它做互斥。</li>
     *  </ul>
     *  JVM 锁须从获取 OS 锁一直持到 handle 关闭(与 OS 锁同生命周期)。static 而非实例级,
     *  保证同一 JVM 内多个 MemoryStore 也串行。 */
    private static final ReentrantLock INTRA_JVM_LOCK = new ReentrantLock();
    private static final String LOCK_FILE = "memory.lock";

    /** 等锁轮询间隔:abort 感知的最坏放弃延迟。轮询式 {@code tryLock} 而非阻塞
     *  {@code lock()}——原生文件锁等待对中断/abort 均不可达(
     *  {@code channel.lock()} 在 Windows 上不可中断,CompletableFuture.cancel/join 中断
     *  都够不到等锁的 commonPool 载体线程),会把 commonPool 载体饿死并泄漏。 */
    private static final long LOCK_POLL_MILLIS = 50L;

    public MemoryStore(Path workspace) {
        this.memoryDir = workspace.resolve("memory");
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.historyFile = memoryDir.resolve("HISTORY.md");
        ensureDirectories();
    }

    private void ensureDirectories() {
        try {
            if (!Files.exists(memoryDir)) {
                Files.createDirectories(memoryDir);
                log.info("Created memory directory: {}", memoryDir);
            }
        } catch (IOException e) {
            log.error("Failed to create memory directory", e);
        }
    }

    /**
     * Read long-term memory from MEMORY.md
     */
    public String readLongTermMemory() {
        try {
            if (Files.exists(memoryFile)) {
                String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
                log.debug("Read long-term memory: {} characters", content.length());
                return content;
            }
        } catch (IOException e) {
            log.error("Error reading memory file", e);
        }
        return "";
    }

    /**
     * Write long-term memory to MEMORY.md
     *
     * @return {@code true} 写入成功;IO 故障(盘满/权限/只读目录)返回 {@code false}——
     *         调用方据此决定是否把整合标记为成功(写失败被吞会让 MEMORY.md 未更新
     *         却向用户报"整合完成"并清会话)
     */
    public boolean writeLongTermMemory(String content) {
        try {
            String safe = sanitizeForUtf8(content != null ? content : "");
            // 全量替换写:先写临时文件再原子移动,进程在写中途被杀(如 Skip & Exit 后 JVM 退出
            // 恰逢深度提炼落盘)不留半截 MEMORY.md——移动前的旧文件始终完整。
            Path tmp = Files.createTempFile(memoryDir, "MEMORY-", ".tmp");
            try {
                Files.writeString(tmp, safe, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, memoryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, memoryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    // 清理失败(move 已成功、MEMORY.md 已是最新):不得让清理异常把
                    // "写入成功"翻成 false——否则布尔契约被破坏,调用方报"未整合"
                    // 而内容已落盘,重提炼出重复 HISTORY 条目。仅记录,返回值走成功路径。
                    log.warn("Failed to delete temp memory file after move (content already persisted)", e);
                }
            }
            log.info("Updated long-term memory: {} characters", safe.length());
            return true;
        } catch (IOException e) {
            log.error("Error writing memory file", e);
            return false;
        }
    }

    /**
     * 获取 MEMORY.md 的跨进程写锁,覆盖整个 read-modify-write 事务(read → LLM → write)。
     * 两个共享同一默认 workspace({@code {jmeter.home}/bin/jmeter-agent})的 JMeter 实例
     * 并发深度提炼时,后写者基于陈旧读覆盖前者、整份提炼静默丢失(lost-update 确认真实可达)。锁把读改写串行化:后写者重读到前者结果再提炼。
     *
     * <p><b>等锁可中止、可中断:</b>等锁不阻塞在原生锁调用上,而是以 {@code tryLock()}
     * 轮询(同 JVM 锁与跨进程 OS 锁皆然),每轮先检查 {@code aborted}——关闭期 Stop / 提炼
     * 超时置位后立即放弃(返回 {@code null},调用方按"未执行"处理),不占住载体线程、不
     * 泄漏;线程中断同样立即放弃。轮询的必要性:(a) {@code distillSync} 深度提炼跑在
     * commonPool 载体线程上,interrupt / {@code CF.cancel(true)} 都够不到;(b) 整合虽已
     * 内联到可被 interrupt 命中的 run 执行线程,但阻塞式 {@code channel.lock()} 被
     * interrupt 会抛 {@code ClosedByInterruptException} 并关闭通道(破坏性)。abort flag
     * 是两条路径统一的取消事实来源,轮询让语义一致。
     *
     * @param aborted 等锁期间为 true 即放弃;无中止语义的调用方传 {@code () -> false}
     * @return 写锁句柄;等锁期间被中止/中断返回 {@code null}
     * @throws IOException 锁文件无法创建/打开等真实 IO 故障(调用方按 best-effort 决定)
     */
    public MemoryWriteLock lockLongTermMemory(BooleanSupplier aborted) throws IOException {
        // 必须先拿 JVM 锁、后 open 通道:在 open 之前等锁,失败(abort/中断)时直接
        // return null、此时尚无任何 fd,不存在 try/finally 不能覆盖的泄漏窗口;若反序
        // (先 open 再 awaitJvmLock),等锁失败的那条 return 落在 try 保护区之外,已打开的
        // FileChannel 无人 close,failed 路径会把 fd 泄漏/残留成永久 open;且 open 抢先
        // 在拿锁前进行,同 JVM 内可出现多线程并发 open 同一 memory.lock——每个线程各自
        // 建一个通道,等于放弃了"只有持锁线程才走到 open"的同 JVM 串行语义。open 成功、
        // 进入 try 之后一切失败都落在 finally 覆盖范围内。
        if (!awaitJvmLock(aborted)) {
            return null;
        }
        Path lockFile = memoryDir.resolve(LOCK_FILE);
        // 通道打开放进 try 内(承接上面"先拿 JVM 锁、后 open"的顺序):open 抛 IOException
        // (只读目录/锁文件不可创建)时 finally 仍执行,释放刚获的 INTRA_JVM_LOCK——否则
        // 静态锁永久泄漏,后续所有 MEMORY.md 写永久轮询 tryLock 至 abort/死锁。
        FileChannel channel = null;
        boolean handedOff = false;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (true) {
                if (aborted.getAsBoolean()) {
                    return null; // finally 释放 JVM 锁并关闭通道
                }
                // 请求跨进程排他锁:对 memory.lock 发起非阻塞 tryLock(而非阻塞 lock()),
                // 拿不到返回 null 而非抛异常——跨进程竞争时对方返回 null,同 JVM 竞争时抛
                // OverlappingFileLockException(已由 INTRA_JVM_LOCK 兜底串行)。tryLock 保证
                // 本线程不阻塞在原生锁调用上,待会 sleep 轮询可查 abort/响中断。
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    handedOff = true;
                    return new MemoryWriteLock(INTRA_JVM_LOCK, channel, lock);
                }
                try {
                    Thread.sleep(LOCK_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null; // finally 释放 JVM 锁并关闭通道
                }
            }
        } finally {
            if (!handedOff) {
                if (channel != null) { // open 抛 IOException 时通道未建,无需关闭(但要释放 JVM 锁)
                    try {
                        channel.close();
                    } catch (IOException e) {
                        log.debug("Failed to close lock channel after abandon", e);
                    }
                }
                INTRA_JVM_LOCK.unlock();
            }
        }
    }

    /** 同 JVM 锁 {@code tryLock} 轮询,直到成功或 {@code aborted}/中断。 */
    private static boolean awaitJvmLock(BooleanSupplier aborted) {
        while (true) {
            if (aborted.getAsBoolean()) {
                return false;
            }
            try {
                if (INTRA_JVM_LOCK.tryLock(LOCK_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** {@link #lockLongTermMemory(BooleanSupplier)} 返回的写锁句柄;{@link #close()} 释放
     *  OS 锁、关闭通道,再释放同 JVM 的 {@code ReentrantLock}(与获取顺序相反)。幂等:
     *  重复 {@code close()} 无副作用,{@code release()} 抛未检查异常也不会跳过
     *  {@code jvmLock.unlock()}(防静态锁永久泄漏)。 */
    public static class MemoryWriteLock implements AutoCloseable {
        private final ReentrantLock jvmLock;
        private final FileChannel channel;
        private final FileLock fileLock;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private MemoryWriteLock(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
            this.jvmLock = jvmLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                if (fileLock != null && fileLock.isValid()) {
                    fileLock.release();
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Failed to release memory write lock", e);
            }
            try {
                channel.close();
            } catch (IOException | RuntimeException e) {
                log.debug("Failed to close memory write lock channel", e);
            }
            jvmLock.unlock();
        }
    }

    /**
     * Append an entry to HISTORY.md.
     * Callers are responsible for including a [YYYY-MM-DD HH:MM] timestamp prefix.
     *
     * @return {@code true} 追加成功;IO 故障(盘满/权限/HISTORY.md 只读)返回 {@code false}——
     *         调用方据此把整合判定为失败,而非向用户报成功却丢记录(布尔契约的历史侧)。
     */
    public boolean appendHistory(String entry) {
        try {
            String formattedEntry = sanitizeForUtf8(entry) + "\n\n";
            Files.writeString(historyFile, formattedEntry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Appended to history: {} characters", formattedEntry.length());
            return true;
        } catch (IOException e) {
            log.error("Error appending to history file", e);
            return false;
        }
    }

    /**
     * Strip isolated UTF-16 surrogate chars that would make Files.writeString throw.
     * JDK's Files.writeString(path, str, UTF_8) routes through String.getBytesNoRepl(),
     * which throws UnmappableCharacterException on half surrogate pairs (common in
     * corrupted LLM output) instead of replacing them.
     */
    private static String sanitizeForUtf8(String s) {
        if (s == null || s.isEmpty()) return s;
        int len = s.length();
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                sb.append(c).append(s.charAt(i + 1));
                i += 2;
            } else if (Character.isSurrogate(c)) {
                // isolated high/low surrogate — drop
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Get memory context for system prompt
     */
    public String getMemoryContext() {
        String longTerm = readLongTermMemory();
        if (!longTerm.isEmpty()) {
            return "## Long-term Memory\n" + longTerm;
        }
        return "";
    }

    /**
     * Check if memory system is enabled
     */
    public boolean isEnabled() {
        return AiConfig.isMemoryEnabled();
    }

    public Path getMemoryDir() {
        return memoryDir;
    }

    public Path getMemoryFile() {
        return memoryFile;
    }

    public Path getHistoryFile() {
        return historyFile;
    }
}
