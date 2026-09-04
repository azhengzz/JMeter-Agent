package org.gitee.jmeter.ai.service.provider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import com.openai.models.ReasoningEffort;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the pure-logic methods of {@link OpenAICompatibleProvider}.
 * <p>
 * Same scope discipline as {@code OpenAiServiceTest}: no real SDK calls,
 * no SDK-field construction — those are covered by the manual end-to-end test
 * in the upgrade plan. Tests cover provider-prefix stripping, reasoning-effort
 * conversion, thinking-style normalization, raw JSON response parsing,
 * tool-choice support detection, and error-message mapping.
 */
@ExtendWith(MockitoExtension.class)
class OpenAICompatibleProviderTest {

    private static MockedStatic<AiConfig> aiConfigMock;
    private static MockedStatic<SystemPrompt> systemPromptMock;

    private OpenAICompatibleProvider provider;

    @BeforeAll
    static void setUpAll() {
        aiConfigMock = mockStatic(AiConfig.class);
        aiConfigMock.when(() -> AiConfig.getProperty("MINIMAX_API_KEY", "")).thenReturn("");
        aiConfigMock.when(() -> AiConfig.getProperty("minimax.api.base.url", "https://api.minimaxi.chat/v1"))
                .thenReturn("https://api.minimaxi.chat/v1");
        aiConfigMock.when(() -> AiConfig.getDefaultModel()).thenReturn("minimax:MiniMax-M2.7");
        aiConfigMock.when(() -> AiConfig.getTemperature()).thenReturn(0.7);
        aiConfigMock.when(() -> AiConfig.getMaxTokens()).thenReturn(4096);
        aiConfigMock.when(() -> AiConfig.getReasoningEffort()).thenReturn("medium");

        systemPromptMock = mockStatic(SystemPrompt.class);
        systemPromptMock.when(SystemPrompt::get).thenReturn("Mocked system prompt");
    }

    @AfterAll
    static void tearDownAll() {
        if (systemPromptMock != null) systemPromptMock.close();
        if (aiConfigMock != null) aiConfigMock.close();
    }

    @BeforeEach
    void setUp() {
        ProviderSpec spec = new ProviderSpec.Builder()
                .name("minimax")
                .displayName("MiniMax")
                .defaultApiBase("https://api.minimaxi.chat/v1")
                .envKey("MINIMAX_API_KEY")
                .build();
        provider = new OpenAICompatibleProvider(spec);
    }

    // ==================== Reflection helpers ====================

    private Object invokeInstance(String methodName, Class<?>[] paramTypes, Object... args) throws Throwable {
        Method m = OpenAICompatibleProvider.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(provider, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args) throws Throwable {
        Method m = OpenAICompatibleProvider.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ==================== stripProviderPrefix ====================

    @Test
    void testStripProviderPrefix_WithPrefix() throws Throwable {
        assertEquals("MiniMax-M2.7",
                invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, "minimax:MiniMax-M2.7"));
    }

    @Test
    void testStripProviderPrefix_NoPrefix() throws Throwable {
        assertEquals("MiniMax-M2.7",
                invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, "MiniMax-M2.7"));
    }

