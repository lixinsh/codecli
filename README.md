# pai-cli — AI-Powered Coding Assistant

基于 Java 17 的终端 AI 编程助手，通过连接 DeepSeek LLM API，支持 **ReAct** 和 **Plan-and-Execute** 两种模式，可自主读写文件、执行 Shell 命令来完成编程任务。

## 项目概述

pai-cli 是一个命令行 AI 编程助手：

- **ReAct 模式**：Think-Act-Observe 循环（最多 10 轮），LLM 自主推理 → 调用工具 → 观察结果 → 继续推理，直到给出最终答案
- **Plan-and-Execute 模式**：先由 Planner 将用户需求分解为 DAG 任务图，再按拓扑顺序逐任务执行，失败时支持一次重规划
- **三种内置工具**：`read_file`、`write_file`、`shell_exec`
- **Token 预算管理**：基于字符估算的 Token 计数，超过 80% 阈值自动触发对话历史压缩

## 架构

```
com.cugb
├── cli/           # 入口与 UI
│   ├── Main.java         交互式 REPL，模式切换（/react, /plan-and-execute）
│   └── ConsoleUI.java     ANSI 颜色、图标、分隔线、面板渲染
├── agent/         # Agent 层
│   ├── Agent.java             ReAct Agent（Think-Act-Observe 循环）
│   ├── PlanExecutionAgent.java Plan-and-Execute Agent（规划 → 执行 → 重规划）
│   ├── IAgentContext.java     上下文统一接口
│   └── UnifiedAgentContext.java 上下文实现（三层 system prompt 构建）
├── plan/          # 规划层
│   ├── Planner.java        调用 LLM 生成/重规划 DAG 任务图
│   ├── ExecutionPlan.java  DAG + Kahn 拓扑排序
│   └── Task.java           任务模型（READ/WRITE/EXECUTE/ANALYZE/VERIFY）
├── tool/          # 工具层
│   ├── Tool.java           record：name + description + parameters + executor
│   ├── ToolExecutor.java   函数式接口：String execute(Map<String, String>)
│   ├── ToolRegistry.java   注册 read_file / write_file / shell_exec
│   └── Param.java          record：name + type + description + required
├── llm/           # LLM 客户端
│   ├── DsClient.java        DeepSeek API 客户端（OkHttp + Jackson）
│   ├── PromptTemplate.java  提示词枚举（惰性加载 + 缓存）
│   └── PromptLoader.java    从 resources/prompts/*.md 加载提示词
├── memory/        # 记忆系统
│   ├── ConversationMemory.java       两层存储：summaries + messages
│   ├── MemoryEntry.java              记忆条目（自动 token 计数）
│   ├── TokenBudget.java              Token 预算（计数 + 阈值判断）
│   ├── ConversationHistoryCompactor.java Map-Reduce 对话历史压缩
│   ├── MemoryRetriever.java          占位类（未实现）
│   └── LongTermMemory.java           占位类（未实现）
└── config/        # 配置
    └── AppConfig.java  从 config.properties 加载全部配置
```

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+

### 构建

```bash
mvn clean package
```

产物为 `target/codecli-3.0-SNAPSHOT.jar`（含所有依赖的 fat jar）。

### 配置

在 `src/main/resources/config.properties` 中配置 API 密钥：

```properties
llm.model=deepseek-v4-pro
llm.api.url=https://api.deepseek.com/v1
llm.api.key=your-deepseek-api-key
context.max.token=200000
```

### 运行

```bash
java -jar target/codecli-3.0-SNAPSHOT.jar
```

### 交互命令

| 命令 | 说明 |
|------|------|
| `/react` | 切换到 ReAct 模式（默认） |
| `/plan-and-execute` | 切换到 Plan-and-Execute 模式 |
| `/context` | 查看 Token 用量、消息统计、预算状态 |
| `/clear` | 清除全部对话历史和摘要 |
| `exit` / `quit` | 退出程序 |

## 两种运行模式

### ReAct 模式（默认）

[Agent.java](src/main/java/com/cugb/agent/Agent.java) 实现标准 ReAct 循环：

1. 用户输入追加为 `user` 消息
2. 调用 LLM（携带全部工具定义）
3. 若 LLM 返回 tool_calls：执行工具，结果作为 `tool` 消息追加，回到第 2 步
4. 若 LLM 返回纯文本：追加为 `assistant` 消息，返回给用户
5. 最多循环 10 轮

Agent 使用 `AGENT_SYSTEM` 提示词（[agent-system.md](src/main/resources/prompts/agent-system.md)）。

### Plan-and-Execute 模式

[PlanExecutionAgent.java](src/main/java/com/cugb/agent/PlanExecutionAgent.java) 的执行流程：

1. **规划**：[Planner](src/main/java/com/cugb/plan/Planner.java) 调用 LLM（使用 `PLANNER_PLAN` 提示词），要求 LLM 返回 JSON 任务列表
2. **拓扑排序**：[ExecutionPlan](src/main/java/com/cugb/plan/ExecutionPlan.java) 使用 Kahn 算法对 DAG 进行拓扑排序，检测循环依赖
3. **逐任务执行**：按拓扑顺序，每个任务作为一个独立的对话轮次——发送 task prompt 给 LLM，LLM 可选择调用工具（最多 3 轮），最后返回结果。任务成功则 `task.complete(result)`，失败则取消后续任务
4. **重规划**（失败时）：调用 Planner 的 `replan()` 方法，传入当前计划摘要和错误原因，保留已完成任务，仅重新规划剩余任务。最多 1 次重规划
5. **收尾**：全部完成后，将任务执行摘要作为一条 `assistant` 消息追加到共享上下文

