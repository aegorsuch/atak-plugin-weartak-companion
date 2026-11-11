package com.weartak.atak.weartak_companion.plugin;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** One fresh response for one ready BLE session. Missing capability means unsupported. */
final class WatchCapabilities {
  private long session = -1;
  private String requestId;
  private JSONObject snapshot;
  private String legacyVersion;

  String request(long readySession) throws Exception {
    session = readySession;
    requestId = UUID.randomUUID().toString();
    snapshot = null;
    return new JSONObject()
        .put("msg_type", "request_capabilities")
        .put("version", 1)
        .put("requestId", requestId)
        .toString();
  }

  void clear() {
    session = -1;
    requestId = null;
    snapshot = null;
    legacyVersion = null;
  }

  boolean accept(JSONObject response, long readySession) {
    if (session != readySession
        || requestId == null
        || !requestId.equals(response.optString("requestId"))
        || !Integer.valueOf(1).equals(response.opt("version"))) return false;
    JSONObject software = response.optJSONObject("software");
    JSONArray plugins = response.optJSONArray("plugins");
    JSONObject features = response.optJSONObject("features");
    if (software == null
        || software.optString("versionName").length() > 128
        || plugins == null
        || plugins.length() > 64
        || features == null
        || response.optString("watchUid").isEmpty()
        || response.toString().length() > 65536) return false;
    try {
      snapshot = new JSONObject(response.toString());
    } catch (Exception e) {
      return false;
    }
    return true;
  }

  String watchUid(long readySession) {
    return readySession == session && snapshot != null ? snapshot.optString("watchUid") : null;
  }

  String requestId(long readySession) {
    return readySession == session && snapshot != null ? requestId : null;
  }

  boolean supports(String feature, long readySession) {
    return readySession == session
        && snapshot != null
        && Integer.valueOf(1).equals(snapshot.optJSONObject("features").opt(feature));
  }

  void legacy(JSONObject payload) {
    if (payload != null) legacyVersion = payload.optString("wtVrs", "unknown");
  }

  String voiceUnavailableReason() {
    JSONObject reasons = snapshot == null ? null : snapshot.optJSONObject("unavailableReasons");
    String reason = reasons == null ? "" : reasons.optString("voice_channels_v1");
    if ("HOST_VOICE_STAGING_UNAVAILABLE".equals(reason))
      return "unavailable: host SDK staging pending";
    if ("VOICE_PLUGIN_NOT_READY".equals(reason)) return "unavailable: receiver not ready";
    if ("AMBIGUOUS_VOICE_RECEIVERS".equals(reason)) return "unavailable: multiple receivers";
    return "unavailable";
  }

  String summary(long readySession) {
    if (readySession < 0) return "Watch capabilities: disconnected";
    if (readySession != session || snapshot == null)
      return "WearTAK "
          + (legacyVersion == null ? "version unknown" : legacyVersion)
          + " · capabilities unavailable (older watch or waiting for response)";
    JSONObject software = snapshot.optJSONObject("software");
    return "WearTAK "
        + software.optString("versionName")
        + " ("
        + software.optLong("versionCode")
        + ")"
        + " · voice "
        + (supports("voice_channels_v1", readySession) ? "available" : voiceUnavailableReason());
  }

  JSONObject detailsSnapshot(long readySession) {
    if (readySession != session || snapshot == null) return null;
    try {
      return new JSONObject(snapshot.toString());
    } catch (org.json.JSONException e) {
      return null;
    }
  }
}
