package com.hgtv.clean;

import android.content.Context;
import android.os.Looper;
import android.util.Base64;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/** One explicit QR attempt with isolated cookies and no writes before phone confirmation. */
public final class AuthApi implements Closeable {
    private static final String HOST = "https://api5-normal-lq.fqnovel.com";
    private final PlatformApi platform;
    private final Map<String, String> profile;
    private final Map<String, String> cookies;
    private final ProtocolHttp http = new ProtocolHttp();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile String token;
    private volatile long deadline;
    private String accountToken = "";
    private String ticketSignature = "";

    AuthApi(Context context, PlatformApi platform, Map<String, String> profile, Map<String, String> deviceCookies) {
        this.platform = platform;
        this.profile = new LinkedHashMap<>(profile);
        this.cookies = new LinkedHashMap<>(deviceCookies);
    }

    /** Requests a QR image; this attempt never inherits the previously saved account session. */
    public QrCode request() throws IOException {
        checkActive();
        LinkedHashMap<String, String> query = parameters();
        query.put("language", "zh");
        return code(requestData("/passport/web/get_qrcode/", query));
    }

    /** Polls one state and commits a session only after an explicit confirmed response. */
    public Result poll() throws IOException {
        checkActive();
        String current = token;
        if (current == null || System.currentTimeMillis() >= deadline) return new Result("expired", null, null);
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("next", HOST + "/"); query.put("token", current); query.put("aid", "513755");
        JSONObject data = requestData("/passport/web/check_qrconnect/", query);
        checkActive();
        switch (AuthSession.state(data.optString("status"))) {
            case "confirmed":
                String nextToken = data.optString("token");
                if (nextToken.length() > 64 && !nextToken.endsWith("_lq")) accountToken = nextToken;
                JSONObject user = data.optJSONObject("user_data");
                String name = user == null ? "" : user.optString("screen_name");
                if (name.trim().isEmpty() && user != null) name = user.optString("name");
                try {
                    JSONObject record = new JSONObject();
                    record.put("a", AuthSession.sessionCookies(cookies)).put("b", accountToken).put("c", ticketSignature);
                    record.put("d", value("device_id")).put("e", value("iid"));
                    record.put("f", value("cdid")).put("g", value("openudid"));
                    record.put("h", AuthSession.expiry(cookies)).put("i", new JSONObject(profile));
                    checkActive();
                    PlatformApi.AccountStatus status = platform.acceptQrAccount(record, name, active);
                    token = null;
                    return new Result("confirmed", null, status);
                } catch (JSONException error) { throw new IOException("扫码会话内容不完整，已保留原登录。", error); }
            case "scanned": return new Result("scanned", null, null);
            case "expired":
                if (data.optString("token").isEmpty() || data.optString("qrcode").isEmpty())
                    return new Result("expired", null, null);
                return new Result("new", code(data), null);
            default: return new Result("waiting", null, null);
        }
    }

    /** Cancels this attempt's HTTP work without clearing an already saved account. */
    @Override public void close() {
        synchronized (active) { active.set(false); }
        token = null;
        http.close();
    }

    private JSONObject requestData(String path, Map<String, String> query) throws IOException {
        checkActive();
        String address = HOST + path + "?" + ProtocolHttp.query(query);
        ProtocolHttp.Response response;
        for (int redirects = 0; ; redirects++) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", AuthSession.userAgent(profile));
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Referer", HOST + "/");
            if (!cookies.isEmpty()) headers.put("Cookie", ProtocolHttp.cookie(cookies));
            if (!accountToken.isEmpty()) headers.put("X-Tt-Token", accountToken);
            response = http.request("GET", address, headers, null, 3 * 1024 * 1024);
            checkActive();
            for (Map.Entry<String, List<String>> entry : response.headers.entrySet()) {
                String header = entry.getKey();
                if (header == null || entry.getValue() == null) continue;
                for (String value : entry.getValue()) {
                    if ("Set-Cookie".equalsIgnoreCase(header)) AuthSession.receiveCookie(cookies, value);
                    else if ("X-Tt-Token".equalsIgnoreCase(header) && AuthSession.headerValue(value, 16384)) accountToken = value;
                    else if ("bd-ticket-guard-sign-res-static-ts-sign".equalsIgnoreCase(header) && AuthSession.headerValue(value, 16384)) ticketSignature = value;
                }
            }
            if (response.status != 301 && response.status != 302 && response.status != 303 && response.status != 307 && response.status != 308) break;
            if (redirects >= 4) throw new IOException("扫码服务跳转次数过多。");
            address = AuthSession.redirect(address, response.header("Location"));
        }
        if (response.status < 200 || response.status >= 300) throw new IOException("扫码服务暂不可用，请刷新重试。");
        JSONObject data = response.json().optJSONObject("data");
        if (data == null) throw new IOException("账号服务返回的内容不完整。");
        String error = data.optString("error_code");
        if (!error.isEmpty() && !"0".equals(error)) throw new IOException("扫码服务未接受请求，请刷新重试。");
        return data;
    }

    private QrCode code(JSONObject data) throws IOException {
        checkActive();
        String next = data.optString("token");
        String encoded = data.optString("qrcode");
        if (next.trim().isEmpty() || next.length() > 4096 || encoded.length() > 3 * 1024 * 1024)
            throw new IOException("二维码内容不完整，请刷新重试。");
        int prefix = encoded.indexOf("base64,");
        if (prefix >= 0) encoded = encoded.substring(prefix + 7);
        byte[] png;
        try { png = Base64.decode(encoded, Base64.DEFAULT); }
        catch (IllegalArgumentException error) { throw new IOException("二维码图像格式无效。", error); }
        if (png.length == 0 || png.length > 2 * 1024 * 1024) throw new IOException("二维码图像大小无效。");
        deadline = AuthSession.deadline(data.optLong("expire_time"), System.currentTimeMillis());
        token = next;
        return new QrCode(png);
    }

    private LinkedHashMap<String, String> parameters() {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("next", HOST + "/"); query.put("aid", "513755");
        return query;
    }

    private String value(String key) { String value = profile.get(key); return value == null ? "" : value; }

    private void checkActive() throws IOException {
        if (!active.get() || Thread.currentThread().isInterrupted()) throw new IOException("扫码已取消");
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("二维码请求不能在界面线程执行");
    }

    /** QR PNG to display; the polling token is deliberately omitted. */
    public static final class QrCode {
        public final byte[] png;
        private QrCode(byte[] png) { this.png = png; }
    }

    /** Display-only QR state without session credentials. */
    public static final class Result {
        public final String state;
        public final QrCode code;
        public final PlatformApi.AccountStatus account;
        private Result(String state, QrCode code, PlatformApi.AccountStatus account) { this.state = state; this.code = code; this.account = account; }
    }
}
