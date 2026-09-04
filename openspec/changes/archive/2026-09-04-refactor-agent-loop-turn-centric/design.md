# Design: refactor-agent-loop-turn-centric

## Context

见 [proposal.md](proposal.md) 的 Why。现状代码事实（约束设计的输入）：

- `AgentLoop`（1084 行，@679cce0）持有 7 张按 sessionKey 索引的 ConcurrentHashMap（6 张 per-turn：`activeTasks`、`abortFlags`、`completionLatches`、`activeTurnTokens`、`drainTimedOut`、`activeTurnHandles` + 1 张会话级 `sessionEpochs`）+ 3 个 ThreadLocal（`turnOwnedByThisThread`、`currentTurnSelf`/`TurnSelfRef`、`ownResetEpoch`）+ volatile `runningTurnSession`（另有 `AgentRunner.runningThread`，共 2 个 volatile）；第 8 张是 `InjectionManager.injectionQueues`（路由槽）。
- `AgentRunner.run()` 用单参 `supplyAsync`（commonPool 池化载体）包装同步工作；主链路唯一调用点 `AgentLoop.startTurn` 立即 `.join()`，`SubagentManager` 也立即 `.join()`。实际执行线程 = commonPool 载体，`agent-loop` 专用线程只充当串行器。
- 每个 spawn 新建一个 `AgentRunner` 实例——代码注释声称的的理由是 `runningThread` 单字段（"共享会破坏 Stop 定位"）；实况勘误见 D3（`ContextWindowManager` 并无 per-instance 可变状态，保留仅因改动最小）。
- 并发契约已由 `AgentLoopAdversarialTest` 等 9+ 个测试类锁定（真实构造 + 脚本化 AiService，非 mock 循环）。

## Goals / Non-Goals

**Goals:**

- 回合生命周期状态单点化：一个 `Turn` 对象承载全部 per-turn 状态，跨容器一致性约束消失。
- 删除假异步层：`AgentRunner` 同步执行，中断定位按 Turn 归属线程精确命中。
- `runAgentLoop` 从 300 行巨方法变为可读的步骤函数组合，注入检查点仪式去重（位置不动）。
- 每阶段独立编译 + 测试全绿，单 commit 可回滚。

**Non-Goals:**

- 不改任何可观察行为：注入 ack 契约、取消语义与自我豁免、重置代数栅栏、re-publish 规则、subagent 汇流/公告、回合事件流（`dispatchTurnEvent`→`TurnSubscriber`）通知时序全部保持。
- 不合并 `AgentResponse`/`AgentRunResult` 双响应模型、不收敛 `processMessage` 重载（公共面稳定，留待独立变更）。
- 不改 InjectionManager 的可观察语义（注入 ack 契约、槽位时序约定）；Phase 4 容器合并采用「closed 标志双生命周期」方案解决条目摘除时机的三生命周期冲突（见 D2），非简单替换容器。
- 不引入每会话执行器/actor 模型/virtual threads（JDK 17）。

## Decisions

### D1: `Turn` 聚合对象 + `TurnRegistry`（新包 `agent.turn`）

**Turn 字段**（来源标注）：

