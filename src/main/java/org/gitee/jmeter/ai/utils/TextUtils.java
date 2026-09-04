package org.gitee.jmeter.ai.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text processing utilities.
 */
public final class TextUtils {

    private static final Pattern THINK_BLOCK = Pattern.compile("<think[\\s\\S]*?</think\\s*>");
    private static final Pattern THINK_UNCLOSED = Pattern.compile("<think[\\s\\S]*$");
    private static final Pattern THINK_SPAN = Pattern.compile("<think[\\s\\S]*?</think\\s*>|<think[\\s\\S]*$");

    private TextUtils() {}

    /**
     * Remove &lt;think&gt;…&lt;/think&gt; blocks that some reasoning models
     * embed in their content, plus any unclosed trailing &lt;think&gt; tag.
     */
    public static String stripThink(String text) {
        if (text == null || text.isEmpty()) return text;
        text = THINK_BLOCK.matcher(text).replaceAll("");
        text = THINK_UNCLOSED.matcher(text).replaceAll("");
        return text.trim();
    }

    /**
     * A piece of a model message split around {@code <think>...</think>} spans:
     * {@code thinking=true} carries the model's chain-of-thought (tag text itself
     * excluded), {@code thinking=false} carries visible reply content.
     */
    public record ThinkSegment(boolean thinking, String text) {}

    /**
     * Split text around &lt;think&gt;…&lt;/think&gt; spans (same tag tolerance as
     * {@link #stripThink}, including a trailing unclosed &lt;think&gt;). Segments
     * come back in order, trimmed and blank-dropped (empty input yields an empty
     * list), so a renderer can style reasoning and visible reply content separately.
     */
    public static List<ThinkSegment> splitThink(String text) {
        List<ThinkSegment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) return segments;
        Matcher matcher = THINK_SPAN.matcher(text);
        int last = 0;
        while (matcher.find()) {
            addSegment(segments, false, text.substring(last, matcher.start()));
            addSegment(segments, true, spanInner(matcher.group()));
            last = matcher.end();
        }
        addSegment(segments, false, text.substring(last));
        return segments;
    }

    private static void addSegment(List<ThinkSegment> segments, boolean thinking, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (!trimmed.isEmpty()) {
            segments.add(new ThinkSegment(thinking, trimmed));
        }
    }

    /**
     * Strip the opening {@code <think...>} tag and, when present, the trailing {@code </think...>} tag.
     * The opening tag's closing {@code '>'} is only honored when no line break precedes it
     * (a real tag never spans lines); a tolerated bracket-less opener (e.g. {@code <think\n…})
     * falls back to stripping the literal {@code <think}, so a {@code '>'} inside the thinking
     * text cannot be mistaken for the tag end and swallow the thinking head.
     */
    private static String spanInner(String span) {
        int open = -1;
        for (int i = "<think".length(); i < span.length(); i++) {
            char c = span.charAt(i);
            if (c == '>') {
                open = i;
                break;
            }
            if (c == '\n' || c == '\r') {
                break;
            }
        }
        String body = open >= 0 ? span.substring(open + 1) : span.substring("<think".length());
        int close = body.lastIndexOf("</think");
        return close >= 0 ? body.substring(0, close) : body;
    }
}
