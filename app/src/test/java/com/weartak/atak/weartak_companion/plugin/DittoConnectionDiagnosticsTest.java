package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DittoConnectionDiagnosticsTest {
    @Test
    public void containsStartupLinkageFailureOnConnectionThread() throws Exception {
        NoSuchMethodError error = new NoSuchMethodError("private endpoint and token");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread thread = new Thread(() -> DittoConnectionDiagnostics.runConnectionAttempt(
                () -> { throw error; }, reported::set));
        thread.setUncaughtExceptionHandler((ignored, failure) -> uncaught.set(failure));
        thread.start();
        thread.join(5000);

        assertFalse("Connection thread did not finish", thread.isAlive());
        assertNull(uncaught.get());
        assertSame(error, reported.get());
        String status = DittoConnectionDiagnostics.failureMessage(
                true, "community-identity", reported.get());
        assertEquals("Community sync failed at community-identity "
                + "(sdk-linkage, NoSuchMethodError).", status);
        assertFalse(DittoConnectionDiagnostics.logMessage(
                "community-identity", reported.get()).contains("private"));
    }

    @Test
    public void doesNotContainVirtualMachineFailure() {
        OutOfMemoryError fatal = new OutOfMemoryError("synthetic");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        assertSame(fatal, assertThrows(OutOfMemoryError.class,
                () -> DittoConnectionDiagnostics.runConnectionAttempt(
                        () -> { throw fatal; }, reported::set)));
        assertNull(reported.get());
    }

    @Test
    public void reportsDeepNetworkCauseAndStageWithoutExceptionMessage() {
        Exception error = new IllegalStateException(
                "authorizationToken=do-not-log",
                new SocketTimeoutException("private endpoint"));

        String log = DittoConnectionDiagnostics.logMessage("sync-start", error);
        assertTrue(log.startsWith("Ditto connect failed; stage=sync-start, category=network, "
                + "type=SocketTimeoutException"));
        assertFalse(log.contains("do-not-log"));
        assertFalse(log.contains("private endpoint"));
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

    @Test
    public void reportsNestedSdkFramesWithoutMessagesOrSourcePaths() {
        Exception cause = new NullPointerException("token=private");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.ditto.dittoffi.MemScope", "convert",
                        "private/location/license.txt", 42),
                new StackTraceElement("com.ditto.Bad", "token=private", "private", 1),
                new StackTraceElement("other.private.Class", "run", "private", 2)
        });
        Exception error = new IllegalStateException("license=private", cause);
        error.setStackTrace(new StackTraceElement[0]);

        String log = DittoConnectionDiagnostics.logMessage("sdk-factory", error);
        assertTrue(log.contains("type=NullPointerException"));
        assertTrue(log.contains("cause=1 com.ditto.dittoffi.MemScope#convert:42"));
        assertFalse(log.contains("private"));
        assertFalse(log.contains("license"));
        assertFalse(log.contains("token"));
    }

    @Test
    public void boundsFramesAndCyclicCauses() {
        Exception first = new Exception("private");
        Exception second = new Exception("private", first);
        first.initCause(second);
        StackTraceElement[] frames = new StackTraceElement[30];
        java.util.Arrays.fill(frames,
                new StackTraceElement("com.ditto.Factory", "create", "private", 12));
        first.setStackTrace(frames);
        second.setStackTrace(frames);

        String log = DittoConnectionDiagnostics.logMessage("sdk-factory", first);
        assertEquals(16, log.split("\n  cause=", -1).length - 1);
        assertFalse(log.contains("private"));
    }
}
