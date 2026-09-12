package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextUsageRing} 状态机单测（headless）：占比 clamp、update 去重、
 * reset 清零、tooltip k 记法（对齐 /status 口径）、高/危险水位色档判定，
 * 以及弧几何像素钉（12 点起顺时针）。
 */
class ContextUsageRingTest {

    // ---- 1. 占比计算与 >100% clamp ----

    @Test
    void ratioClampedToUnit() {
        ContextUsageRing ring = new ContextUsageRing();
        assertEquals(0, ring.ratio(), "no data yet");

        ring.update(32_000, 64_000);
        assertEquals(0.5, ring.ratio(), 1e-9);

        ring.update(80_000, 64_000);
        assertEquals(1.0, ring.ratio(), "over-window usage must clamp to full ring");
    }

    // ---- 2. update 去重：同值重复不计数、变化才计数 ----

    @Test
    void updateDeduplicated() {
        ContextUsageRing ring = new ContextUsageRing();
        ring.update(1000, 10_000);
        ring.update(1000, 10_000);
        assertEquals(1, ring.acceptedUpdateCount(), "duplicate state must be a no-op");

        ring.update(2000, 10_000);
        assertEquals(2, ring.acceptedUpdateCount());
    }

    // ---- 3. reset 清零并清 tooltip ----

    @Test
    void resetClearsState() {
        ContextUsageRing ring = new ContextUsageRing();
        ring.update(5000, 65_536);
        assertEquals("Context: 5k / 64k (7%)", ring.getToolTipText());

        ring.reset();
        assertEquals(0, ring.ratio());
        assertNull(ring.getToolTipText(), "reset must clear the hover detail");
    }

    // ---- 4. tooltip 文案（/status 口径：已用 /1000、总量 /1024、百分比取整）----

    @Test
    void tooltipFormatMatchesStatusQuirk() {
        ContextUsageRing ring = new ContextUsageRing();
        ring.update(12_345, 65_536);
        assertEquals("Context: 12k / 64k (18%)", ring.getToolTipText());

        ring.update(999, 65_536);
        assertEquals("Context: 999 / 64k (1%)", ring.getToolTipText(), "below 1k shows raw value");

        ring.update(500, 0);
        assertEquals("Context: 500 / n/a", ring.getToolTipText(), "non-positive total degrades gracefully");
    }

    // ---- 5. 高/危险水位色档判定 ----

    @Test
    void colorBandsDistinct() {
        ContextUsageRing ring = new ContextUsageRing();
        Object normal = ring.arcColor(0.5);
        Object high = ring.arcColor(0.80);
        Object danger = ring.arcColor(0.95);

        assertEquals(normal, ring.arcColor(0.79), "below high watermark stays normal");
        assertEquals(high, ring.arcColor(0.94), "high watermark band");
        assertEquals(danger, ring.arcColor(1.0), "danger watermark band");

        assertNotEquals(normal, high, "high watermark must be visually distinct");
        assertNotEquals(high, danger, "danger watermark must be visually distinct");
    }

    // ---- 6. 弧几何：12 点起顺时针（像素钉——AWT 正角逆时针，负 extent 才是顺时针）----

    @Test
    void arcFillsClockwiseFromTwelveOclock() {
        ContextUsageRing ring = new ContextUsageRing();
        ring.update(1, 6); // 60° 弧从 12 点扫到 2 点钟：整体远离象限边界（起点圆头帽可跨界左上）

        BufferedImage img = new BufferedImage(18, 18, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 18, 18);
        ring.setSize(18, 18);
        ring.paint(g);
        g.dispose();

        Color arc = ring.arcColor(1.0 / 6);
        int topRight = 0;
        int bottomLeft = 0;
        int bottomRight = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 18; x++) {
                if (colorDistance(img.getRGB(x, y), arc) < 60) {
                    if (x >= 9 && y < 9) {
                        topRight++;
                    } else if (x < 9 && y >= 9) {
                        bottomLeft++;
                    } else if (x >= 9 && y >= 9) {
                        bottomRight++;
                    }
                }
            }
        }
        assertTrue(topRight >= 5, "60° 弧的右半段必须出现在右上象限");
        assertEquals(0, bottomLeft, "弧不得出现在左下象限");
        assertEquals(0, bottomRight, "起点为 12 点：右下象限不得有弧（6 点起逆时针的旧实现恰好落此）");
    }

    private static double colorDistance(int rgb, Color expected) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double dr = r - expected.getRed();
        double dg = g - expected.getGreen();
        double db = b - expected.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
