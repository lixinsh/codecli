package com.cugb.plan;

import com.cugb.llm.DsClient;
import com.cugb.llm.PromptTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static com.cugb.cli.ConsoleUI.*;

/**
 * 规划器，将用户输入分解为可执行的任务 DAG。
 */
public class Planner {
    private final DsClient dsClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PLANNING_SYSTEM_PROMPT  = PromptTemplate.PLANNER_PLAN.load();
    private static final String REPLANNING_SYSTEM_PROMPT = PromptTemplate.PLANNER_REPLAN.load();

    public Planner() {
        this.dsClient = new DsClient();
    }

    /**
     * 根据用户输入生成执行计划。
     */
    public ExecutionPlan plan(String userInput) {
        List<DsClient.Message> history = buildHistory(PLANNING_SYSTEM_PROMPT, userInput);
        String content = callLLMAndGetContent(history, "Planning");

        List<JsonNode> taskNodes = parseAndValidateTaskNodes(content, "Planning");
        ExecutionPlan plan = new ExecutionPlan();
        List<String> taskIds = addTasksFromNodes(plan, taskNodes, 0);

        resolveDependencies(plan, taskNodes, taskIds);
        finalizeWithTopologicalSort(plan, "Planning");

        info("Plan created: " + plan.getExecutionOrder().size() + " tasks");
        return plan;
    }

    /**
     * 重新规划：基于已部分执行的计划和错误原因生成新计划。
     */
    public ExecutionPlan replan(String userInput, ExecutionPlan currentPlan, String errorReason) {
        String summary = currentPlan.toSummaryText();
        String userMessage = String.format("""
                Original request: %s

                %s

                Error: %s

                Replan the remaining tasks. Exclude completed ones.
                """, userInput, summary, errorReason);

        List<DsClient.Message> history = buildHistory(REPLANNING_SYSTEM_PROMPT, userMessage);
        String content = callLLMAndGetContent(history, "Replanning");

        List<JsonNode> taskNodes = parseAndValidateTaskNodes(content, "Replanning");

        ExecutionPlan newPlan = new ExecutionPlan();
        int completedCount = copyCompletedTasksToPlan(newPlan, currentPlan);

        List<String> newTaskIds = addTasksFromNodes(newPlan, taskNodes, 0);
        resolveDependencies(newPlan, taskNodes, newTaskIds);
        linkToCompletedTasks(newPlan, taskNodes, newTaskIds, currentPlan, completedCount);

        finalizeWithTopologicalSort(newPlan, "Replanning");
        info("Replan: " + newPlan.getExecutionOrder().size() + " tasks ("
             + completedCount + " kept + " + newTaskIds.size() + " new)");
        return newPlan;
    }

    // ==================== 内部工具方法 ====================

    private List<DsClient.Message> buildHistory(String systemPrompt, String userMessage) {
        List<DsClient.Message> history = new ArrayList<>();
        history.add(new DsClient.Message("system", systemPrompt));
        history.add(new DsClient.Message("user", userMessage));
        return history;
    }

    private String callLLMAndGetContent(List<DsClient.Message> history, String op) {
        JsonNode response = dsClient.chat(history, null);
        String content = response.path("choices").get(0).path("message").path("content").asText();
        if (content == null || content.isEmpty()) {
            throw new RuntimeException(op + " failed: empty response");
        }
        return content;
    }

