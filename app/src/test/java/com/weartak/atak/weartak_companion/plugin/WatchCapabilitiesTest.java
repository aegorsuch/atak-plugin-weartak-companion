package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class WatchCapabilitiesTest {
  private JSONObject response(String requestId) throws Exception {
    return new JSONObject()
        .put("msgType", "watch_capabilities")
        .put("version", 1)
        .put("requestId", requestId)
        .put("watchUid", "watch-a")
        .put("software", new JSONObject().put("versionName", "5.8.4").put("versionCode", 584))
        .put("plugins", new JSONArray())
        .put("features", new JSONObject().put("voice_channels_v1", 1));
  }

  @Test
  public void onlyFreshSessionAndNonceEnableFeature() throws Exception {
    WatchCapabilities caps = new WatchCapabilities();
    JSONObject request = new JSONObject(caps.request(10));
    JSONObject reply = response(request.getString("requestId"));
    assertFalse(caps.supports("voice_channels_v1", 10));
    assertFalse(caps.accept(reply, 11));
    assertTrue(caps.accept(reply, 10));
    assertTrue(caps.supports("voice_channels_v1", 10));
    assertFalse(caps.supports("voice_channels_v1", 11));
    caps.request(10);
    assertFalse(caps.accept(reply, 10));
    assertFalse(caps.supports("voice_channels_v1", 10));
  }

  @Test
  public void legacyAndUnknownProtocolNeverGuessSupport() throws Exception {
    WatchCapabilities caps = new WatchCapabilities();
    String nonce = new JSONObject(caps.request(1)).getString("requestId");
    caps.legacy(new JSONObject().put("wtVrs", "99.0"));
    assertFalse(caps.supports("voice_channels_v1", 1));
    assertFalse(caps.accept(response(nonce).put("version", 2), 1));
    assertTrue(caps.accept(response(nonce), 1));
    assertFalse(caps.supports("unknown_future_feature", 1));
    caps.clear();
    assertFalse(caps.supports("voice_channels_v1", 1));
  }

  @Test
  public void boundedInventoryAndExactFeatureVersions() throws Exception {
    WatchCapabilities caps = new WatchCapabilities();
    String nonce = new JSONObject(caps.request(1)).getString("requestId");
    JSONObject reply = response(nonce);
    reply.getJSONObject("features").put("voice_channels_v1", 2);
    assertTrue(caps.accept(reply, 1));
    assertFalse(caps.supports("voice_channels_v1", 1));
    for (int i = 0; i < 65; i++) reply.getJSONArray("plugins").put(new JSONObject());
    assertFalse(caps.accept(reply, 1));
  }

  @Test
  public void missingHostEndpointExplainsDisabledTransfer() throws Exception {
    WatchCapabilities caps = new WatchCapabilities();
    String nonce = new JSONObject(caps.request(1)).getString("requestId");
    JSONObject reply = response(nonce);
    reply.getJSONObject("features").put("voice_channels_v1", 0);
    reply.put(
        "unavailableReasons",
        new JSONObject().put("voice_channels_v1", "HOST_VOICE_STAGING_UNAVAILABLE"));
    assertTrue(caps.accept(reply, 1));
    assertFalse(caps.supports("voice_channels_v1", 1));
    assertTrue(caps.summary(1).contains("host SDK staging pending"));
  }

  @Test
  public void detailsContainCurrentReportAndCannotMutateTransportState() throws Exception {
    WatchCapabilities caps = new WatchCapabilities();
    String nonce = new JSONObject(caps.request(10)).getString("requestId");
    JSONObject reply = response(nonce);
    reply.getJSONArray("plugins").put(new JSONObject()
        .put("name", "Voice").put("versionName", "0.4.0").put("loaded", true));
    reply.put("voiceReceivers", new JSONArray().put(new JSONObject().put("installed", true)));
    assertTrue(caps.accept(reply, 10));

    JSONObject details = caps.detailsSnapshot(10);
    assertEquals("5.8.4", details.getJSONObject("software").getString("versionName"));
    assertEquals("0.4.0", details.getJSONArray("plugins").getJSONObject(0).getString("versionName"));
    assertTrue(details.getJSONArray("voiceReceivers").getJSONObject(0).getBoolean("installed"));
    details.getJSONObject("features").put("voice_channels_v1", 0);
    assertTrue(caps.supports("voice_channels_v1", 10));
    assertNull(caps.detailsSnapshot(11));
    caps.request(10);
    assertNull(caps.detailsSnapshot(10));
  }
}
