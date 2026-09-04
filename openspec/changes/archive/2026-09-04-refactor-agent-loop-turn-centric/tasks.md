# Tasks: refactor-agent-loop-turn-centric

> 硬门禁（每个任务组结束时）：`mvn clean test` 全绿（clean 防 stale class）；契约测试（AgentLoopAdversarial/Republish/SignalCancelScoping/PerTurnCallback/DelegationGuard/TurnEvent/EventParity/NewCommandCancel/InjectionManager/subagent 系列）**不改断言**。每阶段一个独立 commit——**实施方不执行任何 `git add`/`git commit`/`git push`**：每阶段完成代码 + 验证 + 勾选 checkbox 后报告就绪，由用户亲自审阅并提交；下文各「Phase X commit」任务项均按此解读（commit 边界仍按阶段划分，仅执行者为用户）。
>
> 阶段顺序（design D5，相对早期草案重排）：同步化 → 巨方法分解 → Turn 聚合 →（可选）路由槽合并。同步化先行使 Turn 聚合的 `runnerThread`「pickup 时写入」语义在干净线程模型上一步到位。

## 0. Phase 0 — 先导（已完成 2026-08，变更 `unify-turn-event-display`）

回合事件流落地：`agent.presenter` 5 类型（`TurnHandle` 含进程唯一 id/`terminalEmitted`、`TurnEvent` 7 Kind、`TurnSubscriber`）+ `AgentLoop` 7 发射点与 `dispatchTurnEvent` 三守卫 + `AgentLoopFactory` 工厂级订阅表 + `activeTurnHandles`/`activeTurn()`；旧 TurnPresenter/AgentSwingWorker 通道删除。本变更在其之上继续（见 proposal Why 尾注与 design D1 补行）。

## 1. Phase 1 — AgentRunner 同步化（中断追踪原样保留）

- [x] 1.1 `AgentRunner.run(spec)` 改同步签名（去 `supplyAsync`/CF 包装）；`AgentLoop.startTurn` 去 `.join()` 直接调用；`SubagentManager.runSubagent` 去 `.join()` 直接调用。**`AgentRunner.interrupt()`/`runningThread` 与 `AgentLoop.runningTurnSession` 本阶段一律不动**（同步化后其记录的目标线程自动变为 agent-loop 专用线程/subagent 池线程，三处调用方命中语义等价）。验证：`mvn clean test-compile` + 全量测试
- [x] 1.2 入口/出口 `Thread.interrupted()` 清扫保留各一处，注释改写为新理由（专用线程跨回合残留 / subagent 池化线程污染）；**顺序不变式随代码固化：出口清扫必须在持久化守卫（`lockLongTermMemory` abort 谓词）读取中断位之后**。`isAbortedFlag` 现有 javadoc 在 HEAD 上已失实，一并重新核验改写（勿照抄旧注释）。验证：`git diff` 审查清扫位置未变 + 全量测试
- [x] 1.3 评估并合一 `isAborted`/`isAbortedFlag`（design D3：同步化后前置/后置整合都在可中断执行线程上，统一含中断版只更保守；不破 MemoryConsolidator abort 感知锁契约）；若合一后任一测试变红则回退保留双轨并在 commit message 记录原因。验证：`mvn clean test -Dtest='MemoryConsolidator*,MemoryStoreWriteLockTest'` 后全量
- [x] 1.4 机械适配唯一直接 `run().join()` 的测试 `SubagentExecutorDeadlockTest`（`.join()` → 直接调用，断言不动），其 javadoc 记载的「同步化防死锁」理由随之失效须改写；`AgentRunnerToolCallingRequirementTest` 经 `processMessage(...).get()` 驱动，零适配（跑通即可）。同步核对：去 `.join()` 直调后 `run()` 抛出的 `Error` 不再包 `CompletionException`，验证主链路 catch 面覆盖。验证：`mvn clean test -Dtest='Subagent*'` 后全量
- [ ] 1.5 Phase 1 commit（独立可 revert）

## 2. Phase 2 — runAgentLoop 分解（特征测试先行）

