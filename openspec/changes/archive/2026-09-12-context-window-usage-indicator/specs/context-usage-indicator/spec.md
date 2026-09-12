# context-usage-indicator Delta

## Purpose

聊天面板以常驻可视化指示器实时展示会话上下文窗口用量：token usage 经回合事件流在每次 LLM 调用后通知（回合内多次刷新），面板以空心环形进度呈现占比并在悬浮时展示已用/总量明细；会话重置同步复位；对既有进度消费者（IPC 累积器、聊天文本渲染域、加载指示）保持隔离。

## ADDED Requirements

### Requirement: usage 实时通知

Agent SHALL 在每次 LLM 调用返回并携带非空 usage 时，经该回合的进度事件流（PROGRESS 事件 + usage 载荷类型）通知输入/输出 token 数；通知 MUST 在单次 LLM 调用返回后立即发出，MUST NOT 等待回合结束。usage 为空或缺失的响应 MUST NOT 产生通知。通知 MUST 复用既有 PROGRESS 事件的顺序、回合身份与过滤语义，MUST NOT 新增独立的事件种类或旁路通道。

#### Scenario: 回合内逐次刷新

- **WHEN** 一个回合内发生 3 次 LLM 调用（含工具迭代）
- **THEN** 订阅者在该回合内收到 3 条 usage 通知，末条反映最终一次调用的输入规模
- **AND** 通知不晚于该回合终态事件到达

#### Scenario: 无 usage 载荷静默跳过

- **WHEN** 某次 LLM 调用的响应不含 usage（字段缺失或为空）
- **THEN** 该次调用不产生 usage 通知，既有上下文中的用量值保持不变

#### Scenario: 与既有进度消费者隔离

- **WHEN** usage 通知到达 IPC 回合的文本累积器
- **THEN** 累积结果不受影响（usage 不是可累积的助手内容）

- **WHEN** usage 通知到达面板的进度渲染路径
- **THEN** 不在聊天转录渲染任何文本行，也不清除该回合的加载指示（loading 武装态只由回合生命周期的既有规则管理）

### Requirement: 环形指示器渲染

面板 SHALL 在模型选择器右侧常驻一个空心环形指示器：完整底环 + 按占比的进度弧；占比 = 已用 tokens ÷ 上下文窗口 tokens，超过 100% 时弧 MUST 封满（clamp）。分子 SHALL 为最近一次 LLM 调用的输入 tokens（当前会话、跨回合保留至下一次调用覆盖）；分母 SHALL 为配置的上下文窗口 tokens 上限（与上下文裁剪预算同源）。新会话首次 LLM 调用前指示器 SHALL 显示为空（0）。指示器更新 MUST NOT 扰动 JMeter 编辑器的焦点与单元格编辑状态（更新走重绘、不走布局重算）。当占比越过高水位阈值时，进度弧 SHALL 呈现与常规态可区分的警示形态。

#### Scenario: 占比随调用刷新

- **WHEN** 最近一次 LLM 调用的输入 tokens 为 t、窗口配置为 T
- **THEN** 环形进度弧覆盖比例约为 t/T，随回合内每次调用逐步增长

#### Scenario: 超限封满

- **WHEN** 输入 tokens 超过窗口配置（如网关口径差异）
- **THEN** 弧封满整环而非绘制越界

#### Scenario: 高水位警示形态

- **WHEN** 占比越过预设高水位
- **THEN** 进度弧颜色/形态与常规态可视觉区分

#### Scenario: 更新不扰动编辑器

- **WHEN** 指示器在 JMeter 树表格处于单元格编辑态时更新
- **THEN** 编辑态不被取消（无布局重算传播）

### Requirement: 悬浮明细

鼠标悬浮于指示器时 SHALL 显示明细 tooltip：已用 tokens、总窗口 tokens 与百分比（如 `Context: 12k / 64k (18%)`，千位以上 k 记法，与 `/status` 文案口径一致）。tooltip 内容 MUST 随用量更新同步刷新。

#### Scenario: 悬浮显示已用与总量

- **WHEN** 已用 12345 tokens、窗口 65536、鼠标悬浮指示器
- **THEN** tooltip 呈现已用值、总量与百分比三者

#### Scenario: tooltip 随更新刷新

- **WHEN** 用量通知更新后再次悬浮
- **THEN** tooltip 反映最新一次调用的数值

### Requirement: 会话重置复位

会话重置（本地面板 `/new` 或 "+"、关闭整合清空、远程 `/new` 经命令通道）SHALL 将指示器复位为空（0）并清空悬浮明细中的已用值；复位 MUST 与聊天转录清空同点发生，迟到回合的 usage 通知 MUST NOT 渗入复位后的指示器。

#### Scenario: 本地 /new 复位

- **WHEN** 用户执行 `/new` 或点击 "+"
- **THEN** 指示器归零，与聊天区清空同步

#### Scenario: 远程 /new 同步复位

- **WHEN** 对端实例经 IPC 在本实例执行 `/new` 清空会话
- **THEN** 本地面板指示器同步归零

#### Scenario: 旧会话迟到 usage 不渗入

- **WHEN** 会话重置后，被中止的旧回合仍有迟到的 usage 通知到达
- **THEN** 复位后的指示器不受该通知影响

### Requirement: 显示域跟随回合事件流

指示器更新 MUST 由回合事件流驱动，对本地提交、IPC 委派、CLI 直连在本实例运行的回合 SHALL 以同一口径刷新；MUST NOT 采用独立轮询或定时器通道。

#### Scenario: 委派回合照常刷新

- **WHEN** 对端实例把任务委派到本实例、回合在本实例会话上运行
- **THEN** 本地面板指示器在该回合每次 LLM 调用后照常刷新，与本地回合无差别
