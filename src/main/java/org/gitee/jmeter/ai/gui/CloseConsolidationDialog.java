package org.gitee.jmeter.ai.gui;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.memory.CloseConsolidationCoordinator;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * 关闭期记忆整合交互对话框。由 {@code ExitCommand} 前置动作监听在 EDT 调用。
 *
 * <p><b>职责切分</b>:
 * <ul>
 *   <li>本对话框(EDT 前置监听):捕获未整合快照、询问是否深度提炼、选"是"则在
 *       {@code SwingWorker}(非 EDT)执行深度提炼并回传进度/结果。<b>提炼成功后清空当前会话
 *       (数据 + 消息区,视觉效果对齐 {@code /new})</b>——提炼结果已落入 {@code MEMORY.md}
 *       (经系统提示持续生效),原会话消息不再有价值;清空使 {@code unconsolidatedSnapshot()}
 *       返回空,<b>杜绝退出被取消后二次触发提炼同一批消息</b>(反转此前"提炼后不清空"的权衡)。</li>
 *   <li>静默归档(把消息原样追加进 {@code HISTORY.md} 并推进索引)交由 JVM shutdown hook,
 *       仅在真实 {@code System.exit} 时执行;提炼成功已清空会话后,该路径为 no-op。</li>
 * </ul>
 *
 * <p>线程模型:整方法在 EDT;深度提炼经 {@code SwingWorker} 转后台,进度经模态对话框回显。
 * N=0 / 记忆关闭 / 测试运行中 均不弹框。
 */
public final class CloseConsolidationDialog {
    private static final Logger log = LoggerFactory.getLogger(CloseConsolidationDialog.class);

    private CloseConsolidationDialog() {
    }

    /**
     * 关闭整合主入口(EDT)。顺序:门控 → 询问 → 选"是"则深度提炼并告知。
     */
    public static void handleExit() {
        // 测试运行中:ExitCommand 自身会拒绝退出,前置监听不做任何副作用
        if (JMeterUtils.isTestRunning()) {
            return;
        }
        List<Message> snapshot = CloseConsolidationCoordinator.unconsolidatedSnapshot();
        // 展示口径:按全量消息计数(含工具消息,对齐 /status 与 max.history.size 的口径)。
        // 快照本身仍全量交给深度提炼(工具上下文对提炼有价值)。
        long n = snapshot.size();

        if (n == 0
                || !AiConfig.isMemoryEnabled()) {
            return;
        }

        Frame parent = mainFrame();
        int choice = JOptionPane.showConfirmDialog(parent,
                "This session has " + n + " messages available to consolidate.\n"
                        + "Yes: Consolidate messages into long-term memory (MEMORY.md)\n"
                        + "No: Skip consolidation; session history is still archived as a log on exit",
                "Consolidate Memory", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        runDistillWithProgress(parent, snapshot);
    }

    /**
     * 模态进度对话框 + 后台 SwingWorker 深度提炼;完成后告知用户再返回(随后退出流程继续)。
     *
     * <p>提供"Skip &amp; Exit"逃生按钮:用户不想等(LLM 卡慢)时可立即放行退出——后台提炼
     * 尽力而为,若随后完成仍会落盘,JVM 退出时自然终止。
     */
    private static void runDistillWithProgress(Frame parent, List<Message> snapshot) {
        JDialog progress = new JDialog(parent, "Consolidating Memory", true);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JLabel label = new JLabel("Distilling " + snapshot.size() + " messages, please wait...");

        final boolean[] skipped = {false};
        JButton skip = new JButton("Skip & Exit");
        skip.addActionListener(e -> {
            skipped[0] = true;
            progress.dispose(); // 解除下方 setVisible 的模态阻塞,退出流程继续
        });
        JPanel south = new JPanel();
        south.add(skip);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 26, 18, 26));
        panel.add(label, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        progress.setContentPane(panel);
        progress.pack();
        progress.setLocationRelativeTo(parent);

        final boolean[] result = {false};
        SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                // 先取消本会话尚在跑的 Agent 回合(关闭只挡测试运行,不挡 agent 回合):
                // 否则提炼/清空与并发回合写会话相互竞态,快照之后新增的消息也不会进本次提炼。
                // 走协调器入口 = 工厂跨实例路由 cancelActiveTaskAny:取消触达当前+退役
                // loop 上该会话的在跑回合(换血后旧 loop 的 IPC/委派回合不再漏取消,
                // 重建窗口 instance==null 也不再有 IllegalStateException 被吞的路径)。
                // 最多合计等 5s 收尾——在后台线程,不占 EDT。
                // SILENT 语义:面板对 LOCAL 源回合不渲染取消噪音行;IPC 源回合照旧
                // 渲染终止回执行,wire 信封仍由 IpcServer 收口为 cancelled_by_target_user。
                CloseConsolidationCoordinator.cancelActiveTurnsSilently();
                // EDT 快照先于 cancel 取得,模态等待期间
                // run 的后置整合(AgentRunner 回合后 maybeConsolidate)可能已完成——写完
                // HISTORY/MEMORY、推进 lastConsolidatedIndex、run future 完成后 abort flag 被
                // 移除,cancelActiveTaskAny 空转。此处 cancel 后重读当前未整合集:已空则后置整合
                // 已覆盖,视为完成(不二次提炼);否则只提炼仍未整合的部分,杜绝重复 HISTORY 条目。
                List<Message> fresh = CloseConsolidationCoordinator.unconsolidatedSnapshot();
                if (fresh.isEmpty()) {
                    return true;
                }
                return CloseConsolidationCoordinator.distill(fresh);
            }

            @Override
            protected void done() {
                try {
                    result[0] = get();
                } catch (Exception e) {
                    log.warn("Close-consolidation SwingWorker failed: {}", e.toString());
                    result[0] = false;
                }
                progress.dispose(); // 解除下方 setVisible 的模态阻塞(skip 后为 no-op)
                if (result[0]) {
                    // 提炼成功:清空会话数据(杜绝退出取消后二次触发提炼同一批消息)
                    // + 清空消息区(视觉效果对齐 /new,只保留还原提示)。
                    CloseConsolidationCoordinator.clearCurrentSession();
                    AiChatPanel.resetAfterConsolidation();
                }
            }
        };
        worker.execute();
        progress.setVisible(true); // 阻塞直至 done()/skip 调 dispose

        if (skipped[0]) {
            return; // 用户放弃等待:不再弹完成提示,退出流程继续
        }
        String msg = result[0]
                ? "Consolidation complete. Long-term memory updated."
                : "Consolidation incomplete (timed out or failed). Session history will still be archived on exit.";
        JOptionPane.showMessageDialog(parent, msg, "Memory Consolidation", JOptionPane.INFORMATION_MESSAGE);
    }

    private static Frame mainFrame() {
        try {
            GuiPackage gp = GuiPackage.getInstance();
            return gp != null ? gp.getMainFrame() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
