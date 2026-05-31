package com.cugb.agent;

import com.cugb.llm.DsClient;
import com.cugb.memory.ConversationHistoryCompactor;
import com.cugb.memory.ConversationMemory;
import com.cugb.memory.MemoryEntry;
import com.cugb.memory.TokenBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 统一的 Agent 上下文管理实现
 * <p>
 * system prompt 采用三层模型：
 * <pre>
 * [system]  BASE_PROMPT（永久角色定义）
 *           + MODE_INSTRUCTION（动态注入，如 PE 的 EXECUTE_TASK_PROMPT）
 *           + SUMMARY（压缩器产出的历史摘要）
 * </pre>
 * <p>
 * 设计特点：
 * 1. 基于 ConversationMemory 管理所有对话历史
 * 2. 集成 ConversationHistoryCompactor 进行专业压缩
 * 3. 支持 ReAct 和 Plan-and-Execute 两种模式共享上下文
 * 4. 用户可以在运行时切换模式，上下文保持连续
 * 5. setInstruction/clearInstruction 实现模式指令的动态注入与清除
 */
public class UnifiedAgentContext implements IAgentContext {

    private final ConversationMemory conversationMemory;
    private final ConversationHistoryCompactor compactor;
    /** 基础 system prompt，永久不变（如 AGENT_SYSTEM） */
    private final String basePrompt;
    /** basePrompt 的 token 数（构造时计算，运行时不变） */
    private final int basePromptTokens;
    /** 当前模式指令，null 表示无特殊指令，使用 basePrompt */
    private String instruction;
    /** instruction 的 token 数，无指令时为 0 */
    private int instructionTokens = 0;
    /** 当前工具列表的 token 数（由外部 Agent 在调用前设置） */
    private int toolTokens = 0;
    private boolean autoCompactEnabled = true;
    private static final double COMPACT_TRIGGER_RATIO = 0.8;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建 Agent 上下文
     *
     * @param dsClient   LLM 客户端
     * @param basePrompt 基础 system prompt（如 AGENT_SYSTEM）
     * @param maxToken   最大 token 数上限
     */
    public UnifiedAgentContext(DsClient dsClient, String basePrompt, int maxToken) {
        this.basePrompt = basePrompt;
        this.basePromptTokens = TokenBudget.countTokens(basePrompt);
        this.instruction = null;
        this.instructionTokens = 0;
        this.conversationMemory = new ConversationMemory(maxToken);
        // compactor 使用独立的长超时 DsClient，避免压缩时因输入巨大而超时
        this.compactor = new ConversationHistoryCompactor(
                new DsClient(60, 300, TimeUnit.SECONDS));
    }

    // ==================== 消息写入 ====================

    @Override
    public void addMessage(String role, String content) {
        MemoryEntry entry = new MemoryEntry(
                content, role, null, MemoryEntry.MemoryType.CONVERSATION);
        conversationMemory.addEntry(entry);
        if (autoCompactEnabled && shouldCompact()) {
            compact();
        }
    }

    @Override
    public void addToolMessage(String content, String toolCallId) {
        java.util.Map<String, String> metaData = new java.util.HashMap<>();
        metaData.put("tool_call_id", toolCallId);
        MemoryEntry entry = new MemoryEntry(content, "tool", metaData, MemoryEntry.MemoryType.TOOL_RESULT);
        conversationMemory.addEntry(entry);
        if (autoCompactEnabled && shouldCompact()) {
            compact();
        }
    }

    @Override
    public void addMessageWithToolCalls(String role, String content,
                                         JsonNode toolCalls, String reasoningContent) {
        java.util.Map<String, String> metaData = new java.util.HashMap<>();
        if (reasoningContent != null) {
            metaData.put("reasoning_content", reasoningContent);
        }
        if (toolCalls != null) {
            metaData.put("tool_calls", toolCalls.toString());
        }
        MemoryEntry entry = new MemoryEntry(content, role, metaData, MemoryEntry.MemoryType.CONVERSATION);
        conversationMemory.addEntry(entry);
        if (autoCompactEnabled && shouldCompact()) {
            compact();
        }
    }

    // ==================== 三层 system 消息构建 ====================

