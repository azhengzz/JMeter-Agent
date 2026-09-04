package org.gitee.jmeter.ai.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextUtils#splitThink(String)} 的分段语义单测：思考段（think 标签内，
 * 标签文本剔除）与正文段（标签外）按序返回、trim、空段丢弃——与
 * {@link TextUtils#stripThink(String)} 的标签容错口径一致（含未闭合尾段）。
 */
class TextUtilsTest {

    @Test
    void splitsWrappedReasoningFromBody() {
        // ProgressCallbackHookAdapter 的结构化 reasoning_content 展示形态
        List<TextUtils.ThinkSegment> segments =
                TextUtils.splitThink("<think>let me check the plan</think>\nThe answer is 42.");
        assertEquals(2, segments.size());
        assertTrue(segments.get(0).thinking());
        assertEquals("let me check the plan", segments.get(0).text());
        assertFalse(segments.get(1).thinking());
        assertEquals("The answer is 42.", segments.get(1).text());
    }

    @Test
    void bodyBeforeAndBetweenMultipleThinkBlocks() {
        List<TextUtils.ThinkSegment> segments =
                TextUtils.splitThink("pre note <think>a</think> mid <think>b</think> post");
        assertEquals(5, segments.size());
        assertFalse(segments.get(0).thinking());
        assertEquals("pre note", segments.get(0).text());
        assertTrue(segments.get(1).thinking());
        assertEquals("a", segments.get(1).text());
        assertFalse(segments.get(2).thinking());
        assertEquals("mid", segments.get(2).text());
        assertTrue(segments.get(3).thinking());
        assertEquals("b", segments.get(3).text());
        assertFalse(segments.get(4).thinking());
        assertEquals("post", segments.get(4).text());
    }

    @Test
    void unclosedTrailingThinkIsThinking() {
        List<TextUtils.ThinkSegment> segments =
                TextUtils.splitThink("answer part<think>still reasoning");
        assertEquals(2, segments.size());
        assertFalse(segments.get(0).thinking());
        assertEquals("answer part", segments.get(0).text());
        assertTrue(segments.get(1).thinking());
        assertEquals("still reasoning", segments.get(1).text());
    }

    @Test
    void thinkOnlyYieldsSingleThinkingSegment() {
        List<TextUtils.ThinkSegment> segments = TextUtils.splitThink("<think>only reasoning</think>");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0).thinking());
        assertEquals("only reasoning", segments.get(0).text());
    }

    @Test
    void noTagsYieldsSingleBodySegment() {
        List<TextUtils.ThinkSegment> segments = TextUtils.splitThink("plain commentary");
        assertEquals(1, segments.size());
        assertFalse(segments.get(0).thinking());
        assertEquals("plain commentary", segments.get(0).text());
    }

    @Test
    void blankSegmentsDroppedAndNullSafe() {
        // think 与 </think> 之间的空白不产生空正文段
        List<TextUtils.ThinkSegment> segments = TextUtils.splitThink("<think>x</think>   ");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0).thinking());

        assertEquals(0, TextUtils.splitThink(null).size());
        assertEquals(0, TextUtils.splitThink("").size());
        assertEquals(0, TextUtils.splitThink("   ").size());
    }

    @Test
    void closingTagWithoutOpeningStaysBody() {
        List<TextUtils.ThinkSegment> segments = TextUtils.splitThink("stray </think> closer");
        assertEquals(1, segments.size());
        assertFalse(segments.get(0).thinking());
        assertEquals("stray </think> closer", segments.get(0).text());
    }

    @Test
    void malformedBracketlessOpenTagKeepsThinkingHead() {
        // 对抗确认修复：开标签无 '>' 收尾（regex 容错形态，如 "<think\n"）时，思考
        // 内容里的首个 '>' 不得被当作标签收尾——其前的思考文本不得被吞掉
        List<TextUtils.ThinkSegment> segments =
                TextUtils.splitThink("<think\nis a > b? still musing");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0).thinking());
        assertEquals("is a > b? still musing", segments.get(0).text());

        segments = TextUtils.splitThink("<think\nis a > b?</think>ans");
        assertEquals(2, segments.size());
        assertTrue(segments.get(0).thinking());
        assertEquals("is a > b?", segments.get(0).text());
        assertFalse(segments.get(1).thinking());
        assertEquals("ans", segments.get(1).text());
    }

    @Test
    void attributeBearingOpenTagStillTerminatesAtItsBracket() {
        // 带属性的规整开标签照常以自身 '>' 收尾（含跨 think 体出现 '>' 的场景）
        List<TextUtils.ThinkSegment> segments =
                TextUtils.splitThink("<think foo=\"1\">a > b</think>ans");
        assertEquals(2, segments.size());
        assertTrue(segments.get(0).thinking());
        assertEquals("a > b", segments.get(0).text());
        assertFalse(segments.get(1).thinking());
        assertEquals("ans", segments.get(1).text());
    }
}
