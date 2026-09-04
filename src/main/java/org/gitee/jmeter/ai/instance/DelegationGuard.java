package org.gitee.jmeter.ai.instance;

/**
 * 委派回合标记(深度 1 硬阻断):本实例经 IPC {@code /agent} 收到 {@code delegated=true}
 * 的请求时,{@code AgentRunner} 在该回合的 run() 内(主链路=agent-loop 执行器线程,子代理=
 * 池化线程)置位,回合结束清除。{@code DelegateToInstanceTool} 执行前检查——被委派任务内
 * 禁止再次委派。
 *
 * <p><b>防护分层</b>:委派链失控有两类形态,分别由两层独立防御拦住——
 * 本守卫(深度 1 硬阻断)是主防线,封委派链经<b>空闲</b>实例不断延长的无界链
 * (A→B→C→D…每跳合法、每跳阻塞满 {@code jmeter.ai.ipc.agent.timeout.ms} 且深度无界);
 * 另由接收侧 delegated-busy 快速失败兜底——目标实例此刻已有未完成回合占用其单槽
 * ({@code AgentLoop.hasActiveRun} 为真,注入路由槽存在)时新委派直接拒收,防同一实例
 * 并发执行多个回合,与委派链形状无关。
 *
 * <p>线程模型:置位必须发生在执行回合的线程<b>内部</b>——{@code AgentRunner.run} 同步执行,
 * 整回合(LLM 循环 + 串行工具调用,工具内联无线程跳转)内联跑在调用方线程上(主链路=agent-loop
 * 单线程执行器线程,子代理=池化线程),begin/end 在同一线程置/清(与 {@code AgentRunContext}
 * 同款推理,故同位置置/清)。
 * 并发可见性双通道(concurrency-safe-tool-batching):非安全工具(含本守卫的主要消费者
 * {@code delegate_to_instance})永不进入并行批,以单例批内联在 run 线程上执行,守卫天然可见;
 * 安全工具并行时经 {@code ToolRegistry.executeAsyncWithEvent} 派发,守卫随
 * {@code AgentRunContext} 同点 capture/set/clear 搬运(contextvars 模式的 Java 直译)。
 */
public final class DelegationGuard {
    private static final ThreadLocal<Boolean> DELEGATED = new ThreadLocal<>();

    private DelegationGuard() {
    }

    /** 在被委派回合的执行线程上调用(回合开始)。 */
    public static void begin() {
        DELEGATED.set(Boolean.TRUE);
    }

    /** 回合结束调用;未置位时为 no-op。 */
    public static void end() {
        DELEGATED.remove();
    }

    /** 当前线程是否正执行一个被委派的回合。 */
    public static boolean isActive() {
        return Boolean.TRUE.equals(DELEGATED.get());
    }
}
