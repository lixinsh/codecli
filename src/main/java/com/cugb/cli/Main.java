package com.cugb.cli;

import com.cugb.agent.Agent;
import com.cugb.agent.IAgentContext;
import com.cugb.agent.PlanExecutionAgent;
import com.cugb.agent.UnifiedAgentContext;
import com.cugb.config.AppConfig;
import com.cugb.llm.DsClient;
import com.cugb.llm.PromptTemplate;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Scanner;

import static com.cugb.cli.ConsoleUI.*;

public class Main {
    /** 运行模式 */
    private enum Mode { REACT, PLAN_EXECUTE }

    public static void main(String[] args){
        printBanner();
        printHelp();

        // 创建共享上下文
        DsClient dsClient = new DsClient();
        IAgentContext sharedContext = new UnifiedAgentContext(
                dsClient,
                PromptTemplate.AGENT_SYSTEM.load(),
                AppConfig.CONTEXT_MAX_TOKEN
        );

        Agent reactAgent = new Agent(sharedContext);
        PlanExecutionAgent peAgent = new PlanExecutionAgent(sharedContext);
        // 使用控制台原生编码读取输入，避免 Windows GBK→UTF-8 乱码
        Charset consoleCharset = System.console() != null
                ? System.console().charset()
                : Charset.defaultCharset();
        Scanner scanner = new Scanner(System.in, consoleCharset);

        Mode currentMode = Mode.REACT;
        info("Current mode: ReAct");

        while (true) {
            // 彩色提示符
            String modeTag = currentMode == Mode.REACT
                    ? BLUE + "ReAct" + RESET
                    : MAGENTA + "Plan" + RESET;
            System.out.print("\n" + modeTag + " " + ICON_USER + " ");

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            // 退出命令
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println(GREEN + "\n  Goodbye!" + RESET);
                break;
            }

            // 模式切换命令
            if (input.equalsIgnoreCase("/react")) {
                currentMode = Mode.REACT;
                line();
                success("Switched to ReAct mode");
                continue;
            }

            if (input.equalsIgnoreCase("/plan")) {
                currentMode = Mode.PLAN_EXECUTE;
                line();
                success("Switched to Plan-and-Execute mode");
                continue;
            }

            // 显示上下文信息
            if (input.equalsIgnoreCase("/context")) {
                printContextInfo(sharedContext);
                continue;
            }

            // 清空历史
            if (input.equalsIgnoreCase("/clear")) {
                sharedContext.clear();
                line();
                success("Conversation history cleared");
                continue;
            }

            // 执行请求
            try {
                String response;
                if (currentMode == Mode.REACT) {
                    response = reactAgent.run(input);
                } else {
                    response = peAgent.run(input);
                }

                // 直接输出响应（不包裹面板）
                newline();
                System.out.println(response);
            } catch (Exception e) {
                error(e.getMessage());
            }
        }

        scanner.close();
    }

    // ==================== 上下文信息 ====================

    private static void printContextInfo(IAgentContext context) {
        int max = context.getMaxTokenLimit();

        // ---- 各组件 token 明细 ----
        int basePromptTokens  = context.getBasePromptTokens();
        int instructionTokens = context.getInstructionTokens();
        int msgTokens = context.getMessageTokens();    // messages + summaries 预算追踪值
        int toolTokens = context.getToolTokens();
        int effectiveTotal = basePromptTokens + instructionTokens + msgTokens + toolTokens;

        double pct = max > 0 ? (double) effectiveTotal / max * 100.0 : 0;
        String bar = tokenBar(effectiveTotal, max, 42);

        // ---- 角色统计 ----
        List<DsClient.Message> messages = context.getMessages();
        int msgEntryCount = context.getMessageCount();
        int summaryCount = context.getSummaryCount();
        int userCount = 0, assistantCount = 0, toolCount = 0, systemCount = 0;
        for (DsClient.Message msg : messages) {
            switch (msg.role()) {
                case "user"      -> userCount++;
                case "assistant" -> assistantCount++;
                case "tool"      -> toolCount++;
                case "system"    -> systemCount++;
            }
        }

        // ---- 状态 ----
        String remaining = String.format("%,d", Math.max(0, max - effectiveTotal));
        String usage = String.format("%,d / %,d tokens (%.1f%%)", effectiveTotal, max, pct);
        String statusLine = effectiveTotal > max
                ? RED + "OVER BUDGET" + RESET + "  (" + remaining + " remaining)"
                : GREEN + "OK" + RESET + "  (" + remaining + " remaining)";

        String instructionLabel = instructionTokens > 0
                ? "  instruction   " + fmt(instructionTokens) + " tokens  (active)"
                : "  instruction   0 tokens  (none)";

        String[] body = {
                "",
                "  system prompt  " + fmt(basePromptTokens) + " tokens",
                instructionLabel,
                "  messages       " + fmt(msgTokens) + " tokens  ("
                        + (msgEntryCount - summaryCount) + " msgs + " + summaryCount + " summaries)",
                "  tools          " + fmt(toolTokens) + " tokens",
                "  " + GRAY + "───────────────" + RESET,
                "  total effect.  " + BOLD + fmt(effectiveTotal) + " / " + fmt(max)
                        + " tokens  (" + String.format("%.1f", pct) + "%)" + RESET,
                "  " + bar,
                "",
                "  entries        " + msgEntryCount + " total  ·  summaries:" + summaryCount,
                "  roles          user:" + userCount + "  assistant:" + assistantCount
                        + "  tool:" + toolCount + "  system:" + systemCount,
                "",
                "  status         " + statusLine,
        };

        newline();
        bordered("Context", body);
        newline();
    }

    /** 格式化整数带千位分隔 */
    private static String fmt(int n) {
        return String.format("%,d", n);
    }

    // ==================== 辅助方法 ====================

    private static void printHelp() {
        dim("  /react           Switch to ReAct mode");
        dim("  /plan            Switch to Plan-and-Execute mode");
        dim("  /context         Show context information");
        dim("  /clear           Clear conversation history");
        dim("  exit / quit      Exit the program");
        newline();
    }

    private static void printBanner() {
        String title = BOLD + BLUE + "pai-cli" + RESET + GRAY + "  v1.0" + RESET;
        String subtitle = GRAY + "AI-Powered Coding Assistant" + RESET;
        System.out.println();
        System.out.println("  " + title + "  —  " + subtitle);
        lineDouble();
    }
}
