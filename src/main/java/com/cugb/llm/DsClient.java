package com.cugb.llm;

import com.cugb.config.AppConfig;
import com.cugb.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DsClient {
    private static final String MODEL              = AppConfig.LLM_MODEL;
    private static final String API_URL            = AppConfig.LLM_API_URL;
    private static final String API_KEY            = AppConfig.LLM_API_KEY;
    private static final String REQUEST_LOG_FILE   = AppConfig.LLM_REQUEST_LOG_FILE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static int requestCount = 0;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- OpenAI Protocol Data Structures ---
    public record Message(String role, String content, JsonNode tool_calls, String tool_call_id,
                          String reasoning_content) {
        public Message(String role, String content) {
            this(role, content, null, null, null);
        }
        
        public Message(String role, String content, JsonNode tool_calls) {
            this(role, content, tool_calls, null, null);
        }

        public Message(String role, String content, JsonNode tool_calls, String reasoning_content) {
            this(role, content, tool_calls, null, reasoning_content);
        }

        public Message(String role, String content, String tool_call_id) {
            this(role, content, null, tool_call_id, null);
        }
    }

    public DsClient(){
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120,TimeUnit.SECONDS)
                .build();
    }

    /**
     * 发送聊天请求到大模型服务端
     * @param history 历史聊天记录
     * @param tools 工具集合（可以为 null）
     * @return 大模型返回的完整响应 JsonNode
     */
    public JsonNode chat(List<Message> history, List<Tool> tools) {
        try {
            // 1. 使用 JsonNode 构建请求体
            ObjectNode requestNode = objectMapper.createObjectNode();
            requestNode.put("model", MODEL);

            // 添加 messages
            ArrayNode messagesNode = objectMapper.createArrayNode();
            for (Message msg : history) {
                ObjectNode msgNode = objectMapper.createObjectNode();
                msgNode.put("role", msg.role());
                
                // 处理 content (可以为 null)
                if (msg.content() != null) {
                    msgNode.put("content", msg.content());
                } else {
                    msgNode.set("content", objectMapper.nullNode());
                }

                // 处理 tool_calls (仅 assistant 角色可能有)
                if (msg.tool_calls() != null) {
                    msgNode.set("tool_calls", msg.tool_calls());
                }

                // 处理 tool_call_id (仅 tool 角色必须有)
                if (msg.tool_call_id() != null) {
                    msgNode.put("tool_call_id", msg.tool_call_id());
                }

                // 处理 reasoning_content (thinking 模式需要回传)
                if (msg.reasoning_content() != null) {
                    msgNode.put("reasoning_content", msg.reasoning_content());
                }
                
                messagesNode.add(msgNode);
            }
            requestNode.set("messages", messagesNode);

            // 添加 tools
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsNode = objectMapper.createArrayNode();
                for (Tool tool : tools) {
                    // 1. 构建 function 节点
                    ObjectNode functionNode = objectMapper.createObjectNode();
                    functionNode.put("name", tool.name());
                    functionNode.put("description", tool.description());
                    // 直接设置 parameters 的 JsonNode，确保结构完整
                    functionNode.set("parameters", tool.parameters());

                    // 2. 构建外层 tool 节点 {"type": "function", "function": {...}}
                    ObjectNode toolWrapper = objectMapper.createObjectNode();
                    toolWrapper.put("type", "function");
                    toolWrapper.set("function", functionNode);
                    
                    toolsNode.add(toolWrapper);
                }
                requestNode.set("tools", toolsNode);
            }

            String jsonBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestNode);

            // 写入请求日志
            logRequest(jsonBody);

            // 2. 构建 HTTP 请求
            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
            Request httpRequest = new Request.Builder()
                    .url(API_URL + "/chat/completions")
                    .post(body)
                    .addHeader("Authorization", "Bearer " + API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .build();

            // 3. 执行请求并处理响应
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(无响应体)";
                    throw new IOException("API 请求失败: " + response.code() + " " + response.message()
                            + "，响应: " + errorBody);
                }

                String responseBody = response.body().string();
                // 返回完整的响应树
                return objectMapper.readTree(responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("调用大模型接口异常: " + e.getMessage(), e);
        }
    }

    private static synchronized void logRequest(String jsonBody) {
        requestCount++;
        try {
            File logFile = new File(REQUEST_LOG_FILE);
            File parent = logFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
                writer.println("=== 请求 #" + requestCount
                        + "  [" + LocalDateTime.now().format(TIME_FMT) + "] ===");
                writer.println(jsonBody);
                writer.println();
            }
        } catch (IOException e) {
            System.err.println("[DsClient] 写入请求日志失败: " + e.getMessage());
        }
    }
}
