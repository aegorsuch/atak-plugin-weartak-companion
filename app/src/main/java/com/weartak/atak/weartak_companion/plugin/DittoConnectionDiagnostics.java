package com.weartak.atak.weartak_companion.plugin;

import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;

final class DittoConnectionDiagnostics {
    private DittoConnectionDiagnostics() {
    }

    static void runConnectionAttempt(Runnable connect, Consumer<Throwable> reportFailure) {
        try {
            connect.run();
        } catch (Exception | LinkageError error) {
            reportFailure.accept(error);
        }
    }

    static String category(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof LinkageError) return "sdk-linkage";
            String type = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (type.contains("auth") || type.contains("token")) return "authentication";
            if (current instanceof SecurityException) return "permissions";
            if (current instanceof IOException || type.contains("network")
                    || type.contains("connect") || type.contains("transport")
                    || type.contains("timeout")) return "network";
            if (current instanceof IllegalArgumentException) return "configuration";
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return "sdk";
    }

    static String exceptionType(Throwable error) {
        Throwable current = error;
        Throwable deepest = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            deepest = current;
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        String type = deepest == null ? "" : deepest.getClass().getSimpleName();
        return type.isEmpty() ? "UnknownException" : type;
    }

    static String logMessage(String stage, Throwable error) {
        return "Ditto connect failed; stage=" + safeStage(stage)
                + ", category=" + category(error)
                + ", type=" + exceptionType(error)
                + stackFrames(error);
    }

    private static String stackFrames(Throwable error) {
        StringBuilder frames = new StringBuilder();
        Throwable current = error;
        int remaining = 16;
        for (int depth = 0; current != null && depth < 8 && remaining > 0; depth++) {
            for (StackTraceElement frame : current.getStackTrace()) {
                String owner = frame.getClassName();
                String method = frame.getMethodName();
                if (!(owner.startsWith("com.ditto.") || owner.startsWith("kotlin.")
                        || owner.startsWith("kotlinx.")
                        || owner.startsWith("com.weartak.atak.weartak_companion."))
                        || !safeSymbol(owner) || !safeSymbol(method)) continue;
                frames.append("\n  cause=").append(depth).append(' ')
                        .append(owner).append('#').append(method)
                        .append(':').append(frame.getLineNumber());
                if (--remaining == 0) break;
            }
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return frames.toString();
    }

    private static boolean safeSymbol(String symbol) {
        if (symbol.isEmpty() || symbol.length() > 200) return false;
        for (int i = 0; i < symbol.length(); i++) {
            char character = symbol.charAt(i);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '.' && character != '$' && character != '_'
                    && character != '<' && character != '>') return false;
        }
        return true;
    }

    static String failureMessage(boolean community, String stage, Throwable error) {
        return (community ? "Community sync" : "ESH sync") + " failed at "
                + safeStage(stage) + " (" + category(error) + ", "
                + exceptionType(error) + ").";
    }

    private static String safeStage(String stage) {
        if (stage == null || stage.isEmpty() || stage.length() > 48) return "unknown";
        for (int i = 0; i < stage.length(); i++) {
            char character = stage.charAt(i);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '-') return "unknown";
        }
        return stage;
    }
}