- [x] 2.1 **先补特征测试组**：runAgentLoop 内部时序（`afterIteration`/`onIntermediateResponse`/`finalizeContent` 的发射顺序与次数、注入异构路径、drain6 行为）现状零覆盖，用脚本化 AiService（`agent.testsupport.GatedScriptAiService`）锁定，分解前入仓。验证：新测试在**当前未分解代码**上全绿（锁的是现状行为）
- [x] 2.2 引入私有 `LoopState` 收纳共享可变状态（currentMessages/finalContent/iteration/injectionCycles/hadInjections/toolsUsed/llmOptions），while 体两大分支抽为 `handleToolCallsBranch`/`handleFinalResponseBranch`。验证：`mvn clean test` 全绿，既有测试零改动
- [x] 2.3 注入检查点仪式去重：新增 `checkpoint(state)` 返回 InjectionDecision，**仅替换 5 处同构复制块**；第 6 处（maxIterations 后收尾抽干 drain6）**保留手写**（绕过 MAX_INJECTION_CYCLES、直接 append、永不 continue，无测试锁定，design D4）；`finalContent` 只在特定检查点 continue 路径清除——逐检查点核对未丢。**不移动任何检查点位置**，调用侧保留 hook 差异三变体。验证：`mvn clean test -Dtest='AgentLoopAdversarialTest,SubagentTurnConfluenceIT,InjectionManagerTest'` + 2.1 特征组 后全量
- [x] 2.4 删除 runAgentLoop 残留匿名 `{}` 块与错位的历史注释。验证：`git diff` 审查 + 全量测试
- [ ] 2.5 Phase 2 commit

## 3. Phase 3 — Turn 聚合（在简化后的线程模型上做）

- [x] 3.1 新建 `agent.turn.Turn`（字段清单见 design D1 表格，含 `handle` 字段；final + volatile 后写字段）与 `agent.turn.TurnRegistry`（register/find/removeIfCurrent/cancelRouting/hasActiveRun）。编译通过：`mvn clean test-compile`
- [x] 3.2 `AgentLoop.startTurn` 改为创建 Turn 并经 TurnRegistry 注册；**保序武装**——现状五个错开的注册时机（abortFlags/latches put → register → activeTurnHandles.put → TURN_STARTED → execute/token/runningTurnSession → activeTasks.put）不得被单次 register 压扁（否则改变 [startTurn 起步→activeTasks.put] 窗口内 signalCancel 行为与终态事件 Kind，design Risks）；Turn 先建后逐字段武装保持时序。execute lambda 内以 Turn 字段替换 `activeTasks`/`abortFlags`/`completionLatches`/`activeTurnTokens`/`drainTimedOut`/`activeTurnHandles` **六张 map** 的全部读写（`activeTurn(sessionKey)` 查询改经 TurnRegistry）；`whenComplete` 清理改为 Turn 收尾。验证：`mvn clean test` 全绿且 src/test 零改动（实施期两项申报已记 design Risks ③：handle 改随构造武装以保 activeTurn 交接窗口连续性；内层 finally 恢复 turnTeardownLock 临界区——offerInjection 互斥对端 + IPC 领养窗口锚点）
- [x] 3.3 `currentTurn: ThreadLocal<Turn>` 替换 `turnOwnedByThisThread` + `currentTurnSelf`/`TurnSelfRef` + `ownResetEpoch` 三个 ThreadLocal；`signalCancel` 自我豁免改为单一回合身份比较；`resetConversation` 写 `turn.ownResetEpoch` **必须经 `currentTurn.get()` 定位目标回合**（不可 `registry.find(sessionKey)`——同 key 新回合可能已注册，会击穿代数栅栏）。验证：`mvn clean test -Dtest='AgentLoopSignalCancelScopingTest,NewCommandCancelTest,AgentLoopRepublishTest'` 后跑全量（507/0/0）
- [x] 3.4 中断重定向并删旧通道：主链路 `signalCancel` 改 `turn.runnerThread.interrupt()`（**pickup 时写入、teardown 时置 null**——残留引用会跨回合误中断，design D1）；`RunningSubagent` 新增 `volatile Thread runThread`（任务体开头赋值），`SubagentManager` 两处 `handle.runner.interrupt()`（cancelBySession/shutdown）改为 `handle.runThread.interrupt()`——显式方案，不依赖 `future.cancel(true)` 的隐式中断副作用（防未来 cancel(true)→cancel(false) 静默丢子代理中断）；同步改写 SubagentManager 类 javadoc 与内联注释中「runningThread 单字段逼出来的」陈旧理由。随后删除 `AgentRunner.interrupt()`/`runningThread` 与 `AgentLoop.runningTurnSession`。验证：`mvn clean test -Dtest='AgentLoopSignalCancelScopingTest,SubagentCancellationTest,Subagent*Test'` 后全量
- [x] 3.5 迁移随行注释：被删 map/ThreadLocal/volatile 上描述回合生命周期契约的注释（按值条件删除理由、pre-pickup 善后、代数栅栏语义等）移到 Turn/TurnRegistry 对应成员上，逐条对照不丢失。验证：`git diff` 审查确认每段被删注释在新归属处有对应文本（13 簇逐一核对：按值摘除→TurnRegistry.removeIfCurrent；interrupt 连坐→signalCancel 第 2 步；代数栅栏→Turn.ownResetEpoch；epoch 孤儿→Turn.epoch；whenComplete/latch 谎报→外层 finally + TurnRegistry 类 javadoc；drainTimedOut 复位→Turn.drainTimedOut per-turn 语义；offerInjection 守卫→turnTeardownLock javadoc；REPUBLISH 句柄→startTurn + Turn 类 javadoc；TOCTOU 双读→signalCancel 单次读取注；另清 DelegationGuard/DelegateToInstanceTool 两处 stale `activeTasks` javadoc → `hasActiveRun`）
- [ ] 3.6 Phase 3 commit

