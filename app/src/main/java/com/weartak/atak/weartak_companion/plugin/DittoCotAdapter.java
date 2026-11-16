package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONObject;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.SAXException;

/** Adapter for the WearTAK Ditto CoT document v2 schema. */
final class DittoCotAdapter {
    static final class Document {
        final String uid;
        final String peer;
        final String xml;
        final boolean deleted;

        Document(String uid, String peer, String xml, boolean deleted) {
            this.uid = uid;
            this.peer = peer;
            this.xml = xml;
            this.deleted = deleted;
        }
    }

    private static final String POINT_METADATA = "weartakPoint";
    private static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    private static final long MAX_TIMESTAMP = 253402300799999L;

    private DittoCotAdapter() {}

    static Document decode(String collection, String json) throws Exception {
        if (!Arrays.asList("track", "mapitem", "alert").contains(collection)) {
            throw new IllegalArgumentException("Unsupported Ditto collection");
        }
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Oversized Ditto document");
        }

        JSONObject raw = new JSONObject(json);
        if (number(raw, "_v") != 2) throw new IllegalArgumentException("Unsupported Ditto schema version");
        String uid = text(raw, "d");
        if (!uid.equals(text(raw, "_id"))) {
            throw new IllegalArgumentException("Ditto document/event identity mismatch");
        }
        String peer = text(raw, "a");
        String type = text(raw, "w");
        validateCollectionType(collection, type);

        boolean removed = booleanValue(raw, "_r");
        boolean visible = optionalBoolean(raw, "visible", true);
        boolean enabled = optionalBoolean(raw, "f", true);
        boolean deleted = removed || !visible || !enabled;
        if (deleted && !raw.has(POINT_METADATA)) return null;
        if (deleted && !"mapitem".equals(collection)) {
            throw new IllegalArgumentException("Point tombstone outside mapitem");
        }

        long time = timestamp(raw, "b");
        long start = timestamp(raw, "n");
        long stale = timestamp(raw, "o");
        if (stale < time) throw new IllegalArgumentException("Ditto stale precedes event time");
        double latitude = number(raw, "j");
        double longitude = number(raw, "l");
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid Ditto location");
        }

        Object rawDetail = raw.opt("r");
        if (!(rawDetail instanceof String)) {
            throw new IllegalArgumentException("Missing Ditto detail");
        }
        String detail = (String) rawDetail;
        validateDetail(detail);
        String xml = buildEvent(raw, uid, type, time, start, stale,
                latitude, longitude, number(raw, "i"), number(raw, "h"), number(raw, "k"), detail);
        if (xml.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Oversized Ditto CoT event");
        }

        if (deleted) validatePointTombstone(raw, type, time, xml);
        return new Document(uid, peer, xml, deleted);
    }

    static String fingerprint(String value) throws Exception {
        return sha256(value);
    }

    private static void validateCollectionType(String collection, String type) {
        boolean position = "a-f-G-U-C".equals(type) || type.startsWith("a-f-G-U-C-")
                || "a-f-G-E".equals(type) || type.startsWith("a-f-G-E-");
        boolean emergency = "b-a-g".equals(type) || "b-a-o".equals(type) || type.startsWith("b-a-o-");
        boolean marker = type.startsWith("a-") || "b-m-p-s-p-i".equals(type);
        boolean supported = marker || emergency || "b-t-f".equals(type);
        if ("track".equals(collection) && !position) {
            throw new IllegalArgumentException("Non-PLI Ditto document in track");
        }
        if ("alert".equals(collection) && !emergency) {
            throw new IllegalArgumentException("Non-emergency Ditto document in alert");
        }
        if ("mapitem".equals(collection) && !supported) {
            throw new IllegalArgumentException("Unsupported Ditto mapitem type");
        }
    }

    private static String buildEvent(JSONObject raw, String uid, String type, long time, long start,
                                     long stale, double latitude, double longitude, double hae,
                                     double ce, double le, String detail) throws Exception {
        String xml = "<event version='" + escape(text(raw, "g"))
                + "' uid='" + escape(uid)
                + "' type='" + escape(type)
                + "' time='" + Instant.ofEpochMilli(time)
                + "' start='" + Instant.ofEpochMilli(start)
                + "' stale='" + Instant.ofEpochMilli(stale)
                + "' how='" + escape(text(raw, "p"))
                + "' access='" + escape(text(raw, "q"))
                + "'><point lat='" + latitude
                + "' lon='" + longitude
                + "' hae='" + hae
                + "' ce='" + ce
                + "' le='" + le
                + "'/>" + detail + "</event>";
        return xml;
    }

    private static void validatePointTombstone(JSONObject raw, String type, long time, String xml)
            throws Exception {
        boolean marker = type.startsWith("a-") || "b-m-p-s-p-i".equals(type);
        if (!marker || type.startsWith("a-f-G-U-C") || type.startsWith("a-f-G-E")) {
            throw new IllegalArgumentException("Unsupported Ditto point tombstone type");
        }
        JSONObject metadata = raw.getJSONObject(POINT_METADATA);
        String author = text(raw, "AuthorId");
        if (number(metadata, "version") != 1
                || !author.equals(text(metadata, "owner"))
                || !booleanValue(metadata, "deleted")
                || !sha256(xml).equals(text(metadata, "revision"))
                || !removedLifecycle(raw)) {
            throw new IllegalArgumentException("Invalid WearTAK point tombstone");
        }
        long deletedAt = timestamp(metadata, "deletedAt");
        if (deletedAt < time) throw new IllegalArgumentException("Invalid point deletion time");
    }

    private static boolean removedLifecycle(JSONObject raw) {
        return booleanValue(raw, "_r") && !optionalBoolean(raw, "visible", true)
                && !optionalBoolean(raw, "f", true);
    }

    private static void validateDetail(String detail) throws Exception {
        if (detail.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES
                || detail.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")
                || detail.toUpperCase(Locale.ROOT).contains("<!ENTITY")) {
            throw new IllegalArgumentException("Invalid Ditto detail");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External Ditto detail references are not allowed");
        });
        org.w3c.dom.Element root = builder.parse(new InputSource(new StringReader(detail)))
                .getDocumentElement();
        if (root == null || !"detail".equals(root.getTagName())
                || root.getElementsByTagName("event").getLength() != 0
                || root.getElementsByTagName("detail").getLength() != 0) {
            throw new IllegalArgumentException("Invalid Ditto CoT detail");
        }
    }

    private static String text(JSONObject object, String key) {
        return text(object, key, 512);
    }

    private static String text(JSONObject object, String key, int maxLength) {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Missing Ditto field " + key);
        String text = (String) value;
        if (text.trim().isEmpty() || text.length() > maxLength
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Ditto field " + key);
        }
        return text;
    }

    private static long timestamp(JSONObject object, String key) {
        double value = number(object, key);
        if (value <= 0 || value > MAX_TIMESTAMP || value != (long) value) {
            throw new IllegalArgumentException("Invalid Ditto timestamp " + key);
        }
        return (long) value;
    }

    private static double number(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing Ditto number " + key);
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Invalid Ditto number " + key);
        return number;
    }

    private static boolean booleanValue(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Missing Ditto boolean " + key);
        return (Boolean) value;
    }

    private static boolean optionalBoolean(JSONObject object, String key, boolean fallback) {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return fallback;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid Ditto boolean " + key);
        return (Boolean) value;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;").replace("\"", "&quot;");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }
}
