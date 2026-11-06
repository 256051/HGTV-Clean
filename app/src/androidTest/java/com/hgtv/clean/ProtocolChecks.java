package com.hgtv.clean;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** Explicit local test runner; never included in the client APK or started by normal users. */
public final class ProtocolChecks extends Instrumentation {
    private final JSONObject results = new JSONObject();
    private boolean offline;

    /** Starts checks on the instrumentation thread. */
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        offline = arguments != null && Boolean.parseBoolean(arguments.getString("offline", "false"));
        start();
    }

    /** Exercises source-built protocols without account, registration, history or collection writes. */
    @Override public void onStart() {
        check("auth_rules", () -> AuthSessionTest.main(new String[0]));
        check("content_parser", () -> ContentParserTest.main(new String[0]));
        check("media_protocol", () -> MediaProtocolCheck.main(new String[0]));
        check("model_recovery", () -> ModelDeviceStateTest.main(new String[0]));
        if (offline) { complete(); return; }
        Context context = getTargetContext();
        String before = context.getSharedPreferences("clean-platform-v1", Context.MODE_PRIVATE).getString("qr_account", "");
        PlatformApi api = new PlatformApi(context);
        check("cancelled_login_preserved", () -> {
            try {
                api.acceptQrAccount(new JSONObject(before), "", new java.util.concurrent.atomic.AtomicBoolean(false));
                throw new AssertionError("cancelled login accepted");
            } catch (java.io.IOException expected) {
                require(before.equals(context.getSharedPreferences("clean-platform-v1", Context.MODE_PRIVATE).getString("qr_account", "")), "cancel preserves record");
            }
        });
        check("changed_device_login_preserved", () -> {
            JSONObject candidate = new JSONObject(before);
            candidate.getJSONObject("i").put("device_id", "0");
            try {
                api.acceptQrAccount(candidate, "", new java.util.concurrent.atomic.AtomicBoolean(true));
                throw new AssertionError("changed device accepted");
            } catch (java.io.IOException expected) {
                require(before.equals(context.getSharedPreferences("clean-platform-v1", Context.MODE_PRIVATE).getString("qr_account", "")), "device mismatch preserves record");
            }
        });
        List<Models.Drama> comic = new ArrayList<>();
        check("saved_login", () -> require(api.hasSavedLogin(), "saved login"));
        check("registered_device", () -> require(api.hasRegisteredDevice(), "registration"));
        check("account", () -> require(api.verifyAccount().verified, "verified account"));
        check("comic", () -> { comic.addAll(api.recommended(1, "COMIC")); require(!comic.isEmpty(), "comic list"); });
        check("real", () -> require(!api.recommended(1, "REAL").isEmpty(), "real list"));
        check("ai", () -> require(!api.recommended(1, "AI").isEmpty(), "ai list"));
        check("search", () -> require(!api.search("AI", 1).isEmpty(), "search"));
        List<Models.Episode> episodes = new ArrayList<>();
        check("episodes", () -> { require(!comic.isEmpty(), "comic prerequisite"); episodes.addAll(api.episodes(comic.get(0))); require(!episodes.isEmpty(), "episode list"); });
        for (int quality : new int[]{480, 720, 1080}) {
            check("media_" + quality, () -> {
                require(!episodes.isEmpty(), "episodes prerequisite");
                try (Models.Playback playback = api.play(episodes.get(0), quality)) {
                    long length = playback.source.length(); require(length > 1024, "media size");
                    byte[] first = playback.source.read(0, 1024);
                    require(first.length == 1024 && first[4] == 'f' && first[5] == 't' && first[6] == 'y' && first[7] == 'p', "MP4");
                    long offset = length / 2; int count = (int) Math.min(2048L, length - offset);
                    byte[] middle = playback.source.read(offset, count);
                    require(Arrays.equals(middle, playback.source.read(offset, count)), "seek repeat");
                    require(Arrays.equals(first, playback.source.read(0, 1024)), "seek back");
                }
            });
        }
        check("qr_request_cancel", () -> {
            try (AuthApi login = api.startQrLogin()) { require(login.request().png.length > 64, "QR image"); }
            require(before.equals(context.getSharedPreferences("clean-platform-v1", Context.MODE_PRIVATE).getString("qr_account", "")), "cancel preserves login");
        });
        check("account_after", () -> require(api.verifyAccount().verified, "account after reads"));
        api.close();
        check("session_unchanged", () -> require(before.equals(context.getSharedPreferences("clean-platform-v1", Context.MODE_PRIVATE).getString("qr_account", "")), "session unchanged"));
        complete();
    }

    private void complete() { Bundle bundle = new Bundle(); bundle.putString("results", results.toString()); finish(0, bundle); }

    private void check(String name, Check task) {
        Log.i("HGTV-ProtocolChecks", "start=" + name);
        try { task.run(); results.put(name, "passed"); }
        catch (Exception | AssertionError error) {
            StringBuilder types = new StringBuilder();
            Throwable current = error;
            for (int i = 0; current != null && i < 4; i++) {
                if (types.length() > 0) types.append(" > "); types.append(current.getClass().getSimpleName());
                StackTraceElement[] trace = current.getStackTrace();
                if (trace.length > 0) types.append('@').append(trace[0].getClassName()).append(':').append(trace[0].getLineNumber());
                current = current.getCause();
            }
            try { results.put(name, types.toString()); } catch (org.json.JSONException ignored) { }
        }
        Log.i("HGTV-ProtocolChecks", "end=" + name + " result=" + results.optString(name));
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private interface Check { void run() throws Exception; }
}
