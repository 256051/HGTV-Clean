package com.hgtv.clean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Decodes a successful playback model into one device-compatible media source. */
public final class MediaAddress {
    private static final String URL_SALT = "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58";
    private static final Pattern RESOLUTION = Pattern.compile("2160|1440|1080|720|576|540|480|360");
    public final String url;
    public final byte[] key;
    public final String quality;

    private MediaAddress(String url, byte[] key, String quality) { this.url = url; this.key = key; this.quality = quality; }

    /** Chooses the best supported definition no higher than requested, retaining the lowest fallback. */
    public static MediaAddress resolve(JSONObject data, int requestedQuality, int deviceLevel) throws IOException {
        if (data == null || requestedQuality <= 0 || deviceLevel < 0) throw new IOException("视频模型参数异常");
        JSONObject videos = data.optJSONObject("video_list");
        if (videos == null) throw new IOException("没有可用清晰度");
        List<Definition> choices = new ArrayList<>();
        Iterator<String> names = videos.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject video = videos.optJSONObject(name);
            if (video == null || text(video, "main_url").isEmpty()) continue;
            String codec = text(video, "codec_type").toLowerCase(Locale.ROOT);
            if ("bytevc2".equals(codec) || "bvc2".equals(codec)) continue;
            choices.add(new Definition(definition(name, video), video));
        }
        if (choices.isEmpty()) throw new IOException("没有电视可播放的清晰度，请选择其他剧集");
        Collections.sort(choices, (first, second) -> Integer.compare(second.quality, first.quality));
        Definition selected = choices.get(choices.size() - 1);
        for (Definition choice : choices) {
            if (choice.quality <= requestedQuality) { selected = choice; break; }
        }
        String address = unescape(text(selected.video, "main_url"));
        String seed = text(data, "key_seed");
        if (!seed.isEmpty()) address = decodeUrl(address, seed);
        URL checked = new URL(address);
        if ((!"https".equals(checked.getProtocol()) && !"http".equals(checked.getProtocol()))
                || checked.getHost().isEmpty() || checked.getUserInfo() != null) throw new IOException("播放地址无效");
        String spade = text(selected.video, "spade_a");
        byte[] key = spade.isEmpty() ? null : decodeContentKey(spade);
        String quality = selected.quality > 0 ? selected.quality + "P" + (selected.quality > requestedQuality ? "（兼容资源）" : "") : "自动";
        return new MediaAddress(address, key, quality);
    }

    private static int definition(String name, JSONObject row) {
        int value = resolution(text(row, "quality_desc"));
        if (value > 0) return value;
        int width = integer(text(row, "vwidth"));
        int height = integer(text(row, "vheight"));
        if (width > 0 && height > 0 && (value = resolution(String.valueOf(Math.min(width, height)))) > 0) return value;
        if (height > 0 && (value = resolution(String.valueOf(height))) > 0) return value;
        if ((value = resolution(text(row, "height"))) > 0) return value;
        if ((value = resolution(text(row, "quality"))) > 0) return value;
        return resolution(name);
    }

    static int resolution(String value) {
        Matcher found = RESOLUTION.matcher(value);
        if (found.find()) return Integer.parseInt(found.group());
        switch (value) {
            case "1920": return 1080;
            case "1280": return 720;
            case "1024": return 576;
            case "854": return 480;
            case "640": return 360;
            default: return integer(value);
        }
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String text(JSONObject object, String key) {
        Object value = object.opt(key);
        return value == null || value == JSONObject.NULL ? "" : value.toString();
    }

    static String unescape(String value) {
        return value.replace("\\/", "/").replaceAll("(?i)\\\\u002f", "/")
                .replace("\\u0026", "&").replace("&amp;", "&");
    }

    static byte[] base64(String text) throws IOException {
        if (text.length() > 1024 * 1024) throw new IOException("视频模型字段过大");
        ByteArrayOutputStream output = new ByteArrayOutputStream(text.length() * 3 / 4);
        int pending = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '=') break;
            int digit = value >= 'A' && value <= 'Z' ? value - 'A'
                    : value >= 'a' && value <= 'z' ? value - 'a' + 26
                    : value >= '0' && value <= '9' ? value - '0' + 52
                    : value == '+' || value == '-' ? 62 : value == '/' || value == '_' ? 63 : -1;
            if (digit < 0) continue;
            pending = (pending << 6) | digit;
            bits += 6;
            if (bits >= 8) { bits -= 8; output.write((pending >>> bits) & 255); }
        }
        return output.toByteArray();
    }

    static String decodeUrl(String value, String seed) throws IOException {
        byte[] envelope = base64(value);
        if (envelope.length < 20 || (envelope[0] & 255) != 168 || envelope[2] != 1 || envelope[3] != 0)
            throw new IOException("播放地址信封格式异常");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            byte[] seedHash = sha.digest(base64(seed));
            sha.update(seedHash);
            byte[] material = sha.digest(hex(URL_SALT));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(material, 0, 16), "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(material, 16, 32)));
            byte[] clear = cipher.doFinal(envelope, 4, (envelope.length - 4) / 16 * 16);
            int length = clear.length;
            int padding = clear[length - 1] & 255;
            if (padding >= 1 && padding <= 16 && padding <= length) length -= padding;
            while (length > 0 && clear[length - 1] == 0) length--;
            return new String(clear, 0, length, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) { throw new IOException("播放地址解析失败", error); }
    }

    static byte[] decodeContentKey(String value) throws IOException {
        byte[] envelope = base64(value);
        if (envelope.length < 3) throw new IOException("内容密钥格式异常");
        int stop = Math.min(envelope.length - ((envelope[0] & 255) ^ (envelope[1] & 255) ^ (envelope[2] & 255)) + 47,
                envelope.length - 1);
        if (stop < 33) throw new IOException("内容密钥格式异常");
        byte[] decoded = Arrays.copyOfRange(envelope, 1, stop + 1);
        int even = 246;
        int odd = 85;
        for (int i = 0; i < decoded.length; i++) {
            int previous = (i & 1) == 0 ? even : odd;
            int current = decoded[i] & 255;
            if ((i & 1) == 0) even = current; else odd = current;
            decoded[i] = (byte) ((current ^ previous) - 21 - Integer.bitCount(i));
        }
        byte[] key = hex(new String(decoded, 1, 32, StandardCharsets.US_ASCII));
        if (key.length != 16) throw new IOException("内容密钥长度异常");
        return key;
    }

    private static byte[] hex(String value) throws IOException {
        if ((value.length() & 1) != 0) throw new IOException("十六进制字段长度异常");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IOException("十六进制字段异常");
            result[i] = (byte) ((hi << 4) | lo);
        }
        return result;
    }

    private static final class Definition {
        final int quality;
        final JSONObject video;
        Definition(int quality, JSONObject video) { this.quality = quality; this.video = video; }
    }
}
