package se.takcampus.voice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Reads the JSON mission already emitted by TAKS and Martine; no Vx installation required. */
public final class VoicePackageImporter {
    public static final int MAX_ZIP = 262144, MAX_EXPANDED = 524288, MAX_JSON = 32700;
    private static final String MANIFEST = "MANIFEST/manifest.xml";
    private VoicePackageImporter() {}

    public static VoiceMission fromZip(byte[] data) throws Exception {
        return fromJson(jsonFromZip(data));
    }

    public static byte[] jsonFromZip(byte[] data) throws Exception {
        require(data.length > 0 && data.length <= MAX_ZIP, "Package size");
        Map<String, byte[]> entries = new HashMap<>();
        int expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName();
                safePath(path);
                require(!entry.isDirectory(), "Directory entries unsupported");
                require(entries.size() < 32 && !entries.containsKey(path), "Duplicate/too many entries");
                byte[] bytes = readBounded(zip, Math.min(MAX_EXPANDED - expanded, 131072));
                expanded += bytes.length;
                entries.put(path, bytes);
            }
        }
        require(entries.containsKey(MANIFEST), "Missing manifest");
        byte[] manifest = entries.get(MANIFEST);
        String xml = new String(manifest, StandardCharsets.UTF_8);
        // Also reject before parsing: some Android XML providers have different feature support.
        require(!xml.contains("<!DOCTYPE") && !xml.contains("<!ENTITY"), "DTD forbidden");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        Element root = doc.getDocumentElement();
        require(root.getTagName().equals("MissionPackageManifest") && root.getAttribute("version").equals("2"), "Manifest version");
        NodeList contents = root.getElementsByTagName("Content");
        byte[] mission = null;
        byte[] proto = null;
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < contents.getLength(); i++) {
            Element content = (Element) contents.item(i);
            String path = content.getAttribute("zipEntry");
            safePath(path);
            String ignore = content.getAttribute("ignore");
            require(ignore.isEmpty() || ignore.equals("true") || ignore.equals("false"), "Ignore value");
            if (ignore.equals("true")) continue;
            require(selected.add(path) && entries.containsKey(path), "Missing/duplicate manifest entry");
            if (path.endsWith("_proto")) {
                require(proto == null, "Ambiguous binary mission");
                proto = VxProtoImporter.toJson(entries.get(path));
                continue;
            }
            byte[] candidate = entries.get(path);
            // TAKS JSON filenames have no extension. Only referenced JSON is considered.
            if (candidate.length > 0 && candidate[0] == '{') {
                require(mission == null, "Ambiguous voice mission");
                fromJson(candidate);
                mission = candidate;
            }
        }
        require(mission != null || proto != null, "No supported voice mission");
        if (mission != null && proto != null) sameMission(fromJson(mission), fromJson(proto));
        return mission != null ? mission : proto;
    }

    public static VoiceMission fromJson(byte[] data) throws Exception {
        require(data.length > 0 && data.length <= MAX_JSON, "Mission size");
        String text = new String(data, StandardCharsets.UTF_8);
        require(java.util.Arrays.equals(data, text.getBytes(StandardCharsets.UTF_8)), "Invalid UTF-8");
        validateDepth(text);
        org.json.JSONTokener tokener = new org.json.JSONTokener(text);
        Object root = tokener.nextValue();
        require(root instanceof JSONObject && tokener.nextClean() == 0, "Invalid mission JSON");
        JSONObject obj = (JSONObject) root;
        // Credentials/trust are deliberately outside a channel package.
        require(keys(obj).equals(keys("missionId", "name", "channels", "missionType", "missionIP", "missionPort", "missionDefaultProtocol")), "Unexpected mission fields");
        String missionId = uuid(obj.getJSONObject("missionId").getString("uuid"));
        require(keys(obj.getJSONObject("missionId")).equals(keys("uuid")), "Unexpected identity fields");
        String name = label(obj.getString("name"));
        require(obj.getString("missionType").equals("COMBINED"), "Mission type");
        require(obj.getString("missionIP").isEmpty() && obj.getString("missionPort").equals("-1") && obj.getString("missionDefaultProtocol").equals("UDP"), "Unsupported mission transport metadata");
        JSONArray input = obj.getJSONArray("channels");
        require(input.length() > 0 && input.length() <= 64, "Channel count");
        ArrayList<VoiceMission.Channel> channels = new ArrayList<>();
        Set<String> ids = new HashSet<>(), destinations = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            JSONObject ch = input.getJSONObject(i);
            require(keys(ch).equals(keys("id", "name", "host", "missionId", "serverChannelId", "subtitle", "isMumble", "isEngineering", "port")), "Unexpected channel fields");
            require(ch.opt("isMumble") == Boolean.TRUE && ch.opt("isEngineering") == Boolean.FALSE, "Non-Mumble channel");
            label(ch.getString("subtitle"));
            require(Integer.valueOf(-1).equals(ch.get("port")), "Unsupported legacy channel port");
            require(ch.getString("missionId").equals(missionId), "Channel mission mismatch");
            String id = uuid(ch.getString("id"));
            require(ids.add(id), "Duplicate channel UUID");
            String endpoint = ch.getString("host");
            require(endpoint.length() <= 270 && !endpoint.contains("/") && !endpoint.contains("@") && !endpoint.contains("?") && !endpoint.contains("#"), "Invalid endpoint");
            URI uri = new URI("mumble://" + endpoint);
            String host = uri.getHost();
            require(host != null && !host.isEmpty() && uri.getPort() > 0 && uri.getPort() <= 65535 && uri.getRawUserInfo() == null, "Invalid endpoint");
            Object rawId = ch.get("serverChannelId");
            require(rawId instanceof Integer || rawId instanceof Long, "Channel ID must be integer");
            long channelId = ((Number) rawId).longValue();
            require(channelId >= 0 && channelId <= Integer.MAX_VALUE, "Invalid channel ID");
            require(destinations.add(endpoint + "/" + channelId), "Duplicate channel destination");
            channels.add(new VoiceMission.Channel(id, label(ch.getString("name")), host, uri.getPort(), (int) channelId));
        }
        return new VoiceMission(missionId, name, channels);
    }

    public static byte[] readBounded(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > max) throw new IOException("Size limit");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    private static void safePath(String path) {
        require(!path.isEmpty() && path.length() <= 512 && !path.contains("\\") && !path.contains(":"), "Unsafe path");
        for (String part : path.split("/", -1)) require(!part.isEmpty() && !part.equals(".") && !part.equals(".."), "Unsafe path");
    }
    private static String uuid(String value) { require(UUID.fromString(value).toString().equals(value), "Invalid UUID"); return value; }
    private static void sameMission(VoiceMission json, VoiceMission proto) {
        require(json.id.equals(proto.id) && json.name.equals(proto.name) && json.channels.size() == proto.channels.size(), "Conflicting JSON/binary missions");
        for (int i = 0; i < json.channels.size(); i++) {
            VoiceMission.Channel a = json.channels.get(i), b = proto.channels.get(i);
            require(a.id.equals(b.id) && a.name.equals(b.name) && a.host.equals(b.host) && a.port == b.port && a.serverChannelId == b.serverChannelId, "Conflicting JSON/binary channel");
        }
    }
    private static String label(String value) {
        require(!value.isEmpty() && value.equals(value.trim()) && value.getBytes(StandardCharsets.UTF_8).length <= 256, "Invalid label");
        for (int i = 0; i < value.length(); i++) require(!Character.isISOControl(value.charAt(i)), "Control character");
        return value;
    }
    static void validateDepth(String text) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') require(++depth <= 8, "JSON nesting limit");
            else if (c == '}' || c == ']') require(--depth >= 0, "Invalid JSON nesting");
        }
        require(depth == 0 && !quoted, "Incomplete JSON");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static Set<String> keys(String... names) { return new java.util.HashSet<>(java.util.Arrays.asList(names)); }

    private static Set<String> keys(JSONObject obj) {
        Set<String> result = new java.util.HashSet<>();
        java.util.Iterator<String> iterator = obj.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

}
