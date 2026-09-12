# Proposal: context-window-usage-indicator

## Why

长会话中用户对上下文窗口余量完全无感：`/status` 是唯一入口（按需、一次性、需用户主动想起查询），而上下文实际由 `ContextWindowManager` 在每次 LLM 调用前**静默裁剪**（microcompact/snip）——窗口耗尽、早期消息被裁掉的瞬间用户毫无察觉，直到模型"忘记"先前约定才被动发现。上下文是 Agent 会话中最关键的消耗性资源，其用量应提升为常驻可见状态，而非查询型命令。

且数据已近在手边：每次 LLM 调用的 `prompt_tokens`（即模型实际收到的上下文大小）已经到达 `AgentLoop.lastUsage`，但仅在**回合结束后**落库、且无任何通往 UI 的事件通道——缺的只是最后一公里。

## What Changes

- **usage 实时通知**：`ProgressUpdate.Type.USAGE` 新载荷类型 + `AgentHook.onUsage` default 钩子。`AgentRunner` 在每次迭代 LLM 调用返回后立即经现有 progress hook 链（adapter → 发起方回调 + `dispatchTurnEvent`）通知 usage——回合内每次 LLM 调用都刷新，不等回合结束。不新增 `TurnEvent` Kind（USAGE 是 PROGRESS Kind 的 `ProgressUpdate` 载荷类型扩展）。
- **环形指示器组件**：新自绘组件 `ContextUsageRing`（空心环形进度：底环 + 按占比的弧，repaint-only、固定尺寸、主题色），挂模型选择器右侧（`modelGroup` EAST）。实时显示 used/total 占比，高用量有视觉区分。
- **悬浮明细**：鼠标悬浮 tooltip 显示已用 tokens / 总窗口 tokens / 百分比（对齐 `/status` 既有 "12k/64k (18%)" 文案口径）。
- **口径**：分子 = 最近一次 LLM 调用输入 tokens（真实计费口径）；分母 = 现有配置 `jmeter.ai.context.window.tokens`（governor 裁剪预算的唯一权威，与 `/status` 同源）。**不引入 per-model 窗口表、不新增任何配置项**。
- **会话重置联动**：`/new`、"+"、关闭整合清空、远程 `/new` 四处既有重置路径经 `advanceRenderEpoch()` 单点一并复位指示器。
- **对现有消费者零扰动**：IPC `TurnContentAccumulator` 按 `INTERMEDIATE_RESPONSE` 白名单过滤，usage 通知天然不污染 partialContent；面板侧 USAGE 分支早退于 `removeLoadingIndicator()`（不清加载指示、不渲染聊天行）。

## Capabilities

### New Capabilities

- `context-usage-indicator`: 聊天面板上下文窗口用量指示器——token usage 经回合事件流实时通知（每次 LLM 调用后、回合内多次刷新）、环形占比渲染与悬浮明细、会话重置复位、与既有进度消费者（IPC 累积器/聊天文本渲染域/加载指示）的隔离契约。

### Modified Capabilities

（无）——`agent-turn-events` 的 7 类事件 Kind 不变（USAGE 是 PROGRESS Kind 的载荷类型扩展，非新 Kind），其顺序保证、终态恰好一次、订阅者契约均不受影响；usage 通知复用既有 PROGRESS 事件的全部顺序与过滤语义。

## Impact

- **代码**（净加法，约 +200 行）：`ProgressUpdate`（+USAGE 枚举值与工厂）、`AgentHook`（+`onUsage` default no-op）、`AgentRunner`（+1 个调用点，位于既有 usage 捕获处）、`ProgressCallbackHookAdapter`（+`onUsage` 转发）、新文件 `gui/ContextUsageRing.java`、`AiChatPanel`（+ring 字段与 3 处接线：`modelGroup` EAST 布局、`handleProgressNow` USAGE 分支、`advanceRenderEpoch` 复位）。
- **零变化面**：无新配置项（分母读现有 `jmeter.ai.context.window.tokens`）、无依赖变化、无 IPC wire/协议变化、`/status` 行为不变。
- **测试**：usage 通知链路单测（fake AiService → AgentRunner → hook → ProgressUpdate）；面板行为测试（USAGE 事件不清加载指示、不渲染聊天行、ring 状态更新、`/new` 复位、IPC 委派回合照常刷新）。
- **风险前置**：USAGE 类型落地与面板 USAGE 分支必须同一任务组交付（面板 default 分支会把 USAGE 当普通进度渲染并清掉 loading 指示——中间态即回归）。
