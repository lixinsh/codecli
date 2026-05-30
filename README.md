# pai-cli — AI-Powered Coding Assistant

基于 Java 17 的终端 AI 编程助手，通过连接 DeepSeek LLM API，支持 **ReAct** 和 **Plan-and-Execute** 两种智能模式，可自主读写文件、执行 Shell 命令来完成复杂编程任务。

## 项目概述

pai-cli 是一个命令行 AI 编程助手，它能够：

- **ReAct 模式**：Think-Act-Observe 循环，LLM 自主推理、调用工具、观察结果，直到完成任务
- **Plan-and-Execute 模式**：先制定 DAG 执行计划，再按拓扑顺序逐步执行，支持失败重规划
- **三种内置工具**：`read_file`（读文件）、`write_file`（写文件）、`shell_exec`（执行 Shell 命令）
- **上下文管理**：统一的 AgentContext 抽象，Token 预算驱动的自动压缩，任务间摘要传递

## 架构设计

```
com.cugb
├── cli/           # 入口：Main.java（交互式 REPL）、ConsoleUI.java（终端 UI 渲染）
├── agent/         # Agent 层：Agent（ReAct）、PlanExecutionAgent、IAgentContext、UnifiedAgentContext
├── plan/          # 规划层：Planner、ExecutionPlan（DAG + 拓扑排序）、Task
├── tool/          # 工具层：Tool（record）、ToolExecutor、ToolRegistry
├── llm/           # LLM 客户端：DsClient（DeepSeek API）、PromptTemplate、PromptLoader
├── memory/        # 记忆系统：ConversationMemory、TokenBudget、ConversationHistoryCompactor
└── config/        # 配置：AppConfig
```

## AgentContext 统一上下文管理

### 设计目标

1. **规划与执行隔离**：Plan 阶段完全独立，不依赖任何执行历史
2. **任务间摘要传递**：Execution 阶段通过轻量级摘要在任务间传递关键信息
3. **统一接口抽象**：所有 Agent 通过 `IAgentContext` 接口访问上下文
4. **可扩展性**：支持上下文压缩和优化策略

### 三层 System Prompt 模型

`UnifiedAgentContext` 使用三层 System Prompt 结构：

```
┌──────────────────────────────┐
│  BASE_PROMPT（永久）          │  ← Agent 角色定义
│  + MODE_INSTRUCTION（动态）   │  ← 运行时切换（如 Plan-Execute 时注入执行指令）
│  + SUMMARY（已压缩的历史摘要） │  ← ConversationHistoryCompactor 产物
├──────────────────────────────┤
│  对话消息（user/assistant/tool）│  ← 发送给 LLM 的普通消息
└──────────────────────────────┘
```

### 数据流

```
用户输入
  → Agent.run()  [ReAct 模式]
    → Think-Act-Observe 循环（最多 10 轮）
    → 工具调用 → 观察结果 → 继续思考 → 最终答案

用户输入
  → PlanExecutionAgent.run()  [Plan-and-Execute 模式]
    → Planner.plan()（使用独立上下文，不依赖执行历史）
    → ExecutionPlan（DAG + 拓扑排序）
    → 逐任务执行（每个任务携带前序任务的执行摘要）
    → 失败时 replan（最多 1 次，保留已完成任务）
```

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+

### 构建

```bash
mvn clean package
```

### 配置

在 `src/main/resources/config.properties` 中配置 API 密钥：

```properties
llm.api.key=your-deepseek-api-key
context.max.token=2000
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
| `/context` | 查看 Token 使用量、消息统计、预算状态 |
| `/clear` | 清除对话历史 |
| `exit` / `quit` | 退出程序 |

## 关键特性

### PlanContext（规划阶段）

| 特性 | 说明 |
|------|------|
| **独立性** | 每次规划都是全新的上下文，不依赖历史 |
| **简洁性** | 仅包含 system prompt + 用户输入 |
| **不支持摘要** | `getSummary()` 返回空字符串 |
| **无需压缩** | 上下文很短，`shouldCompact()` 返回 false |

### ExecutionAgentContext（执行阶段）

| 特性 | 说明 |
|------|------|
| **摘要传递** | 任务完成后通过 `updateSummary()` 累积摘要 |
| **自动注入** | 新任务开始时自动将摘要作为 system 消息注入 |
| **历史清理** | `clearCurrentTaskHistory()` 清除对话历史但保留摘要 |
| **支持压缩** | Token 使用超过 80% 预算时自动触发压缩 |

### 摘要格式

```
✓ 任务: 读取配置文件 config.json
  结果: {"database": "mysql", "port": 3306}

✓ 任务: 连接数据库并查询用户表
  结果: 共找到 150 条记录

✓ 任务: 分析用户活跃度
  结果: 活跃用户占比 65%
```

## ReAct 模式手动使用示例

```java
// 创建执行上下文
String systemPrompt = PromptLoader.load("execute-task");
ExecutionAgentContext context = new ExecutionAgentContext(systemPrompt);

// 执行第一个任务
context.addMessage("user", "任务1: 读取文件 A");
// ... 调用 LLM，处理工具调用 ...
context.updateSummary("读取文件 A", "文件内容: {...}");

// 执行第二个任务（自动携带第一个任务的摘要）
context.clearCurrentTaskHistory(); // 清除对话历史，保留摘要
context.addMessage("user", "任务2: 分析文件 A 的内容");
// 此时 getMessages() 包含：
//     1. system prompt
//     2. 之前任务摘要（自动添加）
//     3. 当前任务的用户消息
```

## 工具系统

| 工具 | 参数 | 说明 |
|------|------|------|
| `read_file` | `file_path` (string, required) | 读取文件内容 |
| `write_file` | `file_path`, `content` (string, required) | 写入文件内容 |
| `shell_exec` | `command` (string, required) | 执行 Shell 命令（Windows: cmd.exe /c，Unix: sh -c） |

## Token 管理与压缩

- **Token 估算**：中文约 0.67 字符/Token，非中文约 4 字符/Token
- **自动压缩阈值**：Token 使用超过预算的 80% 时触发
- **压缩策略**：保留最近 5 轮对话，对更早的历史进行 Map-Reduce 摘要压缩
  - 少量旧消息：单次 LLM 调用压缩
  - 大量旧消息：分组压缩（Map）→ 合并整合（Reduce）

## 性能优化建议

1. **控制摘要长度**：在 `updateSummary()` 时截断或限制结果长度
2. **启用上下文压缩**：长任务执行中检查 `shouldCompact()` 并调用 `compact()`
3. **选择性包含摘要**：对独立任务创建不含摘要的上下文 `new ExecutionAgentContext(prompt, false)`

## 注意事项

1. **线程安全**：当前实现不是线程安全的，并发使用需加锁
2. **内存管理**：长时间运行的 Agent 应定期调用 `clear()` 或 `compact()`
3. **Token 预算**：`config.properties` 中的 `context.max.token=2000` 设置较低，生产环境可根据模型上下文窗口调整

## 未来扩展

- [ ] 集成 LongTermMemory，支持摘要持久化和会话恢复
- [ ] 智能摘要生成：使用 LLM 自动生成更精准的摘要
- [ ] Replan 时传入执行摘要，帮助 LLM 更好地重规划
- [ ] 扩展工具系统，支持插件化工具注册
- [ ] 多轮对话 ReAct Agent 的完整上下文管理

## 许可证

MIT License
