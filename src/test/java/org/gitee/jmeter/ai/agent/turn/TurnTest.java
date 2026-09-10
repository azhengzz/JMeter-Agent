package org.gitee.jmeter.ai.agent.turn;

import org.gitee.jmeter.ai.agent.presenter.TurnHandle;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turn/TurnRegistry 注柄语义单元测试（队列归回合所有，注册表条目 + closed 作路由槽；
 * 原 {@code InjectionManagerTest} 随 Phase 4 路由槽合并逐字迁移，断言语义不动）。
 *
 * <p>必须成立的不变量：
 * <ul>
 *   <li>drain/cleanup 按句柄操作——一个回合抽不干别的回合的队列（防垂死回合偷后继消息）；</li>
 *   <li>cleanup/closeRouting 条件摘槽——只摘自己的路由（置 closed），不误摘后继回合的
 *       （防僵尸路由）；</li>
 *   <li>closeRouting 后 offer 原子失败——不存在「拿到队列引用后被摘、写入悬挂队列
 *       却返回成功」的竞态；句柄仍可抽干（pre-pickup 死任务善后依赖）。</li>
 * </ul>
 */
class TurnTest {

    @Test
    void registerTwice_drainIsolatedByHandle() {
        TurnRegistry registry = new TurnRegistry();
        Turn t1 = newTurn("s");
        registry.register("s", t1);
        t1.armQueue();
        assertTrue(registry.offer("s", "m1", false) == TurnRegistry.OfferStatus.OFFERED);

        // 后继回合占槽（put 替换）：路由指向新队列，旧队列由旧回合句柄继续持有
        Turn t2 = newTurn("s");
        registry.register("s", t2);
        t2.armQueue();
        assertNotSame(t1.queue(), t2.queue());
        assertTrue(registry.offer("s", "m2", false) == TurnRegistry.OfferStatus.OFFERED);

        // 按句柄抽干：t1 的消息绝不会被 t2 的回合抽走
        assertEquals(List.of("m1"), texts(t1.drain(10)));
        assertEquals(List.of("m2"), texts(t2.drain(10)));
    }

    @Test
    void cleanup_removesOnlyOwnRoutingSlot() {
        TurnRegistry registry = new TurnRegistry();
        Turn t1 = newTurn("s");
        registry.register("s", t1);
        t1.armQueue();
        assertTrue(registry.offer("s", "m1", false) == TurnRegistry.OfferStatus.OFFERED);
        Turn t2 = newTurn("s");
        registry.register("s", t2);
        t2.armQueue();

        // 前回合收尾：抽干自己的残留，条件摘槽失败（槽是后继的），后继路由不受影响
        List<InjectionItem> leftover1 = registry.cleanup("s", t1);
        assertEquals(List.of("m1"), texts(leftover1));
        assertTrue(registry.hasActiveRun("s"), "后继回合的路由槽必须存活");

        assertTrue(registry.offer("s", "m2", false) == TurnRegistry.OfferStatus.OFFERED);
        List<InjectionItem> leftover2 = registry.cleanup("s", t2);
        assertEquals(List.of("m2"), texts(leftover2));
        assertFalse(registry.hasActiveRun("s"), "最后一个回合收尾后槽应清空");
    }

    @Test
    void closeRouting_offerFailsAtomically_handleStillDrainable() {
        TurnRegistry registry = new TurnRegistry();
        Turn t = newTurn("s");
        registry.register("s", t);
        t.armQueue();
        assertTrue(registry.offer("s", "m1", false) == TurnRegistry.OfferStatus.OFFERED);

        registry.closeRouting("s", t);
        assertFalse(registry.hasActiveRun("s"), "取消即摘槽");
        assertEquals(TurnRegistry.OfferStatus.NO_SLOT, registry.offer("s", "m2", false),
                "槽已摘除，offer 必须失败（computeIfPresent 原子语义）");

        // 死任务善后：句柄仍可抽干残留，且不误摘（槽已不在）
        List<InjectionItem> leftover = registry.cleanup("s", t);
        assertEquals(List.of("m1"), texts(leftover));
        assertFalse(registry.hasActiveRun("s"));
    }

    /**
     * offer 三态（2026-09-09 审计 P0 修复的钉定）：槽存活但队列满必须返回 FULL 而
     * 非 NO_SLOT——doProcessMessage 据此分流（FULL → busy 拒绝；NO_SLOT → 落穿开
     * 新回合），两者混同曾让队满消息落穿 startTurn 把健康回合整条替换出注册表。
     */
    @Test
    void offerQueueFull_returnsFull_notNoSlot() {
        TurnRegistry registry = new TurnRegistry();
        Turn t = newTurn("s");
        registry.register("s", t);
        t.armQueue();
        int capacity = t.queue().size() + t.queue().remainingCapacity();
        for (int i = 0; i < capacity; i++) {
            assertEquals(TurnRegistry.OfferStatus.OFFERED, registry.offer("s", "m-" + i, false),
                    "填满队列的第 " + i + " 条应入队成功");
        }
        assertEquals(TurnRegistry.OfferStatus.FULL, registry.offer("s", "overflow", false),
                "队满必须返回 FULL（槽存活），不得混同 NO_SLOT");
        assertTrue(registry.hasActiveRun("s"), "队满不等于槽摘除——路由仍存活");
    }

