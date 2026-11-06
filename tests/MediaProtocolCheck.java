package com.hgtv.clean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Standalone checks use synthetic media and keys; no account or remote service is accessed. */
public final class MediaProtocolCheck {
    private static final byte[] KEY = new byte[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
    private static final byte[] IV0 = new byte[]{0,2,4,6,8,10,12,14,0,0,0,0,0,0,0,0};
    private static final byte[] IV1 = new byte[]{1,3,5,7,9,11,13,15,0,0,0,0,0,0,0,0};
    private static int assertions;

    /** Runs range, index, cryptographic and source-selection checks with ordinary assertions. */
    public static void main(String[] args) throws Exception {
        checkCtrOffsets();
        checkAddress();
        checkRanges();
        for (boolean wide : new boolean[]{false, true}) {
            for (boolean aux64 : new boolean[]{false, true}) checkIndex(wide, aux64);
        }
        System.out.println("Media protocol checks passed: " + assertions + " assertions");
    }

    private static void checkCtrOffsets() throws Exception {
        byte[] clear = new byte[300]; new Random(19).nextBytes(clear);
        byte[] iv = new byte[16]; Arrays.fill(iv, (byte) 255);
        byte[] encrypted = aes("CTR", Cipher.ENCRYPT_MODE, KEY, iv, clear);
        for (int start = 0; start < 180; start++) {
            byte[] slice = Arrays.copyOfRange(encrypted, start, start + 117);
            PreparedMedia.crypt(slice, 0, slice.length, KEY, iv, start);
            equal(slice, Arrays.copyOfRange(clear, start, start + 117), "CTR random offset/carry");
        }
    }

    private static void checkAddress() throws Exception {
        JSONObject rows = new JSONObject()
                .put("ultra", video("1080P", "h264", "https://media.example/1080"))
                .put("high", video("720P", "hevc", "https://media.example/720"))
                .put("low", video("480P", "h264", "https://media.example/480"))
                .put("bad", video("720P", "bytevc2", "https://media.example/unsupported"));
        JSONObject model = new JSONObject().put("video_list", rows);
        require(MediaAddress.resolve(model, 720, 4).url.endsWith("/720"), "quality exact");
        require(MediaAddress.resolve(model, 600, 3).url.endsWith("/480"), "quality lower");
        require(MediaAddress.resolve(model, 360, 3).quality.equals("480P（兼容资源）"), "lowest fallback");
        require(MediaAddress.resolution("1920") == 1080, "width alias");
        require(MediaAddress.resolution("高清720P") == 720, "quality description");
        require(MediaAddress.unescape("https:\\/\\/x\\u002Fy?a=1\\u0026b=2&amp;c=3").equals("https://x/y?a=1&b=2&c=3"), "URL unescape");
        equal(MediaAddress.base64("_-7d zA=="), new byte[]{(byte)255,(byte)238,(byte)221,(byte)204}, "URL-safe base64");

        byte[] clear = concat(new byte[]{0}, "000102030405060708090a0b0c0d0e0f".getBytes(StandardCharsets.US_ASCII));
        byte[] spade = new byte[clear.length + 1];
        int[] previous = new int[]{246,85};
        for (int i = 0; i < clear.length; i++) {
            int encoded = (((clear[i] & 255) + 21 + Integer.bitCount(i)) & 255) ^ previous[i & 1];
            spade[i + 1] = (byte) encoded; previous[i & 1] = encoded;
        }
        spade[0] = (byte) (spade[1] ^ spade[2]);
        equal(MediaAddress.decodeContentKey(Base64.getEncoder().encodeToString(spade)), KEY, "content-key envelope");
        fails(() -> MediaAddress.decodeContentKey("AQID"), "short content key");

        String salt = "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58";
        byte[] seed = new byte[]{3,1,4,1,5,9};
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        sha.update(sha.digest(seed));
        byte[] material = sha.digest(hex(salt));
        String address = "https://media.example/a.mp4?test=synthetic";
        byte[] text = address.getBytes(StandardCharsets.UTF_8);
        int padding = 16 - text.length % 16;
        byte[] padded = Arrays.copyOf(text, text.length + padding);
        Arrays.fill(padded, text.length, padded.length, (byte) padding);
        byte[] cipher = aes("CBC", Cipher.ENCRYPT_MODE, Arrays.copyOfRange(material, 0, 16), Arrays.copyOfRange(material, 16, 32), padded);
        String encoded = Base64.getEncoder().encodeToString(concat(new byte[]{(byte)168,0,1,0}, cipher));
        String decoded = MediaAddress.decodeUrl(encoded, Base64.getEncoder().encodeToString(seed));
        require(address.equals(decoded), "seed-derived URL envelope");
        fails(() -> MediaAddress.decodeUrl("AQID", "AA=="), "bad URL envelope");
        fails(() -> MediaAddress.resolve(new JSONObject().put("video_list", new JSONObject().put("x", video("720", "bvc2", "https://x/a"))), 720, 4), "unsupported codec rejected");
    }

    private static JSONObject video(String quality, String codec, String address) throws Exception {
        return new JSONObject().put("quality_desc", quality).put("codec_type", codec).put("main_url", address);
    }

    private static void checkRanges() throws Exception {
        require(PreparedMedia.checkedRange("bytes 10-19/20", 10, 30, 20)[1] == 19, "range ending at EOF");
        fails(() -> PreparedMedia.checkedRange("bytes 9-19/20", 10, 19, 20), "range wrong start");
        fails(() -> PreparedMedia.checkedRange("bytes 10-18/20", 10, 19, 20), "range wrong end");
        fails(() -> PreparedMedia.checkedRange("bytes 10-19/21", 10, 19, 20), "resource change");
        fails(() -> PreparedMedia.checkedRange("bytes */20", 10, 19, 20), "unsatisfied range");
    }

    private static void checkIndex(boolean wide, boolean aux64) throws Exception {
        byte[][] fixture = fixture(wide, aux64);
        byte[] header = fixture[0], clear = fixture[1], encrypted = fixture[2];
        PreparedMedia.Index index = PreparedMedia.Index.parse(header, true, header.length + 8L + clear.length);
        require(new String(index.header, StandardCharsets.ISO_8859_1).contains("avc1"), "original sample format restored");
        require(!new String(index.header, StandardCharsets.ISO_8859_1).contains("encv"), "encrypted sample tag removed");
        long dataStart = header.length + 8L;
        for (int p = 0; p < clear.length; p++) {
            int length = Math.min(19, clear.length - p);
            byte[] slice = Arrays.copyOfRange(encrypted, p, p + length);
            index.apply(slice, dataStart + p, KEY);
            equal(slice, Arrays.copyOfRange(clear, p, p + length), "MP4 full/subsample random seek");
        }
        byte[] broken = header.clone();
        int sz = find(broken, "stsz"); ByteBuffer.wrap(broken).putInt(sz + 12, Integer.MAX_VALUE);
        fails(() -> PreparedMedia.Index.parse(broken, true, dataStart + clear.length), "oversized sample count");
        byte[] truncated = Arrays.copyOf(header, header.length - 1);
        fails(() -> PreparedMedia.Index.parse(truncated, true, dataStart + clear.length), "truncated index");
    }

    static byte[][] fixture(boolean wide, boolean aux64) throws Exception {
        byte[] aux = concat(Arrays.copyOf(IV0, 8), Arrays.copyOf(IV1, 8), u16(2), u16(5), u32(17), u16(2), u32(16));
        byte[] first = header(0, 0, aux, wide, aux64);
        byte[] header = header(first.length + 8, first.length - aux.length, aux, wide, aux64);
        byte[] clear = new byte[72]; for (int i = 0; i < clear.length; i++) clear[i] = (byte) (i * 73 + 31);
        byte[] encrypted = clear.clone();
        System.arraycopy(aes("CTR", Cipher.ENCRYPT_MODE, KEY, IV0, Arrays.copyOf(clear, 32)), 0, encrypted, 0, 32);
        byte[] joined = concat(Arrays.copyOfRange(clear, 37, 54), Arrays.copyOfRange(clear, 56, 72));
        byte[] crypt = aes("CTR", Cipher.ENCRYPT_MODE, KEY, IV1, joined);
        System.arraycopy(crypt, 0, encrypted, 37, 17);
        System.arraycopy(crypt, 17, encrypted, 56, 16);
        return new byte[][]{header, clear, encrypted};
    }

    private static byte[] header(int chunkOffset, int auxiliaryOffset, byte[] auxiliary, boolean wide, boolean aux64) throws Exception {
        byte[] sample = box("encv", new byte[78], box("sinf", box("frma", "avc1".getBytes(StandardCharsets.US_ASCII))));
        byte[] stbl = box("stbl",
                box("stsd", u32(0), u32(1), sample),
                box("stsz", u32(0), u32(0), u32(2), u32(32), u32(40)),
                box("stsc", u32(0), u32(1), u32(1), u32(2), u32(1)),
                box(wide ? "co64" : "stco", u32(0), u32(1), wide ? u64(chunkOffset) : u32(chunkOffset)),
                box("saiz", u32(0), new byte[]{0}, u32(2), new byte[]{8,22}),
                box("saio", u32(aux64 ? 0x1000000 : 0), u32(1), aux64 ? u64(auxiliaryOffset) : u32(auxiliaryOffset)));
        return concat(box("ftyp", new byte[8]), box("moov", box("trak", box("mdia", box("minf", stbl))), box("free", auxiliary)));
    }

    private static byte[] aes(String mode, int operation, byte[] key, byte[] iv, byte[] bytes) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/" + mode + "/NoPadding");
        cipher.init(operation, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(bytes);
    }

    private static int find(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).indexOf(text);
    }

    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    private static byte[] u16(int value) { return ByteBuffer.allocate(2).putShort((short) value).array(); }
    private static byte[] u32(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
    private static byte[] u64(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
    private static byte[] box(String name, byte[]... data) throws Exception {
        byte[] payload = concat(data); return concat(u32(payload.length + 8), name.getBytes(StandardCharsets.US_ASCII), payload);
    }
    private static byte[] concat(byte[]... arrays) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] bytes : arrays) out.write(bytes); return out.toByteArray();
    }
    private static void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private static void equal(byte[] first, byte[] second, String reason) { require(Arrays.equals(first, second), reason); }
    private interface Action { void run() throws Exception; }
    private static void fails(Action action, String reason) throws Exception {
        try { action.run(); throw new AssertionError(reason); } catch (IOException expected) { assertions++; }
    }
}
