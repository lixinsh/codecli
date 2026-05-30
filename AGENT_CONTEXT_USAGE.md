# AgentContext 统一上下文管理方案

## 📋 概述

本方案实现了统一的 Agent 上下文管理抽象，支持 ReAct 和 Plan-and-Execute 两种模式，并通过轻量级摘要传递实现任务间的上下文连贯性。

## 🎯 设计目标

1. **规划与执行隔离**：Plan 阶段完全独立，不依赖任何执行历史
2. **任务间摘要传递**：Execution 阶段通过轻量级摘要在任务间传递关键信息
3. **统一接口抽象**：所有 Agent 通过 `AgentContext` 接口访问上下文
4. **可扩展性**：支持未来的上下文压缩和优化策略

## 🏗️ 架构设计

### 核心组件

```
AgentContext (接口)
├── PlanContext (规划阶段)
│   └── 特点：完全独立、无摘要、每次规划全新上下文
│
└── ExecutionAgentContext (执行阶段)
    └── 特点：摘要传递、任务间共享、支持压缩
```

### 数据流

```
用户输入 
  → Planner (使用 PlanContext，独立上下文)
  → ExecutionPlan
  → PlanExecutionAgent (创建 ExecutionAgentContext)
    → Task1 执行 (空摘要)
      → 更新摘要
    → Task2 执行 (携带 Task1 摘要)
      → 更新摘要
    → Task3 执行 (携带 Task1+Task2 摘要)
      → ...
```

## 💻 使用示例

### 1. Plan-and-Execute 模式（已集成）

```java
// PlanExecutionAgent 内部自动管理上下文
PlanExecutionAgent agent = new PlanExecutionAgent();
String result = agent.run("读取 config.json 并分析内容，然后生成报告");

// 内部流程：
// 1. Planner 使用 PlanContext 生成计划（独立上下文）
// 2. executePlan 创建 ExecutionAgentContext
// 3. 每个任务执行时：
//    - clearCurrentTaskHistory() 清除上一任务的对话历史
//    - 自动注入之前任务的摘要到系统消息
//    - 执行完成后 updateSummary() 更新摘要
```

### 2. 手动使用 ExecutionAgentContext

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
// ... 此时 getMessages() 包含：
//     1. system prompt
//     2. 之前任务摘要（自动添加）
//     3. 当前任务的用户消息
```

### 3. 为 ReAct Agent 创建上下文（未来扩展）

```java
public class ReActAgent {
    private AgentContext context;
    
    public ReActAgent(String systemPrompt) {
        // ReAct 模式可以使用 ExecutionAgentContext
        this.context = new ExecutionAgentContext(systemPrompt, false);
        // includePreviousSummary=false 表示不需要摘要传递
    }
    
    public String run(String userInput) {
        context.addMessage("user", userInput);
        
        while (!isFinished()) {
            List<DsClient.Message> messages = context.getMessages();
            JsonNode response = dsClient.chat(messages, tools);
            
            // 添加工具调用和观察结果
            context.addMessageWithToolCalls("assistant", null, toolCalls, reasoning);
            context.addMessage("tool", observation, callId);
            
            // 如果上下文过长，执行压缩
            if (context.shouldCompact()) {
                context.compact();
            }
        }
        
        return getFinalAnswer();
    }
}
```

## 🔑 关键特性

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
| **支持压缩** | 当消息数 > 20 时可触发压缩（保留系统消息和最近5条） |

## 📊 摘要格式

```
✓ 任务: 读取配置文件 config.json
  结果: {"database": "mysql", "port": 3306}

✓ 任务: 连接数据库并查询用户表
  结果: 共找到 150 条记录

✓ 任务: 分析用户活跃度
  结果: 活跃用户占比 65%
```

## 🔄 重新规划时的上下文处理

```java
// 在 replan 时，可以将执行摘要传递给 Planner
String executionSummary = agentContext.getSummary();
ExecutionPlan newPlan = planner.replan(
    userInput, 
    currentPlan, 
    errorReason,
    executionSummary  // 新增参数（需要修改 Planner 接口）
);
```

**注意**：当前实现中，replan 尚未传入执行摘要。如需此功能，可以：
1. 修改 `Planner.replan()` 方法签名，增加 `executionSummary` 参数
2. 在 replanning prompt 中加入摘要信息
3. 帮助 LLM 理解哪些任务已完成、失败原因是什么

## 🚀 性能优化建议

### 1. 控制摘要长度

```java
// 如果摘要过长，可以在 updateSummary 时进行截断或压缩
public void updateSummary(String taskDescription, String taskResult) {
    String conciseResult = truncateIfNeeded(taskResult, 200); // 限制200字符
    executionSummary.append("✓ 任务: ").append(taskDescription).append("\n")
                    .append("  结果: ").append(conciseResult).append("\n\n");
}
```

### 2. 启用上下文压缩

```java
// 在长任务执行过程中检查是否需要压缩
if (agentContext.shouldCompact()) {
    agentContext.compact();
    System.out.println("[优化] 已压缩当前任务的对话历史");
}
```

### 3. 选择性包含摘要

```java
// 对于独立任务，可以不包含之前的摘要
ExecutionAgentContext context = new ExecutionAgentContext(prompt, false);
```

## 📝 最佳实践

1. **规划阶段保持纯粹**：不要让执行历史影响规划的客观性
2. **摘要简洁明了**：只保留关键信息，避免冗余
3. **及时清理历史**：每个任务开始前清除上一任务的对话历史
4. **监控 Token 消耗**：定期检查 `getMessageCount()` 和摘要长度
5. **灵活配置**：根据任务类型决定是否包含摘要

## 🔮 未来扩展

1. **集成 ConversationHistoryCompactor**：对长摘要进行 Map-Reduce 压缩
2. **支持多轮对话的 ReAct Agent**：使用同一个 AgentContext 管理完整对话
3. **上下文持久化**：将摘要保存到文件或数据库，支持会话恢复
4. **智能摘要生成**：使用 LLM 自动生成更精准的摘要，而非简单拼接

## ⚠️ 注意事项

1. **线程安全**：当前实现不是线程安全的，如需并发使用请加锁
2. **内存管理**：长时间运行的 Agent 应定期调用 `clear()` 或 `compact()`
3. **摘要质量**：当前是简单拼接，未来可引入 LLM 生成更智能的摘要
