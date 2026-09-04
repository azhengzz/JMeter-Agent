# Proposal: refactor-agent-loop-turn-centric

## Why

AgentLoop（1084 行，@679cce0）与 AgentRunner（762 行）承载的领域复杂度是真实的（回合中途注入、四通道取消、会话重置栅栏），但**表征方式**出了问题：一个"回合"（turn）的状态被打散在 **AgentLoop 内 7 张按 sessionKey 索引的并行 Map（6 张 per-turn：activeTasks/abortFlags/completionLatches/activeTurnTokens/drainTimedOut/activeTurnHandles + 1 张会话级 sessionEpochs，另有 InjectionManager.injectionQueues 共 8 张）+ 3 个 ThreadLocal + 2 个 volatile**（AgentLoop.runningTurnSession 与 AgentRunner.runningThread）里，AgentLoop 里近半数的防御性注释描述的都是这些并行容器之间的一致性约束（"按值条件删除，否则会摘掉新回合的表项"、"先 cancel future 再摘槽"……）。同时 AgentRunner 是**假异步**：`run()` 用 `supplyAsync` 把循环体丢到 commonPool 池化载体，唯一的主链路调用点立刻 `.join()`——实际效果只是换了线程，却引入了"池化载体残留中断"这一整类 bug（入口/出口两处 `Thread.interrupted()` 清扫防御、`runningThread` TOCTOU 窗口、`isAborted`/`isAbortedFlag` 双轨判定皆源于此；InjectionManager.drainBlocking 的 interrupt 恢复是另一机制，不计入）。现有实现能工作且有一整套对抗性契约测试锁定，但每次新增回合相关特性（近期：IPC 回合显示、子代理回合汇流——679cce0 新增的 signalCancel「摘表后重读认领句柄」补丁正是此类成本的第 N 次支付）都要在 10 个并发容器间重新推理一遍时序——边际成本过高，是收敛的时机。

> **Phase 0 已落地（2026-08，先导变更 `unify-turn-event-display`）**：回合已获得进程唯一身份（`TurnHandle`）与终态去重位（`terminalEmitted`），取消原因经事件载荷（`TurnEvent.cause`）交付，全部可观察呈现经 `dispatchTurnEvent` 事件流（多订阅者 `TurnSubscriber`，工厂级订阅表跨模型切换存活）；旧单槽 TurnPresenter/AgentSwingWorker 通道已删。本变更（Turn 聚合）在其基础上继续收敛执行层状态——design D1 字段表已补对应行与两条继承不变式。

## What Changes

- **AgentRunner 同步化（先行阶段）**：`run(spec)` 改为同步方法（跑在调用方线程：主链路 = agent-loop 执行器线程，子代理 = subagent 池线程），删除 `supplyAsync(commonPool)` + `.join()` 的假异步包装。本阶段**原样保留** `AgentRunner.interrupt()`/`runningThread` 与 `runningTurnSession` 守卫——同步化后其记录的目标线程自动从 commonPool 载体变为 agent-loop 专用线程/subagent 池线程（恰是 LLM 调用与 drainBlocking park 所在线程），中断命中语义等价而无需 Turn 存在。这是四项变更中唯一有客观正确性收益者（消除"池化载体残留中断→空回复"真实故障类，见 AgentRunner 入口清扫注释），先行落地还使 Turn 聚合的 `runnerThread`（pickup 时写入）语义一步到位、无过渡态。
- **runAgentLoop 巨方法分解**：引入每回合 `LoopState`（currentMessages / iteration / injectionCycles / hadInjections / toolsUsed 等），抽出迭代步骤函数；**5 处同构**的注入检查点仪式（`injX = tryDrain…; injectionCycles = …; hadInjections |= …; if (shouldContinue) {…continue;}`）收敛为一个返回决策的辅助方法；第 6 处（maxIterations 后的收尾抽干 drain6）结构性异构——绕过 MAX_INJECTION_CYCLES 上限、直接 append、永不 continue——**保留手写**（统一进带上限的辅助方法会改变"已用满 5 周期后打到 maxIterations"场景的行为，且该路径无测试锁定）。**检查点位置不动**（语义敏感，仅去重机制）；移除 runAgentLoop 内残留的匿名 `{}` 块与历史注释错位；先补特征测试组锁定 hook 时序（现状 afterIteration/onIntermediateResponse/finalizeContent 在测试目录零覆盖，纯绿灯不充分）。
- **Turn 一等公民化（在简化后的线程模型上做）**：新增 `Turn` 聚合对象（future / abortFlag / latch / epoch / 注入队列句柄 / turnToken / 回调 / delegated 标记 / 归属线程 / ownResetEpoch / handle）与 `TurnRegistry`（sessionKey → Turn 的单一路由槽 Map）。`AgentLoop` 现有的 `activeTasks`、`abortFlags`、`completionLatches`、`activeTurnTokens`、`drainTimedOut`、`activeTurnHandles` **6 张 Map** 与 `turnOwnedByThisThread`、`currentTurnSelf`（含 `TurnSelfRef` record）、`ownResetEpoch` 3 个 ThreadLocal、`runningTurnSession` volatile 全部收敛为 Turn 的字段或随 Turn 身份自然消解；跨 Map 的按值条件删除、身份豁免比对等补丁式逻辑退化为普通的单对象操作。中断路径随聚合切换：主链路改 `turn.runnerThread.interrupt()`（pickup 写、**teardown 置 null**——残留引用会造成跨会话误中断），子代理路径 `SubagentManager` 的两处 `handle.runner.interrupt()`（cancelBySession/shutdown）改为 `RunningSubagent` 自持 `volatile Thread runThread`（与 Turn.runnerThread 同构模式，子代理无 Turn 对象、registry 覆盖不到），随后删除 `AgentRunner.interrupt()`/`runningThread`/`runningTurnSession`。**非 BREAKING**：`processMessage`/`signalCancel`/`cancelActiveTask` 等公共 API 签名不变。
- **不做的事**（明确出界）：不改 InjectionManager 的队列所有权/路由槽模型（设计良好且有独立测试；容器合并作为可选末阶段，须用「closed 标志双生命周期」方案，见 design D2）；不改消息路由语义；不改全局单线程执行器串行所有会话的既有选择（承重理由须记录为不变式：改树工具默认非并发安全且内联跑回合线程、`JMeterElementManager` 直取 GuiPackage 无锁——跨回合并行需要一层尚不存在的树写锁）；不引入每会话执行器或 actor/event 模型；不动 AgentResponse/AgentRunResult 双响应模型与 `processMessage` 4 个重载（公共面保持稳定，收敛留待后续独立变更）。

