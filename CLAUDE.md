# CLAUDE.md

此文件为 Claude Code (claude.ai/code) 在处理本仓库代码时提供指导。

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.


## 项目概述

Gitee Ai (JMeter Agent) 是一个 JMeter 插件，提供 AI Agent 驱动的聊天界面，用于创建、优化和排查 JMeter 测试计划。它集成了 Claude (Anthropic)、OpenAI 和 Ollama AI 模型，并实现了完整的 Agent Loop 架构（工具调用、技能系统、上下文管理、会话管理等）。

## 构建和测试命令

**构建项目：**
```bash
mvn clean package
```

**运行测试：**
```bash
mvn test
```

**运行特定测试：**
```bash
mvn test -Dtest=ClassNameTest
```

**安装到本地 JMeter（`jmeter.home` 读自 `JMETER_HOME` 环境变量，可用 `-Djmeter.home=...` 覆盖）：**
```bash
mvn clean install                    # 复制 jar/skills/templates/CLI 脚本，默认不启动 GUI
mvn clean install -DskipTests
mvn clean install -Djmeter.home=C:/path/to/apache-jmeter-5.6.3  # 未设 JMETER_HOME 时显式传路径
mvn clean install "-Dlaunch.gui=true"  # 同上 + 启动 JMeter GUI（仅 Windows；其他平台静默跳过）
```

> **前提**：pom.xml 中 `<jmeter.home>` 写为 `${env.JMETER_HOME}`，不再写死机器路径。未设置 `JMETER_HOME` 环境变量且未传 `-Djmeter.home` 时，`${jmeter.home}` 解析为空，antrun copy 的目标退化为项目根下的相对路径 `lib/ext`（静默复制到错处，不报错）。故**裸跑 `mvn install` 前须先配 `JMETER_HOME`**。JDK 相关写死也已移除：编译器/测试 JVM 均归零为 `JAVA_HOME`（当前 `D:\IDE\Java\jdk-17.0.8`），换 JDK 版本靠改 `JAVA_HOME`。

**跳过测试构建：**
```bash
mvn clean package -DskipTests
```

## 高层架构

插件采用分层架构，职责分离清晰：

### AI Agent 框架 (`org.gitee.jmeter.ai.agent`)
核心 Agent 执行引擎，实现工具调用的闭环：

- **AgentLoop** / **AgentLoopFactory** - Agent 主循环，驱动 LLM 调用 → 工具执行 → 结果反馈的迭代（回合生命周期状态收敛于 `agent.turn` 单表，见「回合对象」）
- **AgentConfig** - Agent 配置管理（模型、温度、最大轮次等）
- **GenerationSettings** - AI 生成参数的唯一来源

#### 回合事件流 (`agent.presenter`)
所有 UI 呈现的唯一通道（旧单槽 TurnPresenter 与 AgentSwingWorker 已删，见「关键设计模式」）：

- **TurnEvent** / **TurnHandle** / **TurnOrigin** / **CancelCause** / **TurnSubscriber** - 回合事件流 5 类型。`TurnEvent` 7 种 Kind（TURN_STARTED/PROGRESS/TURN_COMPLETED/TURN_CANCELLED/INJECTED/REJECTED_BUSY/COMMAND_RESULT）；`TurnOrigin` 分 LOCAL_PANEL/IPC_CLI/IPC_DELEGATED/REPUBLISH；`TurnHandle` 携进程唯一回合 id 与显示域元数据
- **订阅挂接**：订阅关系挂 `AgentLoopFactory` 静态表（`addTurnSubscriber` 记全局表并挂存活单例；模型切换换血 loop 后由 `createAgentLoop` 全量重挂，订阅不丢）；`AgentLoop.activeTurn(sessionKey)` 供面板懒创建时领养在跑 IPC 回合
- **线程契约**：回调线程不保证（EDT/ipc-worker/loop 线程/池化线程均可能）；订阅端（如 AiChatPanel）自投 EDT + 通知时代数快照，防 /new 后迟到事件渗入新会话

