# Tasks: context-window-usage-indicator

> 硬门禁（每任务组结束时）：`mvn clean test` 全绿（clean 防 stale class）。契约不变式：`TurnEvent` 7 Kind 不变、IPC wire/partialContent 不变、`/status` 与 `AgentLoop.lastUsage` 语义不变、`agent-turn-events` spec 既有 Requirements 全程保持。

## 1. P0 — usage 通知链路 + 面板防误伤（同批交付，缺一即回归）

- [x] 1.1 `ProgressUpdate`：`Type` 枚举新增 `USAGE`；新增静态工厂 `usage(Map<String, Integer> usage)`（message=空串、payload=usage map，javadoc 钉"面向指示器、非文本渲染域"）。验证：`mvn clean test-compile`
- [x] 1.2 `AgentHook` 新增 default 方法 `onUsage(Map<String, Integer> usage, AgentHookContext context)`（no-op，javadoc 注明"每次 LLM 调用返回且 usage 非空时回调"）；`ProgressCallbackHookAdapter` override 之 → `callback != null` 时 `publish(ProgressUpdate.usage(usage))`（对齐既有方法的 null 早退与 publish 吞异常）。验证：`mvn clean test-compile`
- [x] 1.3 `AgentRunner` 在既有 usage 捕获点（`context.setUsage(respUsage)` 同处，`AgentRunner.java:385-388`）追加 `state.hook != null` 时 `state.hook.onUsage(respUsage, context)`——仅 usage 非空路径，末次迭代胜出语义不变。验证：`mvn clean test`
- [x] 1.4 `AiChatPanel.handleProgressNow` 顶部（`removeLoadingIndicator()` 之前）加 USAGE 早退分支：暂 no-op `return`（防 default 分支渲染空行 + 清 loading 的中间态回归；真实现 P2 接）。验证：`mvn clean test`
- [x] 1.5 新增 `UsageProgressEventTest`（`agent/testsupport` 脚手架：`GatedScriptAiService`/`RecordingSubscriber`）：①fake AiService 带 usage 响应 → 订阅者收到 PROGRESS(USAGE) 事件且 payload 为 usage map；②多迭代回合逐次收到、末次反映最终调用；③响应无 usage → 零 USAGE 事件；④USAGE 事件先于回合终态。验证：`mvn clean test -Dtest=UsageProgressEventTest` 后全量（4/4 + 546/0/0 全绿）

## 2. P1 — ContextUsageRing 组件

- [x] 2.1 新建 `gui/ContextUsageRing.java`（`SelectionContextBar` 模板）：`extends JComponent`、固定 preferredSize(~18×18)、`setOpaque(false)`、EDT-only javavadoc；`paintComponent` 抗锯齿画底环 + 12 点起顺时针进度弧（clamp [0,1]、`CAP_ROUND`）；`update(long used, long total)` 内容去重后仅 `repaint()` + `setToolTipText`（无 revalidate）；`reset()` 置空；k 记法 tooltip `Context: 12k / 64k (18%)`；主题色 `UIManager.getColor` + 兜底常量；高水位(≥80%)/危险水位(≥95%)弧色可区分。验证：`mvn clean test-compile`
- [x] 2.2 新增 `ContextUsageRingTest`（headless）：①占比计算与 >100% clamp；②update 去重（同值不重复 repaint 语义——断言内部状态不变）；③reset 清零；④tooltip 文案格式（12345/65536 → "Context: 12k / 64k (18%)" 口径、<1000 显示原值）；⑤高/危险水位色档判定方法。验证：`mvn clean test -Dtest=ContextUsageRingTest`

## 3. P2 — 面板接线

- [x] 3.1 `AiChatPanel`：`modelGroup.add(contextRing, BorderLayout.EAST)`（布局段 `AiChatPanel.java:424-436`），字段与创建对齐 `modelSelector` 写法。验证：`mvn clean test-compile`
- [x] 3.2 USAGE 早退分支落地真实现：payload instanceof `Map` 读 `prompt_tokens`，`contextRing.update(used, AiConfig.getContextWindowTokens())`；不渲染聊天行、不清 loading（1.4 的早退位置保持不变）。验证：`mvn clean test`
- [x] 3.3 `advanceRenderEpoch()` 追加 `contextRing.reset()`（/new、"+"、关闭整合清空、远程 /new 四处共用单点）。验证：`mvn clean test`
- [x] 3.4 面板测试（复用 `AiChatPanelIpcTurnPresenterTest` 接线模式）：①busy 期 USAGE 事件不清 loading 武装、转录无新增行、ring 状态更新；②`/new` 后 ring 复位；③旧会话迟到 USAGE 事件（代数翻后）不渗入复位后的 ring；④IPC 委派回合的 USAGE 照常刷新 ring。验证：`mvn clean test` 全量

## 4. 收尾验收

- [x] 4.1 CLAUDE.md：GUI 层条目补 `ContextUsageRing`（用途 + repaint-only 模式）；「回合事件流」段 PROGRESS 载荷补 USAGE 类型一句。验证：`git diff` 审查
- [x] 4.2 对照 spec `context-usage-indicator` 逐条 Requirement 复核（5 条 Requirement × 全部 Scenario 有测试或代码路径映射）；确认 `agent-turn-events` 零 delta 成立（7 Kind 未动、订阅契约未动）。验证：`openspec validate context-window-usage-indicator` + 人工复核记录（复核结论：R1 四 scenario → UsageProgressEventTest 5 用例全覆盖（含补的 IPC 累积器隔离钉子）；R2 → 组件/面板测试 + repaint-only 代码路径（「不扰动编辑器」无 headless 钉子，归 4.3 手动冒烟）；R3 → tooltipFormat 两用例；R4 → 面板测试②③ + 远程 /new 经 advanceRenderEpoch 单点代码路径；R5 → delegatedTurnUsageUpdatesRing。零 delta：diff 证实 presenter 包零改动、TurnEvent 7 Kind 未动。最终门禁 556/0/0）
- [x] 4.3 手动冒烟（用户执行）：长会话观察环随回合增长、悬浮明细、≥80%/≥95% 变色、`/new` 归零、模型切换后下一回合刷新、GUI 运行期编辑树节点时指示器更新不打断单元格编辑（用户确认已手动测试通过，2026-09-12）
- [x] 4.4 冒烟反馈收敛 + 对抗审计（5 维度 find × 2-lens 反驳，15 agents）：①环描边 +50%（2.5f→3.75f）②modelGroup hgap 6 与 combo 留隔 ③底环固定浅灰 (215,215,215)（disabledForeground 在 Metal 下偏深压弧）④进度弧改 12 点起顺时针（AWT 正角**逆时针**，原 -90°/+extent 实为 6 点起逆时针；改 90°/-extent + 像素级几何钉）⑤`/status` Current Context 优先 API 实报 lastIn、冷会话回落估算（用户裁决；**取代 design.md D1「/status 用 ② 优先」与 proposal「/status 行为不变」的规划期表述**——spec delta 无 /status 要求，归档同步无冲突）。审计结论：并发/EDT 与事件流/生命周期两维度零发现；负数 token 被 provider 层 `>0` 门 + AgentRunner 空 map 门双重拦截；分母实时读 vs governor 构造期钉值仅在运行期 props.put 下发散（前置行为、环重建收敛，记为已知）；面板测试 pct 字面量改随分母动态推导
