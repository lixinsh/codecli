package com.cugb.agent;

import com.cugb.config.AppConfig;
import com.cugb.llm.DsClient;
import com.cugb.llm.PromptTemplate;
import com.cugb.plan.ExecutionPlan;
import com.cugb.plan.Task;
import com.cugb.plan.Planner;
import com.cugb.tool.Tool;
import com.cugb.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.cugb.cli.ConsoleUI.*;

/**
 * Plan-and-Execute 模式的计划执行智能体。
 */
public class PlanExecutionAgent {
    private final Planner planner;
    private final DsClient dsClient;
    private final ToolRegistry toolRegistry;
    private final IAgentContext sharedContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_REPLAN_ATTEMPTS = 1;
    private static final String EXECUTE_TASK_PROMPT = PromptTemplate.EXECUTE_TASK.load();

    public PlanExecutionAgent() {
        this.planner = new Planner();
        this.dsClient = new DsClient();
        this.toolRegistry = new ToolRegistry();
        this.sharedContext = null;
    }

    public PlanExecutionAgent(IAgentContext sharedContext) {
        this.planner = new Planner();
        this.dsClient = new DsClient();
        this.toolRegistry = new ToolRegistry();
        this.sharedContext = sharedContext;
    }

    public String run(String userInput) {
        line();
        info("Plan-and-Execute started");

        ExecutionPlan plan;
        try {
            plan = planner.plan(userInput);
        } catch (Exception e) {
            return "Planning failed: " + e.getMessage();
        }

        PlanExecutionResult result = executePlan(plan);
        if (result.success) {
            finalizeContext(result.summary());
            return GREEN + ICON_SUCCESS + "  Plan executed successfully" + RESET;
        }

        warn("Execution failed: " + result.errorReason);
        info("Attempting replan...");

        for (int attempt = 1; attempt <= MAX_REPLAN_ATTEMPTS; attempt++) {
            line();
            info("Replan attempt " + attempt);

            ExecutionPlan newPlan;
            try {
                newPlan = planner.replan(userInput, plan, result.errorReason);
            } catch (Exception e) {
                return "Replan failed (attempt " + attempt + "): " + e.getMessage();
            }

            result = executePlan(newPlan);
            if (result.success) {
                finalizeContext(result.summary());
                return GREEN + ICON_SUCCESS + "  Executed after replan" + RESET;
            }
            plan = newPlan;
        }

        return RED + ICON_ERROR + "  Plan execution failed after "
               + MAX_REPLAN_ATTEMPTS + " replan attempts\n  " + result.errorReason + RESET;
    }

    private PlanExecutionResult executePlan(ExecutionPlan plan) {
        plan.setStatus(ExecutionPlan.PlanStatus.RUNNING);
        List<String> completedTaskIds = new ArrayList<>();
        StringBuilder summaryBuilder = new StringBuilder();
        boolean hasFailure = false;
        String failureReason = null;

        IAgentContext agentContext = sharedContext != null
                ? sharedContext
                : new UnifiedAgentContext(dsClient, EXECUTE_TASK_PROMPT, AppConfig.CONTEXT_MAX_TOKEN);

        if (sharedContext != null) {
            sharedContext.setInstruction(EXECUTE_TASK_PROMPT);
        }
        try {
            return executeTasks(plan, agentContext, completedTaskIds, summaryBuilder);
        } finally {
            if (sharedContext != null) {
                sharedContext.clearInstruction();
            }
        }
    }

    private PlanExecutionResult executeTasks(ExecutionPlan plan, IAgentContext agentContext,
                                              List<String> completedTaskIds,
                                              StringBuilder summaryBuilder) {
        boolean hasFailure = false;
        String failureReason = null;

        List<String> executionOrder = plan.getExecutionOrder();
        int total = executionOrder.size();

        for (int i = 0; i < executionOrder.size(); i++) {
            String taskId = executionOrder.get(i);
            Task task = plan.getTask(taskId);
            if (task == null) continue;

            // 跳过已完成任务
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                completedTaskIds.add(taskId);
                dim("  [" + (i + 1) + "/" + total + "] (done) " + task.getDescription());
                continue;
            }

            // 检查依赖
            if (!task.canExecute(completedTaskIds)) {
                String errMsg = "Unmet dependencies for: " + task.getDescription();
                error("  " + errMsg);
                task.fail(errMsg);
                plan.setErrorMessage(errMsg);
                return new PlanExecutionResult(false, errMsg, summaryBuilder.toString());
            }

            // 执行任务
            task.setStatus(Task.TaskStatus.RUNNING);
            System.out.print("  [" + (i + 1) + "/" + total + "] "
                    + GRAY + "[" + task.getType() + "]" + RESET
                    + " " + task.getDescription() + " ... ");

            TaskResult taskResult = executeSingleTask(task, agentContext);
            if (taskResult.success) {
                task.complete(taskResult.output);
                completedTaskIds.add(taskId);
                summaryBuilder.append(ICON_SUCCESS).append(" [").append(task.getType()).append("] ")
                              .append(task.getDescription()).append("\n");
                System.out.println(GREEN + ICON_SUCCESS + RESET);
            } else {
                task.fail(taskResult.output);
                hasFailure = true;
                failureReason = "[" + task.getType() + "] " + task.getDescription() + ": " + taskResult.output;
                System.out.println(RED + ICON_ERROR + "  " + taskResult.output + RESET);

                // 取消后续任务
                for (int j = i + 1; j < executionOrder.size(); j++) {
                    Task remaining = plan.getTask(executionOrder.get(j));
                    if (remaining != null && remaining.getStatus() == Task.TaskStatus.PENDING) {
                        remaining.cancel();
                        dim("  [" + (j + 1) + "/" + total + "] cancelled: " + remaining.getDescription());
                    }
                }
                break;
            }
        }