## 4. Phase 4（可选，验收后拍板）— 路由槽合并（closed 标志双生命周期）

- [x] 4.1 `Turn` 加 `volatile boolean closed`：三组状态的原摘除动作（cancelRouting/cleanup 的注入槽摘除、whenComplete 的 loop 侧条目摘除）改为在 `computeIfPresent` 内置 closed（与 offer 同一 bin 锁，原子），条目真正 remove 推迟到 latch 释放点；`hasActiveRun` = 存在 && !closed；`offer` 发现 closed 走现状「槽已摘」分支（ack 拒绝语义不变）。design D2——**不可把三组摘除时机压成一个**。验证：`mvn clean test` 全绿（与 4.2 原子实施——closed 标志与 offer/closeRouting 容器操作一体，单独 4.1 不可编译；三处与 D2 行文的偏差已在 design Risks 申报：hasActiveRun 增补「队列已武装」合取项、cleanup 反转为先置 closed 再抽干（修复性）、signalCancel 第 3.5 步自我豁免保留真摘除）
- [x] 4.2 将 `InjectionManager` 的队列操作（offer/drain/drainBlocking/cleanup/cancelRouting）并入 Turn/TurnRegistry；`turnTeardownLock` 公告互斥逻辑逐字迁移。验证：`mvn clean test` 全绿（InjectionManager.java 删除；InjectionItem 迁为 agent.turn 顶层类；drain/drainBlocking/drainAll 落 Turn（队列归回合私有），offer/closeRouting/cleanup/hasActiveRun 落 TurnRegistry；AgentLoop 全部调用点改经 activeTurnTokens 单表；turnTeardownLock 互斥逐字保留于内层 finally 临界区）
- [x] 4.3 测试迁移：`InjectionManagerTest` 用例逐字迁移为 `TurnTest`（仅类名/构造适配，断言语义不动）；同时迁移 `AgentLoopAdversarialTest` 与 `SubagentIsolationTest` 中直接构造/使用 InjectionManager 公共 API 的用例。diff 审查点：新旧断言一一对应。验证：迁移后全量测试 + `git diff` 对照审查（507/0/0 全绿；TurnTest 4 用例 + Adversarial offerVsCancelRouting 压测 + SubagentIsolation 4 个 drainBlocking 用例迁移，断言逐字未动；另修 5 处描述现行机制的 stale 注释：IpcTurnPresenterTest 摘槽窗口/领养锚点、IpcServerAgentTimeoutRaceTest 收尾锚点 ×3、AgentLoopTurnEventTest 跨 loop 路由 map 名——历史缺陷叙述性注释（描述修复前世界）保留不动）
- [ ] 4.4 Phase 4 commit

## 5. 收尾验收

- [x] 5.1 全量 `mvn clean test` + `mvn clean install`（JMETER_HOME 就绪）；手动冒烟：GUI 发消息、运行中注入并观察 ack、Stop、运行中 /new、子代理 spawn 与结果汇流、IPC 委派回合在面板显示与终止（2026-09-04 用户确认手动冒烟执行完毕；终局门禁含当日 think 标签渲染改动：`mvn clean install` 531/0/0/11 全绿，jar 已部署）
- [x] 5.2 对照 proposal「不做的事」清单复核出界情况；CLAUDE.md 架构章节中 AgentLoop/AgentRunner 的描述按新结构更新（Turn/TurnRegistry 条目）（五项出界条款逐一核对无违反：路由槽合并=用户拍板执行的可选末阶段且用 D2 closed 方案；消息路由语义未改；单线程执行器不变式未触碰；无每会话执行器/actor 模型；AgentResponse/AgentRunResult 双模型与 processMessage 重载公共面零改动。CLAUDE.md：删 InjectionManager 条目，新增「回合对象 (`agent.turn`)」小节（Turn/TurnRegistry/InjectionItem），AgentRunner 条目更新为同步模型，AgentLoop 条目指向回合对象单表）
- [x] 5.3 `openspec archive` 归档变更（2026-09-04 归档至 archive/2026-09-04-refactor-agent-loop-turn-centric/）

