package org.gitee.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.UIManager;

import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.selection.SelectionSnapshot;
import org.gitee.jmeter.ai.utils.JMeterElementManager;

/**
 * 选中上下文展示栏 — 使用 {@link #paintComponent} 直接绘制，**完全避免 revalidate**。
 *
 * <p><b>设计要点</b>：早期版本用 {@code JLabel.setText(html)}，但 JDK 的 JLabel.setText
 * 在 text 变化时会调用 {@code revalidate()}，沿组件树向上传播，最终触发 MainFrame 重新 layout。
 * JTable 在重新 layout 时会 {@code cancelCellEditing()} — 这会让用户在表格中新增的行
 * "一闪而过"无法保存，也会破坏 Body Data textarea 的编辑状态。
 *
 * <p>本实现改为：{@code update()} 只更新内部数据并调用 {@code repaint()}，
 * repaint 只触发 paintComponent，不改变组件树结构。revalidate 不会传播，cell editor 不受影响。
 *
 * <p>固定 4 行高度，每行可独立设置颜色和加粗。
 */
public class SelectionContextBar extends JPanel {

    private static final int LINE_HEIGHT = 14;
    private static final int PADDING_X = 8;
    private static final int PADDING_TOP = 4;
    private static final int PADDING_BOTTOM = 4;
    private static final int ROW_COUNT = 2;

    private final Line[] lines;
    private final Font plainFont;
    private final Font boldFont;
    private final Color defaultColor;
    private final Color secondaryColor;
    private final Color focusColor = new Color(70, 130, 180);
    private String lastKey = "";

