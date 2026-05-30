 package com.cugb.memory;

import java.util.List;

/**
 * Token 预算管理器，统一负责 token 计数与预算状态控制
 * <p>
 * 双层职责：
 * 1. 静态计数：countTokens() — 供 MemoryEntry 等外部类计算文本 token 数
 * 2. 状态管理：持有 currentToken / maxToken，提供预算增减、阈值判断、校验等能力
 */
public class TokenBudget {

    /** 当前已使用的 token 数 */
    private int currentToken;
    /** 最大 token 数上限 */
    private final int maxToken;

    /**
     * 创建指定上限的预算实例
     *
     * @param maxToken 最大 token 数上限
     */
    public TokenBudget(int maxToken) {
        this.currentToken = 0;
        this.maxToken = maxToken;
    }

    // ==================== 静态计数 ====================

    /**
     * 估算文本的 token 数量（静态工具方法）
     * <p>
     * 中文字符按 ~1.5 字符/token，英文按 ~4 字符/token 估算，
     * 与 DeepSeek 等主流 LLM 的 tokenizer 行为接近。
     *
     * @param text 待计算的文本
     * @return 估算的 token 数量
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int otherChars = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c)) {
                chineseChars++;
            } else if (!Character.isWhitespace(c)) {
                otherChars++;
            }
        }

        // 中文：~1.5 字符/token → 乘以 0.67
        // 英文：~4 字符/token → 除以 4
        int chineseTokens = (int) Math.ceil(chineseChars * 0.67);
        int otherTokens = (int) Math.ceil(otherChars / 4.0);

        return chineseTokens + otherTokens;
    }

    /**
     * 判断字符是否为中文字符
     */
    private static boolean isChinese(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    // ==================== 预算操作 ====================

    /**
     * 增加已使用的 token 数
     *
     * @param tokens 要增加的 token 数
     */
    public void add(int tokens) {
        currentToken += tokens;
    }

    /**
     * 减少已使用的 token 数
     *
     * @param tokens 要减少的 token 数
     */
    public void subtract(int tokens) {
        currentToken -= tokens;
    }

    /**
     * 重置已使用的 token 数为零
     */
    public void reset() {
        currentToken = 0;
    }

    // ==================== 预算查询 ====================

    /**
     * 是否已超出预算上限
     *
     * @return currentToken > maxToken
     */
    public boolean isOverBudget() {
        return currentToken > maxToken;
    }

    /**
     * 是否应触发压缩（超预算或达到触发比率）
     *
     * @param triggerRatio 触发比率（如 0.8 表示使用量达到 80% 时触发）
     * @return 是否应压缩
     */
    public boolean shouldCompact(double triggerRatio) {
        return isOverBudget() || currentToken > maxToken * triggerRatio;
    }

    /**
     * 获取当前已使用的 token 数
     */
    public int getCurrentToken() {
        return currentToken;
    }

    /**
     * 获取最大 token 上限
     */
    public int getMaxToken() {
        return maxToken;
    }

    // ==================== 预算校验 ====================

    /**
     * 重新计算条目列表的总 token 数（用于校验一致性）
     *
     * @param entries 记忆条目列表
     * @return 条目列表的 token 总数
     */
    public static int calculateTotalTokens(List<MemoryEntry> entries) {
        int total = 0;
        for (MemoryEntry entry : entries) {
            total += entry.getTokenCount();
        }
        return total;
    }
}
