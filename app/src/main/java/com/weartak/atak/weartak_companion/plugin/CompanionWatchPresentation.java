package com.weartak.atak.weartak_companion.plugin;

final class CompanionWatchPresentation {
    private CompanionWatchPresentation() {}

    static String heading(String callsign, String model, String fallback) {
        String who = trimmed(callsign);
        String device = trimmed(model);
        if (who.isEmpty()) return device.isEmpty() ? fallback : device;
        return device.isEmpty() ? who : who + " @ " + device;
    }

    static String serverLabel(String name, String address, int port) {
        String serverName = trimmed(name);
        String endpoint = trimmed(address);
        if (endpoint.isEmpty()) return serverName;
        if (port > 0) {
            if (endpoint.indexOf(':') >= 0 && !endpoint.startsWith("[")) endpoint = "[" + endpoint + "]";
            endpoint += ":" + port;
        }
        return serverName.isEmpty() || serverName.equals(endpoint)
                ? endpoint : serverName + " · " + endpoint;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
