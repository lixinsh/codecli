package com.cugb.memory;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆基本单元，用于存入上下文信息、数据库存储及 token 计算
 */
@Data
public class MemoryEntry {

    private final String id;                // 记忆唯一标识
    private String sessionId;               // 所属会话id（预留字段）
    private String context;                 // 上下文内容
    private String role;                    // LLM message角色（user/assistant/system/tool）
    private Map<String, String> metaData;   // 元数据（tool调用信息等）
    private final Instant createdAt;        // 创建时间戳（UTC绝对点）
    private int tokenCount;                 // token计数
    private final MemoryType type;          // 记忆类型
    /**
     * 记忆类型枚举
     */
    public enum MemoryType {
        CONVERSATION,   // 对话记忆
        FACT,           // 事实记忆
        SUMMARY,        // 压缩后的摘要
        TOOL_RESULT     // 工具结果
    }

    /**
     * 创建记忆实例
     * id、createdAt、tokenCount 自动生成
     *
     * @param context  上下文内容
     * @param role     LLM message 角色
     * @param metaData 元数据（如 tool 调用信息）
     * @param type     记忆类型
     */
    public MemoryEntry(String context, String role, Map<String, String> metaData, MemoryType type) {
        this.id = UUID.randomUUID().toString();
        this.context = context;
        this.role = role;
        this.metaData = metaData;
        this.createdAt = Instant.now();
        this.tokenCount = TokenBudget.countTokens(context);
        this.type = type;
    }

}

