package com.cugb.cli;

/**
 * CLI UI utility: colors, icons, separators, and formatted output.
 * Uses ANSI 16-color (supports Windows Terminal / macOS / Linux).
 * Legacy cmd.exe requires Win10+ with VT enabled.
 */
public final class ConsoleUI {

    // ==================== ANSI 颜色 ====================

    public static final String RESET   = "\033[0m";
    public static final String BOLD    = "\033[1m";
    public static final String DIM     = "\033[2m";

    public static final String RED     = "\033[31m";
    public static final String GREEN   = "\033[32m";
    public static final String YELLOW  = "\033[33m";
    public static final String BLUE    = "\033[34m";
    public static final String MAGENTA = "\033[35m";
    public static final String CYAN    = "\033[36m";
    public static final String GRAY    = "\033[90m";

    // ==================== Icons ====================

    public static final String ICON_USER    = "●";
    public static final String ICON_AGENT   = "◉";
    public static final String ICON_TOOL    = "⚙";
    public static final String ICON_SUCCESS = "✓";
    public static final String ICON_ERROR   = "✗";
    public static final String ICON_WARN    = "⚠";
    public static final String ICON_INFO    = "ℹ";
    public static final String ICON_CLEAR   = "♻";

    // ==================== Separators ====================

    /** Single-line separator (default 60 chars) */
    private static final int SEP_LEN = 60;

    private static String repeat(String ch, int n) {
        return ch.repeat(n);
    }

    /** 单线分隔符 */
    public static String sep() {
        return repeat("─", SEP_LEN);
    }

    /** Double-line separator */
    public static String sepDouble() {
        return repeat("═", SEP_LEN);
    }

    /** Thin dot separator */
    public static String sepThin() {
        return repeat("·", SEP_LEN);
    }

    // ==================== Convenience output ====================

    public static void line() {
        System.out.println(sep());
    }

    public static void lineDouble() {
        System.out.println(sepDouble());
    }

    public static void newline() {
        System.out.println();
    }

    /** Green success */
    public static void success(String msg) {
        System.out.println(GREEN + "  " + ICON_SUCCESS + "  " + msg + RESET);
    }

    /** Red error */
    public static void error(String msg) {
        System.err.println(RED + "  " + ICON_ERROR + "  " + msg + RESET);
    }

    /** Yellow warning */
    public static void warn(String msg) {
        System.out.println(YELLOW + "  " + ICON_WARN + "  " + msg + RESET);
    }

    /** Cyan info */
    public static void info(String msg) {
        System.out.println(CYAN + "  " + ICON_INFO + "  " + msg + RESET);
    }

    /** Gray secondary info */
    public static void dim(String msg) {
        System.out.println(GRAY + "  " + msg + RESET);
    }

    /** Agent step indicator */
    public static void agentStep(String label) {
        System.out.println(DIM + "  " + ICON_AGENT + "  " + label + RESET);
    }

    /** Tool call hint */
    public static void toolCall(String toolNames) {
        System.out.println(GRAY + "  " + ICON_TOOL + "  " + toolNames + RESET);
    }

    // ==================== Panels ====================

    /**
     * Bordered panel with optional title.
     */
    public static void bordered(String title, String[] body) {
        int width = SEP_LEN - 2;

        // 顶边
        if (title != null && !title.isEmpty()) {
            String top = "┌─ " + BOLD + title + RESET + " ─";
            int used = title.length() + 4;
            String fill = repeat("─", Math.max(0, width - used));
            System.out.println(top + fill + "┐");
        } else {
            System.out.println("┌" + repeat("─", width) + "┐");
        }

        // 内容行
        for (String line : body) {
            if (line == null || line.isEmpty()) {
                System.out.println("│" + repeat(" ", width) + "│");
            } else {
                System.out.println("│  " + line + repeat(" ", Math.max(0, width - line.length() - 2)) + "│");
            }
        }

        // 底边
        System.out.println("└" + repeat("─", width) + "┘");
    }

    /**
     * Token usage progress bar.
     */
    public static String tokenBar(int current, int max, int barLen) {
        double pct = max > 0 ? (double) current / max : 0;
        int filled = (int) Math.round(pct * barLen);
        filled = Math.min(filled, barLen);
        filled = Math.max(filled, 0);

        String color = current > max ? RED : (pct > 0.8 ? YELLOW : GREEN);
        return color + "█".repeat(filled) + GRAY + "░".repeat(barLen - filled) + RESET;
    }

    private ConsoleUI() { /* utility class */ }
}