| 字段 | 收敛自 | 说明 |
|---|---|---|
| `sessionKey`, `callback`, `delegated` | startTurn 局部变量 | 提交时定 |
| `queue: LinkedBlockingQueue<InjectionItem>` | `injectionManager.register()` 返回句柄 | 归回合私有，现状已如此，仅改持有者 |
| `abortFlag: AtomicBoolean` | `abortFlags` map | |
| `future: CompletableFuture<AgentResponse>` | `activeTasks` map | |
| `completionLatch: CountDownLatch` | `completionLatches` map | `waitForCancellation` 等收尾 |
| `epoch: long`（pickup 时写入） | 局部变量 `turnEpoch` | 写入时机保持 pickup 时（非提交时），语义不变 |
| `ownResetEpoch: volatile long`（UNSET=-1） | ThreadLocal `ownResetEpoch` | `resetConversation` 在回合线程内调用时写入本字段，收尾分类读取——隐藏线程通道变显式字段 |
| `runnerThread: volatile Thread`（pickup 时写入，**teardown 时置 null**） | `agentRunner.runningThread` + volatile `runningTurnSession` | 中断精确目标；两字段合一。「pickup 时写入」的正确性**以同步化先行（新 Phase 1）为前提**——同步化后 pickup 线程即执行线程；若假异步仍在，pickup 线程阻塞在 `.join()` 而 LLM 跑在 commonPool 载体，写入会记错线程（这正是阶段重排的动因）。teardown 置 null 是必要不变式：Turn 条目在 latch 释放点才从 registry 摘除，此前的残留线程引用会让迟到中断打向已复用/已死的线程 |
| `drainTimedOut: boolean`（仅 loop 线程） | `drainTimedOut` map | 语义本就 per-turn（"本回合不再阻塞"），现状按 session 存储靠单 executor 串行才碰巧正确 |
| Turn 对象自身 | `activeTurnTokens` map + `Object turnToken` | 身份即令牌；`currentTurnToken()` 直接返回 Turn 的 `SubagentManager.TurnToken` 视图 |
| `handle: TurnHandle`（含 `terminalEmitted` 终态去重原子位） | `activeTurnHandles` map + startTurn 局部 handle | Phase 0（`unify-turn-event-display`）引入的回合事件流身份：Turn 聚合后内嵌为字段，`activeTurnHandles` map 随之消解（`activeTurn(sessionKey)` 查询改经 TurnRegistry）；取消原因不在句柄上——经 `TurnEvent.cause` 载荷交付 |

**TurnRegistry**：`ConcurrentHashMap<String, Turn>`，操作 = `register`(put 替换，最新回合赢)、`find`、`removeIfCurrent(sessionKey, turn)`(身份条件摘除)、`cancelRouting`(无条件摘)、`hasActiveRun`。Phase 3 起与 `InjectionManager` 并存（各自一张 map，槽位时序约定与现状相同）；Phase 4 合并为单 map（closed 标志方案，见 D2）。

**继承不变式（来自 Phase 0 回合事件流，Turn 聚合不得破坏）**：
1. **终态先于 finally-republish**：终态事件必须在 try/catch 内、`future.complete` 之前、finally 的 re-publish 之前发射——垂死回合孤儿的 STARTED 不得插进本回合终态之前（`tryClaimTerminal` 去重恰好一次）。
2. **Turn 聚合不得绕过 `dispatchTurnEvent`**：一切可观察的终态/进度/注入/命令结果通知都经事件流发射（7 发射点清单见 `AgentLoop` 注释），状态收敛进 Turn 字段时不得吞掉或重排任何发射点。

**ThreadLocal 收敛**：三个变一个——`currentTurn: ThreadLocal<Turn>`（pickup 时 set、finally remove）。自我豁免从"会话相等 + TurnSelfRef 身份"两层判定简化为单一回合身份比较 `target == currentTurn.get()`（单 executor 上同线程至多一个在跑回合，两判定等价，身份版更严）。**约束**：`ownResetEpoch` 字段化后，`resetConversation` 在回合线程内写入时必须经 `currentTurn.get()` 定位目标回合——不可用 `registry.find(sessionKey)`（同 key 更新回合可能已注册，find 会把代数写进错误回合、击穿重置代数栅栏）。

*备选（否决）：继续并行 Map 但加封装访问器——不消除一致性约束，只是把散乱藏进 getter；event-sourced/actor 模型——对 JDK 17 Swing 插件过重。*

### D2: 路由槽合并——closed 标志双生命周期方案（可选 Phase 4）

**为什么不能把三组状态的摘除时机压成一个**：现状同一 sessionKey 下三组状态有三个刻意错开的死亡时机——①注入槽（`injectionQueues` 条目）死于 cancelRouting（取消时无条件摘，晚于 `future.cancel(true)`）或 cleanup（自然收尾、身份条件摘）；②future/handle/abortFlag 等 loop 侧条目死于 whenComplete 回调（future 完成即摘）；③completionLatch 死于任务体外层 finally（latch.countDown 后才摘，最晚）。若合并后任取一组的摘除时机当唯一时机，必然回归另一组的契约（latch 等待方读不到条目、或注入槽被提前摘除致 ack 走"无活跃回合"分支）。