**阶段顺序（相对早期草案的重排）**：同步化 → 巨方法分解 → Turn 聚合 →（可选）路由槽合并。理由：同步化不依赖 Turn 即拿到全部客观收益，并使聚合阶段的 runnerThread 语义在干净线程模型上一步到位（早期草案「先聚合后同步化」会产生 pickup 线程阻塞在 join、LLM 跑在 commonPool 载体的双线程过渡态，中断会打错线程）；分解独立于两者、先做可降低聚合重写的审读成本；聚合阶段（动约 36 个并发交互点、无已知活性 bug 支撑、重写 9+ 对抗测试类锁定的最敏感区）押后，可借同步化落地后实测「并行容器是否仍难推理」重新校准投入。

## Capabilities

纯重构：所有可观察行为契约（注入 ack 不悬挂、取消幂等与隔离、重置代数栅栏、re-publish 语义、子代理回合汇流、回合事件流通知时序）保持不变，由现有测试套件（`AgentLoopAdversarialTest`、`AgentLoopRepublishTest`、`AgentLoopSignalCancelScopingTest`、`AgentLoopPerTurnCallbackTest`、`AgentLoopDelegationGuardTest`、`AgentLoopTurnEventTest` + `EventParityTest`（原 `AgentLoopPresenterTest` 场景的 1:1 替代——随 `unify-turn-event-display` P2 删除）、`NewCommandCancelTest`、`InjectionManagerTest`、subagent 系列等）全程绿灯锁定。无 spec 级行为变更，`skip_specs: true`。

### New Capabilities

（无）

### Modified Capabilities

（无）

## Impact

- **代码**：`org.gitee.jmeter.ai.agent.AgentLoop`（状态收敛 + 防御性注释退役带来缩减，但近半数行是须迁移的契约注释，不承诺具体幅度）、`agent.run.AgentRunner`（同步化 + 分解）、新增 `agent.turn.Turn` / `agent.turn.TurnRegistry`（或置于 `agent.run` 下，design 拍板）；`SubagentManager`（去 `.join()`、中断重定向到 `RunningSubagent.runThread`）、`AgentLoopFactory`（构造接线，签名不变）。
- **测试**：主契约测试**不改断言**即应全绿。直接调 `run()` 的生产调用方之外，测试目录唯一直接 `run().join()` 的是 `SubagentExecutorDeadlockTest`（需机械适配 + 其 javadoc 记载的「同步化防死锁」理由随之失效须改写）；`AgentRunnerToolCallingRequirementTest` 经 `processMessage(...).get()` 驱动，零适配。`InjectionManagerTest` 在 Phase 1-3 不动；若做可选末阶段（路由槽合并），直接使用 InjectionManager 公共 API 的测试（`InjectionManagerTest`、`AgentLoopAdversarialTest`、`SubagentIsolationTest` 等）需按 design D2 方案迁移。
- **调用方**：`AiChatPanel`、`IpcServer`、CLI 经由不变的公共 API 访问，零改动。
- **风险与门禁**：并发契约靠注释 + 测试双重固化，重构中注释随代码迁移到新归属处；每阶段独立编译 + `mvn clean test` 全绿后才进入下一阶段（规避 mvn 增量编译 stale class，见项目记忆）。
