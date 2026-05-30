package com.cugb.memory;

import com.cugb.llm.DsClient;
import com.cugb.llm.PromptLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史压缩器，负责将过长的对话历史压缩为摘要，保留关键上下文信息。
 * <p>
 * 压缩策略：
 * 1. 保留最近5轮对话（用户+LLM各算一轮）
 * 2. 旧对话小于等于10轮：单次调用LLM生成摘要
 * 3. 旧对话超过10轮：Map-Reduce策略
 *    - Map阶段：每10轮一组，并行/串行调用LLM生成分片摘要
 *    - Reduce阶段：将所有分片摘要整合为最终摘要
 * 4. 合并system prompt + 摘要 + 最近5轮对话生成全新上下文
 * 5. 标记已压缩的条目
 */
public class ConversationHistoryCompactor {

    private static final int RECENT_ROUNDS_TO_KEEP = 5;  // 保留最近5轮
    private static final int MAP_GROUP_SIZE = 10;         // Map阶段每组10轮
    
    private final DsClient dsClient;
    private final String systemPrompt;

    /**
     * 创建压缩器实例
     *
     * @param dsClient     LLM客户端
     * @param systemPrompt 系统提示词（Agent角色定义）
     */
    public ConversationHistoryCompactor(DsClient dsClient, String systemPrompt) {
        this.dsClient = dsClient;
        this.systemPrompt = systemPrompt;
    }

    /**
     * 压缩对话历史
     *
     * @param entries 当前所有对话记忆条目（按时间顺序）
     * @return 压缩后的新记忆条目列表（包含摘要和最近对话）
     */
    public List<MemoryEntry> compact(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 分离最近对话和旧对话
        int totalEntries = entries.size();
        int recentCount = Math.min(RECENT_ROUNDS_TO_KEEP * 2, totalEntries); // 每轮=2条(user+assistant)
        
        List<MemoryEntry> recentEntries = entries.subList(
                Math.max(0, totalEntries - recentCount), 
                totalEntries
        );
        
        List<MemoryEntry> oldEntries = entries.subList(0, Math.max(0, totalEntries - recentCount));

        // 2. 如果没有旧对话，直接返回最近对话
        if (oldEntries.isEmpty()) {
            return new ArrayList<>(recentEntries);
        }

        // 3. 生成旧对话摘要
        String summary = generateSummary(oldEntries);

        // 4. 创建摘要记忆条目
        MemoryEntry summaryEntry = createSummaryEntry(summary);

        // 5. 合并：摘要 + 最近对话
        List<MemoryEntry> result = new ArrayList<>();
        result.add(summaryEntry);
        result.addAll(recentEntries);

        return result;
    }

    /**
     * 生成旧对话摘要（根据长度选择单次或Map-Reduce策略）
     *
     * @param oldEntries 旧对话条目
     * @return 生成的摘要文本
     */
    private String generateSummary(List<MemoryEntry> oldEntries) {
        if (oldEntries.size() <= MAP_GROUP_SIZE) {
            // 旧对话较少，单次摘要
            return singlePassSummarize(oldEntries);
        } else {
            // 旧对话较多，Map-Reduce策略
            return mapReduceSummarize(oldEntries);
        }
    }

    /**
     * 单次调用LLM生成摘要
     *
     * @param entries 对话条目
     * @return 摘要文本
     */
    private String singlePassSummarize(List<MemoryEntry> entries) {
        String conversation = formatConversation(entries);
        String prompt = PromptLoader.load("compact-map")
                .replace("{conversation}", conversation);
        
        return callLLMForSummary(prompt);
    }

    /**
     * Map-Reduce策略生成摘要
     *
     * @param entries 对话条目
     * @return 最终整合的摘要文本
     */
    private String mapReduceSummarize(List<MemoryEntry> entries) {
        // Map阶段：分组生成分片摘要
        List<String> chunkSummaries = new ArrayList<>();
        
        for (int i = 0; i < entries.size(); i += MAP_GROUP_SIZE) {
            int end = Math.min(i + MAP_GROUP_SIZE, entries.size());
            List<MemoryEntry> chunk = entries.subList(i, end);
            
            String chunkSummary = singlePassSummarize(chunk);
            chunkSummaries.add(chunkSummary);
        }

        // Reduce阶段:整合所有分片摘要
        StringBuilder summariesTextBuilder = new StringBuilder();
        for (int i = 0; i < chunkSummaries.size(); i++) {
            if (i > 0) {
                summariesTextBuilder.append("\n\n");
            }
            summariesTextBuilder.append("### 片段 ").append(i + 1).append("\n")
                                .append(chunkSummaries.get(i));
        }
        String summariesText = summariesTextBuilder.toString();
        
        String reducePrompt = PromptLoader.load("compact-reduce")
                .replace("{summaries}", summariesText);
        
        return callLLMForSummary(reducePrompt);
    }

    /**
     * 调用LLM生成摘要
     *
     * @param prompt 提示词
     * @return 生成的摘要文本
     */
    private String callLLMForSummary(String prompt) {
        List<DsClient.Message> messages = new ArrayList<>();
        messages.add(new DsClient.Message("system", systemPrompt));
        messages.add(new DsClient.Message("user", prompt));
        
        try {
            var response = dsClient.chat(messages, null);
            
            // 解析响应提取content
            var choices = response.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                var message = choices.get(0).get("message");
                if (message != null) {
                    String content = message.get("content").asText();
                    return content != null ? content.trim() : "";
                }
            }
        } catch (Exception e) {
            System.err.println("生成对话摘要失败: " + e.getMessage());
        }
        
        // 失败时返回简单拼接
        return "[摘要生成失败，保留原始对话]";
    }

    /**
     * 格式化对话条目为文本
     *
     * @param entries 对话条目列表
     * @return 格式化的对话文本
     */
    private String formatConversation(List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < entries.size(); i++) {
            MemoryEntry entry = entries.get(i);
            String role = entry.getRole() != null ? entry.getRole() : "unknown";
            String content = entry.getContext() != null ? entry.getContext() : "";
            
            sb.append("[").append(role.toUpperCase()).append("] ")
              .append(content)
              .append("\n\n");
        }
        
        return sb.toString().trim();
    }

    /**
     * 创建摘要记忆条目
     *
     * @param summary 摘要文本
     * @return 新的记忆条目
     */
    private MemoryEntry createSummaryEntry(String summary) {
        return new MemoryEntry(
                summary,
                "system",
                null,
                MemoryEntry.MemoryType.SUMMARY
        );
    }
}