**方案**：TurnRegistry 单 map 中条目存活到最晚死亡点（latch 释放点）；更早的"已终结"用 Turn 上的 `volatile boolean closed` 表达，且**在 `computeIfPresent` 内写入**（与 `offer` 的 computeIfPresent 同一 CHM bin 锁，天然原子，与现状 `InjectionManager.offer` 的原子性边界同构）：cancelRouting/cleanup/whenComplete 的原摘除动作改为置 closed（持 bin 锁），latch 释放点才真正 remove 条目。`hasActiveRun` = 条目存在 && !closed；`offer` 在 computeIfPresent 里发现 closed 即走现状"槽已摘"分支（ack 拒绝语义不变）。`offerInjection` 的 `turnTeardownLock`（子代理公告 vs 收尾互斥）与身份比对逻辑逐字迁移，仅容器从两张 map 变一张。

**测试迁移范围**：不止 `InjectionManagerTest`——`AgentLoopAdversarialTest`（约 :394-438）与 `SubagentIsolationTest`（约 :170-236）也直接构造/使用 InjectionManager 公共 API，须随容器变化同步迁移（断言语义不变，载体改变）。

### D3: `AgentRunner` 同步化（先行阶段；中断追踪原样保留）

- `run(spec)` → 同步方法（跑在调用方线程）。主链路在 `startTurn` 的 execute lambda 里直接调用（agent-loop 专用线程）；`SubagentManager` 去掉 `.join()` 直接调用（本就跑在 subagent 池线程，注释"NO runExecutor 防自饿死"的约束自动满足且更直白）。
- **本阶段原样保留** `AgentRunner.interrupt()`/`runningThread` 与 `AgentLoop.runningTurnSession` 守卫：同步化后 `runningThread` 记录的目标线程自动从 commonPool 载体变为 agent-loop 专用线程/subagent 池线程（恰是 LLM 调用与 drainBlocking park 所在线程），三处调用方（signalCancel :834、`SubagentManager.cancelBySession` :522、`shutdown` :549）命中语义等价——无需 Turn 存在即全量兑现同步化收益。中断追踪的聚合与删减留给 Turn 聚合阶段（D1）。
- `Thread.interrupted()` 清扫**保留入口/出口各一处**：专用 agent-loop 线程上中断可能在 abort 退出后残留给同线程下一回合（入口清扫防这个）；subagent 路径跑在池化线程上（出口清扫防池污染）。**顺序不变式（须随代码迁移）**：出口清扫必须在持久化守卫（`lockLongTermMemory` 的 abort 谓词）读取中断位**之后**——清扫在前会把"是否被中断"洗成 false，被中止的整合误判为正常完成而落盘。注释改写为准确的新理由；注意 `isAbortedFlag` 现有 javadoc 在 HEAD 上已失实，改写以重新核验为准绳、勿照抄旧注释。
- `isAborted`（flag+中断）/`isAbortedFlag`（仅 flag）双轨**可合一**：同步化后前置/后置整合都在可中断的执行线程上，统一用 `isAborted` 只会更保守；`MemoryConsolidator` 的 abort 感知锁契约（"被中止/中断返回 null 不落盘"）不破。
- 每 spawn 一个 `AgentRunner` 实例的做法保留——但其传统论据已不实：`ContextWindowManager` 的字段全部为 final/static，并无随实例走的可变状态；保留的真实理由仅是改动最小。随 Turn 聚合删掉 `runningThread` 后可评估共享单例——**列为可选清理，不阻塞**。
- 边缘核对项：去 `.join()` 直调后，`run()` 抛出的 `Error` 类异常不再经 CF 包装为 `CompletionException` 而是直接传播——实施时验证主链路 catch 面与追踪层（catch Throwable 契约）覆盖即可，预期无行为变化。

*备选（否决）：保留 CF 签名内部改手动完成——调用方语义仍是假异步，且 supplyAsync 对已取消任务跳过 lambda 的坑（pre-pickup 善后已用手工 future 解决）继续纠缠。*

### D4: `runAgentLoop` 分解（只动结构，不动检查点位置）

