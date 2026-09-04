package org.gitee.jmeter.ai.agent.turn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话 → 在跑回合（Turn）注册表（design D1「Turn 聚合」+ D2「路由槽合并」，收敛
 * AgentLoop 的 activeTurnTokens/activeTurnHandles/abortFlags/completionLatches/
 * activeTasks 与 InjectionManager 的路由槽 map——Phase 4 起单表承载全部 per-turn 状态）。
 *
 * <p><b>继承 ConcurrentHashMap 而非包装：</b>注册表本身就是「会话 → 活跃回合」表，
 * Turn 身份即令牌（原 activeTurnTokens 的 value 即 Turn）；具名操作承载生命周期
 * 语义，父类只读面（get/containsKey/isEmpty 等）可安全混用——面板等待回合收尾的
 * 测试脚手架即按 {@code ConcurrentHashMap} 反射本表并轮询 isEmpty。路由槽合并后
 * {@code offer} 与置 closed 的操作在 {@code computeIfPresent} 的同一 bin 锁下原子
 * （design D2 的关键前提），继承使其直接可用。
 *
 * <p><b>条目生命周期 = 双段式（closed 标志，design D2）：</b>条目真正 remove 只发生在
 * 最晚死亡点（任务体外层 finally 的 latch 释放点 / signalCancel 自我豁免路径的
 * 按值条件摘除 removeIfCurrent——按值防误摘同 key 后继）；更早的「注入槽已摘」用
 * {@link Turn#closed()} 表达——closeRouting
 * （取消时无条件置位）与 cleanup（收尾时身份条件置位）都改置 closed 而非 remove，
 * offer/hasActiveRun 据此派生。三组状态三个死亡时机的完整分析见 design D2。
 */
public class TurnRegistry extends ConcurrentHashMap<String, Turn> {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(TurnRegistry.class);

    /**
     * 注册回合（put 替换，最新回合赢）：路由与取消始终指向最新回合；被替换回合的
     * 收尾经 {@link #removeIfCurrent} 身份条件摘除，不误摘后继。
     */
    public void register(String sessionKey, Turn turn) {
        put(sessionKey, turn);
    }

    /** 此刻该会话的在跑回合；无则 null。读取线程不保证（EDT/ipc-worker/loop 线程皆可能）。 */
    public Turn find(String sessionKey) {
        return get(sessionKey);
    }

    /**
     * 身份条件摘除：仅当表项仍是 turn 本身时移除——本回合收尾（finally 的 re-publish）
     * 可能已把后继回合 put 进同一 key，无条件 remove(key) 会当场摘掉后继的表项
     * （与旧 activeTasks/activeTurnHandles/abortFlags/completionLatches 的按值条件
     * 删除同款防线）。
     */
    public boolean removeIfCurrent(String sessionKey, Turn turn) {
        return remove(sessionKey, turn);
    }

    /**
     * 摘除会话的注入路由（原 {@code InjectionManager.cancelRouting}，signalCancel
     * 向会话当前路由的回合队列投递一条消息（原 {@code InjectionManager.offer} 随
     * Phase 4 迁入）。computeIfPresent 与 {@link #closeRouting}/{@link #cleanup} 的
     * 置 closed 同一 CHM bin 锁：offer 要么落入仍被路由且未摘槽的队列，要么观察到
     * 槽已摘（closed / 队列未武装）返回 false——不存在「写入已脱离路由的队列却
     * 回报成功」的窗口。LinkedBlockingQueue 支持多生产者（Swing EDT / ipc-worker /
     * 子代理完成）与单消费者（回合任务）。
     *
     * @return true 已入队；false 槽缺失（无条目/已 closed/队列未武装）或队列满
     */
    public boolean offer(String sessionKey, String message, boolean announcement) {
        AtomicReference<Boolean> offered = new AtomicReference<>(Boolean.FALSE);
        AtomicBoolean slotLive = new AtomicBoolean(false);
        Turn routed = computeIfPresent(sessionKey, (key, turn) -> {
            LinkedBlockingQueue<InjectionItem> queue = turn.queue();
            if (!turn.closed() && queue != null) {
                slotLive.set(true);
                offered.set(queue.offer(new InjectionItem(message, announcement)));
            }
            return turn;
        });
        if (routed == null || !slotLive.get()) {
            return false;
        }
        if (!offered.get()) {
            log.warn("Injection queue full for session {}, dropping message", sessionKey);
            return false;
        }
        log.debug("Message offered to injection queue for session {}", sessionKey);
        return true;
    }

    /**
     * 摘除会话的注入路由（原 {@code InjectionManager.cancelRouting}，signalCancel
     * 第 4 步摘）：置 closed 而非 remove——条目须活到 latch 释放点（design D2）。
     * 垂死会话立即不可注入（offer/hasActiveRun 派生 false，新消息只能开新回合）；
     * 已 pickup 的回合仍持队列句柄，其 finally 照常抽干残留；pre-pickup 被取消的
     * 回合由死任务 guard 分支（见 AgentLoop.startTurn）善后。
     * <p>按身份条件置位（仅当表项仍是取消方观察到的那个回合）：turn 是 signalCancel
     * 入口的快照，[find → 此处] 窗口内该回合收尾 + re-publish 可把条目整条替换成
     * 后继回合——无条件置 closed 会连后继的路由一并摘掉（后继从此不可注入、
     * hasActiveRun 报假空），与 {@link #removeIfCurrent}/{@link #cleanup} 的按值
     * 条件摘除同理（对抗审查 2026-09-03 追加修复）。turn 为 null 时不置：未观察到
     * 回合即无垂死对象可摘。
     */
    public void closeRouting(String sessionKey, Turn turn) {
        computeIfPresent(sessionKey, (key, t) -> {
            if (t == turn) {
                t.markClosed();
            }
            return t;
        });
    }

    /**
     * 回合收尾善后（原 {@code InjectionManager.cleanup}）：先按身份条件置 closed
     * （仅当表项仍是本回合——内层 finally 的 re-publish 可能已注册后继，不得误摘
     * 后继的路由），再抽干本回合队列残留。与旧版 drainTo→remove 的顺序相反：
     * 先置 closed 再抽干，offer 要么赶在置位前入队（随下方残留扫描可见）、要么
     * 事后见 closed 被拒——消除旧版两步间隙内「offer 成功入队但 drainTo 已过、
     * 消息滞留垂死队列无人消费」的微竞态。
     *
     * @return 队列残留（调用方过滤/re-publish）
     */
    public List<InjectionItem> cleanup(String sessionKey, Turn turn) {
        computeIfPresent(sessionKey, (key, t) -> {
            if (t == turn) {
                t.markClosed();
            }
            return t;
        });
        List<InjectionItem> remaining = turn.drainAll();
        if (!remaining.isEmpty()) {
            log.info("Cleanup: {} remaining messages for session {}", remaining.size(), sessionKey);
        }
        return remaining;
    }

    /**
     * 会话当前是否有可注入的活跃回合：条目存在 && 未 closed && 队列已武装
     * （原 {@code InjectionManager.hasActiveRun} 的槽位存在性，Phase 4 起由
     * closed/queue 派生）。AgentLoop.hasActiveRun 与 Phase 2 路由的唯一事实来源。
     */
    public boolean hasActiveRun(String sessionKey) {
        Turn turn = get(sessionKey);
        return turn != null && !turn.closed() && turn.queue() != null;
    }
}
