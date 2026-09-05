# Gitee Ai - JMeter Agent

English | [中文](README.md)

Gitee Ai is a JMeter AI Agent plugin powered by an Agent Loop architecture that drives iterative cycles of LLM calls, tool execution, and result feedback — enabling intelligent test plan creation, optimization, and debugging within JMeter.

![Gitee Ai](./images/JMeter-Agent-Demo-EN.gif)

## Key Features

- **Agent Loop Architecture** — Full iterative cycle of LLM call → tool execution → result feedback, supporting multi-turn tool calling for complex tasks
- **30 Built-in Agent Tools** — Covering JMeter element CRUD (incl. batch operations and enable/disable), JMX parsing, test execution, cross-instance coordination, filesystem, web search/fetch, and command execution
- **Skills System** — Dynamically loaded skill modules from filesystem, with built-in JMeter expertise (73 component references, 58 function references)
- **8 AI Providers** — Anthropic Claude, OpenAI, DeepSeek, Zhipu GLM, Moonshot Kimi, MiniMax, LangCat, Ollama
- **Component Schema Validation** — 73 YAML schema files providing type, required, enum, and range validation for JMeter component parameters
- **Memory System** — Two-layer memory architecture (long-term memory + event history) with cross-session consolidation
- **Live Selection Sync** — Chat panel shows the currently selected JMeter element and focused control in real time, with one-click injection into the AI context
- **Security Controls** — File access whitelisting, SSRF protection, dangerous command blocking
- **Tracing** — Optional LangSmith integration for LLM call tracing and monitoring

## Architecture Overview

```
User Message
  ↓
CommandRouter (command routing)
  ↓
AgentLoop (main loop)
  ├── ContextBuilder (build context)
  ├── LLM API Call (Claude / OpenAI / Ollama / ...)
  ├── ToolRegistry (tool registry)
  │   ├── JMeter Element Tools (element CRUD)
  │   ├── Test Execution Tools (run/status/results)
  │   ├── Filesystem Tools (read/write/edit)
  │   ├── Web Tools (search/fetch)
  │   └── Exec Tool (command execution)
  ├── SkillsLoader (skill loading)
  ├── MemoryStore (memory storage)
  └── SessionManager (session management)
  ↓
Response to Chat UI
```

**Tool Call Flow:** LLM decides to call a tool → ToolRegistry finds and executes it → SchemaBasedPropertyHandler validates parameters → Result fed back to LLM → Continue iterating or return final response

## Installation

### Prerequisites

- **JMeter** 5.6.3
- **JDK** 17 or higher

### Manual Installation

1. Place the `jmeter-agent-xxx.jar` file into the `jmeter/lib/ext` directory
2. Copy all contents from the `src/main/jmeter-agent/` directory into the `jmeter/bin/jmeter-agent/` directory (includes skills, templates, etc.)
3. Append the contents of `jmeter-ai-sample.properties` to `jmeter/bin/user.properties` and adjust the configuration as needed

## Quick Start

1. **Configure API Key** — Set your AI provider key in `user.properties`:
   ```properties
   # The default provider is deepseek — just set its API key to get started
   deepseek.api.key=your-api-key

   # For other providers, also set jmeter.ai.default.provider, e.g. Anthropic Claude:
   anthropic.api.key=your-api-key
   jmeter.ai.default.provider=anthropic
   ```
2. **Open Chat Panel** — Click the **AI** menu in the menu bar or the AI toolbar button (shortcut `Alt+V`)
3. **Start Chatting** — Describe your needs directly, e.g., "Create a thread group with 10 threads sending GET requests to http://example.com"

## Agent Tools