## 6. 全量对抗性验证（用户追加，2026-09-02/03；Ultracode 工作流）

- [x] 6.1 8 视角对抗发现工作流（race/lifecycle/memory-visibility/injection/cancel-reset/interrupt/subagent/registry 原子性）× 双质疑验证（interleave-replay + coverage/declared-delta）；发现清单人工裁决（工作流验证阶段遇 429 限额大面积失败，`votes.filter(Boolean)` 缺陷致空裁决误判 CONFIRMED——不采信，逐条对照源码裁决）→ **4 真缺陷 + 1 死字段 + ~9 覆盖缺口 + 2 存疑（1 已被 cancelRouting 删除 moot、1 补廉价压测）**
- [x] 6.2 生产代码修复 4+1（见 design Risks 末条申报）：①signalCancel 3.5 步 remove → `removeIfCurrent`；②runnerThread 双读 → 单局部读；③重置代数翻转移出 self!=null 守卫（无条件化，仅 ownResetEpoch 写入留守卫）；④`markPickup(turnEpoch)` 接线。附带：`TurnRegistry.cancelRouting` 删除（真摘除由 3.5 步直接 removeIfCurrent 承担）
- [x] 6.3 钉定测试 10 个（6 文件）：`TurnTest` +2（removeIfCurrent 防误摘后继 / markPickup 字段语义）、`NewCommandCancelTest` +2（非 loop 线程代数翻转 / 自我豁免后迟到 Stop 不吞确认——latch 钉住归档 park 点）、`AgentRunnerLoopSequencingTest` +1（入场中断位清扫）、`AgentLoopRepublishTest` +1（resetFence 互斥 + 非loop线程翻代数——REPUBLISH TURN_STARTED 在临界区内派发钉住 loop 线程）、`AgentLoopAdversarialTest` +3（收尾期 signalCancel 轰炸 150 迭代不抛 / 跨会话排队取消不误中断运行回合 / offer-vs-cleanup 原子性 300 迭代）、`SubagentTurnConfluenceIT` +1（drain 超时闩锁不随检查点重复阻塞）。验证：`mvn clean test -Dtest=TurnTest,NewCommandCancelTest,AgentRunnerLoopSequencingTest,AgentLoopRepublishTest,AgentLoopAdversarialTest,SubagentTurnConfluenceIT` → 58/0/0
- [x] 6.4 修复后追加对抗验证工作流（修复 diff 审查 + 上次 429 缺失的完备性批评补做）（2026-09-03：25/25 agent 零 429，5 项修复全数复核 SOUND；8 发现裁决 → 3 CONFIRMED 行动全部落地：FIX-6 `closeRouting` 按身份条件置 closed（陈旧快照不误摘后继路由，`TurnTest.closeRouting_identityGuarded_neverClosesSuccessor` 钉定）；两窗口取消语义的注释失实修正（[register→armFuture] 走 abortFlag/空内容 TURN_COMPLETED，非预检作废，`cancelDuringArmingWindow_taskRunsAndAborts_completesWithoutCancelledTerminal` 停车钉定）；FIX-2 双读 NPE 补 mock-Turn 连续桩确定性钉定 `signalCancel_runnerThreadSingleRead_interruptLands_noNpe`。余 5 发现 REFUTED（含 #1 真核：epoch 无生产读者，markPickup 属忠实状态修复——已记录不改动）。+3 钉定测试后定向 24/24 绿，结局申报见 design Risks 末条）
- [x] 6.5 全量 `mvn clean test` 全绿门禁（2026-09-03：516 run / 0 failures / 0 errors / 11 skipped——skips 全部为既有环境条件跳过：SchemaBasedPropertyHandler 5、MinimaxRawHttpSmoke 2、SaveMemoryTruncationSmoke 4，与本变更无关；六个触及测试类 0 skip，+10 新钉定测试全部执行且通过）。终局门禁（2026-09-03 晚，含 6.4 三钉定与 recordOwnReset 恢复）：`mvn clean install` **519 / 0 / 0 / 11 全绿 + BUILD SUCCESS**，jar/skills/CLI 已复制到 `JMETER_HOME`（lib/ext 单 jar 0.3.2 + bin/jmeter-agent 4 文件）；过程中裁决处置 3 起：recordOwnReset 反事实开关悬挂（生产，已恢复）、T2 探针观察竞态与 EventParityTest interrupt→cancel 窗口（测试侧确定性加固，断言零改动），详见 design Risks 末两条
