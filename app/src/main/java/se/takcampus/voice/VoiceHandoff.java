package se.takcampus.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Configuration transport over the existing bonded Companion link, not an audio relay. */
public final class VoiceHandoff {
    public static final String TYPE = "voice_channels_v1";
    public static final String ACTION = "se.takcampus.voice.STAGE_CHANNELS";
    public static final String WATCH_PACKAGE = "se.takcampus.voice.watch";
    public static final int MAX_ENVELOPE = 49152;
    public final String transferId, sha256;
    public final byte[] missionJson;

    private VoiceHandoff(String id, String hash, byte[] data) {
        transferId = id; sha256 = hash; missionJson = data;
    }
    public static String encode(byte[] missionJson, String transferId) throws Exception {
        VoicePackageImporter.fromJson(missionJson);
        UUID.fromString(transferId);
        String envelope = new JSONObject().put("type", TYPE).put("version", 1)
            .put("transferId", transferId).put("sha256", hash(missionJson))
            .put("missionJson", new String(missionJson, StandardCharsets.UTF_8)).toString();
        if (envelope.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE) throw new IllegalArgumentException("Envelope size");
        return envelope;
    }
    public static VoiceHandoff decode(String envelope) throws Exception {
        if (envelope.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE) throw new IllegalArgumentException("Envelope size");
        VoicePackageImporter.validateDepth(envelope);
        org.json.JSONTokener tokener = new org.json.JSONTokener(envelope);
        Object parsed = tokener.nextValue();
        if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Handoff JSON");
        JSONObject obj = (JSONObject) parsed;
        if (!keys(obj).equals(keys("type", "version", "transferId", "sha256", "missionJson")) ||
            !TYPE.equals(obj.getString("type")) || !Integer.valueOf(1).equals(obj.get("version")))
            throw new IllegalArgumentException("Unsupported handoff");
        String id = obj.getString("transferId");
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Transfer ID");
        byte[] data = obj.getString("missionJson").getBytes(StandardCharsets.UTF_8);
        String digest = hash(data);
        if (!digest.equals(obj.getString("sha256"))) throw new IllegalArgumentException("Digest mismatch");
        VoicePackageImporter.fromJson(data);
        return new VoiceHandoff(id, digest, data);
    }
    public static String hash(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static Set<String> keys(String... names) { return new java.util.HashSet<>(java.util.Arrays.asList(names)); }

    private static Set<String> keys(JSONObject obj) {
        Set<String> result = new java.util.HashSet<>();
        java.util.Iterator<String> iterator = obj.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

}
