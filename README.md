# Gitee Ai - JMeter Agent

[English](README_en.md) | 中文

Gitee Ai 是一个 JMeter AI Agent 插件，通过 Agent Loop 架构驱动 LLM 调用、工具执行与结果反馈的迭代循环，在 JMeter 中实现智能化的测试计划创建、优化和调试。

![Gitee Ai](./images/JMeter-Agent-Demo.gif)

## 核心特性

- **Agent Loop 架构** — LLM 调用 → 工具执行 → 结果反馈的完整迭代循环，支持多轮工具调用完成复杂任务
- **30 个内置 Agent 工具** — 覆盖 JMeter 元素增删改查（含批量操作与启用/禁用）、JMX 解析、测试执行、跨实例协作、文件系统、Web 搜索/抓取、命令执行等场景
- **技能系统** — 从文件系统动态加载技能模块，内置 JMeter 专业知识（73 组件参考文档、58 个函数参考）
- **8 个 AI 提供者** — 支持 Anthropic Claude、OpenAI、DeepSeek、智谱 GLM、Moonshot Kimi、MiniMax、LangCat、Ollama
- **组件 Schema 校验** — 73 个 YAML Schema 文件，为 JMeter 组件参数提供类型、必填、枚举、范围等校验
- **记忆系统** — 双层记忆架构（长期记忆 + 事件历史），支持跨会话记忆整合
- **选中上下文联动** — 聊天面板实时展示当前 JMeter 元素与焦点控件，并可一键注入到 AI 上下文
- **安全控制** — 文件访问白名单、SSRF 防护、危险命令拦截
- **链路追踪** — 可选 LangSmith 集成，提供 LLM 调用追踪与监控

## 架构概览

```
用户消息
  ↓
CommandRouter（命令路由）
  ↓
AgentLoop（主循环）
  ├── ContextBuilder（构建上下文）
  ├── LLM API 调用（Claude / OpenAI / Ollama / ...）
  ├── ToolRegistry（工具注册中心）
  │   ├── JMeter Element Tools（元素 CRUD）
  │   ├── Test Execution Tools（测试运行/状态/结果）
  │   ├── Filesystem Tools（文件读写编辑）
  │   ├── Web Tools（搜索/抓取）
  │   └── Exec Tool（命令执行）
  ├── SkillsLoader（技能加载）
  ├── MemoryStore（记忆存储）
  └── SessionManager（会话管理）
  ↓
响应输出到 Chat UI
```

**工具调用流程：** LLM 决定调用工具 → ToolRegistry 查找并执行 → SchemaBasedPropertyHandler 校验参数 → 结果反馈给 LLM → 继续迭代或返回最终响应

## 安装

### 环境要求

- **JMeter** 5.6.3
- **JDK** 17 或更高版本

### 手动安装

1. 将 `jmeter-agent-xxx.jar` jar包放到 `jmeter/lib/ext` 目录下
2. 将 `src/main/jmeter-agent/` 目录下的所有内容复制到 `jmeter/bin/jmeter-agent/` 目录下（包含技能文件、模板等）
3. 将 `jmeter-ai-sample.properties` 的内容追加到 `jmeter/bin/user.properties` 中，并按需修改配置

## 快速开始

1. **配置 API Key** — 在 `user.properties` 中设置你的 AI 提供者密钥，例如：
   ```properties
   # 默认提供者为 deepseek，配置其 API Key 即可直接使用
   deepseek.api.key=your-api-key

   # 使用其他提供者时需同时指定 jmeter.ai.default.provider，例如 Anthropic Claude：
   anthropic.api.key=your-api-key
   jmeter.ai.default.provider=anthropic
   ```
2. **打开聊天面板** — 点击菜单栏 **AI** 菜单或工具栏 AI 按钮（快捷键 `Alt+V`）
3. **开始对话** — 直接描述你的需求，例如"创建一个包含 10 个线程的线程组，发送 GET 请求到 http://example.com"

## Agent 工具

