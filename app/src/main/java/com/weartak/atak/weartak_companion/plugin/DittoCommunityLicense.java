package com.weartak.atak.weartak_companion.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.InflaterInputStream;

final class DittoCommunityLicense {
    final String appId;
    final String sharedKey;
    final String token;

    private DittoCommunityLicense(String appId, String sharedKey, String token) {
        this.appId = appId;
        this.sharedKey = sharedKey;
        this.token = token;
    }

    static DittoCommunityLicense parse(String encoded, long nowMillis) {
        if (encoded == null || encoded.trim().isEmpty()) {
            throw new IllegalArgumentException("Community license is missing");
        }
        if (encoded.length() > 131072) {
            throw new IllegalArgumentException("Community license is too large");
        }
        try {
            byte[] compressed = Base64.getUrlDecoder().decode(encoded.trim());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InflaterInputStream input = new InflaterInputStream(
                    new ByteArrayInputStream(compressed))) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 65536) {
                        throw new IllegalArgumentException("Community license is too large");
                    }
                    output.write(buffer, 0, count);
                }
            }
            JSONObject json = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            if (json.optInt("v", -1) != 2 || !"SK".equals(json.optString("it"))) {
                throw new IllegalArgumentException("Unsupported Community license");
            }
            if (!(json.opt("a") instanceof String) || !(json.opt("s") instanceof String)
                    || !(json.opt("t") instanceof String)) {
                throw new IllegalArgumentException("Incomplete Community license");
            }
            String appId = json.optString("a");
            String sharedKey = json.optString("s");
            String token = json.optString("t");
            long expires = json.optLong("e", 0);
            if (appId.trim().isEmpty() || sharedKey.trim().isEmpty()
                    || token.trim().isEmpty() || expires <= 0) {
                throw new IllegalArgumentException("Incomplete Community license");
            }
            if (nowMillis >= expires) {
                throw new IllegalArgumentException("Community license has expired");
            }
            return new DittoCommunityLicense(appId, sharedKey, token);
        } catch (IOException | JSONException e) {
            throw new IllegalArgumentException("Invalid Community license");
        } catch (IllegalArgumentException e) {
            // Decoder exceptions may contain input; never propagate their messages.
            if (e.getMessage() != null && e.getMessage().startsWith("Community license")) throw e;
            throw new IllegalArgumentException("Invalid or unsupported Community license");
        }
    }
}
