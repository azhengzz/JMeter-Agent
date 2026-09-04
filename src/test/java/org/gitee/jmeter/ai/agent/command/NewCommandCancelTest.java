package org.gitee.jmeter.ai.agent.command;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /new} clears the session, and to do that safely it signals any in-flight
 * run to stop. When nothing is running, that signal must not hit the command's own
 * turn — the user still needs to see the confirmation.
 */
class NewCommandCancelTest {

    private static class QuietAiService implements AiService {
        @Override public String getName() { return "quiet"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            return LLMResponse.text("ok");
        }
    }

    /**
     * A service whose tool-call entry blocks until released, so a run stays "active"
     * long enough for a concurrent {@code /new} to cancel it mid-flight. The block is
     * interruptible so signalCancel's thread interrupt unblocks it for cleanup.
     */
    private static class BlockingAiService implements AiService {
        private final CountDownLatch release;
        BlockingAiService(CountDownLatch release) { this.release = release; }
        @Override public String getName() { return "blocking"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            try {
                release.await();
                return LLMResponse.text("done");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LLMResponse.error("Interrupted");
            }
        }
    }

    private static AgentLoop newLoop() throws Exception {
        return newLoop(new QuietAiService());
    }

    private static AgentLoop newLoop(AiService ai) throws Exception {
        Path workspace = Files.createTempDirectory("new-cmd-test");
        ToolRegistry registry = new ToolRegistry();
        MemoryStore memory = new MemoryStore(workspace);
        SessionManager sessions = new SessionManager(workspace);
        ContextBuilder context = new ContextBuilder(memory, workspace);
        return new AgentLoop(registry, memory,
            new MemoryConsolidator(memory, ai, sessions, context, registry),
            context, sessions, ai);
    }

    /** 同 {@link #newLoop(AiService)}，但注入自定义 consolidator（park 点钉子）。 */
    private static AgentLoop newLoop(AiService ai, MemoryConsolidator consolidator) throws Exception {
        Path workspace = Files.createTempDirectory("new-cmd-test");
        ToolRegistry registry = new ToolRegistry();
        MemoryStore memory = new MemoryStore(workspace);
        SessionManager sessions = new SessionManager(workspace);
        ContextBuilder context = new ContextBuilder(memory, workspace);
        return new AgentLoop(registry, memory, consolidator, context, sessions, ai);
    }

    @Test
    void newOnAnIdleSessionReturnsItsConfirmation() throws Exception {
        AgentLoop loop = newLoop();
        try {
            AgentResponse response = loop.processMessage("/new", "chat:main")
                .get(20, TimeUnit.SECONDS);

            assertNotNull(response, "/new must produce a response");
            assertTrue(response.isSuccess(),
                "/new must not fail: " + response.getErrorMessage());
            assertTrue(response.getContent() != null && response.getContent().contains("New session"),
                "the user must see the confirmation, not a cancelled turn: " + response.getContent());
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void newStillClearsTheSessionHistory() throws Exception {
        AgentLoop loop = newLoop();
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            session.addMessage(Message.user("old question"));
            session.addMessage(Message.assistant("old answer", null));
            assertEquals(2, session.getUnconsolidatedMessages().size());

            loop.processMessage("/new", "chat:main").get(20, TimeUnit.SECONDS);

            assertTrue(loop.getSessionManager().getOrCreate("chat:main")
                    .getUnconsolidatedMessages().isEmpty(),
                "/new must still clear the conversation");
        } finally {
            loop.shutdown();
        }
    }

    /**
     * {@code /new} typed while a run is in flight must cancel that run's future, and
     * the {@code /new} command itself must still return its confirmation. The cancelled
     * run surfaces to the UI as a benign {@code TURN_CANCELLED} event (stop-style
     * line), not as an error.
     */
    @Test
    void newDuringAnActiveRunCancelsTheRun() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AgentLoop loop = newLoop(new BlockingAiService(release));
        try {
            // Start a run that blocks inside the LLM call so it stays active.
            CompletableFuture<AgentResponse> run =
                loop.processMessage("analyze the plan", "chat:main");

            // Wait until the run is actually in flight (registered as the active task).
            assertTrue(await(() -> loop.hasActiveRun("chat:main")),
                "run should become active");

            // Dispatch /new mid-run, exactly as the EDT does via injectMessage.
            CompletableFuture<AgentResponse> newCmd =
                loop.processMessage("/new", "chat:main");
            AgentResponse result = newCmd.get(20, TimeUnit.SECONDS);

            assertTrue(result.isSuccess(),
                "/new must still succeed: " + result.getErrorMessage());
            assertNotNull(result.getContent());
            assertTrue(result.getContent().contains("New session"),
                "the user must see the confirmation: " + result.getContent());

            // The in-flight run's future was cancelled — the CF throws
            // CancellationException directly to any awaiter per the Future contract
            // (the panel learns of the cancellation via TURN_CANCELLED instead).
            assertThrows(CancellationException.class,
                () -> run.get(5, TimeUnit.SECONDS),
                "the active run's future must be cancelled, not completed normally");
        } finally {
            release.countDown();
            loop.shutdown();
        }
    }

