package com.cugb.cli;

import com.cugb.agent.Agent;
import com.cugb.agent.IAgentContext;
import com.cugb.agent.PlanExecutionAgent;
import com.cugb.agent.UnifiedAgentContext;
import com.cugb.config.AppConfig;
import com.cugb.llm.DsClient;
import com.cugb.llm.PromptTemplate;

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
        Scanner scanner = new Scanner(System.in);

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

            if (input.equalsIgnoreCase("/plan-and-execute")) {
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
                String modeLabel;
                if (currentMode == Mode.REACT) {
                    response = reactAgent.run(input);
                    modeLabel = BLUE + "ReAct" + RESET;
                } else {
                    response = peAgent.run(input);
                    modeLabel = MAGENTA + "Plan" + RESET;
                }

                // 用面板包裹响应
                newline();
                String header = ICON_AGENT + "  " + modeLabel;
                String[] body = wrapLines(response, 54);
                bordered(header, body);
            } catch (Exception e) {
                error(e.getMessage());
            }
        }

        scanner.close();
    }

    // ==================== 上下文信息 ====================

    private static void printContextInfo(IAgentContext context) {
        int current = context.getCurrentTokenCount();
        int max = context.getMaxTokenLimit();
        int msgCount = context.getMessageCount();
        double pct = max > 0 ? (double) current / max * 100.0 : 0;

        // 角色统计
        List<DsClient.Message> messages = context.getMessages();
        int userCount = 0, assistantCount = 0, toolCount = 0, systemCount = 0;
        for (DsClient.Message msg : messages) {
            switch (msg.role()) {
                case "user"      -> userCount++;
                case "assistant" -> assistantCount++;
                case "tool"      -> toolCount++;
                case "system"    -> systemCount++;
            }
        }

        String bar = tokenBar(current, max, 42);
        String remaining = String.format("%,d", Math.max(0, max - current));
        String usage = String.format("%,d / %,d tokens (%.1f%%)", current, max, pct);
        String msgInfo = msgCount + " messages  ·  user:" + userCount
                + "  assistant:" + assistantCount + "  tool:" + toolCount + "  system:" + systemCount;
        String statusLine = current > max
                ? RED + "OVER BUDGET" + RESET + "  (" + remaining + " remaining)"
                : GREEN + "OK" + RESET + "  (" + remaining + " remaining)";

        String[] body = {
                "",
                "Tokens     " + usage,
                "           [" + bar + "]",
                "",
                "Messages   " + msgInfo,
                "",
                "Status     " + statusLine,
        };

        newline();
        bordered("Context", body);
        newline();
    }

    // ==================== 辅助方法 ====================

    /** 将长文本按最大宽度折行 */
    private static String[] wrapLines(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return new String[]{""};
        String[] paragraphs = text.split("\n");
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                lines.add("");
                continue;
            }
            // 简单按字符截断（英文环境，中文可改用更智能的策略）
            int pos = 0;
            while (pos < para.length()) {
                int end = Math.min(pos + maxWidth, para.length());
                lines.add(para.substring(pos, end));
                pos = end;
            }
        }
        return lines.toArray(new String[0]);
    }

    private static void printHelp() {
        dim("  /react           Switch to ReAct mode");
        dim("  /plan-and-exec   Switch to Plan-and-Execute mode");
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