- **先补特征测试组**：现状 runAgentLoop 内部时序（hook 发射顺序/注入异构路径/drain6 行为）在测试目录零覆盖，纯"现有测试全绿"不构成等价性证据；分解前用脚本化 AiService 锁定 hook 时序（`afterIteration`/`onIntermediateResponse`/`finalizeContent` 的发射顺序与次数）。
- 私有 `LoopState`（或 runner 内部类）收纳共享可变状态：`currentMessages`（引用重赋）、`finalContent`、`iteration`、`injectionCycles`、`hadInjections`、`toolsUsed`、`llmOptions`。
- while 体两大分支抽为 `handleToolCallsBranch(state, response)` / `handleFinalResponseBranch(state, response)`，返回 `boolean shouldContinue`。
- 注入检查点**5 处同构 + 1 处异构**：inj1-inj5 的仪式（`injX = tryDrain…; injectionCycles = …; hadInjections |= …; if (shouldContinue) {…continue;}`）收敛为 `InjectionDecision checkpoint(state, …)`；第 6 处（maxIterations 后收尾抽干 drain6）**保留手写**——它绕过 MAX_INJECTION_CYCLES 上限、直接 appendInjectedMessages、永不 continue，统一进带上限的辅助方法会改变"已用满 5 周期后打到 maxIterations"场景的行为，且该路径无测试锁定。
- 调用侧保留各自 hook 差异（三个变体：`afterIteration`、`onIntermediateResponse`+清 `finalContent`、以及个别检查点独有的副作用顺序）；**`finalContent` 的清除只发生在特定检查点的 continue 路径上**——这是抽取时最易丢的隐藏耦合，LoopState 化后须逐检查点核对。**不移动任何检查点的语义位置**——如"LLM error 后有注入则 continue"这类分支语义逐字保留。
- 删除方法体内残留的匿名 `{}` 块（历史 runningThread 管理的空壳）与错位注释。

### D5: 阶段划分（同步化先行；每阶段独立全绿 + 独立 commit）

1. **Phase 1**：AgentRunner 同步化（D3）——删假异步包装，`interrupt()`/`runningThread`/`runningTurnSession` 原样保留。`SubagentExecutorDeadlockTest` 机械适配 + javadoc 改写。
2. **Phase 2**：runAgentLoop 分解（D4）——特征测试组先行、5 处同构仪式去重（drain6 手写保留）、匿名块清理。
3. **Phase 3**：Turn 聚合（D1）——收敛 loop 侧 6 map（含 `activeTurnHandles`）+ 3 ThreadLocal + 1 volatile；中断重定向到 `turn.runnerThread`（pickup 写、teardown 置 null）与 `RunningSubagent.runThread`；随后删 `AgentRunner.interrupt()`/`runningThread`/`runningTurnSession`。`InjectionManager` 原样。
4. **Phase 4（可选）**：路由槽合并进 TurnRegistry（D2 closed 标志方案），`InjectionManagerTest`/`AgentLoopAdversarialTest`/`SubagentIsolationTest` 相关用例迁移。

**重排理由**：同步化是四项中唯一有客观正确性收益者（消除"池化载体残留中断→空回复"故障类），不依赖 Turn 即可落地；原顺序「先聚合后同步化」会让 D1 的 `runnerThread`「pickup 时写入」在假异步仍在时记错线程（pickup 线程阻塞在 join、LLM 跑在 commonPool 载体的双线程过渡态）。分解独立于两者、先做可降低聚合重写的审读成本；聚合押后还可借同步化落地后的实测重新校准投入——若并行容器在干净线程模型下不再难推理，Phase 3 可缓行。

## Risks / Trade-offs

