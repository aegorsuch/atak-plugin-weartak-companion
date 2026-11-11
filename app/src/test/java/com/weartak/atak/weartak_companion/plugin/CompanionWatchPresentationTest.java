package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CompanionWatchPresentationTest {
    @Test public void headingUsesOnlyAvailableIdentity() {
        assertEquals("Watch", CompanionWatchPresentation.heading(null, "", "Watch"));
        assertEquals("Galaxy Watch Ultra 2", CompanionWatchPresentation.heading(null, "Galaxy Watch Ultra 2", "Watch"));
        assertEquals("Alexander", CompanionWatchPresentation.heading(" Alexander ", null, "Watch"));
        assertEquals("Alexander @ Galaxy Watch Ultra 2",
                CompanionWatchPresentation.heading("Alexander", "Galaxy Watch Ultra 2", "Watch"));
    }

    @Test public void savedServerLabelKeepsAddressAndPortReadable() {
        assertEquals("Field · tak.example.org:8089",
                CompanionWatchPresentation.serverLabel("Field", "tak.example.org", 8089));
        assertEquals("[2001:db8::1]:8089", CompanionWatchPresentation.serverLabel(null, "2001:db8::1", 8089));
        assertEquals("[2001:db8::1]:8089", CompanionWatchPresentation.serverLabel(null, "[2001:db8::1]", 8089));
        assertEquals("Backup", CompanionWatchPresentation.serverLabel("Backup", "", 0));
    }
}