    @Test
    void testStripProviderPrefix_NullInput() throws Throwable {
        assertNull(invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, (Object) null));
    }

    @Test
    void testStripProviderPrefix_OllamaTagStyle_NotStripped() throws Throwable {
        // Ollama tags use ":" inside the model name; a non-provider prefix must not be stripped
        assertEquals("qwen3.5:2b",
                invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, "qwen3.5:2b"));
    }

    @Test
    void testStripProviderPrefix_OllamaProvider_PrefixedTag() throws Throwable {
        // Prefix stripping is per-instance: an ollama provider strips only "ollama:",
        // while a bare Ollama tag (no provider prefix) passes through untouched.
        aiConfigMock.when(() -> AiConfig.getProperty("ollama.api.key", "")).thenReturn("");
        aiConfigMock.when(() -> AiConfig.getProperty("ollama.api.base.url", "http://localhost:11434/v1"))
                .thenReturn("http://localhost:11434/v1");
        ProviderSpec ollamaSpec = new ProviderSpec.Builder()
                .name("ollama")
                .displayName("Ollama")
                .defaultApiBase("http://localhost:11434/v1")
                .envKey("ollama.api.key")
                .build();
        provider = new OpenAICompatibleProvider(ollamaSpec);
        assertEquals("qwen3.5:2b",
                invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, "ollama:qwen3.5:2b"));
        assertEquals("qwen3.5:2b",
                invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, "qwen3.5:2b"));
    }

    // ==================== toReasoningEffort (static) ====================

    @ParameterizedTest
    @MethodSource("provideReasoningEffortMappings")
    void testToReasoningEffort(String input, ReasoningEffort expected) throws Throwable {
        assertEquals(expected,
                invokeStatic("toReasoningEffort", new Class<?>[]{String.class}, input));
    }

    private static Stream<Arguments> provideReasoningEffortMappings() {
        return Stream.of(
                Arguments.of("minimal", ReasoningEffort.MINIMAL),
                Arguments.of("minimum", ReasoningEffort.MINIMAL),
                Arguments.of("low", ReasoningEffort.LOW),
                Arguments.of("medium", ReasoningEffort.MEDIUM),
                Arguments.of("high", ReasoningEffort.HIGH),
                Arguments.of("xhigh", ReasoningEffort.XHIGH),
                Arguments.of("max", ReasoningEffort.MAX),
                Arguments.of("none", null),
                Arguments.of("null", null),
                Arguments.of(null, null),
                Arguments.of("garbage", ReasoningEffort.HIGH),
                Arguments.of("", ReasoningEffort.HIGH)
        );
    }

    // ==================== ProviderSpec thinkingAlwaysOn ====================

    @Test
    void testThinkingAlwaysOn_RegisteredModel_CaseInsensitive() {
        ProviderSpec moonshot = ProviderRegistry.findByName("moonshot");
        assertNotNull(moonshot);
        assertTrue(moonshot.isThinkingAlwaysOn("kimi-k2.7-code"));
        assertTrue(moonshot.isThinkingAlwaysOn("KIMI-K2.7-CODE"));
    }

    @Test
    void testThinkingAlwaysOn_DisableableModel() {
        ProviderSpec moonshot = ProviderRegistry.findByName("moonshot");
        assertNotNull(moonshot);
        assertFalse(moonshot.isThinkingAlwaysOn("kimi-k2.6"));
        assertFalse(moonshot.isThinkingAlwaysOn("kimi-k2.5"));
    }

    @Test
    void testThinkingAlwaysOn_OtherProviders_AlwaysFalse() {
        ProviderSpec deepseek = ProviderRegistry.findByName("deepseek");
        assertNotNull(deepseek);
        assertFalse(deepseek.isThinkingAlwaysOn("deepseek-reasoner"));
        assertFalse(deepseek.isThinkingAlwaysOn(null));
    }

    // ==================== Kimi K3 spec wiring ====================

    @Test
    void testKimiK3_IsThinkingAlwaysOn() {
        ProviderSpec moonshot = ProviderRegistry.findByName("moonshot");
        assertNotNull(moonshot);
        assertTrue(moonshot.isThinkingAlwaysOn("kimi-k3"), "K3 thinking is always on");
        assertTrue(moonshot.isThinkingAlwaysOn("KIMI-K3"), "always-on check is case-insensitive");
    }

    @Test
    void testKimiK3_SupportsThinking() {
        ProviderSpec moonshot = ProviderRegistry.findByName("moonshot");
        assertNotNull(moonshot);
        assertTrue(moonshot.supportsThinking("kimi-k3"));
    }

    @Test
    void testMoonshot_UsesThinkingTypeStyle() {
        // K3 (and all moonshot models) inherit the provider-wide thinking_type style.
        ProviderSpec moonshot = ProviderRegistry.findByName("moonshot");
        assertNotNull(moonshot);
        assertEquals("thinking_type", moonshot.getThinkingStyle());
    }

    // ==================== isToolChoiceUnsupported(Throwable) ====================

    @ParameterizedTest
    @MethodSource("provideToolChoiceUnsupportedThrowables")
    void testIsToolChoiceUnsupported_Throwable(Throwable input, boolean expected) throws Throwable {
        boolean actual = (boolean) invokeInstance(
                "isToolChoiceUnsupported", new Class<?>[]{Throwable.class}, input);
        assertEquals(expected, actual);
    }

    private static Stream<Arguments> provideToolChoiceUnsupportedThrowables() {
        return Stream.of(
                Arguments.of(new RuntimeException("tool_choice is not supported"), true),
                Arguments.of(new RuntimeException("model does not support tool_choice"), true),
                Arguments.of(new RuntimeException("tool_choice should be [\"none\", \"auto\"]"), true),
                Arguments.of(new RuntimeException("some unrelated error"), false),
                Arguments.of(new RuntimeException(""), false),
                Arguments.of(null, false)
        );
    }

    // ==================== isToolChoiceUnsupported(String) ====================

    @ParameterizedTest
    @MethodSource("provideToolChoiceUnsupportedStrings")
    void testIsToolChoiceUnsupported_String(String input, boolean expected) throws Throwable {
        boolean actual = (boolean) invokeInstance(
                "isToolChoiceUnsupported", new Class<?>[]{String.class}, input);
        assertEquals(expected, actual);
    }

    private static Stream<Arguments> provideToolChoiceUnsupportedStrings() {
        return Stream.of(
                Arguments.of("tool_choice is not supported", true),
                Arguments.of("model does not support tool_choice", true),
                Arguments.of("tool_choice should be [\"none\", \"auto\"]", true),
                Arguments.of("TOOL_CHOICE uppercase should match", true),
                Arguments.of("some unrelated error", false),
                Arguments.of("", false),
                Arguments.of(null, false)
        );
    }

    // ==================== isCausedByInterrupt ====================

    @Test
    void testIsCausedByInterrupt_InterruptedIOException() throws Throwable {
        Throwable e = new java.io.InterruptedIOException("timeout");
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, e);
        assertTrue(actual);
    }

    @Test
    void testIsCausedByInterrupt_InterruptedException() throws Throwable {
        Throwable e = new InterruptedException("cancelled");
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, e);
        assertTrue(actual);
    }

    @Test
    void testIsCausedByInterrupt_NestedCause() throws Throwable {
        Throwable root = new InterruptedException("deep");
        Throwable e = new RuntimeException("wrapper", root);
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, e);
        assertTrue(actual);
    }

    @Test
    void testIsCausedByInterrupt_NotInterrupt() throws Throwable {
        Throwable e = new RuntimeException("regular error");
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, e);
        assertFalse(actual);
    }

    @Test
    void testIsCausedByInterrupt_Null() throws Throwable {
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, (Object) null);
        assertFalse(actual);
    }

    // ==================== MiniMax thinking style (minimax_thinking) ====================

    @Test
    void testMinimaxThinkingExtraBody_M3_On() {
        // thinking on/off toggle is thinking.type; M3 "on" must be adaptive (enabled → HTTP 400).
        // reasoning_split:true routes reasoning to reasoning_content (output format).
        assertEquals(Map.of(
                        "thinking", Map.of("type", "adaptive"),
                        "reasoning_split", true),
                OpenAICompatibleProvider.buildMinimaxThinkingExtraBody("MiniMax-M3", true));
    }

    @Test
    void testMinimaxThinkingExtraBody_M3_On_WithProviderPrefix() {
        assertEquals(Map.of(
                        "thinking", Map.of("type", "adaptive"),
                        "reasoning_split", true),
                OpenAICompatibleProvider.buildMinimaxThinkingExtraBody("minimax:MiniMax-M3-Pro", true));
    }

    @Test
    void testMinimaxThinkingExtraBody_M3_Off() {
        Map<String, Object> body = OpenAICompatibleProvider.buildMinimaxThinkingExtraBody("MiniMax-M3", false);
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), body);
        assertFalse(body.containsKey("reasoning_split"),
                "reasoning_split must not be sent when thinking is off");
    }

    @Test
    void testMinimaxThinkingExtraBody_M2x_On() {
        assertEquals(Map.of(
                        "thinking", Map.of("type", "enabled"),
                        "reasoning_split", true),
                OpenAICompatibleProvider.buildMinimaxThinkingExtraBody("MiniMax-M2.7", true));
    }

    @Test
    void testIsM3Family() {
        assertTrue(OpenAICompatibleProvider.isM3Family("MiniMax-M3"));
        assertTrue(OpenAICompatibleProvider.isM3Family("minimax:MiniMax-M3-Pro"));
        assertTrue(OpenAICompatibleProvider.isM3Family("minimax-m3"));
        // Substring match: third-party aggregators may rename M3 (no "minimax-m3" prefix).
        assertTrue(OpenAICompatibleProvider.isM3Family("acme-minimax-m3-pro"));
        assertFalse(OpenAICompatibleProvider.isM3Family("MiniMax-M2.7"));
        assertFalse(OpenAICompatibleProvider.isM3Family("abab6.5"));
        assertFalse(OpenAICompatibleProvider.isM3Family(null));
    }

    @Test
    void testThinkingStyleMap_HasMinimaxThinking_RemovedReasoningSplit() throws Exception {
        java.lang.reflect.Field f = OpenAICompatibleProvider.class.getDeclaredField("THINKING_STYLE_MAP");
        f.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) f.get(null);
        assertTrue(map.containsKey("minimax_thinking"), "minimax_thinking style must be registered");
        assertFalse(map.containsKey("reasoning_split"),
                "semantically-wrong reasoning_split style must be removed");
        assertTrue(map.containsKey("thinking_type"));
        assertTrue(map.containsKey("enable_thinking"));
    }

    @Test
    void testMiniMax_UsesMinimaxThinkingStyle() {
        ProviderSpec minimax = ProviderRegistry.findByName("minimax");
        assertNotNull(minimax);
        assertEquals("minimax_thinking", minimax.getThinkingStyle());
    }
}
