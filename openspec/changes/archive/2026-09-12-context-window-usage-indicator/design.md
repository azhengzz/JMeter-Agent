# Design: context-window-usage-indicator

## Context

见 proposal.md - Why。现状关键事实：

- **usage 已到达但断在最后一公里**：`AgentRunner` 每次迭代把 `response.getUsage()`（`prompt_tokens`/`completion_tokens`）写入 `AgentHookContext.setUsage`（`AgentRunner.java:385-388`，末次迭代胜出）→ 回合结束后 `AgentLoop.setLastUsage`（`AgentLoop.java:441-451`）→ 唯一消费者 `/status`。回合内无通知、UI 无通道。
- **窗口大小唯一权威**：全局配置 `jmeter.ai.context.window.tokens`（默认 65536，`AiConfig.getContextWindowTokens()`）。无 per-model 窗口表（全仓搜索零命中）；该值同时是 `ContextWindowManager` 裁剪预算与 `/status` 分母。
- **布局锚点**：`AiChatPanel` 的 `controlsRow`（BorderLayout）：WEST="Model" 标签、CENTER=`modelGroup`（内含 modelSelector，自身 setBounds 截半宽）、EAST=buttonRow（Stop/Send）。模型选择器右侧现成空位 = `modelGroup` 的 EAST。
- **自绘组件先例**：`SelectionContextBar`（`paintComponent` + repaint-only 更新 + 固定 preferredSize + `setOpaque(false)` + `UIManager.getColor` 主题色兜底）——本设计直接沿用该模板，避免 JLabel.setText 的 revalidate 传播触发 JTable cancelCellEditing。
- **进度链形态**：`AgentRunner` 调 `AgentHook`（default 方法接口）→ `ProgressCallbackHookAdapter` 转 `ProgressUpdate` → `buildProgressHook` 包装（先发起方回调、后 `dispatchTurnEvent`，`AgentLoop.java:555-567`）→ 订阅者（面板自投 EDT）。

## Goals / Non-Goals

**Goals:**

- 回合内每次 LLM 调用后，环形指示器即时反映"模型实际收到的上下文大小 ÷ 配置窗口"
- 悬浮显示已用/总量/百分比，口径与 `/status` 一致
- 四处会话重置路径同步复位指示器
- 零新配置、零 wire 变化、零既有消费者行为变化

**Non-Goals:**

- 不做 per-model 上下文窗口表（模型选择器当前单元素，无从挂 per-model 值；窗口语义统一由配置承担）
- 不做 token 估算预填（新会话首次调用前显示 0，不调 `estimateSessionTokens`——它是重建 probe + jtokkit 的重计算，不适合常驻 UI）
- 不改 `/status` 输出与 `AgentLoop.lastUsage` 语义（按需查询路径原样保留）
- 不映射 Anthropic cache tokens（当前未启用 prompt caching，`input_tokens` 即全量输入；见 Risks）

## Decisions

### D1 分子口径：最近一次 LLM 调用的 `prompt_tokens`

候选：① `prompt_tokens`（真实计费口径，每迭代免费获得）；② `estimateSessionTokens`（随时可算但贵，且估算的是**裁剪前**全量、与 governor 实际发送列表有偏差——反而高估）。

选 ①：反映"模型实际收到多少"，与 governor 裁剪后事实对齐；`/status` 用 ② 优先是按需命令可付估算成本，常驻 UI 应零成本取真值。

**语义定义**："当前上下文大小" = 最近一次 LLM 调用的输入 tokens。回合结束后、下一回合首次调用前，该值不再增长（assistant 回复 + 新用户消息未计）——粒度 = 每次 LLM 调用，这是"实时"的诚实边界（Claude Code 同口径）。

**变体否决**：`ProgressUpdate` 直接携带 used/total 计算好的数值 → 否。total 是 UI 侧配置语义（`AiConfig` 实时读），载荷只带 usage 原始 map，面板算占比——与 `/status`、`lastUsage` 同一份原始数据。

### D2 通道：`ProgressUpdate.Type.USAGE` 沿 PROGRESS Kind，经 `AgentHook.onUsage` 注入

候选：① 新增 `ProgressUpdate.Type.USAGE` + `AgentHook.onUsage(Map, ctx)` default 方法（`AgentRunner` 在既有 `context.setUsage` 处顺带调用）→ adapter `publish(ProgressUpdate.usage(usageMap))` → 既有链路；② 新增 `TurnEvent` Kind（USAGE）→ spec `agent-turn-events` 需 MODIFIED delta（7→8 类），CLI/IPC/面板 switch 全部要穷尽处理，变更面大；③ 面板轮询 `lastUsage` → EDT 轮询反模式，且 `lastUsage` 仅回合末更新，做不到回合内实时。

选 ①：最小变更面、天然继承 PROGRESS 事件的顺序/回合身份/过滤/自投 EDT 语义；`TurnEvent` 7 Kind 不动，`agent-turn-events` spec 零 delta。

