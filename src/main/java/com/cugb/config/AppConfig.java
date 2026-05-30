package com.cugb.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 应用配置加载器，统一管理 config.properties 中的所有配置项。
 * <p>
 * 静态初始化时一次性加载配置文件，各模块通过 public static final 常量读取，
 * 不直接依赖 Properties 或文件 I/O。
 */
public final class AppConfig {

    // ==================== LLM 配置 ====================

    /** DeepSeek 模型名称 */
    public static final String LLM_MODEL;
    /** DeepSeek API 地址 */
    public static final String LLM_API_URL;
    /** DeepSeek API Key */
    public static final String LLM_API_KEY;
    /** LLM 请求日志文件路径 */
    public static final String LLM_REQUEST_LOG_FILE;

    // ==================== 上下文配置 ====================

    /** 上下文窗口 token 上限 */
    public static final int CONTEXT_MAX_TOKEN;

    // ==================== 加载逻辑 ====================

    private static final Properties props = new Properties();

    static {
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new RuntimeException("无法找到 config.properties 文件");
            }
            props.load(input);
        } catch (IOException ex) {
            throw new RuntimeException("读取 config.properties 失败", ex);
        }

        LLM_MODEL             = getString("llm.model", "deepseek-v4-pro");
        LLM_API_URL           = getString("llm.api.url", "");
        LLM_API_KEY           = getString("llm.api.key", "");
        LLM_REQUEST_LOG_FILE  = getString("llm.request.log.file", "llm-requests.log");
        CONTEXT_MAX_TOKEN     = getInt("context.max.token", 200_000);
    }

    private AppConfig() {
        // 工具类，禁止实例化
    }

    private static String getString(String key, String defaultValue) {
        String value = props.getProperty(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private static int getInt(String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
