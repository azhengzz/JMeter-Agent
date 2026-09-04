package org.gitee.jmeter.ai.agent.turn;

/**
 * 注入队列的单个条目（原 {@code InjectionManager.InjectionItem}，Phase 4 路由槽合并
 * 随迁至 {@code agent.turn}）。携带条目来源，收尾清理据此区分用户消息（可 re-publish
 * 成新回合）与子代理公告（取消/收尾时丢弃；其结果仍可经 {@code subagent_status} 查询）。
 */
public final class InjectionItem {
    private final String text;
    private final boolean announcement;

    public InjectionItem(String text, boolean announcement) {
        this.text = text;
        this.announcement = announcement;
    }

    public String getText() {
        return text;
    }

    public boolean isAnnouncement() {
        return announcement;
    }
}
