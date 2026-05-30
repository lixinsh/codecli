package com.cugb.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 短期会话记忆管理，两层存储：
 * <pre>
 *   summaries  — SUMMARY 类型（压缩器产出的历史摘要，合并到 system prompt）
 *   messages   — CONVERSATION + TOOL_RESULT（按序发送给 LLM 的对话消息）
 * </pre>
 * 统一维护 token 预算，addEntry 自动路由到对应层。
 */
public class ConversationMemory {

    /** 压缩历史摘要（合并到 system prompt，不参与对话消息） */
    private final List<MemoryEntry> summaries;
    /** 对话消息（user / assistant / tool，按插入顺序发送给 LLM） */
    private final LinkedHashMap<String, MemoryEntry> messages;
    /** Token 预算管理器，统一管理预算状态 */
    private final TokenBudget tokenBudget;

    public ConversationMemory(int maxToken) {
        this.summaries = new ArrayList<>();
        this.messages = new LinkedHashMap<>();
        this.tokenBudget = new TokenBudget(maxToken);
    }

    /**
     * 添加记忆条目，根据类型自动路由：
     * SUMMARY → summaries，其他 → messages
     */
    public void addEntry(MemoryEntry entry) {
        if (entry.getType() == MemoryEntry.MemoryType.SUMMARY) {
            summaries.add(entry);
        } else {
            messages.put(entry.getId(), entry);
        }
        tokenBudget.add(entry.getTokenCount());
    }

    // ==================== 查询 ====================

    /** 获取所有 SUMMARY 摘要（用于合并到 system prompt） */
    public List<MemoryEntry> getSummaries() {
        return summaries;
    }

    /** 获取所有对话消息（保持插入顺序） */
    public LinkedHashMap<String, MemoryEntry> getMessages() {
        return messages;
    }

    /** 获取所有条目（摘要在前、对话在后，供压缩器等需要完整视图的场景） */
    public List<MemoryEntry> getAllEntries() {
        List<MemoryEntry> all = new ArrayList<>(summaries);
        all.addAll(messages.values());
        return all;
    }

    // ==================== 清理 ====================

    /** 清空全部（messages + summaries），重置 token */
    public void clear() {
        summaries.clear();
        messages.clear();
        tokenBudget.reset();
    }

    /** 仅清空对话消息，修正 token 计数 */
    public void clearMessages() {
        for (MemoryEntry entry : messages.values()) {
            tokenBudget.subtract(entry.getTokenCount());
        }
        messages.clear();
    }

    /** 仅清空摘要，修正 token 计数（预留接口） */
    public void clearSummaries() {
        for (MemoryEntry entry : summaries) {
            tokenBudget.subtract(entry.getTokenCount());
        }
        summaries.clear();
    }

    // ==================== Token 管理 ====================

    /** 获取 Token 预算管理器（供 Agent 上下文使用） */
    public TokenBudget getTokenBudget() {
        return tokenBudget;
    }

    /** 总条目数（summaries + messages） */
    public int size() {
        return summaries.size() + messages.size();
    }

    /** 重新计算 token（委托 TokenBudget 静态方法，遍历两层） */
    public int calculateTotalTokens() {
        return TokenBudget.calculateTotalTokens(getAllEntries());
    }
}
