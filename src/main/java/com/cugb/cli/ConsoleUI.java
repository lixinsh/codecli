package com.cugb.cli;

/**
 * CLI UI utility: colors, icons, separators, and formatted output.
 * Uses ANSI 16-color codes (supports Windows Terminal / macOS / Linux).
 *
 * On Windows, tries to enable Virtual Terminal processing via JNA.
 * Falls back to plain ASCII when ANSI is not available.
 */
public final class ConsoleUI {

    // ==================== ANSI availability ====================

    /** Whether ANSI escape codes are supported in this terminal. */
    public static final boolean ANSI;

    static {
        TerminalSupport.enableAnsi();
        ANSI = TerminalSupport.isAnsiSupported();
    }

    // ==================== ANSI 颜色（仅在 ANSI 模式下有效） ====================

    private static String esc(String code) {
        return ANSI ? ("\033[" + code) : "";
    }

    public static final String RESET   = esc("0m");
    public static final String BOLD    = esc("1m");
    public static final String DIM     = esc("2m");

    public static final String RED     = esc("31m");
    public static final String GREEN   = esc("32m");
    public static final String YELLOW  = esc("33m");
    public static final String BLUE    = esc("34m");
    public static final String MAGENTA = esc("35m");
    public static final String CYAN    = esc("36m");
    public static final String GRAY    = esc("90m");

    // ==================== Icons（ANSI / ASCII 双模式） ====================

    public static final String ICON_USER    = ANSI ? "●" : "> ";
    public static final String ICON_AGENT   = ANSI ? "◉" : "* ";
    public static final String ICON_TOOL    = ANSI ? "⚙" : "~ ";
    public static final String ICON_SUCCESS = ANSI ? "✓" : "[OK]";
    public static final String ICON_ERROR   = ANSI ? "✗" : "[ERR]";
    public static final String ICON_WARN    = ANSI ? "⚠" : "[WARN]";
    public static final String ICON_INFO    = ANSI ? "ℹ" : "[i]";
    public static final String ICON_CLEAR   = ANSI ? "♻" : "[CLEAR]";

    // ==================== Separators ====================

    /** Single-line separator (default 60 chars) */
    private static final int SEP_LEN = 60;

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
        if (ANSI) {
            System.err.println(RED + "  " + ICON_ERROR + "  " + msg + RESET);
        } else {
            System.err.println("  " + ICON_ERROR + "  " + msg);
        }
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
     * Uses Unicode box-drawing chars on ANSI terminals, ASCII fallback otherwise.
     */
    public static void bordered(String title, String[] body) {
        int innerWidth = SEP_LEN - 2; // width inside the border

        // ---- border characters ----
        String tl, tr, bl, br, h, v;

        if (ANSI) {
            tl = "┌"; tr = "┐"; bl = "└"; br = "┘"; h = "─"; v = "│";
        } else {
            tl = "+"; tr = "+"; bl = "+"; br = "+"; h = "-"; v = "|";
        }

        // ---- top border ----
        if (title != null && !title.isEmpty()) {
            String rawTitle = stripAnsi(title);
            String top = tl + h + " " + title + " " + RESET;
            int used = rawTitle.length() + 4; // "─ title ─"
            String fill = repeat(h, Math.max(0, innerWidth - used));
            System.out.println(top + fill + tr);
        } else {
            System.out.println(tl + repeat(h, innerWidth) + tr);
        }

        // ---- body rows ----
        for (String line : body) {
            if (line == null || line.isEmpty()) {
                System.out.println(v + repeat(" ", innerWidth) + v);
            } else {
                int visibleLen = stripAnsi(line).length();
                int pad = Math.max(0, innerWidth - visibleLen - 2);
                System.out.println(v + "  " + line + repeat(" ", pad) + v);
            }
        }

        // ---- bottom border ----
        System.out.println(bl + repeat(h, innerWidth) + br);
    }

    // ==================== Progress bar ====================

    /**
     * Token usage progress bar.
     */
    public static String tokenBar(int current, int max, int barLen) {
        double pct = max > 0 ? (double) current / max : 0;
        int filled = (int) Math.round(pct * barLen);
        filled = Math.min(filled, barLen);
        filled = Math.max(filled, 0);

        String color = current > max ? RED : (pct > 0.8 ? YELLOW : GREEN);
        return color + repeat("█", filled) + GRAY + repeat("░", barLen - filled) + RESET;
    }

    // ==================== Internal helpers ====================

    private static String repeat(String ch, int n) {
        if (n <= 0) return "";
        // Use StringBuilder for efficiency (but ch.repeat works on Java 11+)
        StringBuilder sb = new StringBuilder(ch.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * Strip ANSI escape codes to get visible character count.
     */
    private static String stripAnsi(String s) {
        if (s == null) return "";
        // Remove CSI sequences: ESC [ ... m  and  ESC [ ... ; ... m
        return s.replaceAll("\033\\[[;\\d]*m", "");
    }

    private ConsoleUI() { /* utility class */ }
}