**4 处刻意 UX 差异（本地回合显示域，防未来「对齐旧行为」误修）**：① 竞态注入成回合时补画 You 行（旧版该消息从转录消失）；② busy 期本地命令补画 You 行（旧版只渲染结果行）；③ 空闲 `/new` 经完整回合短暂武装 loading+Stop 后自复位（旧版直接渲染不武装）；④ `/new` 回执渲染时机为事件驱动（busy 期 COMMAND_RESULT / 空闲期终态）。

**SILENT 显示域**：`CancelCause.SILENT`（关闭整合取消）仅抑制 LOCAL_PANEL 源回合的取消渲染；IPC 源回合照旧渲染 USER_STOP 回执行（关闭整合取消 IPC 回合时目标面板的终止反馈行不消失）。

#### 命令路由 (`agent/command`)
- **CommandRouter** - 将用户命令路由到对应处理器
- **BuiltinCommands** - 内置命令定义
- **CommandContext** - 命令执行上下文

#### 上下文管理 (`agent/context`)
- **ContextBuilder** - 构建发送给 LLM 的消息上下文
- **ContextWindowManager** - 管理上下文窗口大小，控制 token 用量

#### 会话管理 (`agent/session`)
- **Session** - 单次 Agent 会话
- **SessionManager** - 管理多个会话的生命周期（每实例会话模式只加载当前 instanceId 的 jsonl，不解析历史遗留/其他实例文件）

#### Agent 运行 (`agent/run`)
- **AgentRunner** - 执行 Agent 运行（同步方法，跑在调用方线程：主链路 = agent-loop 专用执行器线程，子代理 = subagent 池线程；`runAgentLoop` 经 `LoopState` + 分支函数分解）
- **AgentRunSpec** - 运行规格定义
- **AgentRunResult** - 运行结果

#### 回合对象 (`agent.turn`)
- **Turn** - 单回合全部生命周期状态的聚合（原散落 AgentLoop 6 张 per-turn map + 3 ThreadLocal + InjectionManager 路由槽的状态收敛为一对象；sessionKey/handle/callback/delegated/abortFlag/completionLatch 构造时定，queue/future/epoch/ownResetEpoch/runnerThread/closed 为保序武装的 volatile 后写字段；含 drain/drainBlocking 注入队列消费）
- **TurnRegistry** - 会话 → 在跑回合注册表（extends ConcurrentHashMap）；路由槽 = 条目 + `closed` 标志双生命周期：offer/closeRouting/cleanup/hasActiveRun 在 CHM bin 锁下原子，条目真正 remove 只在两处且均按值条件（`removeIfCurrent`，防误摘同 key 后继）：latch 释放点与 signalCancel 自我豁免步
- **InjectionItem** - 注入队列条目（text + announcement 标记）

#### Agent 模型 (`agent/model`)
- **Message** / **ToolCall** / **ToolResult** - LLM 交互消息模型
- **ToolDefinition** - 工具元数据定义
- **LLMResponse** / **AgentResponse** - LLM 响应封装
- **LlmCallOptions** - LLM 调用选项
- **MessageOptimizer** - 消息序列优化
- **ProgressUpdate** / **ToolEvent** - 进度和事件通知

#### Agent 钩子 (`agent/hooks`)
- **AgentHook** / **AgentHookContext** - Agent 生命周期钩子
- **ProgressCallbackHookAdapter** - 进度回调适配器

#### Agent 记忆 (`agent/memory`)
- **MemoryStore** - Agent 记忆存储（MEMORY.md 写路径带跨进程写锁 `lockLongTermMemory(aborted)`：`memory.lock` + OS 级 `FileLock` 覆盖 read→LLM→write 全程，双实例并发深度提炼时串行化防 lost-update。等锁为 **abort 感知 `tryLock()` 轮询**（非阻塞式 `lock()`：`distillSync` 路径在 commonPool 载体上 interrupt 不可达；内联整合线程虽可被 interrupt 命中，但阻塞式 `channel.lock()` 被 interrupt 会抛 `ClosedByInterruptException` 关闭通道；统一以 abort flag 为取消事实来源），每轮查 abort 谓词，被中止/中断返回 `null` = 未执行、不降级写盘；仅真实 IO 故障才 best-effort 降级无锁。`distillSync` 超时先置共享 flag 再 cancel。`MemoryConsolidator` 与 `save_memory` 工具共用）
- **MemoryConsolidator** - 跨会话记忆整合
- **CloseConsolidationCoordinator** - 关闭期记忆整合协调器（静默归档 HISTORY.md 的幂等守卫 + 深度提炼入口，供关闭对话框与 shutdown hook 共用）

