package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DittoConnectionDiagnosticsTest {
    @Test
    public void reportsDeepNetworkCauseAndStageWithoutExceptionMessage() {
        Exception error = new IllegalStateException(
                "authorizationToken=do-not-log",
                new SocketTimeoutException("private endpoint"));

        assertEquals("Ditto connect failed; stage=sync-start, category=network, "
                        + "type=SocketTimeoutException",
                DittoConnectionDiagnostics.logMessage("sync-start", error));
        String status = DittoConnectionDiagnostics.failureMessage(
                true, "sync-start", error);
        assertTrue(status.contains("Community sync failed at sync-start (network, "
                + "SocketTimeoutException)."));
        assertFalse(status.contains("do-not-log"));
        assertFalse(status.contains("private endpoint"));
    }

    @Test
    public void categorizesConfigurationAndPermissionFailures() {
        assertEquals("configuration",
                DittoConnectionDiagnostics.category(new IllegalArgumentException("private")));
        assertEquals("permissions",
                DittoConnectionDiagnostics.category(new SecurityException("private")));
        assertEquals("network", DittoConnectionDiagnostics.category(
                new IOException("private")));
    }

    @Test
    public void rejectsUnsafeStageText() {
        String status = DittoConnectionDiagnostics.failureMessage(
                true, "token=private", new RuntimeException("private"));
        assertTrue(status.contains("failed at unknown (sdk, RuntimeException)."));
        assertFalse(status.contains("private"));
    }
}