- [并发契约回退] → 契约测试不改断言全程绿灯是硬门禁；防御性注释随其描述的代码迁移到新归属（注释是第二份 spec，不许丢）；每阶段单 commit 可独立 revert。
- [Turn 后写字段可见性] → `epoch`/`runnerThread`/`ownResetEpoch` 用 volatile；其余 final；Turn 经 CHM 发布。
- [中断语义漂移（同步化）] → `signalCancel` 的既有顺序不变（子代理 → flag → interrupt → future.cancel → 摘槽）；中断目标从 commonPool 载体换为 agent-loop 专用线程，命中点等价（LLM 调用/drainBlocking park 均在执行线程上）。子代理路径同理：目标从 commonPool 载体换为 subagent 池线程（LLM 调用所在），`cancelBySession`/`shutdown` 的打断能力不变。
- [Turn 聚合的语义微变（须显式申报，不可静默）] → ①现状五个错开的注册时机（abortFlags/latches put → register → activeTurnHandles.put → TURN_STARTED → execute/token/runningTurnSession → activeTasks.put）若被单次 register 压扁，会改变 [startTurn 起步→activeTasks.put] 窗口内 signalCancel 的行为路径与终态事件 Kind——实施时二选一：Turn 先建后逐字段武装保序（推荐），或申报为可观察语义变化并同步改对应测试断言；②turnToken「身份即 Turn」改变了 offerInjection 守卫在 [register→pickup] 窗口的身份载体（现状比对 token 值、聚合后比对 Turn 身份，同窗口同键下行为等价，review 时核对）；③**handle 须随 Turn 构造就位，不按旧 activeTurnHandles.put 时机后写**（3.2 实施期发现并申报）：旧表在垂死→后继交接期间（后继创建于垂死回合收尾的 re-publish、早于其 future 死亡触发的摘除）靠「前驱句柄尚未被摘除」保持非空，`activeTurn()` 轮询方不在交接窗口误判空闲；注册表条目在 register 瞬间即整条替换，若句柄晚于此才武装，`activeTurn()`（= 条目句柄）会在 [register→武装] 窗口误报空——外部「等回合落定」探询（CancelRaceInvariant 的 settle 锚、面板领养）将提前放行并把后继消息误注入垂死回合。signalCancel 的 TURN_CANCELLED 认领以 future 已武装且未完成为前提，句柄提前可见不改任何认领路径（①的约束针对 signalCancel 行为，不受此影响）。
- [hook/回调线程变化] → 现状回调已在 commonPool 载体（非 EDT），改到 agent-loop 线程仍是后台线程；GUI 经回合事件流消费（订阅端自投 EDT）的契约不受影响。
- [单 executor 承重不变式] → 「全局单线程 executor 串行所有会话」不是可随手放弃的实现细节：改树工具默认非并发安全且内联跑回合线程、`JMeterElementManager` 直取 GuiPackage 无锁——跨回合并行需要一层尚不存在的树写锁。任何引入并行回合的后续变更须先建该锁。
- [Phase 4 测试迁移被误读为"改测试放水"] → 迁移 PR 中逐字对照（仅类名/构造变），并在 tasks 中显式要求 diff 审查点。
- [两 map 并存期（Phase 3 起、Phase 4 前）的槽位一致性] → 与现状完全相同的时序约定（register 先槽后队、摘槽先 cancel 后 remove），不引入新窗口；Phase 4 消除该二元性。
- [Phase 4 实施与 D2 行文的三处偏差（均以现状语义为准绳，实施时申报）] → ①`hasActiveRun` 实现为「条目存在 && !closed && 队列已武装」——D2 行文只写了前两个合取项，但旧槽位生于 `injectionManager.register`（= armQueue）而非 loop 侧 register，缺第三项会让 [register→armQueue] 保序武装窗口误报 busy、ack 走错分支；②cleanup 顺序反转为先置 closed（持 bin 锁、身份条件）再抽干——旧 drainTo→remove 两步间隙内 offer 可成功写入垂死队列且再无消费者（静默丢消息微竞态），反转后 offer 要么赶在置位前入队（随残留扫描可见、参与 re-publish）、要么事后见 closed 被拒，属修复性偏差；③signalCancel 自我豁免路径（第 3.5 步）保留真摘除（`cancelRouting` = remove，而非置 closed）——D2 行文把 cancelRouting 一并归入「改置 closed」，但该路径的现状可观察行为是条目即刻消失（waitForCancellation 读 null latch 立即 true、activeTurn 为空、对端不可注入亦不可再取消），置 closed 会把这些观察全部推迟到 latch 释放点；保留 remove 以现状行为为准，代价（latch/句柄随条目提前消失）已在 `TurnRegistry` 摘除方法 javadoc 申报。**后续修订（对抗审查 2026-09-03）**：`cancelRouting` 方法整体删除，该路径的真摘除由 signalCancel 第 3.5 步直接调 `removeIfCurrent(sessionKey, selfTurn)`（按值条件摘除——见下条新申报）。
- [全量对抗审查（8 视角 × 双质疑验证，2026-09-02/03）发现并修复的 4 缺陷 + 1 死字段——均以钉定测试固化] → ①**3.5 步无条件 remove 击穿后继**：垂死回合收尾的 re-publish 会先于其外层 finally 把同 key 后继注册进表，旧 `activeTurnTokens.remove(sessionKey)` 无条件按 key 摘除会当场摘掉后继表项（后继变得不可取消/不可注入）→ 改 `removeIfCurrent(sessionKey, selfTurn)` 按值条件摘除（`TurnTest.removeIfCurrent_neverRemovesSuccessor` 钉定）；②**runnerThread 双读 NPE 窗口**：`turn.runnerThread().interrupt()` 的两次读间 clearRunnerThread 可插入 → 单次读入局部变量后判空（`cancelQueuedForeignSession_runningTurnNotInterrupted` 钉定跨会话不误中断）；③**重置代数翻转误入 self!=null 守卫**：EDT/ipc-worker 等非 loop 线程发起 resetConversation 时 currentTurn 恒空、代数不翻，republishLeftovers 的旧代数丢弃栅栏失守，旧会话残留被复活进新会话 → 翻转无条件化，仅 ownResetEpoch 写入留在守卫内（`NewCommandCancelTest.resetFromNonLoopThread_flipsEpoch` 以 markConversationReset 返回值确定性钉定；栅栏互斥由 `AgentLoopRepublishTest.resetFence_mutuallyExcludesRepublish_andFlipsEpochOffLoopThread` 钉定——REPUBLISH 的 TURN_STARTED 在 resetFenceLock 临界区内派发，停车订阅者把 loop 线程钉在临界区内，重置线程必须阻塞）；④**markPickup 从未接线**（死字段）：`turn.markPickup(turnEpoch)` 在 startTurn 读 currentEpoch 后补接（`TurnTest.markPickup_recordsEpochOnTurn` 钉定字段语义）。另：/new 自我豁免后的迟到 Stop 不得吞掉确认回执（`stopAfterSelfExemptedNewTurn_doesNotCancelConfirmation` 以 latch 钉住归档 park 点）、入场中断位清扫（`presetInterruptBit_sweptAtEntry_runNotAborted`）、drain 超时闩锁不随检查点重复阻塞（`drainTimeoutLatches_subsequentCheckpointsDoNotBlockAgain`）、收尾期 signalCancel 轰炸不抛（150 次迭代）、offer vs cleanup 原子性压测（300 次迭代）均补齐钉定。
- [修复后追加对抗验证（修复 diff 审查 + 完备性批评，2026-09-03）裁决 8 发现 → 3 项行动全部落地] → 工作流对上述 5 项修复逐一复核为 SOUND（25/25 agent 零 429；errored 票计 UNRESOLVED 的聚合修正生效）。行动项：①**FIX-6：`TurnRegistry.closeRouting` 改身份条件置 closed**——验证方以 interleave-replay 正式否决了「现实可命中」但承认该方法是全链路唯一仍在按 key 无条件摘路由的点：陈旧快照 Stop（`turn` = signalCancel 入口快照）与垂死收尾 re-publish 的交错下，无条件 `markClosed()` 会摘掉刚注册的后继路由（此前仅靠第 1 步先于第 4 步 + cancelled 先读后置的非局部不变式侥幸不命中）——签名改 `closeRouting(sessionKey, turn)`，与 `removeIfCurrent`/`cleanup` 同款按值守卫（`TurnTest.closeRouting_identityGuarded_neverClosesSuccessor` 钉定，含 null 快照不置位分支）；②**两窗口取消语义的注释失实修正**：原 javadoc 称 [register → armFuture] 窗口的取消「由任务头取消预检作废」——实际该窗口 future 未武装、`isCancelled` 恒 false，预检不可见：任务照常取出、首次迭代查 abortFlag 中止，终态为空内容 TURN_COMPLETED（无 TURN_CANCELLED）；预检作废 + TURN_CANCELLED 只属 [armFuture → pickup] 窗口（Turn「保序武装」、AgentLoop armFuture 注释与 signalCancel 第 2 步注三处同步改写；`cancelDuringArmingWindow_taskRunsAndAborts_completesWithoutCancelledTerminal` 以停车订阅者钉死提交线程于 TURN_STARTED 同步派发点，确定性复现该窗口并钉定终态 Kind / 无 CancellationException / LLM 零调用）；③**FIX-2 的确定性钉定**：150 迭代锤击对该 NPE 窗口检出率仅 ~1/5，补 mock Turn 连续桩 `thenReturn(probe, null)` 的确定性复现（首读见 probe 次读见 null，双读写法必 NPE；`signalCancel_runnerThreadSingleRead_interruptLands_noNpe`），中断送达以活探针线程旗标断言（unstarted 线程收不到 interrupt）。复核中发现并记录、不属修复项：`turn.epoch()` 至今无生产读者（markPickup 接线属忠实状态修复，非行为修复）；5.1 的 install 门禁此前未执行，本轮已补跑。
- [验证轮收尾事故申报（2026-09-03）] → 落地上述 3 项行动期间，`resetConversation` 的 `self.recordOwnReset(flipped)` 调用被遗留在 `// COUNTERFACTUAL-VERIFY` 注释后（验证 finding #1「epoch 无生产读者」时做的反事实开关，未随裁决结束恢复）。症状：`/new` 命令回合不再记录自身代数翻转 → 收尾分类的 own-reset 采纳读到 UNSET → turnEpoch 停留在 pickup 前值 → 队列残留被误判为「被放弃旧会话」静默作废（`AiChatPanelNewConversationTest.messageInjectedIntoQueuedNewTurn_isRepublishedIntoNewConversation` 全量门禁 519/1 唯一失败；该测试是此路径的独占守卫——`AgentLoopRepublishTest` 的 own-reset 用例走非 loop 线程翻代数路径、不消费 own 写入，故定向 24/24 全绿掩盖了它）。定位：FIX-6 先经 A/B 反证排除（退守卫/退无条件均同样红——该路径 closeRouting 三种形态皆为 no-op），再以 test-scope `log4j2-test.xml` 打开生产 INFO 日志，`Discarding leftover(s) after conversation reset` 一行直指代数失配。恢复调用后单类 11/11 绿。教训：**反事实开关（临时注释代码）必须当场恢复或删除，不得跨步骤存活**；测试期生产日志默认 ERROR 级不可见，收尾分支的日志诊断需 test-scope log4j2 配置。

