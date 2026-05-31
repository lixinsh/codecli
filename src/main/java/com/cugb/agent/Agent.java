package com.cugb.agent;

import com.cugb.config.AppConfig;
import com.cugb.llm.DsClient;
import com.cugb.llm.PromptTemplate;
import com.cugb.memory.TokenBudget;
import com.cugb.tool.Tool;
import com.cugb.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.cugb.cli.ConsoleUI.*;

import static com.cugb.cli.ConsoleUI.*;

public class Agent {
    private final DsClient dsClient = new DsClient();
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final IAgentContext context;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SYSTEM_PROMPT = PromptTemplate.AGENT_SYSTEM.load();

    /**
     * 默认构造，创建独立的 UnifiedAgentContext
     */
    public Agent() {
        this.context = new UnifiedAgentContext(dsClient, SYSTEM_PROMPT, AppConfig.CONTEXT_MAX_TOKEN);
    }

    /**
     * 构造时注入共享上下文（用于模式切换时保持对话连续性）
     */
    public Agent(IAgentContext context) {
        this.context = context;
    }

    /**
     * 清空历史对话记录
     */
    public void clear() {
        context.clear();
    }

    /**
     * 运行 ReAct 循环
     * @param userInput 用户输入
     * @return 最终的回答
     */
    public String run(String userInput) {
        // 1. 添加用户消息到历史
        context.addMessage("user", userInput);

        // 2. 获取所有可用工具
        List<Tool> tools = new ArrayList<>(toolRegistry.getAllTools().values());
        context.setToolTokens(TokenBudget.countToolTokens(tools));

        int maxIterations = 10;
        for (int i = 0; i < maxIterations; i++) {
            // 3. 调用大模型
            JsonNode fullResponse = dsClient.chat(context.getMessages(), tools);
            JsonNode messageNode = fullResponse.path("choices").get(0).path("message");

            // 4. 检查是否有 tool_calls
            JsonNode toolCalls = messageNode.get("tool_calls");
            String reasoningContent = messageNode.has("reasoning_content")
                    ? messageNode.get("reasoning_content").asText() : null;

            if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = messageNode.path("content").asText();
                context.addMessageWithToolCalls("assistant", content, null, reasoningContent);
                return content;
            }

            // 收集工具调用名称，单行显示
            List<String> toolNames = new ArrayList<>();
            for (JsonNode call : toolCalls) {
                toolNames.add(call.path("function").path("name").asText());
            }
            toolCall(String.join(" · ", toolNames));

            // 将助手的 tool_calls 加入历史
            context.addMessageWithToolCalls("assistant", null, toolCalls, reasoningContent);

            for (JsonNode call : toolCalls) {
                String callId = call.path("id").asText();
                String toolName = call.path("function").path("name").asText();
                String argumentsStr = call.path("function").path("arguments").asText();

                Tool tool = toolRegistry.getTool(toolName);
                String observation;

                if (tool == null) {
                    observation = "Tool not found: " + toolName;
                    error("[Agent] " + observation);
                } else {
                    try {
                        Map<String, String> argsMap = convertArgumentsString(argumentsStr);
                        observation = tool.executor().execute(argsMap);
                    } catch (Exception e) {
                        observation = "Tool error: " + e.getMessage();
                        error("[Agent] " + observation);
                    }
                }

                // 将工具执行结果加入历史
                context.addToolMessage(observation, callId);
            }
        }

        return "Max iterations reached, task incomplete.";
    }

    /**
     * 将 JSON 字符串参数转换为 Map<String, String>
     */
    private Map<String, String> convertArgumentsString(String argumentsJson) {
        Map<String, String> argsMap = new java.util.HashMap<>();
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    argsMap.put(entry.getKey(), entry.getValue().asText());
                });
            }
        } catch (Exception e) {
            error("Parse tool args: " + e.getMessage());
        }
        return argsMap;
    }
}