package com.cugb.llm;

/**
 * 提示词模板枚举，每个常量对应 resources/prompts/ 下的一个 md 文件。
 * 外部类通过枚举获取模板内容，禁止直接调用 PromptLoader.load()。
 */
public enum PromptTemplate {

    /** Agent (ReAct) 系统提示词 */
    AGENT_SYSTEM("agent-system"),

    /** 单任务执行提示词 */
    EXECUTE_TASK("execute-task"),

    /** 任务规划提示词 */
    PLANNER_PLAN("planner-plan"),

    /** 重新规划提示词 */
    PLANNER_REPLAN("planner-replan");

    private final String fileName;
    private volatile String cachedContent;

    PromptTemplate(String fileName) {
        this.fileName = fileName;
    }

    /**
     * 加载提示词内容（惰性缓存，线程安全）。
     */
    public String load() {
        String content = cachedContent;
        if (content == null) {
            synchronized (this) {
                content = cachedContent;
                if (content == null) {
                    content = PromptLoader.load(fileName);
                    cachedContent = content;
                }
            }
        }
        return content;
    }
}