#### Agent 技能 (`agent/skills`)
- **SkillsLoader** - 从文件系统加载技能
- **SkillInfo** / **SkillMetadata** - 技能元数据

> `agent/swing` 包（AgentSwingWorker）已删：本地回合显示换轨至 TurnEvent 流后 worker 不再有职责，面板更新统一由 AgentLoop 事件流驱动（见「回合事件流」）。

### 工具层 (`org.gitee.jmeter.ai.agent.tools`)

#### 工具基础设施
- **Tool** - 工具接口（含 `isConcurrencySafe()` 并行资格分类，默认 false=单例批内联串行；只读工具显式覆盖加入并行白名单）
- **AbstractTool** - 工具基类
- **ToolRegistry** / **JMeterToolRegistry** - 工具注册中心（`executeAsyncWithEvent` 派发时搬运 AgentRunContext + DelegationGuard 到池线程）
- **ValidationResult** - 工具参数校验结果

工具并发采用 Nanobot 式 `concurrency_safe` 分批（无用户开关，`AgentRunner` 按调用序分批：连续安全调用并行批、非安全单例批内联 run 线程）

#### JMeter 元素工具 (`tools/jmeter`)
- **AbstractJMeterElementTool** - JMeter 元素工具基类
- **CreateJMeterElementTool** - 创建新的 JMeter 元素
- **DeleteJMeterElementTool** - 删除 JMeter 元素
- **UpdateJMeterElementTool** - 更新现有元素的属性
- **BatchUpdateJMeterElementTool** - 批量更新多个 JMeter 元素
- **MoveJMeterElementTool** - 移动元素到不同父节点
- **CopyPasteJMeterElementTool** - 复制粘贴测试计划元素
- **GetTestPlanTreeTool** - 获取测试计划树结构
- **FindElementTool** - 查找测试计划中的元素
- **GetSelectedElementTool** - 获取当前选中的元素
- **QueryElementPropertiesTool** - 按属性查询 JMeter 组件
- **ToggleJMeterElementTool** - JMeter 组件启用/禁用/切换状态

#### JMeter 测试执行工具 (`tools/jmeter/execution`)
- **RunTestTool** - 运行 JMeter 测试
- **GetTestStatusTool** - 获取测试执行状态
- **GetTestResultsTool** - 获取测试结果
- **AgentResultCollector** - 测试结果收集器

#### JMeter 属性处理 (`tools/jmeter/property`)
- **SchemaBasedPropertyHandler** - 基于 schema 将参数应用到 JMeter 元素

#### JMeter 工具类 (`tools/jmeter/utils`)
- **JMeterTreeUtils** - JMeter 树操作工具

#### 文件系统工具 (`tools/filesystem`)
- **AbstractFsTool** - 文件系统工具基类
- **ReadFileTool** - 读取文件内容
- **WriteFileTool** - 写入文件
- **EditFileTool** - 编辑文件
- **ListDirTool** - 列出目录内容

#### Web 工具 (`tools/web`)
- **AbstractWebTool** - Web 工具基类
- **WebFetchTool** - 获取网页内容
- **WebSearchTool** - 搜索互联网

#### 执行工具 (`tools/exec`)
- **ExecTool** - 执行 shell 命令

#### 跨实例协作工具 (`tools/ipc`)
仅当 `jmeter.ai.ipc.enabled=true` 时注册（IPC 提供传输通道；关闭则不注册）。
- **ListInstancesTool** - 列出本机存活实例（instanceId/pid/port/打开的 jmx/启动时间），标注自身
- **DelegateToInstanceTool** - 把任务委派给持有某 jmx 或某 instanceId 的对端实例，阻塞等待其 Agent 回合回复；载荷带 `[delegated-from …]` 来源前缀，被委派回合内再委派被 DelegationGuard 硬阻断（深度 1，防跨实例 ping-pong）

### 服务层 (`org.gitee.jmeter.ai.service`)
- **AiService** 接口定义了 AI 提供者的契约
- **ClaudeService** - 使用 anthropic-java SDK 集成 Anthropic Claude
- **OpenAiService** - 使用 openai-java SDK 集成 OpenAI GPT

