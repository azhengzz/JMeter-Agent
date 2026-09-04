package org.gitee.jmeter.ai.ipc;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;

/**
 * 单回合助手文本累积器：挂在 {@code /agent} 回合的进度回调上，收集截至任意时刻
 * 已产生的助手内容，供取消/超时响应的 {@code partialContent} 回传（GUI 无关，
 * 面板不存在时同样累积）。
 *
 * <p>累积来源仅 {@code INTERMEDIATE_RESPONSE}（注入驱动续跑的中间回复）——
 * THINKING 事件受思考展示开关影响（{@code <think>} 包裹/剥离后形态不定），
 * 不作为累积来源（design D5）；{@code onStream} 无调用方（无流式 API）。
 * 部分内容定位为「尽力而为」：无注入续跑的回合并无中间回复可累积，快照为空。
 *
 * <p>线程安全：回调从 agent-loop / 子代理池线程打点，取消/超时读取在
 * ipc-worker 线程——append 与 snapshot 并发，均加锁。
 */
public final class TurnContentAccumulator implements AgentLoop.ProgressCallback {

    /** partialContent 截断上限：防长回合把 409/504 响应体撑爆。 */
    public static final int MAX_PARTIAL_CHARS = 8000;

    private static final String SEPARATOR = "\n\n";
    private static final String TRUNCATION_MARKER = "\n...(truncated)";

    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void onProgress(ProgressUpdate update) {
        if (update == null || update.getType() != ProgressUpdate.Type.INTERMEDIATE_RESPONSE) {
            return;
        }
        String text = update.getMessage();
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (buffer.length() > 0) {
                buffer.append(SEPARATOR);
            }
            buffer.append(text);
        }
    }

    /**
     * 截断快照：超过 {@link #MAX_PARTIAL_CHARS} 时截断并追加省略标记；
     * 无累积返回空串（调用方据此省略 partialContent 字段）。
     */
    public String snapshotTruncated() {
        String raw;
        synchronized (this) {
            raw = buffer.toString();
        }
        if (raw.length() <= MAX_PARTIAL_CHARS) {
            return raw;
        }
        return raw.substring(0, MAX_PARTIAL_CHARS) + TRUNCATION_MARKER;
    }
}