    private List<JsonNode> extractJsonArray(String content) {
        List<JsonNode> result = new ArrayList<>();
        try {
            int startIdx = content.indexOf('[');
            int endIdx = content.lastIndexOf(']');
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                String jsonStr = content.substring(startIdx, endIdx + 1).trim();
                if (jsonStr.startsWith("```json")) jsonStr = jsonStr.substring(7);
                else if (jsonStr.startsWith("```")) jsonStr = jsonStr.substring(3);
                if (jsonStr.endsWith("```")) jsonStr = jsonStr.substring(0, jsonStr.lastIndexOf("```"));
                jsonStr = jsonStr.trim();
                JsonNode arrayNode = objectMapper.readTree(jsonStr);
                if (arrayNode.isArray()) {
                    for (JsonNode node : arrayNode) result.add(node);
                }
            }
        } catch (JsonProcessingException e) {
            error("JSON parse: " + e.getMessage());
        }
        return result;
    }

    private List<JsonNode> parseAndValidateTaskNodes(String content, String op) {
        List<JsonNode> taskNodes = extractJsonArray(content);
        if (taskNodes.isEmpty()) {
            throw new RuntimeException(op + " failed: no valid task list");
        }
        return taskNodes;
    }

    private Task parseTask(JsonNode taskNode, int index) {
        try {
            String typeStr = taskNode.path("type").asText().toUpperCase().trim();
            Task.TaskType taskType;
            try {
                taskType = Task.TaskType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                error("Unknown task type: " + typeStr + " (task " + index + ")");
                return null;
            }
            String description = taskNode.path("description").asText();
            if (description.isEmpty()) {
                error("Task " + index + " missing description");
                return null;
            }
            return new Task(taskType, description);
        } catch (Exception e) {
            error("Parse task " + index + ": " + e.getMessage());
            return null;
        }
    }

    private void resolveDependencies(ExecutionPlan plan, List<JsonNode> taskNodes, List<String> taskIds) {
        for (int i = 0; i < taskNodes.size(); i++) {
            JsonNode depsNode = taskNodes.get(i).path("dependencies");
            if (depsNode.isArray()) {
                Task current = plan.getTask(taskIds.get(i));
                if (current == null) continue;
                for (JsonNode depIdx : depsNode) {
                    int idx = depIdx.asInt(-1);
                    if (idx >= 0 && idx < taskIds.size() && idx != i) {
                        String depId = taskIds.get(idx);
                        current.addDependency(depId);
                        Task dep = plan.getTask(depId);
                        if (dep != null) dep.addDependent(current.getId());
                    }
                }
            }
        }
    }

    private List<String> addTasksFromNodes(ExecutionPlan plan, List<JsonNode> taskNodes, int startIdx) {
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i < taskNodes.size(); i++) {
            Task task = parseTask(taskNodes.get(i), startIdx + i);
            if (task != null) {
                plan.addTask(task);
                taskIds.add(task.getId());
            }
        }
        return taskIds;
    }

    private void finalizeWithTopologicalSort(ExecutionPlan plan, String op) {
        if (!plan.topologicalSort()) {
            throw new RuntimeException(op + " failed: " + plan.getErrorMessage());
        }
    }

    private int copyCompletedTasksToPlan(ExecutionPlan newPlan, ExecutionPlan currentPlan) {
        int count = 0;
        for (String taskId : currentPlan.getExecutionOrder()) {
            Task task = currentPlan.getTask(taskId);
            if (task != null && task.getStatus() == Task.TaskStatus.COMPLETED) {
                Task copy = new Task(task.getType(), task.getDescription());
                copy.complete(task.getResult());
                newPlan.addTask(copy);
                count++;
            }
        }
        return count;
    }

    private void linkToCompletedTasks(ExecutionPlan newPlan, List<JsonNode> taskNodes,
                                       List<String> newTaskIds, ExecutionPlan currentPlan,
                                       int completedCount) {
        for (int i = 0; i < taskNodes.size(); i++) {
            JsonNode depsNode = taskNodes.get(i).path("dependencies");
            if (!depsNode.isArray()) continue;
            Task newTask = newPlan.getTask(newTaskIds.get(i));
            if (newTask == null) continue;
            for (JsonNode depIdx : depsNode) {
                int idx = depIdx.asInt(-1);
                if (idx >= 0 && idx < completedCount) {
                    List<String> order = currentPlan.getExecutionOrder();
                    if (idx < order.size()) {
                        Task orig = currentPlan.getTask(order.get(idx));
                        if (orig != null && orig.getStatus() == Task.TaskStatus.COMPLETED) {
                            Task copied = findCopiedTask(newPlan, orig);
                            if (copied != null) {
                                newTask.addDependency(copied.getId());
                                copied.addDependent(newTask.getId());
                            }
                        }
                    }
                }
            }
        }
    }

    private Task findCopiedTask(ExecutionPlan newPlan, Task original) {
        for (Task t : newPlan.getAllTasks()) {
            if (t.getType() == original.getType()
                && t.getDescription().equals(original.getDescription())
                && t.getStatus() == Task.TaskStatus.COMPLETED) {
                return t;
            }
        }
        return null;
    }
}
