package com.hgtv.clean;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Builds and sends a registration for the caller's own installation profile. */
public final class DeviceRegistration {
    private DeviceRegistration() { }

    /** Returns validated identifiers and cookies without changing any local account data. */
    public static JSONObject register(ProtocolHttp http, Map<String, String> profile) throws IOException {
        try {
            long now = System.currentTimeMillis();
            JSONObject header = header(profile, now);
            byte[] plain = new JSONObject().put("magic_tag", "ss_app_log").put("header", header)
                    .put("_gen_time", now).toString().getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = envelope(plain);
            LinkedHashMap<String, String> query = new LinkedHashMap<>();
            query.put("ac", "wifi"); query.put("aid", "8662"); query.put("app_name", "novelread");
            for (String name : new String[]{"channel", "device_brand", "device_platform", "device_type", "dpi", "language",
                    "manifest_version_code", "os", "os_api", "os_version", "resolution", "ssmix", "update_version_code", "version_code", "version_name"}) {
                String fallback;
                switch (name) {
                    case "channel": fallback = "update_64"; break;
                    case "device_brand": fallback = "Redmi"; break;
                    case "device_platform": case "os": fallback = "android"; break;
                    case "device_type": fallback = "25053RT47C"; break;
                    case "dpi": fallback = "520"; break;
                    case "language": fallback = "zh"; break;
                    case "os_api": fallback = "36"; break;
                    case "os_version": fallback = "16"; break;
                    case "resolution": fallback = "1280*2772"; break;
                    case "ssmix": fallback = "a"; break;
                    case "version_name": fallback = "7.3.5.32"; break;
                    default: fallback = "73532";
                }
                query.put(name, get(profile, name, fallback));
            }
            for (String name : new String[]{"cdid", "openudid"}) if (!get(profile, name, "").isEmpty()) query.put(name, profile.get(name));
            query.put("tt_data", "a"); query.put("package", "com.phoenix.read");
            query.put("ts", String.valueOf(now / 1000)); query.put("_rticket", String.valueOf(now));
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", get(profile, "user_agent", RequestSigner.userAgent(profile, false)));
            headers.put("Content-Type", "application/octet-stream;tt-data=a");
            headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
            headers.put("Content-Encoding", "gzip"); headers.put("log-encode-type", "gzip");
            headers.put("X-Tt-Request-Tag", "t=0;n=1"); headers.put("x-ss-dp", "8662");
            headers.put("x-ss-req-ticket", String.valueOf(System.currentTimeMillis()));
            headers.put("x-vc-bdturing-sdk-version", "4.0.3.cn"); headers.put("lc", "102");
            headers.put("sdk-version", "2"); headers.put("passport-sdk-version", "60755");
            headers.put("passport-sdk-settings", "device_transfer_s_0,device_transfer_ab_1");
            headers.put("x-tt-store-region", "cn"); headers.put("x-tt-store-region-src", "did");
            headers.put("x-neptune", "-8|50:51:59:30:40:47:49:39");
            headers.put("x-ss-stub", RequestSigner.hex(MessageDigest.getInstance("MD5").digest(encrypted)).toUpperCase(Locale.ROOT));
            ProtocolHttp.Response response = http.request("POST", "https://log.snssdk.com/service/2/device_register/?" + ProtocolHttp.query(query), headers, encrypted, 16 * 1024 * 1024);
            JSONObject data = response.json();
            String device = data.optString("device_id_str", data.optString("device_id", ""));
            String install = data.optString("install_id_str", data.optString("install_id", ""));
            if (!device.matches("[1-9][0-9]{3,30}") || !install.matches("[1-9][0-9]{3,30}"))
                throw new IOException("设备注册未返回有效编号");
            Map<String, String> cookies = new LinkedHashMap<>(); response.cookies(cookies); cookies.put("install_id", install);
            return new JSONObject().put("device_id", device).put("iid", install).put("cookies", new JSONObject(cookies));
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("设备注册失败", error); }
    }