**载荷**：`message` = 空串（无文本渲染语义），`payload` = `Map<String, Integer>`（`prompt_tokens`/`completion_tokens`，与 `LLMResponse.getUsage()` 同构）。

### D3 分母：`AiConfig.getContextWindowTokens()`

与 governor 裁剪预算、`/status` 分母同源同值。更新时实时读取（JMeter property 读取廉价），不缓存——运行中改配置立即生效于下一次刷新。

### D4 组件：`ContextUsageRing extends JComponent`（SelectionContextBar 模板）

- **位置**：`modelGroup.add(ring, BorderLayout.EAST)`——紧贴选择器右侧；`modelGroup` 半宽上限照旧（combo 让出 ~20px，无布局风险）。
- **尺寸**：固定 preferredSize 约 18×18（行高内不撑高 controlsRow）。
- **绘制**：`paintComponent` 抗锯齿画底环（muted 主题色）+ 自 12 点顺时针的进度弧（`BasicStroke` + `Arc2D`/`drawArc`，`Stroke.CAP_ROUND`）；占比 clamp [0,1]。
- **主题色**：`UIManager.getColor` 取主题前景/弱色并硬编码兜底（对齐 `SelectionContextBar.java:194-197` 模式）；高水位（≥80%）弧转橙、危险水位（≥95%）转红——spec 只钉"高水位可区分"，具体阈值/色值是实现常量。
- **更新协议**：`update(long used, long total)` 内容去重后仅 `repaint()` + `setToolTipText(...)`，**无 revalidate/无 preferredSize 变化**（防 JTable cancelCellEditing 类扰动——`SelectionContextBar` 注释 L17-29 的既有教训）。
- **复位**：`reset()` 置 used=0 并清 tooltip 用量。
- **文案**：`Context: 12k / 64k (18%)`（k 记法对齐 `/status` 的 `ctxUsedStr`/`ctxTotalStr` 格式化；格式化逻辑私有实现，不重构 `BuiltinCommands`——surgical）。
- **EDT**：更新只从 `handleProgressNow`（dispatch 已自投 EDT）与 `advanceRenderEpoch`（只在 EDT）到达，组件自身不加线程防护，javadoc 钉"EDT only"。

### D5 面板接线三点

1. **布局**：`modelGroup` EAST 挂 ring（`AiChatPanel.java:424-436` 区域）。
2. **USAGE 分支早退**：`handleProgressNow`（`AiChatPanel.java:1196-1218`）顶部对所有类型先 `removeLoadingIndicator()`——USAGE 分支 MUST 在此之前早退：`if (update.getType() == Type.USAGE) { updateRingFromPayload(update); return; }`。不早退 = 每次用量通知清掉 loading 武装 + `default` 分支把空 message 渲染成聊天行，双回归。
3. **复位单点**：`advanceRenderEpoch()`（`AiChatPanel.java:995-998`，/new、"+"、关闭整合清空、远程 /new 四处共用）追加 `contextRing.reset()`——迟到旧回合 usage 通知被代数过滤挡在 dispatch 层之外（USAGE 乘 PROGRESS 事件，与 THINKING 等同过滤路径），复位后无渗入。

### D6 发射点与既有消费者核对

- `TurnContentAccumulator.onProgress` 白名单 `INTERMEDIATE_RESPONSE`（`TurnContentAccumulator.java:31`）——USAGE 天然 no-op，IPC partialContent 零污染。
- `ProgressCallbackHookAdapter` 新增 `onUsage` override → `publish`；`callback == null` 早退对齐既有方法。
- `AgentRunner` 在 `context.setUsage(respUsage)` 同点调用 `state.hook.onUsage(respUsage, context)`（`state.hook` null 检查对齐既有调用点写法）。
- 子代理（`SubagentManager` 无 ProgressCallback）与记忆整合（`MemoryConsolidator` 直调 AiService、不经 AgentRunner 回合）不产生 USAGE 通知——指示器语义 = 主会话上下文，正确排除。

## Risks / Trade-offs

- [USAGE 类型与面板分支非同批交付 → 中间态清 loading + 空行渲染] → tasks 把「类型落地」与「面板早退分支」钉在同一任务组、同批过门禁。
- [`prompt_tokens` 滞后一次迭代（当次工具结果未计）] → 接受：显示语义即"最近一次调用"，回合内逐次收敛，无需求方要求预测性数值。
- [Anthropic 未来启用 prompt caching 后 `input_tokens` 低估（cache_read 未计）] → 记为已知局限；届时在 Service 层把 `cache_read_input_tokens`/`cache_creation_input_tokens` 并入 usage map 即可（载荷是 map，向前兼容）。
- [OpenAI 兼容网关 prompt_tokens 口径不一（可能含缓存、可能超窗口配置）] → 弧 clamp 100%，tooltip 展示真实数值（用户可自行判断口径）。
- [高水位阈值/色值写死为常量] → spec 只钉"可区分"，调参不动 spec。

## Migration Plan

纯加法变更，无数据迁移、无配置迁移、无 wire 变化。回滚 = revert 单次提交（组件、类型、接线同批可独立 revert，无跨变更依赖）。