- [终局门禁第 1 轮 2 例测试侧调度竞态（2026-09-03，均非生产行为变更、断言零改动）] → ①`signalCancel_runnerThreadSingleRead_interruptLands_noNpe`（本轮新增钉定）：`interrupt()` 只置位并异步唤起 park，probe 的 catch 置旗标与主线程断言无同步——主线程先读到 false 属调度竞态；补 `probeDone` 闩（probe 的 finally 计数），断言前先等 probe 跑完 catch。②`EventParityTest.userStopCancelEmitsCancelledTerminalEvent`（既有测试首见）：signalCancel 第 2 步 interrupt 先于第 3 步 cancel，fake 单次 `Thread.sleep` 停等被 interrupt 唤醒后即返回、整回合可在 [interrupt → cancel] 间隙自然完成并自行认领终态 → `[started, completed]`（负载相关；signalCancel javadoc 本就把 interrupt 先行竞态列为合法结局，生产行为无变更）；fake 改双门停等——第一次 interrupt 后再停一门，回合只能被 `cancel(true)` 的第二次 interrupt 放行，窗口内 future 恒未完成、cancel 必胜，终态确定性为 TURN_CANCELLED。两例修复后定向 25/25 绿，随后终局全量门禁。

## Migration Plan

无数据/持久化/接口迁移。4 个独立 commit 顺序合入 `feature-gitee`；每 commit 前 `mvn clean test` 全绿（规避增量编译 stale class）。完成后 `mvn clean install` + 手动冒烟清单：GUI 发消息、进行中注入、Stop、/new 于运行中、子代理 spawn/汇流、IPC 委派回合显示。回滚 = revert 对应 commit。

## Open Questions

- `Turn`/`TurnRegistry` 最终包位（`agent.turn` vs `agent.run`）——不改变任务结构，实施时可按包内聚微调。
- Phase 4 是否随本变更执行——独立价值，可在 Phase 1-3 验收后决定。
- D3 中"多 spawn 共享单例 AgentRunner"的可选清理是否顺带做——默认不做，除非实施时证明零风险。
