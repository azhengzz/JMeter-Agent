package org.gitee.jmeter.ai.agent.turn;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.presenter.TurnHandle;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个回合（turn）的全部生命周期状态（design D1「Turn 聚合」）。
 *
 * <p>收敛前同一回合的状态散落在按 sessionKey 索引的 6 张 map 里
 * （activeTasks/abortFlags/completionLatches/activeTurnTokens/drainTimedOut/
 * activeTurnHandles），跨容器一致性靠「按值条件删除」等约定维持；聚合后共享同一
 * Turn 对象，多容器一致性约束消失。Turn 对象自身即回合令牌（原 activeTurnTokens
 * 的 value + {@code Object turnToken}——身份即令牌，{@code SubagentManager.TurnToken}
 * 视图按 Turn 身份比较）。
 *
 * <p><b>保序武装（staggered arming）：</b>字段并非构造时一次武装——后写字段的写入
 * 时机与被收敛的旧 map put 一一对应，不得被单次构造+注册压扁（design Risks ①）：
 * 压扁会改变 [startTurn 起步 → future 武装] 窗口内 signalCancel 的行为路径与终态
 * 事件 Kind（如排队回合本应走「abortFlag 置位 + 任务头预检作废」，提前武装 future
 * 会让窗口内的取消改走 future.cancel + TURN_CANCELLED）。武装次序：
 * <ol>
 *   <li>构造 + {@code TurnRegistry.register}（= 旧 abortFlags/completionLatches
 *       put，startTurn 最前）：sessionKey/handle/callback/delegated/abortFlag/
 *       completionLatch 就位——signalCancel 从此刻起可置中止位，waitForCancellation
 *       从此刻起可等收尾；</li>
 *   <li>{@link #armQueue()}（= 旧 {@code injectionManager.register} 创建队列并占
 *       路由槽）：队列即回合私有字段（容量取 {@code AiConfig.getInjectionQueueSize()}）；
 *       Phase 4 起路由槽 = 注册表条目 + {@code closed} 标志（design D2），队列武装
 *       前 offer/hasActiveRun 视为槽未上线（对应旧「槽 map 尚无 put」窗口）；</li>
 *   <li>{@link #armFuture}（= 旧 activeTasks.put，execute 之后）：signalCancel 据此
 *       future.cancel(true)。早于该时点的取消只能靠 abortFlag，且两个窗口语义不同
 *       （对抗审查 2026-09-03 核定）：[register → armFuture] 内 future 尚未武装，
 *       任务头取消预检（isCancelled 恒 false）不可见——任务照常被取出、于首次迭代
 *       检查 abortFlag 中止，终态为空内容 TURN_COMPLETED（无 TURN_CANCELLED）；
 *       [armFuture → pickup] 内才走 future.cancel + 预检作废 + TURN_CANCELLED。</li>
 * </ol>
 *
 * <p><b>句柄为何随构造就位（不按旧 activeTurnHandles.put 的时机后写）：</b>旧
 * activeTurnHandles 在垂死→后继交接期间（后继回合创建于垂死回合收尾的 re-publish，
 * 早于其 future 死亡触发的摘除）靠「前驱句柄尚未被摘除」保持非空——activeTurn 轮询方
 * 不会在交接窗口误判空闲。注册表条目在注册瞬间即整条替换为新回合，若句柄晚于此才
 * 武装，activeTurn（= 条目句柄）会在 [注册 → 武装] 窗口误报空，外部「等回合落定」
 * 的轮询将提前放行、把后继消息误判为可注入。signalCancel 的 TURN_CANCELLED 认领
 * 以 future 已武装且未完成为前提，不受句柄提前可见影响。
 *
 * <p><b>可见性：</b>构造后写入的字段一律 volatile——写入线程（提交线程 / loop 线程）
 * 与读取线程（EDT、ipc-worker 等取消方）之间不总有 happens-before 边（executor 提交
 * 的 happens-before 只覆盖任务体内部的读写）。仅 loop 线程读写的字段（epoch/
 * drainTimedOut/ownResetEpoch）volatile 为防御性对齐 design（D1 风险表）。
 */
public final class Turn {

    private static final Logger log = LoggerFactory.getLogger(Turn.class);

    /** {@link #ownResetEpoch} 的「未写入」哨兵（代数从 0 起，-1 不与真值冲突）。 */
    public static final long OWN_RESET_EPOCH_UNSET = -1L;

    // ---- 提交时定（构造器），final ----

    private final String sessionKey;
    private final TurnHandle handle;
    private final AgentLoop.ProgressCallback callback;
    private final boolean delegated;
    private final AtomicBoolean abortFlag;
    private final CountDownLatch completionLatch;

    // ---- 保序武装的后写字段，volatile（见类 javadoc「保序武装」） ----

    private volatile LinkedBlockingQueue<InjectionItem> queue;
    private volatile CompletableFuture<AgentResponse> future;

    /**
     * 注入槽已摘除的显式表达（design D2「closed 双生命周期」）：条目本身活到 latch
     * 释放点（{@code TurnRegistry} 的真正 remove），注入路由的早期死亡——signalCancel
     * 摘槽（closeRouting）/ 回尾 cleanup（身份条件置位）——以本标志表达，
     * {@code offer}/{@code hasActiveRun} 据此派生「槽已摘」。置位一律经
     * TurnRegistry 的 {@code computeIfPresent}（与 offer 同一 CHM bin 锁，原子），
     * 不在 registry 外直接写。
     */
    private volatile boolean closed;

    // ---- 回合内生命周期 ----

    /**
     * 本回合 pickup 时归属的会话代数：/new、"+" 重置会话时代数 +1，收尾 re-publish
     * 残留前比对——代数已变 = 残留属于被放弃的旧会话，丢弃。写入时机保持 pickup 时
     * （非提交时，语义与旧局部变量 {@code turnEpoch} 一致）；仅 loop 线程读写。
     */
    private volatile long epoch;

    /**
     * 本线程刚执行的重置所翻转到的会话代数（原 ThreadLocal {@code ownResetEpoch}
     * 的显式字段化）：{@code resetConversation} 在回合线程内调用时经
     * {@code currentTurn.get()} 定位本回合写入，回合收尾对注入残留的分类只采纳
     * 「本回合自身命令造成的翻转」——不重读会话当前代数，免被派发窗口内并发的
     * 外来重置（"+" 点击、关闭整合清空）污染。不可经 registry.find(sessionKey)
     * 定位：同 key 新回合可能已注册，find 会把代数写进错误回合、击穿重置代数栅栏
     * （design D1 ThreadLocal 收敛约束）。初始 {@link #OWN_RESET_EPOCH_UNSET}。
     */
    private volatile long ownResetEpoch = OWN_RESET_EPOCH_UNSET;

    /**
     * 本回合任务体的执行线程：pickup 时写入、teardown 置 null（见
     * {@link #clearRunnerThread}）。以 Phase 1 同步化为前提——pickup 线程即执行
     * 线程（LLM 调用与 drainBlocking park 所在线程），interrupt 据此精确命中。
     */
    private volatile Thread runnerThread;

    /**
     * 本回合子代理等待已超时——之后不再阻塞等待（语义本就 per-turn「本回合不再
     * 阻塞」，原按 sessionKey 存 map 靠单 executor 串行才碰巧正确）。仅 loop 线程
     * 读写，随回合对象消亡，无需显式清理。
     */
    private boolean drainTimedOut;

    public Turn(String sessionKey, TurnHandle handle, AgentLoop.ProgressCallback callback,
            boolean delegated) {
        this.sessionKey = sessionKey;
        this.handle = handle;
        this.callback = callback;
        this.delegated = delegated;
        this.abortFlag = new AtomicBoolean(false);
        this.completionLatch = new CountDownLatch(1);
    }

    public String sessionKey() {
        return sessionKey;
    }

    public AgentLoop.ProgressCallback callback() {
        return callback;
    }

    public boolean delegated() {
        return delegated;
    }

    /** 中止位：signalCancel 置位（自我豁免本回合除外），回合内各 abort 检查点读取。 */
    public AtomicBoolean abortFlag() {
        return abortFlag;
    }

    /** 收尾信号：任务体外层 finally 先按值摘表再 countDown（waitForCancellation 等待）。 */
    public CountDownLatch completionLatch() {
        return completionLatch;
    }

    /** 本回合私有的注入队列句柄（按句柄抽干/清理，垂死回合偷不到后继回合的消息）。 */
    public LinkedBlockingQueue<InjectionItem> queue() {
        return queue;
    }

    /** 回合身份句柄（进程唯一 id + 来源 + 显示域元数据 + 终态去重位 tryClaimTerminal）。 */
    public TurnHandle handle() {
        return handle;
    }

    /** 本回合的 future（发起方等待通道；signalCancel 的 cancel(true) 目标）。 */
    public CompletableFuture<AgentResponse> future() {
        return future;
    }

    public long epoch() {
        return epoch;
    }

    public long ownResetEpoch() {
        return ownResetEpoch;
    }

    public Thread runnerThread() {
        return runnerThread;
    }

    public boolean drainTimedOut() {
        return drainTimedOut;
    }

    /** 武装注入队列（时机 ②，见类 javadoc「保序武装」）：队列即本回合私有，容量取 AiConfig。 */
    public void armQueue() {
        this.queue = new LinkedBlockingQueue<>(AiConfig.getInjectionQueueSize());
    }

    /** 注入槽是否已摘（见 {@link #closed} 字段；仅经 TurnRegistry 的 bin 锁内置位）。 */
    public boolean closed() {
        return closed;
    }

    /** 置 closed（package-private：只许 TurnRegistry 的 computeIfPresent 在 bin 锁内调用）。 */
    void markClosed() {
        this.closed = true;
    }

    /**
     * 从本回合队列抽走至多 {@code limit} 条（原 {@code InjectionManager.drain} 随
     * Phase 4 迁入）。按句柄消费——回合只消费自己的队列，null 队列（未武装）返回空表。
     */
    public List<InjectionItem> drain(int limit) {
        if (queue == null) {
            return Collections.emptyList();
        }
        List<InjectionItem> items = new ArrayList<>();
        while (items.size() < limit) {
            InjectionItem item = queue.poll();
            if (item == null) break;
            items.add(item);
        }
        if (!items.isEmpty()) {
            log.debug("Drained {} messages from an injection queue", items.size());
        }
        return items;
    }

    /**
     * 同 {@link #drain(int)}，但队列空时阻塞至一条到达或超时（原
     * {@code InjectionManager.drainBlocking}，子代理汇流 park 点——主代理在此等待
     * 仍在跑的子代理，使其结果折回同一回合）。
     *
     * <p>经 {@code AgentRunSpec.injectionCallback}（{@code Function} 不能声明受检
     * 异常）调用——interrupt 在此捕获并复位标志、返回已抽到的部分，agent loop 在
     * 下一 abort 检查点退出。本方法永不抛出。
     */
    public List<InjectionItem> drainBlocking(int limit, long timeoutMs) {
        List<InjectionItem> items = drain(limit);
        if (!items.isEmpty() || queue == null) {
            return items;
        }

        try {
            InjectionItem first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (first == null) {
                log.warn("Timed out after {}ms waiting for a subagent result", timeoutMs);
                return items;
            }
            items.add(first);
            // Take whatever else already arrived, without blocking again.
            items.addAll(drain(limit - items.size()));
            return items;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Interrupted while waiting for a subagent result");
            return items;
        }
    }

    /** 抽干本回合队列的全部残留（TurnRegistry.cleanup 收尾善后用，含日志）。 */
    List<InjectionItem> drainAll() {
        List<InjectionItem> remaining = new ArrayList<>();
        if (queue != null) {
            queue.drainTo(remaining);
        }
        return remaining;
    }

    /** 武装 future（时机 ③，见类 javadoc「保序武装」）。 */
    public void armFuture(CompletableFuture<AgentResponse> future) {
        this.future = future;
    }

    /** pickup：捕获回合归属的会话代数（写入时机保持 pickup，见 {@link #epoch}）。 */
    public void markPickup(long epoch) {
        this.epoch = epoch;
    }

    /** 记录本回合自身命令造成的代数翻转（见 {@link #ownResetEpoch}）。 */
    public void recordOwnReset(long epoch) {
        this.ownResetEpoch = epoch;
    }

    /** pickup：记录执行线程（见 {@link #runnerThread}）。 */
    public void setRunnerThread(Thread runner) {
        this.runnerThread = runner;
    }

    /**
     * teardown：清空执行线程引用。<b>null 是必要不变式</b>——Turn 条目在收尾最晚点
     * （latch 释放）才从注册表摘除，此前的残留线程引用会让迟到中断打向已复用/已死
     * 的线程（agent-loop 单线程 executor 跨回合复用线程）。
     */
    public void clearRunnerThread() {
        this.runnerThread = null;
    }

    /** 子代理等待超时打标（本回合后续检查点不再阻塞等待，见 {@link #drainTimedOut}）。 */
    public void markDrainTimedOut() {
        this.drainTimedOut = true;
    }
}
