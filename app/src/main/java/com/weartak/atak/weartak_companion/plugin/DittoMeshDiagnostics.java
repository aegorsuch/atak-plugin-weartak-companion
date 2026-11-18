package com.weartak.atak.weartak_companion.plugin;

import java.util.HashSet;
import java.util.Set;

final class DittoMeshDiagnostics {
    private final Set<String> initialSnapshots = new HashSet<>();
    private int visiblePeers = -1;
    private boolean presenceFailed;
    private long forwardedDocuments;
    private long postInitialForwards;

    synchronized boolean updatePeers(int count) {
        if (count < 0) throw new IllegalArgumentException("Peer count must not be negative");
        boolean changed = visiblePeers != count || presenceFailed;
        visiblePeers = count;
        presenceFailed = false;
        return changed;
    }

    synchronized void presenceUnavailable() {
        visiblePeers = -1;
        presenceFailed = true;
    }

    synchronized boolean recordSnapshot(String collection, int forwarded) {
        if (forwarded < 0) throw new IllegalArgumentException("Forward count must not be negative");
        boolean initial = initialSnapshots.add(collection);
        forwardedDocuments += forwarded;
        if (!initial) postInitialForwards += forwarded;
        return initial || forwarded > 0;
    }

    synchronized String communityStatusText() {
        String peers = visiblePeers >= 0 ? "visible peers: " + visiblePeers
                : presenceFailed ? "peer count unavailable" : "checking peers";
        return "Community sync active; " + peers + ".\nDitto forwards: "
                + forwardedDocuments + " (cache included); after initial load: " + postInitialForwards + ".";
    }

    synchronized String logMessage() {
        return "Mesh diagnostics; visible-peers=" + (visiblePeers < 0 ? "unknown" : visiblePeers)
                + ", presence=" + (presenceFailed ? "unavailable" : visiblePeers < 0 ? "pending" : "available")
                + ", forwarded=" + forwardedDocuments + ", post-initial-forwards=" + postInitialForwards;
    }
}