#### 服务提供者 (`service/provider`)
- **AiServiceFactory** - AI 服务工厂
- **OpenAICompatibleProvider** - OpenAI 兼容提供者
- **ProviderRegistry** - 提供者注册中心
- **ProviderSpec** - 提供者规格定义

所有服务通过 JMeter 属性配置（`anthropic.api.key`、`openai.api.key` 等），通过 **AiConfig** 工具类访问。

### 链路追踪 (`org.gitee.jmeter.ai.tracing`)
- **LangSmithClient** - LangSmith API 客户端
- **TracedAiService** - 带追踪的 AiService 包装器

### 多实例协调与会话隔离

同时打开多个 JMeter 实例时，本插件保证：每个实例用独立的会话文件（互不串扰上下文），关闭时自动归档记忆，且实例间可互相发现并委派任务（共享 IPC 通道）。详见 `openspec/changes/multi-instance-session-ipc/`。

#### 实例上下文 (`org.gitee.jmeter.ai.instance`)
- **InstanceContext** - 进程单例，持有本次启动的 `instanceId`（`{pid}-{startedAtMs}`），是每实例会话键、注册表锚点与委派寻址的唯一来源（`currentSessionKey()` 受 `agent.session.per-instance` 门控，false 回退全局 legacy 键）
- **LegacySessionMigrator** - 启动期 best-effort 把遗留 `jmeter-ai-chat.jsonl` 归档进共享 HISTORY.md
- **SessionReaper** - 启动期 best-effort 回收失活且超 TTL 的孤立 `{instanceId}.jsonl`（经注册表 PID+TCP 双确认失活、且防 PID 复用误判）

#### IPC 通道 (`org.gitee.jmeter.ai.ipc`)
- **InstanceRegistry** - 端口文件（`port-{pid}.json`：pid/port/token/startedAt/bind/instanceId/jmxPath）的读写与实例发现；**零 JMeter 依赖**（CLI 复用），TCP+PID 双确认探活与残留自清理
- **IpcServer** - 内嵌 com.sun.net.httpserver loopback 服务，token 鉴权；`/agent` 处理器在自身超时后 `cancelActiveTask` 自取消并回 504
- **IpcClient** - 进程内可复用的 JMeter-free 传输客户端，供委派工具与 CLI 共用同一传输
- **IpcServer** 还在 `Load`/`LoadRecentProject`/`Save`/`Close` 的 post-action 监听里把当前 jmx 路径原子写回本实例端口文件（供对端发现）

> **前提**：跨实例协作（list_instances / delegate_to_instance）必须 `jmeter.ai.ipc.enabled=true`——IPC 关闭时无传输通道，协作工具不注册。会话隔离与关闭记忆整合独立于 IPC（始终开启）。

### GUI 层 (`org.gitee.jmeter.ai.gui`)
- **AI** - AI 集成入口
- **AiChatPanel** - 主 Swing 面板，包含聊天界面、模型选择器和元素建议（支持 Shift+Enter 换行、拖拽调整区域高度）；实现 `TurnSubscriber`——本地/IPC/委派回合的呈现统一由 AgentLoop 回合事件流驱动（唯一显示通道），自投 EDT + 通知时代数快照
- **AiMenuItem** - 切换聊天面板的菜单项和工具栏按钮
- **AiMenuCreator** - 创建 AI 相关菜单
- **MessageProcessor** - 处理 markdown 渲染和消息显示（支持 reasoningContent 结构化思考内容展示）
- **ComponentFinder** - 查找 JMeter 组件
- **CloseConsolidationDialog** - 关闭期记忆整合交互对话框（EDT 模态：告知未整合消息数 N（仅 user/assistant 口径），选"是"先 `cancelActiveTask` 停掉在跑回合、再经 SwingWorker 后台深度提炼并回传进度，提供"Skip & Exit"逃生按钮；N=0/测试运行中/开关关闭时不弹）。被取消的整合回合经共享 abort flag 写盘前放弃落盘（不会覆盖提炼结果）

