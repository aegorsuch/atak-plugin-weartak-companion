package se.takcampus.voice;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Decoder for the exact two Vx protobuf encodings emitted by TAKS. Not a general Vx schema. */
public final class VxProtoImporter {
    private VxProtoImporter() {}

    public static byte[] toJson(byte[] bytes) throws Exception {
        Fields mission = new Fields(bytes);
        String missionId = mission.text(1), name = mission.text(2);
        Map<String, Fields> servers = new HashMap<>();
        for (byte[] raw : mission.messages(4)) {
            Fields server = new Fields(raw);
            // takctl wraps server in field 1; Martine emits the server directly.
            if (!looksLikeUuid(server.bytes(1))) server = new Fields(server.bytes(1));
            String id = server.text(1);
            if (servers.put(id, server) != null) throw new IllegalArgumentException("Duplicate server");
            // These packages carry connection information, not secrets or trust overrides.
            if (server.has(5) && server.bytes(5).length != 0) throw new IllegalArgumentException("Embedded server material unsupported");
        }
        JSONArray channels = new JSONArray();
        for (byte[] raw : mission.messages(3)) {
            Fields channel = new Fields(new Fields(raw).bytes(1));
            Fields server = servers.get(channel.text(4));
            if (server == null) throw new IllegalArgumentException("Unknown server reference");
            String host = server.text(2);
            if (host.contains(":")) host = "[" + host + "]";
            channels.put(new JSONObject().put("id", channel.text(1)).put("name", channel.text(2))
                .put("host", host + ":" + server.number(3)).put("missionId", missionId)
                .put("serverChannelId", channel.number(3)).put("subtitle", channel.text(2))
                .put("isMumble", true).put("isEngineering", false).put("port", -1));
        }
        byte[] json = new JSONObject().put("missionId", new JSONObject().put("uuid", missionId))
            .put("name", name).put("channels", channels).put("missionType", "COMBINED")
            .put("missionIP", "").put("missionPort", "-1").put("missionDefaultProtocol", "UDP")
            .toString().getBytes(StandardCharsets.UTF_8);
        VoicePackageImporter.fromJson(json);
        return json;
    }
    private static boolean looksLikeUuid(byte[] data) {
        try { return java.util.UUID.fromString(new String(data, StandardCharsets.UTF_8)).toString().equals(new String(data, StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException e) { return false; }
    }

    private static final class Fields {
        final Map<Integer, List<Object>> values = new HashMap<>();
        final byte[] data;
        int position;
        Fields(byte[] data) {
            if (data.length > VoicePackageImporter.MAX_JSON) throw new IllegalArgumentException("Protobuf size");
            this.data = data;
            int fields = 0;
            while (position < data.length) {
                if (++fields > 512) throw new IllegalArgumentException("Too many protobuf fields");
                long tag = varint();
                long field = tag >>> 3;
                if (field == 0 || field > 0x1fffffff) throw new IllegalArgumentException("Invalid protobuf tag");
                Object value;
                switch ((int) (tag & 7)) {
                    case 0: value = varint(); break;
                    case 2:
                        long size = varint();
                        if (size < 0 || size > data.length - position) throw new IllegalArgumentException("Truncated protobuf");
                        value = Arrays.copyOfRange(data, position, position + (int) size);
                        position += (int) size; break;
                    default: throw new IllegalArgumentException("Unsupported protobuf wire type");
                }
                if (!values.containsKey((int) field)) values.put((int) field, new ArrayList<>());
                values.get((int) field).add(value);
            }
        }
        private long varint() {
            long n = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position == data.length) throw new IllegalArgumentException("Truncated varint");
                int b = data[position++] & 255;
                if (shift == 63 && b > 1) throw new IllegalArgumentException("Varint overflow");
                n |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return n;
            }
            throw new IllegalArgumentException("Varint overflow");
        }
        boolean has(int key) { return values.containsKey(key); }
        private Object one(int key) {
            List<Object> found = values.get(key);
            if (found == null || found.size() != 1) throw new IllegalArgumentException("Missing/duplicate protobuf scalar");
            return found.get(0);
        }
        byte[] bytes(int key) { Object v = one(key); if (!(v instanceof byte[])) throw new IllegalArgumentException("Protobuf bytes expected"); return (byte[]) v; }
        String text(int key) {
            byte[] raw = bytes(key);
            String text = new String(raw, StandardCharsets.UTF_8);
            if (!Arrays.equals(raw, text.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Invalid protobuf UTF-8");
            return text;
        }
        long number(int key) { Object v = one(key); if (!(v instanceof Long) || (Long) v < 0) throw new IllegalArgumentException("Protobuf integer expected"); return (Long) v; }
        List<byte[]> messages(int key) {
            List<byte[]> result = new ArrayList<>();
            for (Object v : (values.containsKey(key) ? values.get(key) : java.util.Collections.emptyList())) {
                if (!(v instanceof byte[])) throw new IllegalArgumentException("Protobuf message expected");
                result.add((byte[]) v);
            }
            return result;
        }
    }
}