    static JSONObject header(Map<String, String> profile, long now) throws Exception {
        String brand = get(profile, "device_brand", "Redmi");
        String normalized = brand.toLowerCase(Locale.ROOT);
        String manufacturer = normalized.contains("samsung") ? "Samsung" : normalized.contains("redmi") || normalized.contains("xiaomi") ? "Xiaomi"
                : normalized.contains("huawei") || normalized.contains("honor") ? "HUAWEI" : normalized.contains("oppo") ? "OPPO" : normalized.contains("vivo") ? "vivo" : brand;
        int dpi = integer(get(profile, "dpi", "520"), 520);
        JSONObject header = new JSONObject().put("display_name", "红果免费短剧").put("update_version_code", 73532)
                .put("manifest_version_code", 73532).put("app_version_minor", "").put("aid", 8662)
                .put("channel", get(profile, "channel", "update_64")).put("package", "com.phoenix.read")
                .put("app_version", "7.3.5.32").put("version_code", 73532).put("sdk_version", "3.7.2")
                .put("sdk_target_version", 29).put("os", "Android").put("os_version", get(profile, "os_version", "12"))
                .put("os_api", integer(get(profile, "os_api", "32"), 32)).put("device_model", get(profile, "device_type", "25053RT47C"))
                .put("device_brand", brand).put("device_manufacturer", manufacturer).put("cpu_abi", "arm64-v8a")
                .put("density_dpi", dpi).put("display_density", dpi >= 560 ? "xxxhdpi" : dpi >= 440 ? "xxhdpi" : dpi >= 280 ? "xhdpi" : dpi >= 200 ? "hdpi" : "mdpi")
                .put("resolution", get(profile, "resolution", "1280*2772").replace('*', 'x')).put("language", "zh")
                .put("timezone", 8).put("access", "wifi").put("not_request_sender", 0);
        if (!get(profile, "rom_version", "").isEmpty()) header.put("rom_version", profile.get("rom_version"));
        header.put("cdid", get(profile, "cdid", UUID.randomUUID().toString()))
                .put("openudid", get(profile, "openudid", UUID.randomUUID().toString().replace("-", "").substring(0, 16)))
                .put("clientudid", get(profile, "clientudid", UUID.randomUUID().toString())).put("ipv6_list", new JSONArray())
                .put("region", "CN").put("tz_name", "Asia/Shanghai").put("tz_offset", 28800).put("sim_region", "cn")
                .put("sim_serial_number", new JSONArray()).put("oaid_may_support", true).put("req_id", UUID.randomUUID().toString())
                .put("device_platform", "android").put("custom", new JSONObject().put("account_region_src", "fallback")
                        .put("host_bit", 64).put("account_region", "cn").put("dragon_device_type", get(profile, "dragon_device_type", "pad"))
                        .put("is_android_fold", 0).put("filter_warn", 0).put("web_ua", "").put("is_android_pad", 0))
                .put("pre_installed_channel", "").put("apk_first_install_time", now).put("is_system_app", 0)
                .put("sdk_flavor", "china").put("guest_mode", 0);
        return header;
    }

    static byte[] envelope(byte[] plain) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(plain); }
        byte[] compressed = output.toByteArray();
        byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce);
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        byte[] derived = sha.digest(RequestSigner.concat(sha.digest(nonce), RequestSigner.unhex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(derived, 0, 16), "AES"), new IvParameterSpec(Arrays.copyOfRange(derived, 16, 32)));
        return RequestSigner.concat(new byte[]{0x74, 0x63, 5, 0x10, 0, 0}, nonce, cipher.doFinal(RequestSigner.concat(sha.digest(compressed), compressed)));
    }

    private static String get(Map<String, String> map, String key, String fallback) { String value = map.get(key); return value == null || value.trim().isEmpty() ? fallback : value; }
    private static int integer(String value, int fallback) { try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; } }
}
