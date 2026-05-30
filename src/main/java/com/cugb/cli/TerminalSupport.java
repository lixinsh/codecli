package com.cugb.cli;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;

/**
 * Enables ANSI escape code processing on Windows consoles.
 * Uses JNA to call kernel32 API. On non-Windows platforms this is a no-op.
 *
 * Background: Windows cmd.exe and PowerShell don't process ANSI escape codes
 * by default (prior to Win10 1511). Even on Win10+, the console mode flag
 * ENABLE_VIRTUAL_TERMINAL_PROCESSING must be explicitly set.
 */
public final class TerminalSupport {

    private static volatile Boolean ansiSupported = null;

    // ---------- Windows kernel32 interface via JNA ----------

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int STD_OUTPUT_HANDLE = -11;
        int STD_ERROR_HANDLE  = -12;
        int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;

        /** Get stdout/stderr handle */
        int GetStdHandle(int nStdHandle);

        /** Get current console mode flags */
        boolean GetConsoleMode(int hConsoleHandle, IntByReference lpMode);

        /** Set console mode flags */
        boolean SetConsoleMode(int hConsoleHandle, int dwMode);
    }

    // ---------- Public API ----------

    /**
     * Call once at startup to enable ANSI processing.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public static void enableAnsi() {
        if (ansiSupported != null) return; // already initialized

        if (Platform.isWindows()) {
            ansiSupported = enableAnsiWindows();
        } else {
            // macOS / Linux / etc. — always supported
            ansiSupported = true;
        }
    }

    /**
     * Whether ANSI escape codes are supported in the current terminal.
     */
    public static boolean isAnsiSupported() {
        if (ansiSupported == null) {
            enableAnsi();
        }
        return ansiSupported != null && ansiSupported;
    }

    // ---------- Internal ----------

    private static boolean enableAnsiWindows() {
        try {
            // Try stdout
            int stdoutHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE);
            if (stdoutHandle != 0 && stdoutHandle != -1) {
                if (tryEnableVT(stdoutHandle)) {
                    // Also enable stderr
                    int stderrHandle = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_ERROR_HANDLE);
                    if (stderrHandle != 0 && stderrHandle != -1) {
                        tryEnableVT(stderrHandle);
                    }
                    return true;
                }
            }
            // Fallback: check TERM or other env vars
            String term = System.getenv("TERM");
            if (term != null && term.contains("xterm")) {
                return true;
            }
            // Check if running in Windows Terminal or IntelliJ terminal
            String termProg = System.getenv("TERM_PROGRAM");
            if (termProg != null) {
                return true;
            }
            // JetBrains / VSCode terminal check
            String sessionName = System.getenv("TERMINAL_EMULATOR");
            if (sessionName != null && sessionName.equalsIgnoreCase("JetBrains-JediTerm")) {
                return true;
            }
            // VSCode sets this
            String vsCodePid = System.getenv("VSCODE_PID");
            if (vsCodePid != null && !vsCodePid.isEmpty()) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            // JNA may not be available or we got an error — fall back
            return checkEnvFallback();
        }
    }

    private static boolean tryEnableVT(int handle) {
        try {
            IntByReference mode = new IntByReference();
            if (Kernel32.INSTANCE.GetConsoleMode(handle, mode)) {
                int newMode = mode.getValue() | Kernel32.ENABLE_VIRTUAL_TERMINAL_PROCESSING;
                return Kernel32.INSTANCE.SetConsoleMode(handle, newMode);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }

    private static boolean checkEnvFallback() {
        // Environment variable heuristics for ANSI-capable terminals
        String term = System.getenv("TERM");
        if (term != null && (term.contains("xterm") || term.contains("screen") || term.contains("color"))) {
            return true;
        }
        String termProg = System.getenv("TERM_PROGRAM");
        if (termProg != null) return true;
        String ci = System.getenv("CI");
        if (ci != null) return true; // CI environments usually support ANSI
        String idea = System.getenv("IDEA_PROPERTIES");
        if (idea != null) return true;
        return false;
    }

    private TerminalSupport() { /* utility class */ }
}