### 智能提示 (`org.gitee.jmeter.ai.intellisense`)
- **CommandIntellisenseProvider** - 提供命令建议（/new、/status、/help）
- **InputBoxIntellisense** - 将弹出补全附加到聊天输入框
- **IntellisensePopup** - 补全弹出框显示

### 使用统计 (`org.gitee.jmeter.ai.usage`)
- **AnthropicUsage** - Anthropic 用量统计
- **OpenAiUsage** - OpenAI 用量统计

### 工具类 (`org.gitee.jmeter.ai.utils`)
- **AiConfig** - AI 配置工具类
- **SystemPrompt** - 系统提示模板
- **TextUtils** - 文本处理工具
- **VersionUtils** - 版本比较工具
- **WorkspaceInitializer** - 工作空间初始化
- **JMeterElementManager** - JMeter 元素管理

### 组件参数校验系统 (`org.gitee.jmeter.ai.agent.validation`)
- **ComponentSchema** - 组件 schema 数据模型
- **ComponentSchemaLoader** - 从 YAML schema 文件加载组件校验规则
- **ComponentValidator** - 根据 schema 校验组件参数（必填项、类型、枚举值、范围、正则表达式等）

**Schema 文件位置：** `src/main/jmeter-agent/skills/jmeter/references/`

每个 JMeter 组件类型对应一个 `{ComponentName}.schema.yaml` 文件，定义了：
- 组件类型（`type`）和显示名称（`name`）
- 组件描述（`description`）
- 属性列表（`properties`），包括：
  - 属性名（`name`）
  - 数据类型（`type`）：String, Integer, Boolean, Number, Object, Array
  - 是否必填（`required`）
  - 默认值（`default`）
  - 枚举值限制（`enum`）
  - 数值范围（`min`/`max`）
  - 正则表达式模式（`pattern`）
  - 嵌套属性（`properties`）和集合项属性（`itemProperties`）

**部署位置：** 构建时复制到 `{JMETER_HOME}/bin/jmeter-agent/skills/jmeter/references/`

**Schema 目录结构：**（按**来源**分 3 个顶层目录，其下按**功能类别**分子目录；`ComponentSchemaLoader` 递归扫描，文件放在哪个 `source` 目录不影响加载，仅用于区分原生与第三方）
```
references/
├── native/              # Apache JMeter 原生 (50) — org.apache.jmeter.*，随 JMeter 自带
│   ├── assertions/      (6)  Response/JSONPath/XPath/JSR223/BeanShell/XML Assertion
│   ├── configuration/   (6)  CSVDataSet/CookieManager/HTTPRequestDefaults/HeaderManager/JDBCConnectionConfiguration/UserDefinedVariables
│   ├── controllers/     (10) Loop/If/While/Foreach/Transaction/Simple/OnceOnly/Random/Module/Include
│   ├── listeners/       (4)  ViewResultsTree/SummaryReport/AggregateReport/BackendListener
│   ├── post-processors/ (6)  Regex/JSON/Html/JSR223/BeanShell/Debug PostProcessor
│   ├── pre-processors/  (3)  JSR223/BeanShell PreProcessor, UserParameters
│   ├── samplers/        (7)  HTTPRequest/JDBC/JSR223/BeanShell/FlowControlAction/Debug/OSProcess
│   ├── test-fragments/  (1)  TestFragmentController
│   ├── thread-group/    (3)  ThreadGroup/setUpThreadGroup/tearDownThreadGroup
│   └── timers/          (4)  Constant/UniformRandom/ConstantThroughput/PreciseThroughput
├── gitee-qa/            # Gitee QA 扩展 (19) — com.gitee.qa.jmeter.*，本生态自研
│   ├── assertions/      (3)  JsonAuto/Value/Variable Assertion
│   ├── configuration/   (4)  ExcelDataConfig/S3ConfigElement/HTTPUDConfigElement/HTTPUDIncludeConfig
│   ├── controllers/     (5)  Case/DoWhile/VariableLoop/Probability/ParameterInclude
│   ├── samplers/        (3)  Git/HTTPUD/S3 Sampler
│   ├── test-fragments/  (1)  ParameterTestFragmentController
│   └── thread-group/    (3)  PerforAuto/PerforAutoStepping/PerforAutoUltimate ThreadGroup
├── third-party/         # 外部第三方插件 (4) — 需单独安装
│   ├── samplers/        (2)  SSHCommandSampler/SSHSFTPSampler (SSH Sampler 插件)
│   └── thread-group/    (2)  SteppingThreadGroup/UltimateThreadGroup (jmeter-plugins「Custom Thread Groups」)
└── functions/           # JMeter 函数参考 (58 个 .md 文档)
```