执行期间通过 `setInstruction(EXECUTE_TASK_PROMPT)` 临时替换 system prompt 为任务执行提示词，执行完毕后 `clearInstruction()` 恢复。

## System Prompt 构建方式

[UnifiedAgentContext.getMessages()](src/main/java/com/cugb/agent/UnifiedAgentContext.java#L98-L136) 在每次构建消息列表时：

1. 取 `instruction`（若已设置）或 `basePrompt`（默认）作为 system 消息正文
2. 将所有 `SUMMARY` 类型的 MemoryEntry（压缩器产物）拼接在 system 消息末尾
3. 其余 `CONVERSATION` / `TOOL_RESULT` 类型的消息按原始顺序追加

```
[system]  basePrompt (或 instruction，二者互斥)
          + [对话摘要] ...  (压缩产生的摘要，每段以 [对话摘要] 前缀拼接)
[user]    第一条用户消息
[assistant] ...
[tool]    ...
[user]    最新用户消息
...
```

> **注意**：`instruction` 和 `basePrompt` 是互斥的二选一，并非同时生效。PE 模式执行期间 instruction = EXECUTE_TASK_PROMPT，执行完恢复 basePrompt。

## 工具系统

工具定义遵循 OpenAI function calling 规范。[ToolRegistry](src/main/java/com/cugb/tool/ToolRegistry.java) 在构造时注册三个工具：

| 工具 | 参数 | 实现 |
|------|------|------|
| `read_file` | `file_path` (string, required) | `Files.readString(Paths.get(path))` |
| `write_file` | `file_path`, `content` (string, required) | `Files.writeString(Paths.get(path), content)` |
| `shell_exec` | `command` (string, required) | Windows: `cmd.exe /c`，Unix: `sh -c`，合并 stdout+stderr，返回退出码和输出 |

## Token 管理与压缩

### Token 估算

[TokenBudget.countTokens()](src/main/java/com/cugb/memory/TokenBudget.java#L40-L63) 使用启发式估算：

- 中文字符：约 1.5 字符/Token（即 `字符数 × 0.67`）
- 非中文非空白字符：约 4 字符/Token（即 `字符数 ÷ 4`）

### 自动压缩

[UnifiedAgentContext](src/main/java/com/cugb/agent/UnifiedAgentContext.java) 每次 `addMessage()` 后检查 `shouldCompact()`：

- 触发条件：当前 Token 数超过预算的 80%，或已超出预算上限
- 若触发，调用 [ConversationHistoryCompactor.compact()](src/main/java/com/cugb/memory/ConversationHistoryCompactor.java) 执行压缩

### 压缩策略

压缩器保留最近 5 轮对话（约 10 条消息），对更早的消息生成摘要：

- **少量旧消息**（≤10 条）：单次 LLM 调用 → 生成摘要
- **大量旧消息**（>10 条）：Map-Reduce — 每 10 条一组（Map），生成分片摘要；再调用一次 LLM 整合所有分片（Reduce）

压缩产物以 `SUMMARY` 类型存入 `ConversationMemory.summaries`，在后续 `getMessages()` 时自动注入 system 消息末尾。旧对话消息从 `messages` 中清除。

## 关键行为说明

### 任务间上下文传递

PE 模式执行任务时，所有消息（用户指令、LLM 响应、工具调用、工具结果）都追加到同一个 `IAgentContext` 中。后续任务执行时，`getMessages()` 返回的列表中**包含前面所有任务的全部对话历史**——LLM 可以直接看到之前发生了什么。这不是通过摘要机制实现的，而是利用对话历史的自然累积。

### 共享上下文

[Main.java](src/main/java/com/cugb/cli/Main.java) 创建一个 `UnifiedAgentContext` 实例，同时传给 `Agent` 和 `PlanExecutionAgent`。用户在两种模式间切换时，对话历史保持连续。

### Planner 的独立性

Planner 的 `plan()` 和 `replan()` 方法使用独立的临时消息列表（仅含 system prompt + 用户 input），不依赖共享的 Agent 上下文。这确保规划阶段不受执行历史的污染。

### /context 命令

打印内容包括：Token 用量条形图、各角色消息数量统计、预算状态（OK / OVER BUDGET）。

## 提示词文件

所有提示词以 Markdown 文件形式存放在 `src/main/resources/prompts/`：

| 文件 | 用途 |
|------|------|
| `agent-system.md` | ReAct Agent 的基础 system prompt |
| `execute-task.md` | PE 模式单任务执行的 system prompt |
| `planner-plan.md` | Planner 初始规划提示词 |
| `planner-replan.md` | Planner 重规划提示词 |
| `compact-map.md` | 压缩器 Map 阶段提示词 |
| `compact-reduce.md` | 压缩器 Reduce 阶段提示词 |

## 依赖

- **Jackson** — JSON 解析与构建
- **OkHttp** — HTTP 客户端（连接超时 60s，读取超时 120s）
- **Lombok** — `@Data` 注解（仅 MemoryEntry 使用）

## 待实现

- `LongTermMemory` — 摘要持久化与会话恢复（目前为空类）
- `MemoryRetriever` — 记忆检索（目前为空类）
- 多轮对话 ReAct Agent 的上下文管理优化
- Replan 时向 LLM 传入执行摘要以改善重规划质量
- 工具系统插件化

## 许可证

MIT License