    /**
     * 非 loop 线程的重置（EDT 忙期内联 /new、「+」按钮、关闭整合清空同路径）也必须
     * 无条件翻转会话代数——重置代数栅栏（republishLeftovers 的旧代数丢弃）依赖它。
     * 曾把翻转挪进 self!=null 守卫：EDT/ipc-worker 路径 currentTurn 为空、代数不翻，
     * 比对恒等通过，旧会话残留被复活进新会话（对抗审查 2026-09-02）。
     * 观测手段：markConversationReset 返回翻转到的代数，无需竞态即可确定性断言。
     */
    @Test
    void resetFromNonLoopThread_flipsEpoch() throws Exception {
        AgentLoop loop = newLoop();
        try {
            loop.resetConversation("chat:main");  // 测试线程 = 非 loop 线程路径
            assertEquals(2L, loop.markConversationReset("chat:main"),
                "resetConversation off the loop thread must still flip the epoch "
                    + "(second flip should observe epoch 1, not 0)");
        } finally {
            loop.shutdown();
        }
    }

    /**
     * /new 命令回合自我豁免取消（3.5 步真摘表）后，紧随的 Stop 不得再找到该回合：
     * 其 future 已不是会话的可取消对象——否则确认回执变成 CancellationException，
     * 用户点 Stop 的瞬间把 /new 确认吞掉。用 latch 钉住 consolidator 归档，把 /new
     * 回合停在 resetConversation 尾部（3.5 已执行、命令尚未返回）的确定性窗口。
     */
    @Test
    void stopAfterSelfExemptedNewTurn_doesNotCancelConfirmation() throws Exception {
        CountDownLatch archiveEntered = new CountDownLatch(1);
        CountDownLatch releaseArchive = new CountDownLatch(1);
        MemoryConsolidator consolidator = Mockito.mock(MemoryConsolidator.class);
        Mockito.doAnswer(inv -> {
            archiveEntered.countDown();
            releaseArchive.await();
            return null;
        }).when(consolidator).archiveMessagesAsync(Mockito.anyList());

        AgentLoop loop = newLoop(new QuietAiService(), consolidator);
        try {
            // 预置未整合消息：重置快照非空 → resetConversation 必然调归档（park 点可达）
            var session = loop.getSessionManager().getOrCreate("chat:main");
            session.addMessage(Message.user("old question"));
            session.addMessage(Message.assistant("old answer", null));

            CompletableFuture<AgentResponse> newCmd = loop.processMessage("/new", "chat:main");

            assertTrue(archiveEntered.await(10, TimeUnit.SECONDS),
                "/new turn should reach the archive step (parked inside resetConversation, "
                    + "after the self-exempt removal)");

            boolean cancelled = loop.signalCancel("chat:main");
            assertFalse(cancelled,
                "the self-exempted /new turn must be gone from the registry — "
                    + "a late Stop must find nothing to cancel");
            assertFalse(newCmd.isCancelled(),
                "the confirmation future must not be cancelled by the late Stop");

            releaseArchive.countDown();
            AgentResponse result = newCmd.get(20, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(),
                "confirmation must survive the late Stop: " + result.getErrorMessage());
            assertTrue(result.getContent() != null && result.getContent().contains("New session"),
                "the user must still see the confirmation: " + result.getContent());
        } finally {
            releaseArchive.countDown();
            loop.shutdown();
        }
    }

    /** Spin until condition is true, max ~5s. */
    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
