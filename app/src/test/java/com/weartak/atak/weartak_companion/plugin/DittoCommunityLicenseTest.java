package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.*;

public class DittoCommunityLicenseTest {
    private static String encode(String json) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(output)) {
            zip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray());
    }

    private static final String VALID =
            "{\"v\":2,\"it\":\"SK\",\"a\":\"test-app\",\"s\":\"test-key\","
                    + "\"t\":\"test-token\",\"e\":2000}";

    @Test public void decodesValidLicenseAndRejectsExpiryBoundary() throws Exception {
        DittoCommunityLicense license = DittoCommunityLicense.parse(encode(VALID), 1999);
        assertEquals("test-app", license.appId);
        assertEquals("test-key", license.sharedKey);
        assertEquals("test-token", license.token);
        assertRejected(encode(VALID), 2000);
    }

    @Test public void rejectsMalformedUnsupportedAndIncompleteLicensesWithoutLeakingInput()
            throws Exception {
        assertRejected("sensitive!invalid", 0);
        assertRejected(encode("not json"), 0);
        assertRejected(encode(VALID.replace("\"v\":2", "\"v\":3")), 0);
        assertRejected(encode(VALID.replace("\"SK\"", "\"other-secret\"")), 0);
        assertRejected(encode(VALID.replace("\"test-key\"", "\"\"")), 0);
        assertRejected(encode(VALID.replace("\"test-token\"", "\"\"")), 0);
        assertRejected(encode(VALID.replace("\"test-app\"", "\"\"")), 0);
        assertRejected(encode(VALID.replace("\"test-token\"", "null")), 0);
        assertRejected(encode(VALID.replace("\"e\":2000", "\"e\":0")), 0);
        assertRejected(encode("{\"v\":2,\"it\":\"SK\",\"s\":\"sensitive-secret\""), 0);
        assertRejected("", 0);
    }

    private static void assertRejected(String input, long now) {
        try {
            DittoCommunityLicense.parse(input, now);
            fail("Expected invalid license");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.toString().contains(input) && !input.isEmpty());
            assertFalse(expected.toString().contains("sensitive-secret"));
            assertNull(expected.getCause());
        }
    }
}
