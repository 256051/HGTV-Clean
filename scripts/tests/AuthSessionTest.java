package com.hgtv.clean;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runs the QR state and cookie boundary checks without an Android runtime or live account. */
public final class AuthSessionTest {
    /** Executes synthetic cases on either the JVM or Android. */
    public static void main(String[] args) throws Exception {
        check("waiting".equals(AuthSession.state("new")));
        check("waiting".equals(AuthSession.state("1")));
        check("scanned".equals(AuthSession.state("SCANNED")));
        check("scanned".equals(AuthSession.state("2")));
        check("confirmed".equals(AuthSession.state("confirmed")));
        check("confirmed".equals(AuthSession.state("3")));
        check("expired".equals(AuthSession.state("4")));
        check("expired".equals(AuthSession.state("5")));
        check("waiting".equals(AuthSession.state("unrecognized")));
        Map<String, String> cookies = new LinkedHashMap<>();
        AuthSession.receiveCookie(cookies, "sessionid=synthetic; Path=/; HttpOnly");
        AuthSession.receiveCookie(cookies, "sid_guard=x%7C1000%7C200%7Cignored; Secure");
        AuthSession.receiveCookie(cookies, "transient=discarded; Path=/");
        check(AuthSession.expiry(cookies) == 1200);
        check("sessionid=synthetic; sid_guard=x%7C1000%7C200%7Cignored".equals(AuthSession.sessionCookies(cookies)));
        AuthSession.receiveCookie(cookies, "sessionid=deleted; Max-Age=0");
        check(!cookies.containsKey("sessionid"));
        AuthSession.receiveCookie(cookies, "sessionid=replacement; Max-Age=100");
        AuthSession.receiveCookie(cookies, "sessionid=poison\r\nX-Test: invalid");
        check("replacement".equals(cookies.get("sessionid")));
        AuthSession.receiveCookie(cookies, "Path=invalid");
        check(!cookies.containsKey("Path"));
        cookies.put("sid_guard", "x|9223372036854775807|1");
        check(AuthSession.expiry(cookies) == 0);
        cookies.put("sid_guard", "broken");
        check(AuthSession.expiry(cookies) == 0);
        long now = 1700000000000L;
        check(AuthSession.deadline(now / 1000 + 30, now) == now + 30000);
        check(AuthSession.deadline(now + 45000, now) == now + 45000);
        check(AuthSession.deadline(0, now) == now + 120000);
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("os_version", "12"); profile.put("device_type", "Synthetic TV");
        profile.put("rom_version", "test.build metadata");
        check(AuthSession.userAgent(profile).equals("com.phoenix.read/72232 (Linux; U; Android 12; zh_CN; Synthetic TV; Build/test.build)"));
        check(!AuthSession.headerValue("bad\nheader", 100));
        check("https://example.com/next".equals(AuthSession.redirect("https://example.com/start", "/next")));
        for (String location : new String[]{"https://elsewhere.example/", "http://example.com/", "https://example.com:444/", "https://user@example.com/", "\r\nLocation: bad"}) {
            boolean rejected = false;
            try { AuthSession.redirect("https://example.com/start", location); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected);
        }
        Map<String, String> parsed = AuthSession.parseCookies("sessionid=synthetic; sid_guard=abc%7C1000%7C200; other=x=y; empty=; bad name=ignored; flag; Max-Age=0");
        check(parsed.size() == 5 && "synthetic".equals(parsed.get("sessionid")));
        check("x=y".equals(parsed.get("other")) && parsed.get("empty").isEmpty());
        check("0".equals(parsed.get("Max-Age")));
        check(!parsed.containsKey("bad name") && !parsed.containsKey("flag"));
        check(AuthSession.parseCookies("").isEmpty() && AuthSession.parseCookies(null).isEmpty());
        boolean badHeader = false;
        try { AuthSession.parseCookies("sessionid=a\r\nX-Other: bad"); }
        catch (java.io.IOException expected) { badHeader = true; }
        check(badHeader);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 128; i++) many.append("key").append(i).append("=v; ");
        check(AuthSession.parseCookies(many.toString()).size() == 128);
        many.append("overflow=v");
        boolean tooMany = false;
        try { AuthSession.parseCookies(many.toString()); }
        catch (java.io.IOException expected) { tooMany = true; }
        check(tooMany);
        System.out.println("QR state/cookie/expiry checks passed (synthetic data only).");
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("QR protocol rule"); }
}
