package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public class DittoCotAdapterTest {
    private static final String WATCH_POINT_XML =
            "<event version='2.0' uid='watch-uid' type='a-n-G' "
                    + "time='2023-11-14T22:13:20Z' start='2023-11-14T22:13:20Z' "
                    + "stale='2023-11-14T22:14:20Z' how='m-g' access='Undefined'>"
                    + "<point lat='38.5' lon='-77.2' hae='10.0' ce='1.0' le='2.0'/>"
                    + "<detail><contact callsign='WATCH'/></detail></event>";
    private static final String WATCH_POINT_REVISION =
            "738fd5a5430c531497dc25535eae4e72e5a4fded073e7f2543ed570f0de974b8";

    @Test
    public void decodesWearTakTrackSchemaToCot() throws Exception {
        DittoCotAdapter.Document document = DittoCotAdapter.decode("track", live("a-f-G-U-C"));

        assertNotNull(document);
        assertEquals("watch-uid", document.uid);
        assertEquals("watch-peer", document.peer);
        assertTrue(document.xml.contains("uid='watch-uid'"));
        assertTrue(document.xml.contains("type='a-f-G-U-C'"));
        assertTrue(document.xml.contains("lat='38.5' lon='-77.2'"));
        assertTrue(document.xml.endsWith("<detail><contact callsign='WATCH'/></detail></event>"));
        assertTrue(!document.deleted);
    }

    @Test
    public void rejectsWrongCollectionAndMismatchedDocumentIdentity() throws Exception {
        expectInvalid("track", live("a-n-G"));

        JSONObject raw = new JSONObject(live("a-f-G-U-C"));
        raw.put("_id", "different-uid");
        expectInvalid("track", raw.toString());
    }

    @Test
    public void acceptsWatchProducedWearTakPointTombstone() throws Exception {
        JSONObject raw = new JSONObject(live("a-n-G"));
        DittoCotAdapter.Document liveDocument = DittoCotAdapter.decode("mapitem", raw.toString());
        assertEquals(WATCH_POINT_XML, liveDocument.xml);
        assertEquals(WATCH_POINT_REVISION, sha256(liveDocument.xml));

        raw.put("_r", true).put("visible", false).put("f", false);
        raw.put("weartakPoint", new JSONObject()
                .put("version", 1)
                .put("owner", "watch-uid")
                .put("revision", WATCH_POINT_REVISION)
                .put("deleted", true)
                .put("deletedAt", 1_700_000_000_001L));

        DittoCotAdapter.Document deleted = DittoCotAdapter.decode("mapitem", raw.toString());
        assertNotNull(deleted);
        assertTrue(deleted.deleted);
        assertEquals("watch-uid", deleted.uid);

        raw.getJSONObject("weartakPoint").put("owner", "other-owner");
        expectInvalid("mapitem", raw.toString());
    }

    @Test
    public void rejectsEntityExpansionInDittoDetail() throws Exception {
        JSONObject raw = new JSONObject(live("a-f-G-U-C"));
        raw.put("r", "<!DOCTYPE detail [<!ENTITY x SYSTEM 'file:///private'>]><detail>&x;</detail>");
        expectInvalid("track", raw.toString());
    }

    private static String live(String type) throws Exception {
        return new JSONObject()
                .put("_id", "watch-uid")
                .put("_r", false)
                .put("_v", 2)
                .put("AuthorId", "watch-uid")
                .put("visible", true)
                .put("a", "watch-peer")
                .put("b", 1_700_000_000_000L)
                .put("c", "WATCH")
                .put("d", "watch-uid")
                .put("e", "WATCH")
                .put("f", true)
                .put("g", "2.0")
                .put("h", 1.0)
                .put("i", 10.0)
                .put("j", 38.5)
                .put("k", 2.0)
                .put("l", -77.2)
                .put("n", 1_700_000_000_000L)
                .put("o", 1_700_000_060_000L)
                .put("p", "m-g")
                .put("q", "Undefined")
                .put("r", "<detail><contact callsign='WATCH'/></detail>")
                .put("w", type).toString();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static void expectInvalid(String collection, String json) throws Exception {
        try {
            DittoCotAdapter.decode(collection, json);
            fail("Expected Ditto schema validation to fail");
        } catch (Exception expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
