package com.weartak.atak.weartak_companion.plugin;

import java.io.IOException;
import java.util.Locale;

final class DittoConnectionDiagnostics {
    private DittoConnectionDiagnostics() {
    }

    static String category(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
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
                + ", type=" + exceptionType(error);
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
