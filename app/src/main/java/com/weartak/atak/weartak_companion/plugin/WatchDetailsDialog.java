package com.weartak.atak.weartak_companion.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Scrollable inventory for the watch connected to this Companion session. */
final class WatchDetailsDialog {
    private static final int PRIMARY = Color.WHITE;
    private static final int SECONDARY = 0xffc4c9d1;
    private static final int MAX_ENTRIES = 64;

    private final AlertDialog dialog;
    private final Context resources;
    private final ScrollView scroll;
    private final LinearLayout body;

    WatchDetailsDialog(Context host, Context resources, Runnable refresh) {
        this.resources = resources;
        AlertDialog.Builder builder = new AlertDialog.Builder(host);
        Context context = builder.getContext();
        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(true);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(0xff131518);
        body.setPadding(dp(16), dp(6), dp(16), dp(12));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        builder.setTitle(resources.getString(R.string.watch_details))
                .setView(scroll)
                .setPositiveButton(resources.getString(R.string.watch_details_close), null);
        if (refresh != null) builder.setNeutralButton(resources.getString(R.string.watch_details_refresh), null);
        dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            Button button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (button != null && refresh != null) button.setOnClickListener(v -> refresh.run());
        });
    }

    void show() {
        dialog.show();
    }

    void dismiss() {
        dialog.dismiss();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    void render(WatchCapabilities capabilities, long session, String heading, String transport) {
        int scrollY = scroll.getScrollY();
        body.removeAllViews();
        LinearLayout link = card();
        text(link, heading, 19, true, PRIMARY);
        detail(link, resources.getString(R.string.companion_connection_method), transport);
        JSONObject report = capabilities.detailsSnapshot(session);
        if (report == null) {
            String summary = session < 0 ? resources.getString(R.string.companion_capabilities_not_reported)
                    : capabilities.summary(session);
            text(card(), summary, 14, false, SECONDARY);
            return;
        }

        section("Software");
        JSONObject software = report.optJSONObject("software");
        LinearLayout device = card();
        text(device, "WearTAK " + version(software), 15, false, PRIMARY);
        String android = software.optString("androidRelease");
        int api = software.optInt("androidSdk");
        if (!android.isEmpty() || api > 0) {
            text(device, "Android " + android + (api > 0 ? "  ·  API " + api : ""), 13, false, SECONDARY);
        }
        detail(device, "OS build", software.optString("osBuild"));
        detail(device, "Security patch", software.optString("securityPatch"));
        detail(device, "Package", software.optString("packageName"));
        detail(device, "Commit", software.optString("gitCommit"));
        detail(device, "Built", software.optString("buildTime"));

        section("Plugins");
        JSONArray plugins = report.optJSONArray("plugins");
        boolean hasPlugins = false;
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.optJSONObject(i);
            if (plugin == null) continue;
            hasPlugins = true;
            LinearLayout pluginCard = card();
            boolean loaded = plugin.optBoolean("loaded");
            String state = loaded ? "Loaded" : plugin.optBoolean("enabled") ? "Enabled, unavailable" : "Disabled";
            String packageName = plugin.optString("packageName");
            heading(pluginCard, plugin.optString("name", packageName), state, loaded);
            text(pluginCard, "Version " + version(plugin), 13, false, SECONDARY);
            if (plugin.optInt("apiVersion") > 0) detail(pluginCard, "SDK API", plugin.optString("apiVersion"));
            detail(pluginCard, "Package", packageName);
            detail(pluginCard, "Features", reportedFeatures(plugin.optJSONObject("features"), false));
            detail(pluginCard, "SDK capabilities", reportedFeatures(plugin.optJSONObject("sdkCapabilities"), true));
        }
        if (!hasPlugins) text(card(), "No watch plugins reported.", 14, false, SECONDARY);

        JSONArray receivers = report.optJSONArray("voiceReceivers");
        if (receivers != null && receivers.length() > 0) {
            section("Voice receiver");
            for (int i = 0; i < Math.min(receivers.length(), MAX_ENTRIES); i++) {
                JSONObject receiver = receivers.optJSONObject(i);
                if (receiver == null) continue;
                LinearLayout receiverCard = card();
                boolean ready = receiver.optBoolean("loaded");
                boolean installed = receiver.optBoolean("installed");
                heading(receiverCard, "Voice Standalone",
                        ready ? "Ready" : installed ? "Installed, not linked" : "Not installed", ready);
                if (installed) detail(receiverCard, "Version", version(receiver));
                detail(receiverCard, "Package", receiver.optString("packageName"));
            }
        }

        section("Capabilities");
        LinearLayout featuresCard = card();
        JSONObject features = report.optJSONObject("features");
        List<String> featureKeys = keys(features);
        for (String key : featureKeys) {
            int version = protocolVersion(features, key);
            heading(featuresCard, featureName(key), version > 0 ? "v" + version : "Unavailable", version > 0);
            if (version <= 0 && "voice_channels_v1".equals(key)) {
                text(featuresCard, "Voice " + capabilities.voiceUnavailableReason(), 13, false, SECONDARY);
            }
        }
        if (featureKeys.isEmpty()) text(featuresCard, "No capabilities reported.", 14, false, SECONDARY);
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void section(String title) {
        TextView label = text(body, title, 14, true, SECONDARY);
        label.setPadding(dp(2), dp(12), 0, dp(5));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(body.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(resources.getDrawable(R.drawable.bg_card));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        body.addView(card, params);
        return card;
    }

    private void heading(LinearLayout parent, String title, String status, boolean active) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        parent.addView(row);
        TextView label = text(row, title, 15, true, PRIMARY);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = text(row, status, 11, true, PRIMARY);
        badge.setMaxWidth(dp(105));
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(resources.getDrawable(active ? R.drawable.bg_chip_success : R.drawable.bg_chip_neutral));
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(8);
        badge.setLayoutParams(params);
    }

    private static TextView text(LinearLayout parent, String value, int sp, boolean bold, int color) {
        TextView view = new TextView(parent.getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view);
        return view;
    }

    private static void detail(LinearLayout parent, String label, String value) {
        if (!value.isEmpty()) text(parent, label + "  ·  " + value, 13, false, SECONDARY);
    }

    private static String version(JSONObject entry) {
        String name = entry.optString("versionName", "unknown");
        long code = entry.optLong("versionCode");
        return name + (code > 0 ? " (" + code + ")" : "");
    }

    private static List<String> keys(JSONObject fields) {
        List<String> keys = new ArrayList<>();
        if (fields != null) {
            Iterator<String> iterator = fields.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
        }
        return keys.subList(0, Math.min(keys.size(), MAX_ENTRIES));
    }

    private static String reportedFeatures(JSONObject fields, boolean booleanFlags) {
        List<String> items = new ArrayList<>();
        for (String key : keys(fields)) {
            if (booleanFlags) {
                if (Boolean.TRUE.equals(fields.opt(key))) items.add(featureName(key));
            } else {
                int version = protocolVersion(fields, key);
                if (version > 0) items.add(featureName(key) + " v" + version);
            }
        }
        return android.text.TextUtils.join("  ·  ", items);
    }

    private static int protocolVersion(JSONObject fields, String key) {
        Object value = fields.opt(key);
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static String featureName(String key) {
        switch (key) {
            case "capabilities_v1": return "Capability reporting";
            case "watch_settings_v1": return "Watch settings";
            case "phone_managed_settings_v1": return "Phone-managed settings";
            case "unit_prefs_v1": return "Unit preferences";
            case "self_status_v1": return "Watch status";
            case "voice_channels_v1": return "Voice channels";
            default:
                String label = key.replaceFirst("_v[0-9]+$", "").replace('_', ' ')
                        .replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim();
                return label.isEmpty() ? "Unnamed capability"
                        : Character.toUpperCase(label.charAt(0)) + label.substring(1);
        }
    }

    private int dp(int value) {
        return Math.round(value * body.getResources().getDisplayMetrics().density);
    }
}