    public SelectionContextBar() {
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, PADDING_X, 0, 0));

        Font baseFont = UIManager.getFont("Label.font");
        plainFont = baseFont != null ? baseFont.deriveFont(11f) : new Font(Font.SANS_SERIF, Font.PLAIN, 11);
        boldFont = plainFont.deriveFont(Font.BOLD);
        defaultColor = getThemeColor("Label.foreground", new Color(60, 60, 60));
        secondaryColor = getThemeColor("Label.disabledForeground", new Color(130, 130, 130));

        lines = new Line[ROW_COUNT];
        for (int i = 0; i < ROW_COUNT; i++) {
            lines[i] = new Line();
        }

        // 固定高度（避免 layout 抖动），宽度让 BorderLayout.CENTER 撑满
        Dimension fixed = new Dimension(0, PADDING_TOP + PADDING_BOTTOM + LINE_HEIGHT * ROW_COUNT);
        setPreferredSize(fixed);
        setMinimumSize(fixed);
        setMaximumSize(fixed);

        update(SelectionSnapshot.empty());
    }

    /**
     * 更新展示栏内容。snapshot 内容未变化时直接 return，避免不必要的 repaint。
     *
     * <p><b>关键</b>：只调用 {@code repaint}，不调用 {@code revalidate}，
     * 不触发 setText，避免组件树结构变化。
     */
    public void update(SelectionSnapshot snapshot) {
        String key = computeKey(snapshot);
        if (key.equals(lastKey)) {
            return;
        }
        lastKey = key;
        fillLines(snapshot);
        repaint();
    }

    private void fillLines(SelectionSnapshot snapshot) {
        // 清空所有行
        for (Line line : lines) {
            line.text = "";
            line.color = defaultColor;
            line.bold = false;
        }

        if (snapshot == null || snapshot.isEmpty()) {
            lines[0].text = "No element selected";
            lines[0].color = secondaryColor;
            return;
        }

        // 日志面板上下文：L2 焦点位于底部 LoggerPanel 时，L1 仍保留上次选中元素
        // 会让"元素信息"与"日志选区"互相割裂。改为 L1 展示日志说明，不显示 id。
        boolean isLogContext = snapshot.focusControl != null
                && "LoggerPanel".equals(snapshot.focusControl.controlType);

        if (isLogContext) {
            // Row 1: 📜 Log (no id, no child count)
            lines[0].text = "📜 Log Panel";
            lines[0].color = defaultColor;
            lines[0].boldPrefix = "📜 ";
        } else {
            TestElement element = snapshot.element;
            String displayType = (snapshot.elementType != null)
                    ? JMeterElementManager.getDefaultNameForElement(snapshot.elementType)
                    : element.getClass().getSimpleName();
            String icon = pickIcon(snapshot.elementType, element.getClass().getSimpleName());
            String name = element.getName();

            // Row 1: icon + type (bold) + name + elementId + child count
            StringBuilder r1 = new StringBuilder();
            r1.append(icon).append(' ').append(displayType).append(' ');
            r1.append(name == null || name.isEmpty() ? "(unnamed)" : name);
            r1.append("  #").append(snapshot.elementId);
            if (snapshot.childCount > 0) {
                r1.append("  ×").append(snapshot.childCount);
            }
            lines[0].text = r1.toString();
            lines[0].color = defaultColor;
            lines[0].boldPrefix = icon + " " + displayType + " ";
        }

        // Row 2: focus control (L2)
        if (snapshot.focusControl != null
                && snapshot.focusControl.controlType != null
                && !snapshot.focusControl.isEmpty()) {
            StringBuilder r2 = new StringBuilder();
            r2.append("🎯 [").append(snapshot.focusControl.controlType).append("] ");
            String fn = snapshot.focusControl.fieldName;
            r2.append(fn == null || fn.isEmpty() ? "(unlabeled field)" : fn);
            if (snapshot.focusControl.value != null && !snapshot.focusControl.value.isEmpty()) {
                r2.append(" = ").append(snapshot.focusControl.value);
            }
            lines[1].text = r2.toString();
            lines[1].color = focusColor;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        FontMetrics plainFm = g.getFontMetrics(plainFont);
        FontMetrics boldFm = g.getFontMetrics(boldFont);
        int y = PADDING_TOP + Math.max(plainFm.getAscent(), boldFm.getAscent());

        for (Line line : lines) {
            if (line.text != null && !line.text.isEmpty()) {
                g.setColor(line.color != null ? line.color : defaultColor);

                if (line.boldPrefix != null && !line.boldPrefix.isEmpty()
                        && line.text.startsWith(line.boldPrefix)) {
                    // 主体加粗 + 后续正常
                    g.setFont(boldFont);
                    g.drawString(line.boldPrefix, PADDING_X, y);
                    int w = boldFm.stringWidth(line.boldPrefix);
                    g.setFont(plainFont);
                    g.drawString(line.text.substring(line.boldPrefix.length()), PADDING_X + w, y);
                } else {
                    g.setFont(line.bold ? boldFont : plainFont);
                    g.drawString(line.text, PADDING_X, y);
                }
            }
            y += LINE_HEIGHT;
        }
        // 重置字体（避免影响后续绘制）
        g.setFont(plainFont);
    }

    private String computeKey(SelectionSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(snapshot.elementId).append('|');
        sb.append(snapshot.childCount).append('|');
        sb.append(snapshot.path == null ? "" : snapshot.path).append('|');
        if (snapshot.focusControl != null && snapshot.focusControl.controlType != null) {
            sb.append(snapshot.focusControl.controlType).append(':')
              .append(snapshot.focusControl.fieldName == null ? "" : snapshot.focusControl.fieldName)
              .append('=')
              .append(snapshot.focusControl.value == null ? "" : snapshot.focusControl.value);
        }
        return sb.toString();
    }

    private static Color getThemeColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static String pickIcon(String elementType, String modelClass) {
        String key = elementType != null ? elementType.toLowerCase() : "";
        if (key.contains("httpsampler") || key.contains("httptestsample")
                || modelClass.contains("HTTPSampler")) {
            return "🌐";
        }
        if (key.contains("threadgroup") || modelClass.endsWith("ThreadGroup")) {
            return "👥";
        }
        if (key.contains("controller") || modelClass.endsWith("Controller")) {
            return "🔀";
        }
        if (key.contains("assertion") || modelClass.endsWith("Assertion")) {
            return "✓";
        }
        if (key.contains("timer") || modelClass.endsWith("Timer")) {
            return "⏱";
        }
        if (key.contains("config") || modelClass.contains("Config")
                || modelClass.endsWith("Manager") || modelClass.endsWith("Variables")) {
            return "🔧";
        }
        if (key.contains("preprocessor") || modelClass.endsWith("PreProcessor")) {
            return "▼";
        }
        if (key.contains("postprocessor") || modelClass.endsWith("PostProcessor")) {
            return "▲";
        }
        if (key.contains("listener") || modelClass.endsWith("Listener")
                || modelClass.endsWith("Reporter")) {
            return "📊";
        }
        if (key.contains("jdbc") || modelClass.contains("JDBC")) {
            return "🗄";
        }
        if (key.contains("testplan") || modelClass.contains("TestPlan")) {
            return "📋";
        }
        if (key.contains("testfragment") || modelClass.contains("TestFragment")) {
            return "⛶";
        }
        if (key.contains("jsr223") || key.contains("beanshell") || modelClass.contains("Script")) {
            return "📜";
        }
        return "▶";
    }

    /** 一行绘制数据。 */
    private static class Line {
        String text = "";
        Color color;
        boolean bold;
        /** 如果设置，则 text 开头这一段以加粗字体绘制，剩余正常字体。 */
        String boldPrefix;
    }
}
