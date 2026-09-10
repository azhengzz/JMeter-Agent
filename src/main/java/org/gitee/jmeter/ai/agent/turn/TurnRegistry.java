package org.gitee.jmeter.ai.agent.turn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话 → 在跑回合（Turn）注册表，单表承载回合的全部生命周期状态（回合令牌、
 * 取消标志、完成闩锁、注入队列与路由槽都在 Turn 上）。
 *
 * <p><b>继承 ConcurrentHashMap 而非包装：</b>表本身就是「会话 → 活跃回合」映射，
 * 父类只读方法（get/containsKey/isEmpty 等）可安全混用；{@link #offer} 与置
 * closed 的操作共用 {@code computeIfPresent} 的同一 bin 锁——「入队要么成功，
 * 要么明确看到槽已摘」，继承使其直接可用。
 *
 * <p><b>条目生命周期 = 双段式：</b>真正 remove 只发生在最晚死亡点（回合收尾或
 * 取消路径的按值摘除）；更早的「注入路由已摘」用 {@link Turn#closed()} 表达——
 * {@link #closeRouting} 与 {@link #cleanup} 置 closed 而非 remove，
 * {@link #offer}/{@link #hasActiveRun} 据此派生路由是否存活。
 */
public class TurnRegistry extends ConcurrentHashMap<String, Turn> {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(TurnRegistry.class);

    /**
     * 注册回合（put 替换，最新回合赢）：路由与取消始终指向最新回合；被替换回合的
     * 收尾经 {@link #removeIfCurrent} 按值摘除，不误摘后继。
     */
    public void register(String sessionKey, Turn turn) {
        put(sessionKey, turn);
    }

    /** 此刻该会话的在跑回合；无则 null。读取线程不保证（EDT/ipc-worker/loop 线程皆可能）。 */
    public Turn find(String sessionKey) {
        return get(sessionKey);
    }

    /**
     * 按值条件摘除：仅当表项仍是 turn 本身时移除。收尾路径可能已把后继回合 put 进
     * 同一 key，无条件 remove 会误摘后继的表项。
     */
    public boolean removeIfCurrent(String sessionKey, Turn turn) {
        return remove(sessionKey, turn);
    }

    /**
     * offer 的三态结果，调用方据此分流（区别两类失败是 2026-09-09 审计 P0 修复）：
     * <ul>
     *   <li>{@link #OFFERED} 已入队（ack 语义成立）；</li>
     *   <li>{@link #NO_SLOT} 槽缺失（无条目/已 closed/队列未武装——垂死或交接窗口）：
     *       调用方落穿开新回合是正确归宿；</li>
     *   <li>{@link #FULL} 槽存活但队列满（健康回合仍在跑）：调用方<b>必须</b>拒绝为
     *       busy、不得落穿开新回合——register 的 latest-wins put 会把仍在跑的健康
     *       回合整条替换出注册表，其 abortFlag/runnerThread/future 三条取消通道
     *       从此全部断裂（Stop 只能取消到新排队的回合，原回合继续烧 token/改树）。</li>
     * </ul>
     */
    public enum OfferStatus { OFFERED, NO_SLOT, FULL }

    /**
     * 向会话当前回合的注入队列投递一条消息。与置 closed 的操作在 computeIfPresent
     * 的同一 bin 锁下执行：要么入队成功，要么明确观察到槽已摘（{@link OfferStatus#NO_SLOT}）
     * 或队列满（{@link OfferStatus#FULL}）——不存在写入已脱离路由的队列却回报成功的
     * 窗口。
     *
     * @return 见 {@link OfferStatus}；FULL 时消息未入队、归宿由调用方决定
     */
    public OfferStatus offer(String sessionKey, String message, boolean announcement) {
        AtomicReference<OfferStatus> result = new AtomicReference<>(OfferStatus.NO_SLOT);
        computeIfPresent(sessionKey, (key, turn) -> {
            LinkedBlockingQueue<InjectionItem> queue = turn.queue();
            if (!turn.closed() && queue != null) {
                result.set(queue.offer(new InjectionItem(message, announcement))
                        ? OfferStatus.OFFERED : OfferStatus.FULL);
            }
            return turn;
        });
        OfferStatus status = result.get();
        if (status == OfferStatus.FULL) {
            log.warn("Injection queue full for session {}; message not enqueued (caller decides fate)",
                    sessionKey);
        } else if (status == OfferStatus.OFFERED) {
            log.debug("Message offered to injection queue for session {}", sessionKey);
        }
        return status;
    }

    /**
     * 取消时摘除会话的注入路由：置 closed 而非 remove——条目须活到回合收尾点。
     * 垂死会话立即不可注入，新消息只能开新回合；已开跑的回合仍持队列句柄，
     * 其收尾照常抽干残留。
     * <p>按值置位（仅当表项仍是取消方观察到的那个回合）：[find → 此处] 窗口内
     * 后继回合可能已注册——无条件置 closed 会连后继的路由一并摘掉。turn 为 null
     * 时不置。
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
     * 回合收尾善后：先按值置 closed（不得误摘已注册后继的路由），再抽干队列残留。
     * 先置位再抽干的顺序保证 offer 要么赶在置位前入队（随残留返回）、要么被拒——
     * 消息不会滞留在垂死队列里无人消费。
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
     * 会话当前是否有可注入的活跃回合：条目存在 && 未 closed && 队列已武装。
     * AgentLoop 忙期路由（注入 vs 开新回合）的唯一事实来源。
     */
    public boolean hasActiveRun(String sessionKey) {
        Turn turn = get(sessionKey);
        return turn != null && !turn.closed() && turn.queue() != null;
    }
}