除子代理工具（默认关闭）外，以下工具在默认配置下均注册启用；各分组可分别通过配置开关关闭（见[工具配置](#工具配置)）。

### JMeter 元素工具

| 工具名 | 说明 |
|--------|------|
| `create_jmeter_element` | 创建 JMeter 元素（线程组、采样器、控制器、断言、定时器等） |
| `update_jmeter_element` | 更新已有元素的属性 |
| `batch_update_jmeter_elements` | 批量更新同类型多个元素的属性（一次校验、一次刷新 GUI） |
| `delete_jmeter_element` | 删除指定元素（不可删除 TestPlan 根节点） |
| `batch_delete_jmeter_elements` | 批量删除多个元素（删除前统一校验） |
| `move_jmeter_element` | 移动元素到不同父节点，支持精确位置控制（first / last / before:<id> / after:<id>） |
| `batch_move_jmeter_elements` | 批量移动多个元素到同一父节点的同一位置 |
| `copy_paste_jmeter_element` | 复制粘贴测试计划元素 |
| `toggle_jmeter_element` | 启用、禁用或切换元素状态（禁用的元素在测试执行中被跳过） |
| `batch_toggle_jmeter_elements` | 批量启用、禁用或切换多个元素状态 |
| `get_test_plan_tree` | 获取完整测试计划树结构（JSON） |
| `get_selected_element` | 获取当前选中元素的详细信息 |
| `find_element` | 按名称、类型或路径查找元素 |
| `query_element_properties` | 按属性名/属性值查询测试计划中的元素 |

### JMX 与脚本工具

| 工具名 | 说明 |
|--------|------|
| `parse_jmx_file` | 解析外部 JMX 脚本文件（返回组件树，或按条件过滤查询元素） |
| `open_jmx_file` | 在当前 GUI 中打开外部 JMX 文件（默认替换当前计划，可合并加载） |
| `get_script_info` | 获取当前脚本与运行环境信息（脚本路径、保存状态、JMeter/JDK 版本、JMETER_HOME） |
| `get_log_panel_content` | 按行范围读取 JMeter LoggerPanel 日志面板内容 |

### 测试执行工具

| 工具名 | 说明 |
|--------|------|
| `run_test` | 启动、停止或关闭当前测试计划 |
| `get_test_status` | 获取测试执行状态（运行状态、线程进度、采样数） |
| `get_test_results` | 获取测试结果（响应时间、吞吐量、错误率） |

### 跨实例协作工具（随 IPC 注册，默认开启）

| 工具名 | 说明 |
|--------|------|
| `list_instances` | 列出本机所有存活的 JMeter AI 实例（含自身）及其打开的测试计划 |
| `delegate_to_instance` | 将任务委派给本机另一个 JMeter AI 实例执行，并阻塞等待其结果 |

### 文件系统工具（默认启用）

| 工具名 | 说明 |
|--------|------|
| `read_file` | 读取文件内容，支持分页 |
| `write_file` | 写入文件，自动创建目录 |
| `edit_file` | 编辑文件（字符串替换） |
| `list_dir` | 列出目录内容，支持递归 |

### Web 工具（默认启用）

| 工具名 | 说明 |
|--------|------|
| `web_search` | 搜索互联网（支持 Brave、Tavily、Jina 等搜索引擎） |
| `web_fetch` | 抓取网页内容（优先经 Jina Reader 提取正文，直连时转为 Markdown/纯文本） |

### 命令执行工具（默认启用）

| 工具名 | 说明 |
|--------|------|
| `exec` | 执行 Shell 命令，支持超时和工作目录配置 |

### 子代理工具（默认关闭）

设置 `agent.subagent.enabled=true` 后注册（配置见下文「异步子代理（Subagent）」）。

| 工具名 | 说明 |
|--------|------|
| `spawn` | 将自包含的只读分析任务委派给后台子代理 |
| `subagent_status` | 查询子代理的进度与结果 |

## 命令

### / 命令

斜杠命令，用于会话管理：

| 命令 | 说明 |
|------|------|
| `/new` | 开始新对话（清空当前会话） |
| `/status` | 显示 Bot 状态（版本、模型、Token 用量、会话信息） |
| `/help` | 显示可用命令列表 |

### 命令行客户端 (jmeter-cli)

除聊天面板外，还可通过 **jmeter-cli** 命令行驱动正在运行的 JMeter GUI 实例（经 loopback HTTP/IPC），对测试计划做组件增删改查，或向 AI Agent 推消息。CLI 参数与底层工具 schema **1:1 同名**。IPC 服务默认开启（仅 loopback + token 鉴权），如需关闭设 `jmeter.ai.ipc.enabled=false`。

```bash

# 常用命令(全局选项:--pid --token --json --jmeter-home --timeout)
jmeter-cli list                                                 # 发现实例
jmeter-cli health                                               # 探活
jmeter-cli tool get_test_plan_tree                              # 查 TestPlan 根 id
jmeter-cli find --searchBy name --query "HTTP"                  # 按名称模糊查找元素
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <id>
jmeter-cli agent "再加一个 5 用户的线程组"
```

完整命令清单、87 条测试用例与一键回归脚本见 [docs/test/jmeter-cli-test-cases.md](docs/test/jmeter-cli-test-cases.md)；实现方案见 [TODO/cli-support-plan.md](TODO/cli-support-plan.md)。

`skills/jmeter-cli/` 是面向**第三方 Agent**（如 OpenClaw、Hermes、Codex 等外部自动化工具）使用的 skill 文档，指导它们通过 `jmeter-cli` 命令操作运行中的 JMeter GUI 实例。它不在插件内加载，而是由外部 Agent 的 skill 系统读取。

#### 在外部 Agent 中接入 jmeter-cli

`skills/jmeter-cli/` 遵循 [Agent Skills](https://agentskills.io/) 开放标准（`SKILL.md` + YAML frontmatter），Claude Code、Codex、OpenClaw、Hermes 均原生支持——**同一份 skill 目录即可被这四类 Agent 读取，区别仅在于放置目录不同**。

**前置条件**（所有 Agent 通用）：

1. 启动 JMeter GUI（IPC 默认开启；若曾显式关闭，需设 `jmeter.ai.ipc.enabled=true` 重新开启）
2. 让 `jmeter-cli` 可被调用：将 `$JMETER_HOME/bin` 加入 `PATH`，或设置 `JMETER_HOME` 环境变量（CLI 会据此定位 `jmeter-cli.bat` / `jmeter-cli.sh`）

**各 Agent 安装方式：**

| Agent | Skill 目录 | 安装方式 |
|-------|-----------|---------|
| Claude Code | `~/.claude/skills/`（全局）或 `<项目>/.claude/skills/`（项目级） | 复制 `skills/jmeter-cli/` 到该目录 |
| Codex | `~/.codex/skills/`（全局）或 `.agents/skills/`（项目级） | 复制 `skills/jmeter-cli/` 到该目录；项目级须为**真实目录**，不能是符号链接 |
| OpenClaw | `~/.openclaw/skills/`（全局）或工作区 `./skills/` | `openclaw skills install ./skills/jmeter-cli --global`（去掉 `--global` 装到工作区） |
| Hermes | `~/.hermes/skills/` | 复制 `skills/jmeter-cli/` 到该目录 |

示例（以 Claude Code 全局安装为例）：

```bash
# Linux / macOS
cp -r skills/jmeter-cli ~/.claude/skills/

# Windows (PowerShell)
Copy-Item -Recurse skills/jmeter-cli $HOME\.claude\skills\
```

安装后，Agent 会依据 `SKILL.md` 的 `description` 自动判断何时调用 `jmeter-cli`，并按其中的工作流执行 `list → health → get/find → create/update → run → results`。完整命令清单见 [skills/jmeter-cli/references/cli-reference.md](skills/jmeter-cli/references/cli-reference.md)。

> **可选：** 若希望 Agent 直接引用各 JMeter 组件的属性名与 schema，可将组件技能 `src/main/jmeter-agent/skills/jmeter/` 一并复制到同一 skills 目录（cli skill 中的 `../jmeter/SKILL.md` 即指向它）。

## 技能系统

Agent 通过文件系统动态加载技能模块，每个技能包含 `SKILL.md` 定义和可选的 `references/` 参考文档。

| 技能 | 说明 |
|------|------|
| **jmeter** | JMeter 核心技能 — 73 组件参考文档、73 个参数 Schema、58 个 JMeter 函数参考、编码规范、反模式指南 |
| **memory** | 记忆管理 — 双层记忆（MEMORY.md 长期记忆 + HISTORY.md 事件历史），支持 grep 检索 |
| **skill-creator** | 技能创建 — 元技能，用于创建和更新新的 Agent 技能 |

> **想为 Agent 扩充新的 JMeter 组件？** 组件元信息（`testClass`/`guiClass`）已完全数据驱动 —— 新增组件**零 Java 改动**，只需编写一个 YAML Schema 文件。完整编写指南见 [SCHEMA-GUIDE.md](SCHEMA-GUIDE.md)（[English](SCHEMA-GUIDE_en.md)）。

## 配置参考

将 `jmeter-ai-sample.properties` 的内容复制到 `user.properties`，按需修改。下表中的默认值取自 `jmeter-ai-sample.properties`；若未在 `user.properties` 中显式配置，部分项将回落至源码内置默认值（已注明）。

### 全局 LLM 默认配置

以下配置适用于所有 AI 提供者（除非被提供者专属配置覆盖）：

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `jmeter.ai.temperature` | 温度参数（0.0-1.0），越低越确定性 | `0.7` |
| `jmeter.ai.max.tokens` | 单次响应最大 Token 数 | `4096` |
| `jmeter.ai.max.history.size` | 对话历史保留条数 | `120` |
| `jmeter.ai.reasoning.effort` | 推理强度：none / minimal / low / medium / high / xhigh / max | `high` |
| `jmeter.ai.default.model` | 默认模型（所有提供者共用，除非运行时切换） | `deepseek-v4-flash` |
| `jmeter.ai.default.provider` | 默认提供者（anthropic / openai / ollama / deepseek / zhipu / moonshot / minimax / langcat） | `deepseek` |
| `jmeter.ai.context.window.tokens` | 上下文窗口大小（供 ContextWindowManager、MemoryConsolidator、AgentRunner 使用） | `65536` |
| `jmeter.ai.max.tool.iterations` | 单次 Agent 循环最大工具迭代数 | `50` |
| `jmeter.ai.system.prompt` | 统一系统提示（注意：当前版本 Agent 主循环固定使用内置提示组装上下文，此配置仅对未注入系统消息的服务层兜底路径生效） | 空（使用内置提示） |

### 各模型推荐配置

下表按主流模型给出推荐值。

| provider | model | temperature | max.tokens | reasoning.effort | context.window.tokens |
|--------|---------|-------------|------------|-----------------|----------------------|
| deepseek | deepseek-v4-flash | `0.7` | `65536` | `[none, low, high, max]` | `512000` |
| deepseek | deepseek-v4-pro | `0.7` | `65536` | `[none, low, high, max]` | `512000` |
| zhipu | glm-5.2 | `1.0` | `65536` | `[none, minimal, low, medium, high, xhigh, max]` | `512000` |
| zhipu | glm-5.3 | `1.0` | `65536` | `[low, high, max]` | `512000` |
| zhipu | glm-5.3-flash | `1.0` | `65536` | `[low, high, max]` | `512000` |
| moonshot | kimi-k2.6 | `1.0`（API 强制） | `8192` | `medium` | `128000` |
| moonshot | kimi-k2.7-code | `1.0`（API 强制） | `8192` | `medium` | `128000` |
| moonshot | kimi-k3 | `1.0`（API 强制） | `65536` | `[low, high, max]` [其他值默认按"max"处理](https://platform.kimi.com/docs/guide/use-thinking-effort) | `512000` |
| minimax | MiniMax-M2.7 | `1.0` | `8192` | `medium` | `128000` |
| minimax | MiniMax-M3 | `1.0` | `65536` | `[none, medium]` | `512000` |
| langcat | LongCat-2.0 | `0.7` | `65536` | `[none, medium]` | `512000` |

**使用说明：**

- **max.tokens** — 取各模型 API 的单次输出上限；高于上限的值会被服务端裁剪。推理模型（`deepseek-reasoner` 等）的实际可见输出需扣除思维链 token。
- **reasoning.effort** — `none` 关闭思考（更快、更省 token），适合常规对话与简单工具调用；推理模型应使用 `medium` 或 `high` 以发挥深度分析能力。
- **MiniMax 思考开关** — 经 `thinking.type` 控制（`reasoning_effort=none` → `disabled`，其余 → M3 用 `adaptive` / M2.x 用 `enabled`）。**M3** 系列可真正关闭思考；**M2.x** 系列服务端会忽略 `disabled`，思考无法关闭（已知限制，非本端缺陷）。
- **context.window.tokens** — 建议设为模型上下文上限的 80% 左右，给工具结果回填、记忆整合和系统提示预留缓冲；过大会触发频繁的记忆整合，过小会过早丢弃历史。

### 提供者配置

#### Anthropic (Claude)

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `anthropic.api.key` | API 密钥（必填） | — |
| `anthropic.api.base.url` | API 基础 URL | `https://api.anthropic.com` |
| `anthropic.log.level` | 日志级别（info / debug） | 空（禁用） |

#### OpenAI

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `openai.api.key` | API 密钥（必填） | — |
| `openai.api.base.url` | API 基础 URL（可指向代理或 Azure OpenAI） | `https://api.openai.com` |
| `openai.log.level` | 日志级别 | 空（禁用） |

#### Ollama（本地）

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `ollama.api.base.url` | API 基础 URL（OpenAI 兼容端点，需含 `/v1` 后缀） | `http://localhost:11434/v1` |

#### 国产大模型

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `deepseek.api.key` | DeepSeek API 密钥 | — |
| `deepseek.api.base.url` | DeepSeek API 基础 URL | `https://api.deepseek.com` |
| `zhipu.api.key` | 智谱 GLM API 密钥 | — |
| `zhipu.api.base.url` | 智谱 GLM API 基础 URL | `https://open.bigmodel.cn/api/paas/v4/` |
| `moonshot.api.key` | Moonshot Kimi API 密钥 | — |
| `moonshot.api.base.url` | Moonshot API 基础 URL | `https://api.moonshot.cn/v1` |
| `minimax.api.key` | MiniMax API 密钥 | — |
| `minimax.api.base.url` | MiniMax API 基础 URL | `https://api.minimaxi.com/v1` |
| `langcat.api.key` | LangCat API 密钥 | — |
| `langcat.api.base.url` | LangCat API 基础 URL | `https://api.longcat.chat/openai/v1` |

每个提供者均支持 `*.temperature`、`*.max.history.size` 等覆盖全局默认值（例如 `deepseek.temperature=0.3`）。

### Agent Loop 配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.enabled` | 启用 Agent Loop（工具调用、记忆、会话持久化） | `true` |
| `agent.tool.result.max.chars` | 工具结果截断长度（字符） | `16000` |
| `jmeter.ai.injection.queue.size` | 单个会话最大排队注入消息数 | `20` |
| `jmeter.ai.injection.max.per.turn` | 每个 Agent 回合处理的注入消息上限 | `3` |
| `agent.runcapture.enabled` | 捕获 GUI 手动启动的测试运行（自动注入结果收集器，供 `get_test_status`/`get_test_results` 读取；关闭后仅 `run_test` 发起的运行被捕获） | `true` |
| `agent.session.per-instance` | 每个 JMeter 实例使用独立会话文件（`false` 回退到全局共享会话） | `true` |
| `agent.session.reap.ttl.days` | 孤儿会话文件回收 TTL（天；仅当归属实例已确认死亡且超过该时限才删除） | `7` |
| `agent.workspace.path` | 工作空间路径（保存 MEMORY.md、HISTORY.md、会话、技能、模板） | 三档回退：`agent.workspace.path` → `{jmeter.home}/bin/jmeter-agent`（默认）→ `{user.home}/.jmeter-ai/agent`（JMeter home 不可用时） |

### 记忆配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.memory.enabled` | 启用记忆系统 | `true` |
| `agent.memory.consolidate-on-exit.timeout.ms` | 关闭时深度蒸馏的超时（毫秒；超时则保留已归档的 HISTORY.md 并直接退出。关闭时归档到 HISTORY.md 始终执行，不可配置） | `120000` |

### 工具配置

#### 通用工具行为

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.timeout.ms` | 工具执行默认超时（毫秒） | `30000` |

#### JMeter 工具

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.jmeter.enabled` | 启用 JMeter 工具 | `true` |
| `jmeter.ai.tool.max.string.length` | 序列化 JMeter 元素时单个字符串属性值截断长度（字符） | `2048` |
| `jmeter.loggerpanel.maxlength` | JMeter LoggerPanel 最大行数（用于 get_log_panel_content 容量提示） | `1000` |

#### 文件系统工具

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.filesystem.enabled` | 启用文件系统工具 | `true` |
| `agent.tools.filesystem.allowed.dirs` | 允许访问的目录（逗号分隔） | 空（回落到用户主目录与当前工作目录） |
| `agent.tools.filesystem.denied.dirs` | 禁止访问的路径（逗号分隔，支持目录或具体文件） | — |

#### Web 工具

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.websearch.enabled` | 启用 Web 工具 | `true` |
| `agent.tools.websearch.provider` | 搜索引擎（brave / tavily / jina） | `jina` |
| `agent.tools.websearch.max.results` | 最大搜索结果数 | `10` |
| `agent.tools.websearch.timeout` | 搜索超时（秒） | `30` |
| `agent.tools.websearch.tavily.api.key` | Tavily API 密钥（provider=tavily 时必填） | — |
| `agent.tools.websearch.brave.api.key` | Brave API 密钥（provider=brave 时使用，未配置时回落 Jina） | — |
| `agent.tools.websearch.jina.api.key` | Jina API 密钥（provider=jina 时必填） | — |
| `agent.tools.webfetch.timeout` | 网页抓取超时（秒） | `30` |
| `agent.tools.web.max.redirects` | 最大重定向次数 | `5` |
| `agent.tools.web.ssrf.protection` | SSRF 防护（拦截访问内网/本地地址） | `true` |

#### 命令执行工具

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.exec.enabled` | 启用命令执行工具 | `true` |
| `agent.tools.exec.timeout` | 默认超时（秒，最大 600） | `60` |
| `agent.tools.exec.working.dir` | 限定工作目录（设置后仅允许该目录及其子目录） | — |
| `agent.tools.exec.deny.patterns` | 危险命令拦截规则（正则，逗号分隔） | 内置默认规则（rm -rf、del /f、format、mkfs、shutdown 等） |
| `agent.tools.exec.path.append` | 追加到 PATH 的额外目录 | — |

#### 异步子代理（Subagent）

主代理可通过 `spawn` 工具把一个自包含的**只读分析任务**委派给后台子代理。子代理运行在专用线程池上，使用隔离的只读工具集（仅限只读的 JMeter / 文件 / Web 工具，无法改测试计划、执行测试、写文件或继续派生子代理），并使用与主会话隔离的临时会话——其结果在**同一对话回合内**回注给主代理。

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.subagent.enabled` | 总开关。关闭时不注册 `spawn` 与 `subagent_status` 工具，主代理无法派发子代理 | `false` |
| `agent.subagent.max.concurrent` | 每个主会话的最大并发子代理数（`1` 时串行执行） | `1` |
| `agent.subagent.max.iterations` | 单次子代理运行的最大工具迭代数 | `50` |
| `agent.subagent.drain.timeout.seconds` | 主回合等待子代理结果的阻塞时长（秒，硬上限 300）；超时后子代理继续运行，结果可经 `subagent_status` 查询，或在下个回合投递 | `120` |
| `agent.subagent.status.retention.seconds` | 完成态子代理状态可经 `subagent_status` 查询的保留时长（秒）；晚到/未投递结果保留此窗口后被回收；0 = 永不按时长回收 | `60` |
| `agent.subagent.status.max.completed` | 每会话保留的完成态状态上限（超出按最旧淘汰）；0 = 永不按数量淘汰 | `10` |

### 聊天 UI 配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `ai.chat.show.tool.calls` | 显示工具调用信息（工具名、状态、执行时间、结果） | `true` |
| `ai.chat.show.thinking` | 显示模型思考/推理内容（关闭时移除 `<think>` 块） | `true`（源码内置默认 `false`） |
| `ai.chat.tool.result.max.length` | 工具结果在聊天面板与日志中的最大显示长度 | `500` |
| `ai.chat.font.size` | 聊天输入框字体大小（点，0 = 使用系统字体） | `0`（自动） |

### 链路追踪配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `langsmith.enabled` | 启用 LangSmith 追踪 | `false` |
| `langsmith.api.key` | LangSmith API 密钥 | — |
| `langsmith.project.name` | 项目名称（所有追踪归入该项目） | `jmeter-ai` |
| `langsmith.endpoint` | API 端点 | `https://api.smith.langchain.com` |
| `langsmith.sample.rate` | 采样率（0.0-1.0，1.0 = 全量追踪） | `1.0` |

### IPC / CLI 配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `jmeter.ai.ipc.enabled` | 启用内嵌 IPC HTTP 服务（仅 loopback + token 鉴权），供 jmeter-cli 驱动运行中的 GUI 与跨实例协作 | `true` |
| `jmeter.ai.ipc.bind` | 绑定地址（仅接受 loopback；`0.0.0.0`/`::`/`*` 会被拒绝） | `127.0.0.1` |
| `jmeter.ai.ipc.port` | 监听端口（0 = 自动分配，推荐） | `0` |
| `jmeter.ai.ipc.token` | 鉴权 token（空 = 启动时随机生成并写入端口文件） | 空（随机生成） |
| `jmeter.ai.ipc.agent.timeout.ms` | `/agent` 路由同步等待超时（毫秒） | `120000` |

## API 配置指南

### 国产大模型

| 提供者 | 获取 API Key |
|--------|-------------|
| DeepSeek | [platform.deepseek.com](https://platform.deepseek.com) |
| 智谱 GLM | [open.bigmodel.cn](https://open.bigmodel.cn) |
| Moonshot Kimi | [platform.moonshot.cn](https://platform.moonshot.cn) |
| MiniMax | [api.minimax.com](https://api.minimax.com) |
| LangCat | [longcat.chat](https://longcat.chat) |

## 免责声明

- **AI 局限性：** AI 可能产生错误信息，请在生产环境中验证所有建议
- **备份测试计划：** 在实施 AI 建议的大规模修改前，务必备份测试计划
- **API 费用：** 使用 API 会产生 Token 费用，请关注用量
- **安全：** 不要在对话中分享敏感信息（凭证、专有代码等）
- **性能影响：** 部分 AI 建议的配置可能影响测试性能，请监控资源使用

## 致谢

本项目在设计与实现中参考了以下开源项目：

- [nanobot](https://github.com/HKUDS/nanobot) — Agent 设计与实现参考
- [jmeter-ai](https://github.com/QAInsights/jmeter-ai) — 设计灵感参考

## 许可证

MIT License

<br>
<p align="center">
  Thanks for visiting ✨ <b>JMeter Ai Agent Plugin</b>
</p>
<p align="center">
  <img src="https://visitor-badge.laobi.icu/badge?page_id=azhengzz.JMeter-AI-Agent-Plugin" alt="visitors"/>
</p>
