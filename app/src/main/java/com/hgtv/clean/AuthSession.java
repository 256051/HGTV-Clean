package com.hgtv.clean;

import java.io.IOException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Stateless QR protocol rules, separate from HTTP and local account persistence. */
final class AuthSession {
    private static final String[] SESSION_COOKIES = {"sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss", "d_ticket", "sid_guard", "session_tlb_tag", "store-region", "store-region-src", "install_id", "n_mh", "odin_tt", "ttreq"};
    private AuthSession() { }

    static String state(String status) {
        switch (status.toLowerCase(Locale.ROOT)) {
            case "confirmed": case "3": return "confirmed";
            case "scanned": case "2": return "scanned";
            case "expired": case "4": case "5": return "expired";
            default: return "waiting";
        }
    }

    static Map<String, String> parseCookies(String header) throws IOException {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (header == null || header.isEmpty()) return cookies;
        if (!headerValue(header, 32768)) throw new IOException("已保存的登录 Cookie 格式无效。");
        for (String pair : header.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String name = pair.substring(0, equals).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) continue;
            if (!cookies.containsKey(name) && cookies.size() >= 128) throw new IOException("已保存的登录 Cookie 数量无效。");
            cookies.put(name, pair.substring(equals + 1).trim());
        }
        return cookies;
    }

    static void receiveCookie(Map<String, String> cookies, String header) {
        if (!headerValue(header, 32768)) return;
        String[] parts = header.split(";");
        int equals = parts[0].indexOf('=');
        if (equals <= 0) return;
        String key = parts[0].substring(0, equals).trim();
        String value = parts[0].substring(equals + 1).trim();
        if (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) return;
        switch (key.toLowerCase(Locale.ROOT)) {
            case "path": case "domain": case "expires": case "max-age": case "secure":
            case "httponly": case "samesite": case "priority": case "partitioned": return;
            default: break;
        }
        boolean remove = value.isEmpty();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int split = part.indexOf('=');
            if (split > 0 && "max-age".equalsIgnoreCase(part.substring(0, split).trim())) {
                try { if (Long.parseLong(part.substring(split + 1).trim()) <= 0) remove = true; }
                catch (NumberFormatException ignored) { /* An invalid attribute does not replace the cookie value. */ }
            }
        }
        if (remove) cookies.remove(key);
        else if (cookies.containsKey(key) || cookies.size() < 128) cookies.put(key, value);
    }

    static String sessionCookies(Map<String, String> cookies) {
        StringBuilder result = new StringBuilder();
        for (String key : SESSION_COOKIES) {
            String value = cookies.get(key);
            if (value == null) continue;
            if (result.length() > 0) result.append("; ");
            result.append(key).append('=').append(value);
        }
        return result.toString();
    }

    static long expiry(Map<String, String> cookies) {
        String guard = cookies.get("sid_guard");
        if (guard == null) return 0;
        String[] fields = guard.replace("%7C", "|").split("\\|", -1);
        if (fields.length < 3) return 0;
        try {
            long start = Long.parseLong(fields[1].trim()), duration = Long.parseLong(fields[2].trim());
            return start >= 0 && duration >= 0 && duration <= Long.MAX_VALUE - start ? start + duration : 0;
        } catch (NumberFormatException ignored) { return 0; }
    }

    static long deadline(long expires, long now) {
        if (expires > now / 1000 && expires < now / 1000 + 600) return expires * 1000;
        if (expires > now && expires < now + 600000) return expires;
        return now + 120000;
    }

    static String redirect(String current, String location) throws IOException {
        if (!headerValue(location, 8192)) throw new IOException("扫码服务跳转地址无效。");
        URL source = new URL(current), next = new URL(source, location);
        // The temporary login cookies must never follow a redirect to another origin.
        if (!"https".equals(next.getProtocol()) || !source.getHost().equalsIgnoreCase(next.getHost())
                || (next.getPort() != -1 && next.getPort() != 443) || next.getUserInfo() != null)
            throw new IOException("扫码服务跳转地址无效。");
        return next.toString();
    }

    static boolean headerValue(String value, int limit) {
        return value != null && !value.trim().isEmpty() && value.length() <= limit
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0 && value.indexOf('\0') < 0;
    }

    static String userAgent(Map<String, String> profile) {
        String build = deviceValue(profile, "rom_version", "BP2A.250605.031.A3");
        int space = build.indexOf(' ');
        if (space >= 0) build = build.substring(0, space);
        if (build.isEmpty()) build = "BP2A.250605.031.A3";
        return "com.phoenix.read/72232 (Linux; U; Android " + deviceValue(profile, "os_version", "16")
                + "; zh_CN; " + deviceValue(profile, "device_type", "25053RT47C") + "; Build/" + build + ")";
    }

    private static String deviceValue(Map<String, String> profile, String key, String fallback) {
        String value = profile.get(key);
        return headerValue(value, 256) ? value : fallback;
    }
}