    @Override
    public List<DsClient.Message> getMessages() {
        List<DsClient.Message> messages = new ArrayList<>();

        // 第1层：BASE_PROMPT 或 MODE_INSTRUCTION（二选一）
        String prompt = (instruction != null) ? instruction : basePrompt;

        // 第2+3层：拼接 SUMMARY 压缩历史（O(1) 直接读取）
        StringBuilder combinedSystem = new StringBuilder(prompt);
        for (MemoryEntry entry : conversationMemory.getSummaries()) {
            combinedSystem.append("\n\n[对话摘要]\n").append(entry.getContext());
        }
        messages.add(new DsClient.Message("system", combinedSystem.toString()));

        // 添加对话消息（无需过滤 SUMMARY，因为已经分层存储）
        for (MemoryEntry entry : conversationMemory.getMessages().values()) {
            String role = entry.getRole() != null ? entry.getRole() : "user";
            String content = entry.getContext();
            if ("tool".equals(role) && entry.getMetaData() != null) {
                String toolCallId = entry.getMetaData().get("tool_call_id");
                if (toolCallId != null) {
                    messages.add(new DsClient.Message("tool", content, toolCallId));
                    continue;
                }
            }
            if (entry.getMetaData() != null && entry.getMetaData().containsKey("tool_calls")) {
                try {
                    JsonNode toolCalls = objectMapper.readTree(entry.getMetaData().get("tool_calls"));
                    String reasoningContent = entry.getMetaData().get("reasoning_content");
                    messages.add(new DsClient.Message(role, content, toolCalls, reasoningContent));
                } catch (Exception e) {
                    messages.add(new DsClient.Message(role, content));
                }
            } else {
                messages.add(new DsClient.Message(role, content));
            }
        }
        return messages;
    }

    // ==================== 模式指令管理 ====================

    @Override
    public void setInstruction(String instruction) {
        this.instruction = instruction;
        this.instructionTokens = instruction != null ? TokenBudget.countTokens(instruction) : 0;
    }

    @Override
    public void clearInstruction() {
        this.instruction = null;
        this.instructionTokens = 0;
    }

    // ==================== 清理 ====================

    @Override
    public void clear() {
        conversationMemory.clear();
    }

    @Override
    public void clearSummaries() {
        conversationMemory.clearSummaries();
    }

    // ==================== 压缩管理 ====================

    @Override
    public boolean shouldCompact() {
        if (!autoCompactEnabled) return false;
        // 纳入 basePrompt + instruction + tools 与 messages/summaries 的综合开销
        int effective = conversationMemory.getTokenBudget().getCurrentToken()
                      + basePromptTokens
                      + instructionTokens
                      + toolTokens;
        int max = conversationMemory.getTokenBudget().getMaxToken();
        return effective > (int) (max * COMPACT_TRIGGER_RATIO);
    }

    @Override
    public void compact() {
        List<MemoryEntry> msgList = new ArrayList<>(conversationMemory.getMessages().values());
        if (msgList.isEmpty()) return;

        List<MemoryEntry> oldSummaries = new ArrayList<>(conversationMemory.getSummaries());

        // 1. 备份全部数据（用于失败回滚）
        List<MemoryEntry> backup = new ArrayList<>();
        backup.addAll(oldSummaries);
        backup.addAll(msgList);

        // 2. 清空 messages 和 summaries（token 预算同步归零）
        conversationMemory.clearMessages();
        conversationMemory.clearSummaries();

        // 3. 压缩（将已有摘要注入，使 LLM 产出合并后的统一摘要）
        List<MemoryEntry> compacted;
        try {
            compacted = compactor.compact(msgList, oldSummaries);
        } catch (Exception e) {
            // 4. 失败 → 完整回滚
            for (MemoryEntry entry : backup) {
                conversationMemory.addEntry(entry);
            }
            System.err.println("[UnifiedAgentContext] 对话压缩失败，上下文已恢复: " + e.getMessage());
            return;
        }

        // 5. 成功 → 写入压缩结果（SUMMARY 自动路由到 summaries，CONVERSATION 到 messages）
        for (MemoryEntry entry : compacted) {
            conversationMemory.addEntry(entry);
        }
    }

    @Override
    public int getMessageCount() {
        return conversationMemory.size();
    }

    @Override
    public int getSummaryCount() {
        return conversationMemory.getSummaries().size();
    }

    // ==================== 各组件 token 明细 ====================

    @Override
    public int getCurrentTokenCount() {
        return conversationMemory.getTokenBudget().getCurrentToken();
    }

    @Override
    public int getMaxTokenLimit() {
        return conversationMemory.getTokenBudget().getMaxToken();
    }

    @Override
    public int getBasePromptTokens() {
        return basePromptTokens;
    }

    @Override
    public int getInstructionTokens() {
        return instructionTokens;
    }

    @Override
    public int getMessageTokens() {
        return conversationMemory.getTokenBudget().getCurrentToken();
    }

    // ==================== 工具 token 管理 ====================

    @Override
    public void setToolTokens(int toolTokens) {
        this.toolTokens = toolTokens;
    }

    @Override
    public int getToolTokens() {
        return toolTokens;
    }

    // ==================== 辅助方法 ====================

    public void setAutoCompactEnabled(boolean enabled) {
        this.autoCompactEnabled = enabled;
    }

    public void forceCompact() {
        compact();
    }

    public ConversationMemory getConversationMemory() {
        return conversationMemory;
    }
}