        if (hasFailure) {
            plan.setStatus(ExecutionPlan.PlanStatus.FAILED);
            plan.setErrorMessage(failureReason);
            return new PlanExecutionResult(false, failureReason, summaryBuilder.toString());
        }

        plan.setStatus(ExecutionPlan.PlanStatus.COMPLETED);
        return new PlanExecutionResult(true, null,
                summaryBuilder.isEmpty() ? "All tasks completed" : summaryBuilder.toString());
    }

    private TaskResult executeSingleTask(Task task, IAgentContext agentContext) {
        try {
            String taskPrompt = "Execute this task:\n"
                    + "Type: " + task.getType() + "\n"
                    + "Description: " + task.getDescription();
            agentContext.addMessage("user", taskPrompt);

            List<Tool> tools = new ArrayList<>(toolRegistry.getAllTools().values());

            int maxRounds = 3;
            for (int round = 0; round < maxRounds; round++) {
                JsonNode response = dsClient.chat(agentContext.getMessages(), tools);
                JsonNode messageNode = response.path("choices").get(0).path("message");
                JsonNode toolCalls = messageNode.get("tool_calls");
                String reasoningContent = messageNode.has("reasoning_content")
                        ? messageNode.get("reasoning_content").asText() : null;

                if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                    String content = messageNode.path("content").asText();
                    if (content != null && !content.isEmpty()) {
                        agentContext.addMessage("assistant", content);
                        return new TaskResult(true, content);
                    }
                    return new TaskResult(true, "Done");
                }

                agentContext.addMessageWithToolCalls("assistant", null, toolCalls, reasoningContent);
                for (JsonNode call : toolCalls) {
                    String callId = call.path("id").asText();
                    String toolName = call.path("function").path("name").asText();
                    String argsStr = call.path("function").path("arguments").asText();

                    Tool tool = toolRegistry.getTool(toolName);
                    String observation;
                    if (tool == null) {
                        observation = "Tool not found: " + toolName;
                    } else {
                        try {
                            Map<String, String> argsMap = parseArguments(argsStr);
                            observation = tool.executor().execute(argsMap);
                        } catch (Exception e) {
                            observation = "Tool error: " + e.getMessage();
                        }
                    }
                    agentContext.addToolMessage(observation, callId);
                }
            }

            // 最后一轮获取总结
            JsonNode finalResponse = dsClient.chat(agentContext.getMessages(), tools);
            JsonNode finalMsg = finalResponse.path("choices").get(0).path("message");
            String finalContent = finalMsg.path("content").asText();
            if (finalContent != null && !finalContent.isEmpty()) {
                agentContext.addMessage("assistant", finalContent);
                return new TaskResult(true, finalContent);
            }
            return new TaskResult(true, "Done");
        } catch (Exception e) {
            return new TaskResult(false, "Error: " + e.getMessage());
        }
    }

    private Map<String, String> parseArguments(String argumentsJson) {
        Map<String, String> argsMap = new java.util.HashMap<>();
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (node.isObject()) {
                node.fields().forEachRemaining(entry ->
                        argsMap.put(entry.getKey(), entry.getValue().asText()));
            }
        } catch (Exception e) {
            error("Parse args: " + e.getMessage());
        }
        return argsMap;
    }

    private void finalizeContext(String summary) {
        if (sharedContext != null) {
            sharedContext.addMessage("assistant", "Plan executed:\n" + summary);
        }
    }

    private record PlanExecutionResult(boolean success, String errorReason, String summary) {}
    private record TaskResult(boolean success, String output) {}
}