Except for the subagent tools (off by default), all tools below are registered and enabled under the default configuration; each group can be disabled separately via its configuration switch (see [Tool Configuration](#tool-configuration)).

### JMeter Element Tools

| Tool | Description |
|------|-------------|
| `create_jmeter_element` | Create JMeter elements (thread groups, samplers, controllers, assertions, timers, etc.) |
| `update_jmeter_element` | Update properties of existing elements |
| `batch_update_jmeter_elements` | Batch update properties of multiple elements of the same type (single validation, single GUI refresh) |
| `delete_jmeter_element` | Delete a specific element (TestPlan root cannot be deleted) |
| `batch_delete_jmeter_elements` | Delete multiple elements in one operation (all validated before any deletion) |
| `move_jmeter_element` | Move element to a different parent node with precise positioning (first / last / before:<id> / after:<id>) |
| `batch_move_jmeter_elements` | Batch move multiple elements to a shared parent node at a shared position |
| `copy_paste_jmeter_element` | Copy and paste test plan elements |
| `toggle_jmeter_element` | Enable, disable, or toggle an element (disabled elements are skipped during test execution) |
| `batch_toggle_jmeter_elements` | Batch enable, disable, or toggle multiple elements |
| `get_test_plan_tree` | Get complete test plan tree structure (JSON) |
| `get_selected_element` | Get detailed info about the currently selected element |
| `find_element` | Find elements by name, type, or path |
| `query_element_properties` | Query test plan elements by property name/value |

### JMX & Script Tools

| Tool | Description |
|------|-------------|
| `parse_jmx_file` | Parse an external JMX script file (returns the component tree, or filtered element queries) |
| `open_jmx_file` | Open an external JMX file in the current GUI (replaces the current plan by default; can merge) |
| `get_script_info` | Get current script and runtime environment info (script path, save state, JMeter/JDK version, JMETER_HOME) |
| `get_log_panel_content` | Read JMeter LoggerPanel (bottom log panel) content by line range |

### Test Execution Tools

| Tool | Description |
|------|-------------|
| `run_test` | Start, stop, or shutdown the current test plan |
| `get_test_status` | Get test execution status (running state, thread progress, sample counts) |
| `get_test_results` | Get test results (response times, throughput, error rate) |

### Cross-Instance Coordination Tools (registered with IPC, on by default)

| Tool | Description |
|------|-------------|
| `list_instances` | List all live JMeter AI instances on this machine (including self) and their open test plans |
| `delegate_to_instance` | Delegate a task to another JMeter AI instance on this machine and block for its result |

### Filesystem Tools (Enabled by Default)

| Tool | Description |
|------|-------------|
| `read_file` | Read file contents with pagination support |
| `write_file` | Write files, auto-creating directories |
| `edit_file` | Edit files (string replacement) |
| `list_dir` | List directory contents with recursive support |

### Web Tools (Enabled by Default)

| Tool | Description |
|------|-------------|
| `web_search` | Search the web (supports Brave, Tavily, Jina, and more) |
| `web_fetch` | Fetch web page content (prefers Jina Reader for main-content extraction; falls back to Markdown/plain text on direct fetch) |

### Execution Tool (Enabled by Default)

| Tool | Description |
|------|-------------|
| `exec` | Execute shell commands with timeout and working directory configuration |

### Subagent Tools (Off by Default)

Registered when `agent.subagent.enabled=true` (see the Async Subagent configuration below).

| Tool | Description |
|------|-------------|
| `spawn` | Delegate a self-contained read-only analysis task to a background subagent |
| `subagent_status` | Check the progress and results of spawned subagents |

## Commands

### / Commands

Slash commands for session management:

| Command | Description |
|---------|-------------|
| `/new` | Start a fresh conversation (clears current session) |
| `/status` | Show bot status (version, model, token usage, session info) |
| `/help` | Show available commands |

### Command-Line Client (jmeter-cli)

In addition to the chat panel, you can drive a running JMeter GUI instance via **jmeter-cli** over loopback HTTP/IPC — performing CRUD operations on the test plan or delegating natural-language tasks to the AI Agent. CLI flags match underlying tool schema keys 1:1. IPC is enabled by default (loopback-only + token auth); set `jmeter.ai.ipc.enabled=false` to disable.

```bash

# Common commands (global options: --pid --token --json --jmeter-home --timeout)
jmeter-cli list                                                 # discover instances
jmeter-cli health                                               # health check
jmeter-cli tool get_test_plan_tree                              # get TestPlan root id
jmeter-cli find --searchBy name --query "HTTP"                  # fuzzy-find elements by name
jmeter-cli run --ignoreTimers true --wait                       # run test and wait for completion
jmeter-cli status                                               # check test progress
jmeter-cli results --format both --limit 10                     # view test results
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <id>
jmeter-cli agent "add a thread group with 5 users"
```

See [docs/test/jmeter-cli-test-cases.md](docs/test/jmeter-cli-test-cases.md) for the full command reference and regression scripts.

`skills/jmeter-cli/` is a skill intended for **third-party agents** (such as OpenClaw, Hermes, Codex, and other external automation tools). It teaches them how to operate a running JMeter GUI through `jmeter-cli`. It is not loaded by the plugin — external agents read it through their own skill systems.

#### Wiring jmeter-cli into External Agents

`skills/jmeter-cli/` follows the [Agent Skills](https://agentskills.io/) open standard (`SKILL.md` + YAML frontmatter), which Claude Code, Codex, OpenClaw, and Hermes all support natively — **the same skill folder is read by all four; only the target directory differs.**

**Prerequisites** (common to all agents):

1. Start the JMeter GUI (IPC is on by default; if you previously disabled it, re-enable with `jmeter.ai.ipc.enabled=true`)
2. Make `jmeter-cli` invokable: add `$JMETER_HOME/bin` to `PATH`, or set the `JMETER_HOME` environment variable (the CLI uses it to locate `jmeter-cli.bat` / `jmeter-cli.sh`)

**Installation per agent:**

| Agent | Skill directory | How to install |
|-------|-----------------|----------------|
| Claude Code | `~/.claude/skills/` (global) or `<project>/.claude/skills/` (project) | Copy `skills/jmeter-cli/` into the directory |
| Codex | `~/.codex/skills/` (global) or `.agents/skills/` (project) | Copy `skills/jmeter-cli/` into the directory; the project-level dir must be a **real directory**, not a symlink |
| OpenClaw | `~/.openclaw/skills/` (global) or workspace `./skills/` | `openclaw skills install ./skills/jmeter-cli --global` (drop `--global` for workspace install) |
| Hermes | `~/.hermes/skills/` | Copy `skills/jmeter-cli/` into the directory |

Example (global install for Claude Code):

```bash
# Linux / macOS
cp -r skills/jmeter-cli ~/.claude/skills/

# Windows (PowerShell)
Copy-Item -Recurse skills/jmeter-cli $HOME\.claude\skills\
```

Once installed, the agent uses the `description` in `SKILL.md` to decide when to invoke `jmeter-cli`, then follows its workflow: `list → health → get/find → create/update → run → results`. See [skills/jmeter-cli/references/cli-reference.md](skills/jmeter-cli/references/cli-reference.md) for the full command reference.

> **Optional:** If you want the agent to reference JMeter component property names and schemas directly, also copy the component skill `src/main/jmeter-agent/skills/jmeter/` into the same skills directory (the cli skill points to it via `../jmeter/SKILL.md`).

## Skills System

The Agent dynamically loads skill modules from the filesystem. Each skill contains a `SKILL.md` definition and optional `references/` documentation.

| Skill | Description |
|-------|-------------|
| **jmeter** | Core JMeter skill — 73 component references, 73 parameter schemas, 58 JMeter function references, coding standards, anti-patterns |
| **memory** | Memory management — Two-layer memory (MEMORY.md long-term + HISTORY.md events) with grep-based recall |
| **skill-creator** | Skill creation — Meta-skill for creating and updating Agent skills |

> **Want to add new JMeter components for the Agent?** Component metadata (`testClass`/`guiClass`) is fully data-driven — adding a component requires **zero Java changes**, just a YAML Schema file. See the full authoring guide: [SCHEMA-GUIDE_en.md](SCHEMA-GUIDE_en.md)（[中文版](SCHEMA-GUIDE.md)）.

## Configuration Reference

Copy the contents of `jmeter-ai-sample.properties` into `user.properties` and modify as needed. The default values in the tables below are taken from `jmeter-ai-sample.properties`; when not explicitly configured in `user.properties`, some items fall back to source-code built-in defaults (noted where applicable).

### Global LLM Defaults

These settings apply to all AI providers unless overridden by provider-specific configuration:

| Property | Description | Default |
|----------|-------------|---------|
| `jmeter.ai.temperature` | Temperature (0.0-1.0); lower = more deterministic | `0.7` |
| `jmeter.ai.max.tokens` | Max tokens per response | `4096` |
| `jmeter.ai.max.history.size` | Conversation history size to retain | `120` |
| `jmeter.ai.reasoning.effort` | Reasoning effort: none / minimal / low / medium / high / xhigh / max | `high` |
| `jmeter.ai.default.model` | Default model (shared by all providers unless switched at runtime) | `deepseek-v4-flash` |
| `jmeter.ai.default.provider` | Default provider (anthropic / openai / ollama / deepseek / zhipu / moonshot / minimax / langcat) | `deepseek` |
| `jmeter.ai.context.window.tokens` | Context window size (used by ContextWindowManager, MemoryConsolidator, AgentRunner) | `65536` |
| `jmeter.ai.max.tool.iterations` | Max tool iterations per agent loop | `50` |
| `jmeter.ai.system.prompt` | Unified system prompt (note: in the current version the Agent main loop always assembles its context from the built-in prompt; this setting only takes effect on the service-layer fallback path where no system message is injected) | Empty (uses built-in prompt) |

### Per-Model Recommended Configuration

The table below lists recommended values for mainstream models.

| provider | model | temperature | max.tokens | reasoning.effort | context.window.tokens |
|----------|-------|-------------|------------|-----------------|----------------------|
| deepseek | deepseek-v4-flash | `0.7` | `65536` | `[none, low, high, max]` | `512000` |
| deepseek | deepseek-v4-pro | `0.7` | `65536` | `[none, low, high, max]` | `512000` |
| zhipu | glm-5.2 | `1.0` | `65536` | `[none, minimal, low, medium, high, xhigh, max]` | `512000` |
| zhipu | glm-5.3 | `1.0` | `65536` | `[low, high, max]` | `512000` |
| zhipu | glm-5.3-flash | `1.0` | `65536` | `[low, high, max]` | `512000` |
| moonshot | kimi-k2.6 | `1.0` (API-enforced) | `8192` | `medium` | `128000` |
| moonshot | kimi-k2.7-code | `1.0` (API-enforced) | `8192` | `medium` | `128000` |
| moonshot | kimi-k3 | `1.0` (API-enforced) | `65536` | `[low, high, max]` [Other values are handled by default as "max".](https://platform.kimi.com/docs/guide/use-thinking-effort) | `512000` |
| minimax | MiniMax-M2.7 | `1.0` | `8192` | `medium` | `128000` |
| minimax | MiniMax-M3 | `1.0` | `65536` | `[none, medium]` | `512000` |
| langcat | LongCat-2.0 | `0.7` | `65536` | `[none, medium]` | `512000` |

**Notes:**

- **max.tokens** — The single-response output cap from each model's API; values above the cap are clipped server-side. For reasoning models (e.g., `deepseek-reasoner`), the visible output is reduced by the chain-of-thought tokens.
- **reasoning.effort** — `none` disables thinking (faster and cheaper), suited for routine chat and simple tool calls; reasoning models should use `medium` or `high` to leverage deeper analysis.
- **MiniMax thinking switch** — Controlled via `thinking.type` (`reasoning_effort=none` → `disabled`; otherwise M3 uses `adaptive`, M2.x uses `enabled`). The **M3** family can truly disable thinking; the **M2.x** family silently ignores `disabled`, so thinking cannot be turned off (known limitation, not a client-side defect).
- **GLM-5.3 / GLM-5.3-flash forced thinking** — The API no longer accepts `thinking.type=disabled` (it errors out), and the [official migration guide](https://docs.bigmodel.cn/cn/guide/start/migrate-to-glm-new) states these models do not support `none`. The plugin registers them as always-on: `thinking.type` is always sent as `enabled`; configure `jmeter.ai.reasoning.effort` as `low/high/max` to control thinking depth and cost — with `none` the plugin omits `reasoning_effort`, so the server applies its deepest default (`max`; no error, but no token savings either); `minimal/medium/xhigh` are passed through as-is and arbitrated by the server (no silent clamping, same policy as Kimi K3).
- **context.window.tokens** — Recommend ~80% of the model's context window ceiling, leaving headroom for tool results, memory consolidation, and the system prompt. Too large triggers frequent consolidation; too small discards history prematurely.

### Provider Configuration

#### Anthropic (Claude)

| Property | Description | Default |
|----------|-------------|---------|
| `anthropic.api.key` | API key (required) | — |
| `anthropic.api.base.url` | API base URL | `https://api.anthropic.com` |
| `anthropic.log.level` | Log level (info / debug) | Empty (disabled) |

#### OpenAI

| Property | Description | Default |
|----------|-------------|---------|
| `openai.api.key` | API key (required) | — |
| `openai.api.base.url` | API base URL (can point to a proxy or Azure OpenAI) | `https://api.openai.com` |
| `openai.log.level` | Log level | Empty (disabled) |

#### Ollama (Local)

| Property | Description | Default |
|----------|-------------|---------|
| `ollama.api.base.url` | API base URL (OpenAI-compatible endpoint; must include the `/v1` suffix) | `http://localhost:11434/v1` |

#### Chinese LLM Providers

| Property | Description | Default |
|----------|-------------|---------|
| `deepseek.api.key` | DeepSeek API key | — |
| `deepseek.api.base.url` | DeepSeek API base URL | `https://api.deepseek.com` |
| `zhipu.api.key` | Zhipu GLM API key | — |
| `zhipu.api.base.url` | Zhipu GLM API base URL | `https://open.bigmodel.cn/api/paas/v4/` |
| `moonshot.api.key` | Moonshot Kimi API key | — |
| `moonshot.api.base.url` | Moonshot API base URL | `https://api.moonshot.cn/v1` |
| `minimax.api.key` | MiniMax API key | — |
| `minimax.api.base.url` | MiniMax API base URL | `https://api.minimaxi.com/v1` |
| `langcat.api.key` | LangCat API key | — |
| `langcat.api.base.url` | LangCat API base URL | `https://api.longcat.chat/openai/v1` |

Each provider also supports `*.temperature`, `*.max.history.size`, etc. to override global defaults (e.g., `deepseek.temperature=0.3`).

### Agent Loop Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `agent.enabled` | Enable Agent Loop (tool calling, memory, session persistence) | `true` |
| `agent.tool.result.max.chars` | Tool result truncation length (chars) | `16000` |
| `jmeter.ai.injection.queue.size` | Max queued injection messages per session | `20` |
| `jmeter.ai.injection.max.per.turn` | Max injection messages processed per agent turn | `3` |
| `agent.runcapture.enabled` | Capture GUI-initiated test runs (auto-injects a result collector so `get_test_status`/`get_test_results` see live data; when off, only runs started via `run_test` are captured) | `true` |
| `agent.session.per-instance` | Each JMeter instance uses its own session file (`false` reverts to the legacy global shared session) | `true` |
| `agent.session.reap.ttl.days` | Orphan session reap TTL (days; files are deleted only when the owning instance is confirmed dead and past this age) | `7` |
| `agent.workspace.path` | Workspace path (stores MEMORY.md, HISTORY.md, sessions, skills, templates) | Three-tier fallback: `agent.workspace.path` → `{jmeter.home}/bin/jmeter-agent` (default) → `{user.home}/.jmeter-ai/agent` (when JMeter home is unavailable) |

### Memory Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `agent.memory.enabled` | Enable memory system | `true` |
| `agent.memory.consolidate-on-exit.timeout.ms` | Bounded timeout (ms) for the close-time deep distillation (on timeout, the already-archived HISTORY.md is kept and JMeter exits; archiving to HISTORY.md on close always happens and is not configurable) | `120000` |

### Tool Configuration

#### Common Tool Behavior

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.timeout.ms` | Default tool execution timeout (ms) | `30000` |

#### JMeter Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.jmeter.enabled` | Enable JMeter tools | `true` |
| `jmeter.ai.tool.max.string.length` | Max characters of a single string property value before truncation when serializing JMeter elements | `2048` |
| `jmeter.loggerpanel.maxlength` | Max lines of the JMeter LoggerPanel (used for get_log_panel_content capacity hint) | `1000` |

#### Filesystem Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.filesystem.enabled` | Enable filesystem tools | `true` |
| `agent.tools.filesystem.allowed.dirs` | Allowed directories (comma-separated) | Empty (falls back to user home and current working dir) |
| `agent.tools.filesystem.denied.dirs` | Denied paths (comma-separated; supports dirs or specific files) | — |

#### Web Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.websearch.enabled` | Enable web tools | `true` |
| `agent.tools.websearch.provider` | Search engine (brave / tavily / jina) | `jina` |
| `agent.tools.websearch.max.results` | Max search results | `10` |
| `agent.tools.websearch.timeout` | Search timeout (seconds) | `30` |
| `agent.tools.websearch.tavily.api.key` | Tavily API key (required when provider=tavily) | — |
| `agent.tools.websearch.brave.api.key` | Brave API key (used when provider=brave; falls back to Jina when unset) | — |
| `agent.tools.websearch.jina.api.key` | Jina API key (required when provider=jina) | — |
| `agent.tools.webfetch.timeout` | Web fetch timeout (seconds) | `30` |
| `agent.tools.web.max.redirects` | Max redirects to follow | `5` |
| `agent.tools.web.ssrf.protection` | SSRF protection (blocks private/local network access) | `true` |

#### Execution Tool

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.exec.enabled` | Enable exec tool | `true` |
| `agent.tools.exec.timeout` | Default timeout (seconds, max 600) | `60` |
| `agent.tools.exec.working.dir` | Restrict working directory (only this dir and its subdirs allowed when set) | — |
| `agent.tools.exec.deny.patterns` | Dangerous command patterns (regex, comma-separated) | Built-in defaults (rm -rf, del /f, format, mkfs, shutdown, etc.) |
| `agent.tools.exec.path.append` | Additional directories to append to PATH | — |

#### Async Subagent

The main agent can delegate a self-contained **read-only analysis task** to a background subagent via the `spawn` tool. The subagent runs on a dedicated thread pool with an isolated read-only toolset (only read-only JMeter / file / web tools — it cannot modify the test plan, run tests, write files, or spawn further subagents) and an ephemeral session isolated from the main conversation — its result is folded back into the **same conversation turn**.

| Property | Description | Default |
|----------|-------------|---------|
| `agent.subagent.enabled` | Master switch. When off, the `spawn` and `subagent_status` tools are not registered and the main agent cannot spawn subagents | `false` |
| `agent.subagent.max.concurrent` | Max concurrent subagents per main session (at `1` they run serially) | `1` |
| `agent.subagent.max.iterations` | Max tool iterations for a single subagent run | `50` |
| `agent.subagent.drain.timeout.seconds` | Seconds the main turn parks waiting for a subagent result (hard cap 300); on timeout the subagent keeps running, its result stays queryable via `subagent_status` and may be delivered in a later turn | `120` |
| `agent.subagent.status.retention.seconds` | How long (seconds) a finished subagent's status stays queryable via `subagent_status`; a late/undeliverable result is kept this long then reclaimed; 0 = never reclaim by age | `60` |
| `agent.subagent.status.max.completed` | Max finished statuses retained per session (oldest evicted beyond); 0 = never evict by count | `10` |

### Chat UI Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `ai.chat.show.tool.calls` | Show tool call info (tool name, status, execution time, result) | `true` |
| `ai.chat.show.thinking` | Show model thinking/reasoning content (strips `<think>` blocks when off) | `true` (source-code built-in default: `false`) |
| `ai.chat.tool.result.max.length` | Max tool result display length in chat panel and logs | `500` |
| `ai.chat.font.size` | Chat input font size in points (0 = use system font) | `0` (auto) |

### Tracing Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `langsmith.enabled` | Enable LangSmith tracing | `false` |
| `langsmith.api.key` | LangSmith API key | — |
| `langsmith.project.name` | Project name (all traces grouped under this project) | `jmeter-ai` |
| `langsmith.endpoint` | API endpoint | `https://api.smith.langchain.com` |
| `langsmith.sample.rate` | Sampling rate (0.0-1.0; 1.0 = trace all requests) | `1.0` |

### IPC / CLI Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `jmeter.ai.ipc.enabled` | Enable the embedded IPC HTTP server (loopback-only + token auth) for jmeter-cli to drive the running GUI and for cross-instance coordination | `true` |
| `jmeter.ai.ipc.bind` | Bind address (loopback only; `0.0.0.0`/`::`/`*` are refused) | `127.0.0.1` |
| `jmeter.ai.ipc.port` | Listen port (0 = auto-allocate, recommended) | `0` |
| `jmeter.ai.ipc.token` | Auth token (empty = randomly generated at start and written to the port file) | Empty (random) |
| `jmeter.ai.ipc.agent.timeout.ms` | Sync wait timeout (ms) for the `/agent` route | `120000` |

## API Setup Guide

### Chinese LLM Providers

| Provider | Get API Key |
|----------|------------|
| DeepSeek | [platform.deepseek.com](https://platform.deepseek.com) |
| Zhipu GLM | [open.bigmodel.cn](https://open.bigmodel.cn) |
| Moonshot Kimi | [platform.moonshot.cn](https://platform.moonshot.cn) |
| MiniMax | [api.minimax.com](https://api.minimax.com) |
| LangCat | [longcat.chat](https://longcat.chat) |

## Disclaimer

- **AI Limitations:** AI may produce incorrect information. Always verify suggestions before using them in production.
- **Backup Test Plans:** Always back up your test plans before implementing major AI-suggested changes.
- **API Costs:** Using API incurs token-based costs. Monitor your usage.
- **Security:** Do not share sensitive information (credentials, proprietary code) in conversations.
- **Performance Impact:** Some AI-suggested configurations may affect test performance. Monitor resource usage.

## Acknowledgements

This project drew inspiration and implementation references from the following open-source projects:

- [nanobot](https://github.com/HKUDS/nanobot) — Reference for Agent design and implementation
- [jmeter-ai](https://github.com/QAInsights/jmeter-ai) — Design inspiration reference

## License

MIT License

<br>
<p align="center">
  Thanks for visiting ✨ <b>JMeter Ai Agent Plugin</b>
</p>
<p align="center">
  <img src="https://visitor-badge.laobi.icu/badge?page_id=azhengzz.JMeter-AI-Agent-Plugin" alt="visitors"/>
</p>