**来源判定**：`testClass` 为 `org.apache.jmeter.*` 且真实存在于 JMeter 源码 → `native`；为 `com.gitee.qa.jmeter.*` → `gitee-qa`；其余（如 jmeter-plugins `kg.apc.*`、SSH Sampler）→ `third-party`。

### 技能系统 (`src/main/jmeter-agent/skills/`)
Agent 的技能通过文件系统组织，每个技能包含一个 `SKILL.md` 和可选的 `references/` 目录：

- **jmeter/** - JMeter 核心技能，包含 73 个组件 schema 和 133 个参考文档（含 58 个 JMeter 函数文档）
  - `SKILL.md` - 主技能定义
  - `references/functions/` - 58 个 JMeter 函数参考文档（覆盖全部内置函数和自定义扩展函数）
  - `references/standards.md` - JMeter 编写规范
  - `references/bad-cases.md` - 常见反模式
  - 每个组件目录包含 `{Name}.md`（使用文档）和 `{Name}.schema.yaml`（参数 schema）
- **api-autotest/** - API 自动化测试技能（针对 Gitee-Scan OpenAPI）
- **memory/** - Agent 记忆管理技能
- **skill-creator/** - 技能创建工具，包含模板和工作流模式

### Agent 模板 (`src/main/resources/templates/`)
- `AGENTS.md` - Agent 系统指令
- `SOUL.md` - Agent 人格/上下文定义
- `TOOLS.md` - 工具使用指南
- `USER.md` - 用户交互指南
- `memory/MEMORY.md` - 记忆系统文档

## 测试结构 (`src/test/java/org/gitee/jmeter/ai/`)

- **agent/validation/** - Schema 加载和类型校验测试
  - `ComponentSchemaTypeTest` / `SchemaLoaderTest` / `YamlDebugTest`
- **agent/context/** - 上下文管理测试
  - `ContextWindowManagerTest`
- **agent/testsupport/** - 回合事件流测试公共脚手架（跨测试文件共享）
  - `GatedScriptAiService`（脚本化/门控 fake，Stop/Reset 钉子经 `InterruptStrategy.HANG_UNTIL_RELEASED`）/ `RecordingSubscriber` / `NoopTool` / `AwaitUtil`
- **intellisense/** - 智能提示测试
  - `CommandIntellisenseProviderTest` / `InputBoxIntellisenseTest` / `IntellisensePopupTest`
- **utils/** - 工具类测试
  - `VersionUtilsTest`

## 关键设计模式

- **策略模式**：AiService 接口允许在 Claude、OpenAI 和 Ollama 之间切换
- **观察者模式**：树选择监听器触发 JSR223 编辑器的上下文菜单更新
- **回合事件流模式**：AgentLoop 经 TurnSubscriber 多订阅者事件流驱动所有 UI 呈现（唯一显示通道）。AI 调用跑在 loop 专属 executor 上避免阻塞 UI——旧 SwingWorker（AgentSwingWorker）路线已删：本地/IPC/委派回合换轨至事件流后 worker 不再有职责，UI 更新由订阅端自投 EDT
- **工厂模式**：AiServiceFactory / AgentLoopFactory 创建服务和 Agent 实例
- **Agent Loop 模式**：AgentLoop 驱动 LLM 调用 → 工具执行 → 结果反馈的迭代循环
- **注册中心模式**：ToolRegistry / ProviderRegistry 管理工具和提供者的注册与查找

## 关键依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| anthropic-java | 2.18.0 | Anthropic Claude SDK |
| openai-java | 4.43.0 | OpenAI GPT SDK（ReasoningEffort 自 4.42.0 起含 MAX） |
| langsmith-java | 0.1.0-alpha.24 | LangSmith 链路追踪 |
| ApacheJMeter_core | 5.6.3 | JMeter 核心 |
| snakeyaml | 2.2 | YAML 解析 |
| JUnit 5 | 5.10.1 | 单元测试 |
| Mockito | 5.8.0 | Mock 测试 |

## 配置

所有配置通过 JMeter 属性完成（通常在 `user.properties` 或 `jmeter.properties` 中）：

- `anthropic.api.key` / `openai.api.key` - API 凭证
- `jmeter.ai.default.provider` / `jmeter.ai.default.model` - 全局默认提供者与模型选择（读于 `AiConfig.java:47-48`；无 per-provider 默认模型属性）
- `jmeter.ai.temperature` - 响应创造力 (0.0-1.0)
- `jmeter.ai.max.history.size` - 对话历史限制

**多实例会话与协调（会话隔离、关闭整合与 IPC 均默认开启）：** 每个实例用独立会话文件（`sessions/{instanceId}.jsonl`），关闭时把未整合消息归档进共享 HISTORY.md（可选深度提炼写 MEMORY.md 供他实例系统提示可见），并通过 IPC 让实例间互相发现打开的 jmx 并委派任务。关闭记忆整合与跨实例协作无独立开关（整合始终开启，协作随 IPC 开关），详见 `openspec/changes/multi-instance-session-ipc/`。

- `agent.session.per-instance` - 每实例独立会话文件（默认 `true`；false 回退全局 `jmeter-ai-chat` 键）
- `agent.memory.consolidate-on-exit.timeout.ms` - 关闭整合"深度提炼"有界超时毫秒（默认 `120000`）
- `agent.session.reap.ttl.days` - 启动期回收孤立会话文件的存活 TTL 天数（默认 `7`）
- `jmeter.ai.ipc.enabled` - 内嵌 IPC HTTP 服务开关（默认 `true`，仅 loopback + token 鉴权；**多实例协作依赖此开关**）
- `jmeter.ai.ipc.bind` / `jmeter.ai.ipc.port` / `jmeter.ai.ipc.token` - 绑定地址（仅 loopback）/端口（0 自动分配）/鉴权 token（空则随机生成）
- `jmeter.ai.ipc.agent.timeout.ms` - `/agent` 路由同步等待超时毫秒（默认 `120000`）

**异步子代理（SubAgent，默认关闭）：** 主代理通过 `spawn` 工具把只读分析任务委派给后台子代理（隔离的只读工具集 + 临时会话，不污染主会话），结果回合内回注。详见 `openspec/changes/add-async-subagent/`。

- `agent.subagent.enabled` - 总开关（默认 `false`；关闭时不注册 `spawn`/`subagent_status`）
- `agent.subagent.max.concurrent` - 每主会话并发子代理上限（默认 `1`）
- `agent.subagent.max.iterations` - 单次子代理工具迭代上限（默认 `50`）
- `agent.subagent.drain.timeout.seconds` - 主回合等待子代理结果的阻塞时长秒数（默认 `120`，硬上限 `300`）
- `agent.subagent.status.retention.seconds` - 完成态状态可查询保留 TTL 秒数（默认 `60`；晚到/未投递结果保留此窗口后被回收；0 = 不按时长回收）
- `agent.subagent.status.max.completed` - 每会话保留的完成态状态上限（默认 `10`；超出按最旧淘汰；0 = 不按数量淘汰）

完整可配置项见 `jmeter-ai-sample.properties`。

**GUI 运行结果采集（默认开启）：** 一个全局 JMeter `Start.class` 预监听器在用户点击 GUI Run 按钮（或 Run Thread Group）发起的本地运行前注入结果收集器，使 `get_test_status` / `get_test_results` 对用户发起的运行也返回实时数据（并在 `get_test_status` 显示运行来源 USER/AGENT）。`run_test` 的采集不受影响、开关关闭时仍工作；`Save.class` 预监听器始终剥离收集器节点以防泄漏进 `.jmx`。详见 `openspec/changes/capture-gui-run-results/`。

- `agent.runcapture.enabled` - 仅门控 `Start.class` 预监听器注册（默认 `true`）；关闭时 GUI 运行不采集，但 `run_test` 仍采集

## 开发参考

**JMeter 源码路径：**
```
D:\WorkHome\git\github\jmeter-5.6.3
```

关键类参考：
- **HTTPArgument** - `protocol/http/src/main/java/org/apache/jmeter/protocol/http/util/HTTPArgument.java`
- **Header** - `protocol/http/src/main/java/org/apache/jmeter/protocol/http/control/Header.java`
- **HeaderManager** - `protocol/http/src/main/java/org/apache/jmeter/protocol/http/control/HeaderManager.java`
- **HTTPSamplerProxy** - `protocol/http/src/main/java/org/apache/jmeter/protocol/http/sampler/HTTPSamplerProxy.java`

**注意事项：**
- 由于 `ApacheJMeter_http` 被排除在编译依赖之外，HTTP 相关类需要使用反射访问
- 插件运行时，JMeter 会提供完整的类路径

## JMeter 集成点

- **GuiPackage** - 访问 JMeter 的 GUI 上下文和树结构
- **JMeterTreeNode** - 导航和操作测试计划元素
- **TestElement** - 所有 JMeter 组件的基类

## 重要说明

- 插件使用 JMeter 5.6.3 作为依赖项（ApacheJMeter_core）
- **ELEMENT_CLASS_MAP** 注册了 172 个 JMeter 组件类映射（涵盖采样器、线程组、断言、定时器、前置/后置处理器、配置元件、监听器、控制器、测试片段等）
- 73 个组件拥有完整的参考文档和参数 Schema（覆盖 10 大类别：控制器 15、采样器 12、断言 9、线程组 8、配置元件 10、后置处理器 6、前置处理器 3、定时器 4、监听器 4、测试片段 2）
- 对话历史受到限制以防止 token 耗尽（默认：10 条消息）
- 系统提示仅在第一条消息时发送以节省 token
- 下拉菜单中的模型 ID 带有前缀（例如 "openai:gpt-4o"、"ollama:llama3.1"）
- **GenerationSettings** 是 LLM 默认参数的唯一来源
- Agent 通过 SkillsLoader 从文件系统动态加载技能
- 工具注册通过 JMeterToolRegistry 统一管理
- 聊天输入框支持 Shift+Enter 换行、拖拽调整消息区域与输入区域高度
- 支持 reasoningContent 结构化思考内容展示

## 添加新 JMeter 组件 Checklist

添加一个新的 JMeter 组件需要修改以下 5 处：

### 1. 新建参考文档（英文）

在 `src/main/jmeter-agent/skills/jmeter/references/{source}/{category}/` 下新建：

- `{ComponentName}.md` — 使用文档（描述、参数、示例、最佳实践、注意事项）
- `{ComponentName}.schema.yaml` — 参数 schema 定义（类型、必填、枚举、范围等）

`{source}` 按来源选：`native`（Apache JMeter 原生）/ `gitee-qa`（Gitee QA 扩展）/ `third-party`（外部第三方插件）。

`{category}` 对应子目录：`controllers`、`samplers`、`assertions`、`thread-group`、`timers`、`configuration`、`pre-processors`、`post-processors`、`listeners`、`test-fragments`

### 2. 更新 SKILL.md 组件索引

**文件：** `src/main/jmeter-agent/skills/jmeter/SKILL.md`

在对应的 Component Reference 表格中追加一行，包含 `elementType`、Description、Docs 链接和 Schema 链接。

### 3. 更新 JMeterElementManager.java（2 处）

**文件：** `src/main/java/org/gitee/jmeter/ai/utils/JMeterElementManager.java`

1. **`ELEMENT_CLASS_MAP`** — 添加 `elementType` → (模型类全限定名, GUI 类全限定名) 的映射
2. **`getDefaultNameForElement`** switch — 添加 `case "elementType":` 返回默认显示名称

### 总结

| 步骤 | 操作 | 文件 |
|------|------|------|
| 新建 | 使用文档 | `references/{source}/{category}/{Name}.md` |
| 新建 | 参数 schema | `references/{source}/{category}/{Name}.schema.yaml` |
| 追加 | 组件索引表 | `skills/jmeter/SKILL.md` |
| 追加 | 类映射 | `JMeterElementManager.java` → `ELEMENT_CLASS_MAP` |
| 追加 | 默认名称 | `JMeterElementManager.java` → `getDefaultNameForElement` |

前三步是 AI Agent 运行时所需的（技能文档和 schema 校验），后两步是插件代码层识别和创建组件所必需的。
