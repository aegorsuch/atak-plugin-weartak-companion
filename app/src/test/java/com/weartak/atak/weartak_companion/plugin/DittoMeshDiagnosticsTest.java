package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DittoMeshDiagnosticsTest {
    @Test
    public void distinguishesUnknownZeroAndVisiblePeersWithoutClaimingWatchIdentity() {
        DittoMeshDiagnostics diagnostics = new DittoMeshDiagnostics();
        assertTrue(diagnostics.communityStatusText().contains("checking peers"));
        assertTrue(diagnostics.updatePeers(0));
        assertTrue(diagnostics.communityStatusText().contains("visible peers: 0"));
        assertFalse(diagnostics.updatePeers(0));
        assertTrue(diagnostics.updatePeers(2));
        assertTrue(diagnostics.communityStatusText().contains("visible peers: 2"));
        assertFalse(diagnostics.communityStatusText().contains("watch connected"));

        diagnostics.presenceUnavailable();
        assertTrue(diagnostics.communityStatusText().contains("peer count unavailable"));
        assertTrue(diagnostics.logMessage().contains("visible-peers=unknown"));
        assertTrue(diagnostics.updatePeers(2));
        assertTrue(diagnostics.communityStatusText().contains("visible peers: 2"));
    }

    @Test
    public void separatesEachCollectionsInitialCacheFromLaterForwardedChanges() {
        DittoMeshDiagnostics diagnostics = new DittoMeshDiagnostics();
        assertTrue(diagnostics.recordSnapshot("track", 3));
        assertTrue(diagnostics.recordSnapshot("mapitem", 2));
        assertTrue(diagnostics.recordSnapshot("alert", 0));
        assertTrue(diagnostics.communityStatusText().contains("Ditto forwards: 5 (cache included)"));
        assertTrue(diagnostics.communityStatusText().contains("after initial load: 0"));

        assertFalse(diagnostics.recordSnapshot("track", 0));
        assertTrue(diagnostics.recordSnapshot("track", 1));
        assertTrue(diagnostics.recordSnapshot("alert", 1));
        assertTrue(diagnostics.logMessage().contains("forwarded=7, post-initial-forwards=2"));
    }

    @Test
    public void startsEachConnectionWithoutPreviousPeerOrDocumentEvidence() {
        DittoMeshDiagnostics previous = new DittoMeshDiagnostics();
        previous.updatePeers(1);
        previous.recordSnapshot("track", 4);
        previous.recordSnapshot("track", 2);

        DittoMeshDiagnostics next = new DittoMeshDiagnostics();
        assertTrue(next.logMessage().contains("visible-peers=unknown"));
        assertTrue(next.logMessage().contains("forwarded=0, post-initial-forwards=0"));
        assertTrue(next.recordSnapshot("track", 1));
        assertTrue(next.logMessage().contains("forwarded=1, post-initial-forwards=0"));
    }
}