    /**
     * closeRouting 同款身份守卫：取消方持有的 turn 是 signalCancel 入口快照，条目可能
     * 已被后继整条替换（垂死收尾 re-publish）——陈旧快照的摘槽不得把后继路由一并
     * 置 closed（后继将不可注入且 hasActiveRun 报假空）。对抗审查 2026-09-03 追加修复
     * 的钉定（此前 closeRouting 无条件置位，仅靠取消步骤间序侥幸不命中）。
     */
    @Test
    void closeRouting_identityGuarded_neverClosesSuccessor() {
        TurnRegistry registry = new TurnRegistry();
        Turn t1 = newTurn("s");
        registry.register("s", t1);
        t1.armQueue();
        Turn t2 = newTurn("s");
        registry.register("s", t2);  // 后继整条替换：陈旧快照 t1 的摘槽窗口
        t2.armQueue();

        // 陈旧快照摘槽：必须落空（槽是后继的），后继路由照常存活可注入
        registry.closeRouting("s", t1);
        assertTrue(registry.hasActiveRun("s"), "后继的路由不得被陈旧取消快照误摘");
        assertTrue(registry.offer("s", "m-late", false) == TurnRegistry.OfferStatus.OFFERED,
                "后继必须仍可注入");

        // 本尊摘槽才生效
        registry.closeRouting("s", t2);
        assertFalse(registry.hasActiveRun("s"));
        assertEquals(TurnRegistry.OfferStatus.NO_SLOT, registry.offer("s", "m2", false));

        // null 快照（取消方未观察到回合）不置位任何条目
        Turn t3 = newTurn("s");
        registry.register("s", t3);
        t3.armQueue();
        registry.closeRouting("s", null);
        assertTrue(registry.hasActiveRun("s"), "null 快照不得置 closed");
        assertTrue(registry.offer("s", "m3", false) == TurnRegistry.OfferStatus.OFFERED);
    }

    /**
     * removeIfCurrent 按值条件摘除：前回合收尾时后继已占槽（re-publish / put 替换），
     * 无条件 remove(key) 会摘掉后继表项（Stop/注入/waitForCancellation 从此扑空的
     * 僵尸回合）——只许摘自己（对抗审查 2026-09-02：signalCancel 3.5 步同款防线）。
     */
    @Test
    void removeIfCurrent_neverRemovesSuccessor() {
        TurnRegistry registry = new TurnRegistry();
        Turn t1 = newTurn("s");
        registry.register("s", t1);
        Turn t2 = newTurn("s");
        registry.register("s", t2);  // 后继整条替换前回合

        assertFalse(registry.removeIfCurrent("s", t1), "前回合按值摘除应落空（槽是后继的）");
        assertSame(t2, registry.find("s"), "后继表项不得被前回合的摘除误伤");

        assertTrue(registry.removeIfCurrent("s", t2), "本回合按值摘除命中");
        assertNull(registry.find("s"));
    }

    /**
     * markPickup 接线（D1 聚合落位）：pickup 代数须真实写入 Turn 字段——此前仅局部
     * 变量携带、字段恒 0（对抗审查 2026-09-02 确认的未接线）。初始值 0（未 pickup），
     * ownResetEpoch 初始为哨兵 UNSET。
     */
    @Test
    void markPickup_recordsEpochOnTurn() {
        Turn t = newTurn("s");
        assertEquals(0L, t.epoch(), "未 pickup 的回合 epoch 为默认 0");
        assertEquals(Turn.OWN_RESET_EPOCH_UNSET, t.ownResetEpoch(),
                "未执行过自身重置的回合 ownResetEpoch 为哨兵值");

        t.markPickup(7L);
        assertEquals(7L, t.epoch(), "pickup 代数必须写入字段（此前恒 0 的未接线缺陷）");

        t.recordOwnReset(8L);
        assertEquals(8L, t.ownResetEpoch());
    }

    @Test
    void announcementFlag_roundTrips() {
        TurnRegistry registry = new TurnRegistry();
        Turn t = newTurn("s");
        registry.register("s", t);
        t.armQueue();
        assertTrue(registry.offer("s", "user-msg", false) == TurnRegistry.OfferStatus.OFFERED);
        assertTrue(registry.offer("s", "subagent-announce", true) == TurnRegistry.OfferStatus.OFFERED);

        List<InjectionItem> items = t.drain(10);
        assertEquals(2, items.size());
        assertFalse(items.get(0).isAnnouncement(), "默认 offer 是用户消息");
        assertEquals("user-msg", items.get(0).getText());
        assertTrue(items.get(1).isAnnouncement(), "公告标记必须随队列条目透传");
        assertEquals("subagent-announce", items.get(1).getText());
    }

    private static Turn newTurn(String sessionKey) {
        return new Turn(sessionKey,
                new TurnHandle(sessionKey, TurnOrigin.LOCAL_PANEL, "echo", false),
                null, false);
    }

    private static List<String> texts(List<InjectionItem> items) {
        return items.stream().map(InjectionItem::getText).toList();
    }
}
