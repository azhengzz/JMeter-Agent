package org.gitee.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;

import javax.swing.JComponent;
import javax.swing.UIManager;

/**
 * 上下文窗口用量环形指示器 — 空心环（底环 + 按占比的进度弧），挂在模型选择器右侧。
 *
 * <p><b>设计要点</b>（对齐 {@link SelectionContextBar} 模板）：{@code update()} 只更新
 * 内部数据 + {@code setToolTipText} 并调用 {@code repaint()}，**完全避免 revalidate**——
 * revalidate 沿组件树传播会触发 JTable {@code cancelCellEditing}，破坏用户在 JMeter
 * 编辑器中的编辑状态。固定 preferredSize（18×18）防布局抖动。
 *
 * <p>占比 = 已用 tokens ÷ 上下文窗口 tokens，clamp [0,1]（网关口径差异可能超 100%）。
 * 高水位（≥80%）弧色转橙、危险水位（≥95%）转红，与常规态可视觉区分。
 *
 * <p>线程契约：EDT only——更新只从面板的回合事件分发（已自投 EDT）与
 * {@code advanceRenderEpoch}（只在 EDT）到达。
 */
public class ContextUsageRing extends JComponent {

    private static final int SIZE = 18;
    private static final float STROKE_WIDTH = 3.75f;
    /** 高水位：弧色转橙（spec 只钉"高水位可区分"，阈值/色值为实现常量）。 */
    private static final double HIGH_WATERMARK = 0.80;
    /** 危险水位：弧色转红。 */
    private static final double DANGER_WATERMARK = 0.95;

    private final Color trackColor;
    private final Color normalColor;
    private final Color highColor = new Color(230, 145, 30);
    private final Color dangerColor = new Color(214, 69, 65);

    private long usedTokens;
    private long totalTokens;
    private int acceptedUpdates;

    public ContextUsageRing() {
        setOpaque(false);
        // 底环固定浅灰（手动冒烟反馈：disabledForeground 在 Metal 下偏深，与进度弧
        // 亮度接近导致用量读不出）；弧色仍走 UIManager + 兜底（对齐 SelectionContextBar）
        trackColor = new Color(215, 215, 215);
        normalColor = getThemeColor("Component.accentColor", new Color(70, 130, 180));

        Dimension fixed = new Dimension(SIZE, SIZE);
        setPreferredSize(fixed);
        setMinimumSize(fixed);
        setMaximumSize(fixed);
    }

    /**
     * 更新用量：内容未变化时直接 return（去重，避免无谓 repaint）。只调用
     * {@code repaint}，不触发 revalidate / preferredSize 变化。
     */
    public void update(long used, long total) {
        if (used == usedTokens && total == totalTokens) {
            return;
        }
        usedTokens = used;
        totalTokens = total;
        acceptedUpdates++;
        setToolTipText(formatTooltip(used, total));
        repaint();
    }

    /** 会话重置：指示器归零并清空悬浮明细。 */
    public void reset() {
        usedTokens = 0;
        totalTokens = 0;
        setToolTipText(null);
        repaint();
    }

    /** 占比 clamp [0,1]；total 非正视为无数据（0）。 */
    double ratio() {
        if (totalTokens <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, usedTokens / (double) totalTokens));
    }

    /** 弧色分档：常规 / 高水位 / 危险水位。 */
    Color arcColor(double r) {
        if (r >= DANGER_WATERMARK) {
            return dangerColor;
        }
        if (r >= HIGH_WATERMARK) {
            return highColor;
        }
        return normalColor;
    }

    /** 状态变化计数（去重行为的测试观察缝：同值重复 update 不计数）。 */
    int acceptedUpdateCount() {
        return acceptedUpdates;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double inset = STROKE_WIDTH / 2.0 + 0.5;
            double d = Math.min(getWidth(), getHeight()) - 2 * inset;
            if (d <= 0) {
                return;
            }
            double x = (getWidth() - d) / 2.0;
            double y = (getHeight() - d) / 2.0;
            Stroke stroke = new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            g2.setStroke(stroke);

            // 空心底环
            g2.setColor(trackColor);
            g2.draw(new Ellipse2D.Double(x, y, d, d));

            // 进度弧：12 点起顺时针。AWT 角度约定 0°=3 点、正角**逆时针**——
            // 故 12 点 = 90°，顺时针扫需负向 extent
            double r = ratio();
            if (r > 0) {
                g2.setColor(arcColor(r));
                g2.draw(new Arc2D.Double(x, y, d, d, 90.0, -r * 360.0, Arc2D.OPEN));
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * 悬浮明细，k 记法对齐 {@code /status} 文案口径（已用 /1000、总量 /1024、百分比取整）。
     */
    private static String formatTooltip(long used, long total) {
        if (total <= 0) {
            return "Context: " + used + " / n/a";
        }
        int pct = (int) ((used / (double) total) * 100);
        return "Context: " + formatUsed(used) + " / " + formatTotal(total) + " (" + pct + "%)";
    }

    private static String formatUsed(long t) {
        return t >= 1000 ? (t / 1000) + "k" : String.valueOf(t);
    }

    private static String formatTotal(long t) {
        return t > 0 ? (t / 1024) + "k" : "n/a";
    }

    private static Color getThemeColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }
}
