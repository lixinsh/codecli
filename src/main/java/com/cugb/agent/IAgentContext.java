package com.cugb.agent;

import com.cugb.llm.DsClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Agent 上下文管理统一接口
 * <p>
 * 提供统一的上下文管理抽象，支持不同模式的 Agent：
 * - ReAct 模式：维护完整的对话历史
 * - Plan-and-Execute 模式：维护任务间的摘要传递
 * <p>
 * 设计原则：
 * 1. 规划阶段（Planning）和执行阶段（Execution）可以共享同一个上下文实例
 * 2. 用户可以在运行时切换模式，上下文保持连续
 * 3. 支持上下文的压缩和清理，避免 token 爆炸
 */
public interface IAgentContext {

    /**
     * 添加一条消息到上下文
     *
     * @param role    角色（system/user/assistant/tool）
     * @param content 消息内容
     */
    void addMessage(String role, String content);

    /**
     * 添加一条工具响应消息
     *
     * @param content     工具执行结果
     * @param toolCallId  工具调用ID
     */
    void addToolMessage(String content, String toolCallId);

    /**
     * 添加一条带工具调用的消息
     *
     * @param role             角色（通常为 assistant）
     * @param content          消息内容（可为 null）
     * @param toolCalls        工具调用列表（JSON）
     * @param reasoningContent 推理内容（thinking 模式）
     */
    void addMessageWithToolCalls(String role, String content, 
                                  JsonNode toolCalls,
                                  String reasoningContent);

    /**
     * 获取当前上下文的消息列表（用于发送给 LLM）
     *
     * @return 消息列表
     */
    List<DsClient.Message> getMessages();

    /**
     * 清空上下文（用于重置或开始新的会话）
     */
    void clear();

    /**
     * 检查是否需要压缩上下文
     *
     * @return 是否需要压缩
     */
    boolean shouldCompact();

    /**
     * 执行上下文压缩（如果支持）
     */
    void compact();

    /**
     * 获取当前上下文的消息数量
     *
     * @return 消息数量
     */
    int getMessageCount();

    /**
     * 获取当前已使用的 token 数量（仅 messages + summaries 的预算追踪值）
     *
     * @return 当前预算追踪 token 数
     */
    int getCurrentTokenCount();

    /**
     * 获取最大 token 上限
     *
     * @return 最大 token 数
     */
    int getMaxTokenLimit();

    // ==================== 各组件 token 明细（供 /context 显示） ====================

    /** 获取 basePrompt 的 token 数（构造时计算，运行时不变） */
    int getBasePromptTokens();

    /** 获取当前 instruction（PE 模式指令）的 token 数，无指令时为 0 */
    int getInstructionTokens();

    /** 获取 messages + summaries 的预算追踪 token 数 */
    int getMessageTokens();

    // ==================== 工具 token 管理 ====================

    /** 设置当前生效的工具列表的 token 数 */
    void setToolTokens(int toolTokens);

    /** 获取上次设置的工具列表 token 数 */
    int getToolTokens();

    /** 获取摘要条目数量 */
    int getSummaryCount();

    /**
     * 清除所有 SUMMARY 类型的条目（压缩产生的摘要）
     * <p>
     * 使用场景：Plan-and-Execute 模式执行完毕后，任务执行期间产生的
     * 压缩摘要已不再需要，应清除以避免污染后续交互的 system prompt。
     */
    void clearSummaries();

    /**
     * 设置当前模式指令（临时替换 system prompt）
     * <p>
     * 设置后，getMessages() 将使用该指令替代 basePrompt 作为 system 消息内容。
     * 典型用法：PE 模式执行前设为 EXECUTE_TASK_PROMPT，执行后 clearInstruction() 恢复。
     *
     * @param instruction 模式指令（如 EXECUTE_TASK_PROMPT）
     */
    void setInstruction(String instruction);

    /**
     * 清除当前模式指令，恢复默认 basePrompt
     */
    void clearInstruction();
}
