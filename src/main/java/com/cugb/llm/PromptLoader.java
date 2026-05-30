package com.cugb.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词加载器，从 resources/prompts/ 目录读取 md 文件，解耦提示词与 Java 代码。
 * <p>
 * md 文件格式：
 * 第一行为标题（以 # 开头），后面的内容即为提示词正文。
 * 支持缓存，同一文件只读取一次。
 */
public final class PromptLoader {

    private static final String PROMPTS_DIR = "prompts/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptLoader() {
        // 工具类禁止实例化
    }

    /**
     * 加载指定名称的提示词文件（不含 .md 后缀）。
     *
     * @param name 文件名，如 "agent-system"
     * @return 提示词正文（不含 Markdown 标题行）
     * @throws RuntimeException 如果文件不存在或读取失败
     */
    public static String load(String name) {
        return CACHE.computeIfAbsent(name, PromptLoader::readPrompt);
    }

    /**
     * 清除缓存（用于测试或热加载场景）。
     */
    public static void clearCache() {
        CACHE.clear();
    }

    private static String readPrompt(String name) {
        String filePath = PROMPTS_DIR + name + ".md";
        try (InputStream input = PromptLoader.class.getClassLoader().getResourceAsStream(filePath)) {
            if (input == null) {
                throw new RuntimeException("提示词文件不存在: " + filePath);
            }
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return stripMarkdownHeader(content);
        } catch (IOException e) {
            throw new RuntimeException("读取提示词文件失败: " + filePath, e);
        }
    }

    /**
     * 去除 md 文件中的标题行（以 # 开头）和首尾空白。
     */
    private static String stripMarkdownHeader(String content) {
        String[] lines = content.split("\\R");
        StringBuilder sb = new StringBuilder();
        boolean headerSkipped = false;

        for (String line : lines) {
            if (!headerSkipped && line.trim().startsWith("#")) {
                headerSkipped = true;
                continue;
            }
            if (headerSkipped) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
