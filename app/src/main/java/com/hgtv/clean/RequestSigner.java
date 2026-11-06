package com.hgtv.clean;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Constructs the content protocol's deterministic wire format and per-request signatures. */
public final class RequestSigner {
    private static final SecureRandom RANDOM = new SecureRandom();
    private RequestSigner() { }

    /** Creates an independently signed request using this installation's profile. */
    public static Signed signed(String base, LinkedHashMap<String, String> parameters, byte[] body, Map<String, String> profile) throws IOException {
        try { return sign(base, parameters, body, profile, System.currentTimeMillis(), RANDOM::nextDouble); }
        catch (Exception error) { throw new IOException("请求签名生成失败", error); }
    }

    static Signed sign(String base, LinkedHashMap<String, String> parameters, byte[] body, Map<String, String> profile,
                       long now, RandomSource random) throws Exception {
        int timestamp = (int) (now / 1000);
        byte[] bodyHash = body == null ? new byte[16] : md5(body);
        String query = null;
        long ticket = now;
        byte[] queryHash = null;
        for (int offset = 0; offset < 128; offset++) {
            LinkedHashMap<String, String> candidate = new LinkedHashMap<>(parameters);
            ticket = now + offset;
            candidate.put("ts", String.valueOf(timestamp)); candidate.put("_rticket", String.valueOf(ticket));
            query = ProtocolHttp.query(candidate).replace("+", "%20").replace("%7E", "~"); queryHash = sm3(utf8(query));
            if ((branchHash(queryHash, bodyHash, timestamp) & 15) % 3 == 0) break;
            if (offset == 127) throw new IOException("无法生成请求签名");
        }
        String url = base + '?' + query;
        int gorgonNonce = (int) (random.getAsDouble() * 65536);
        int heliosNonce = randomInt(random);
        String version = get(profile, "version_name", "7.2.2.32");
        String device = get(profile, "device_id", "0");
        byte[] digest = requestDigest(queryHash, bodyHash, timestamp);
        long requestNonce = randomInt(random) & 0xffffffffL;
        byte[] environment = environment(profile, version, now / 1000, random);
        byte[] payload = proto(bytes(1, unhex("f7e85ffad7d7dc3bd62ac87057cf6118")), number(2, 3), number(3, requestNonce),
                text(4, "8662"), text(5, device), text(6, "1588093228"), text(7, version), text(8, "v04.06.04-ml-android"),
                number(9, 67503104), bytes(10, unhex("4001000000000000")), number(12, timestamp), bytes(13, digest),
                bytes(14, Arrays.copyOf(queryHash, 6)), bytes(15, proto(number(1, 111), number(2, 10), number(3, 694367), number(5, 586952199))),
                text(16, "AXYQOS6n2m60x1fVZHIrH3iol"), number(17, timestamp), bytes(19, sm3(concat(utf8(query), bodyHash, utf8("none")))),
                text(20, "none"), number(21, 312), bytes(23, environment),
                text(24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}"));
        int medusaNonce = randomInt(random);
        byte[] medusa = medusa(payload, queryHash, digest, timestamp, medusaNonce, randomInt(random));
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent(profile, body != null));
        headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        headers.put("x-ss-req-ticket", String.valueOf(ticket)); headers.put("x-tt-request-tag", "t=0;n=0");
        headers.put("sdk-version", "2"); headers.put("passport-sdk-version", base.contains("/passport/") ? "5051452" : "6031190");
        headers.put("x-xs-from-web", "0"); headers.put("x-khronos", String.valueOf(timestamp));
        headers.put("x-ladon", b64(be32(timestamp))); headers.put("x-argus", b64(le32(timestamp)));
        headers.put("x-gorgon", gorgon(query, bodyHash, body != null, timestamp, gorgonNonce));
        headers.put("x-helios", b64(helios(timestamp, heliosNonce))); headers.put("x-medusa", b64(medusa)); headers.put("x-tt-dt", "");
        if (base.contains("/passport/")) { headers.put("x-vc-bdturing-sdk-version", "4.0.3.cn"); headers.put("lc", "101"); headers.put("x-ss-dp", "8662"); }
        if (body != null) { headers.put("Content-Type", "application/json; charset=UTF-8"); headers.put("x-vc-bdturing-sdk-version", "3.7.2.cn"); headers.put("x-ss-stub", hex(bodyHash).toUpperCase(Locale.ROOT)); }
        return new Signed(url, headers, body, body == null ? "GET" : "POST");
    }

    /** Formats the protocol user agent from the current device profile. */
    public static String userAgent(Map<String, String> profile, boolean body) {
        String build = get(profile, "rom_version", "BP2A.250605.031.A3").split(" ", 2)[0];
        return "com.phoenix.read/" + (body ? "71332" : "72232") + " (Linux; U; Android " + get(profile, "os_version", "16")
                + "; zh_CN; " + get(profile, "device_type", "25053RT47C") + "; Build/" + build
                + (body ? "; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)" : ")");
    }

    private static int randomInt(RandomSource random) { return (int) (long) (random.getAsDouble() * 4294967296d); }
    private static int branchHash(byte[] query, byte[] body, int timestamp) { return mix(mix(mix(0x20230928, query), body), le32(timestamp)); }
    private static int mix(int hash, byte[] input) {
        for (int i = 0; i < input.length; i++) hash = (i & 1) == 0 ? (hash >>> 4) ^ hash ^ (hash << 6) ^ (input[i] & 255)
                : ~((hash >>> 7) ^ hash ^ ((hash << 12) | (input[i] & 255)));
        return hash;
    }

    private static String gorgon(String query, byte[] bodyHash, boolean hasBody, int timestamp, int nonce) throws Exception {
        byte[] source = concat(Arrays.copyOf(md5(utf8(query)), 4), hasBody ? Arrays.copyOf(bodyHash, 4) : new byte[4], new byte[4], le32(67503104), be32(timestamp));
        int[] key = {74, 64, 22, (nonce >> 8) & 255, 71, 108, 1, nonce & 255};
        int[] state = new int[256]; for (int i = 0; i < 256; i++) state[i] = i;
        int cursor = 0;
        for (int i = 0; i < 256; i++) { cursor = (cursor + state[i] + key[i & 7]) & 255; state[i] = state[cursor]; }
        byte[] output = new byte[source.length]; cursor = 0;
        for (int i = 0; i < source.length; i++) { cursor += state[i + 1]; int value = state[cursor & 255]; state[i + 1] = value; output[i] = (byte) ((source[i] & 255) ^ state[(value * 2) & 255]); }
        for (int i = 0; i < output.length; i++) { int value = output[i] & 255; int swapped = ((value >>> 4) | (value << 4)) & 255; output[i] = (byte) ((Integer.reverse(swapped ^ (output[(i + 1) % output.length] & 255)) >>> 24) ^ -21); }
        return hex(concat(unhex("8404"), new byte[]{(byte) nonce, (byte) (nonce >> 8), 64, 1}, output));
    }

    private static byte[] helios(int timestamp, int nonce) throws Exception {
        byte[] key = utf8(hex(md5(concat(le32(nonce), utf8("8662")))));
        long[] queue = {longAt(key, 8), longAt(key, 16), longAt(key, 24)};
        long[] rounds = new long[35]; rounds[0] = longAt(key, 0);
        for (int i = 0; i < 34; i++) {
            long next = (Long.rotateRight(queue[0], 8) + rounds[i]) ^ i;
            rounds[i + 1] = next ^ Long.rotateRight(rounds[i], 61);
            queue[0] = queue[1]; queue[1] = queue[2]; queue[2] = next;
        }
        byte[] plain = utf8(timestamp + "-1588093228-8662"); int padding = 16 - plain.length % 16;
        int length = plain.length; plain = Arrays.copyOf(plain, length + padding); Arrays.fill(plain, length, plain.length, (byte) padding);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(le32(nonce));
        for (int offset = 0; offset < plain.length; offset += 16) {
            long left = longAt(plain, offset), right = longAt(plain, offset + 8);
            for (int i = 0; i < 34; i++) { right = rounds[i] ^ (Long.rotateRight(right, 8) + left); left = Long.rotateRight(left, 61) ^ right; }
            output.write(le64(left)); output.write(le64(right));
        }
        return output.toByteArray();
    }

    private static byte[] requestDigest(byte[] queryHash, byte[] bodyHash, int timestamp) {
        int branch = (branchHash(queryHash, bodyHash, timestamp) & 15) / 3;
        int seed = new int[]{-995654272, -1279197895, -980658554, -911590489, -173560334, -1774440751}[branch];
        int rotation = seed + timestamp;
        byte[] prefix = new byte[4]; int[] values = {250, 69, 97, 215};
        for (int i = 0; i < 4; i++) prefix[i] = (byte) ((values[i] | (values[i] << 8)) >> ((rotation + 2) & 7));
        byte[] block = concat(queryHash, bodyHash, le32(timestamp), prefix, unhex("00000000000001a0"));
        int[] schedule = new int[128]; for (int i = 0; i < 16; i++) schedule[i] = intAt(block, i * 4, false);
        for (int i = 0; i < 112; i++) {
            int a = schedule[i + 1], b = schedule[i + 14];
            schedule[i + 16] = schedule[i + 9] + schedule[i] + (Integer.rotateLeft(a, 14) ^ Integer.rotateLeft(a, 25) ^ (a >>> 3)) + (Integer.rotateLeft(b, 13) ^ Integer.rotateLeft(b, 15) ^ (b >>> 10));
        }
        int[] initial = {2059030472, 1729520903, 1677982208, 873419055, 984304912, -1289919430, -1420842003, -2142554032};
        for (int i = 0; i < 8; i++) initial[i] = Integer.rotateRight(initial[i], rotation);
        int[] state = initial.clone();
        int[] rule = new int[][]{{101,5,7,6,3,2,1,0,5,4,3}, {96,0,6,7,5,3,2,1,5,4,4}, {96,7,6,2,1,4,0,5,4,3,5}, {99,3,6,2,4,5,1,0,0,7,6}, {96,0,5,6,7,3,1,2,5,4,4}, {100,2,0,3,5,4,6,7,2,1,5}}[branch];
        for (int i = 0; i < rule[0]; i++) {
            int index = seed + i;
            int a = state[rule[1]], b = state[rule[2]], c = state[rule[3]], d = state[rule[4]], e = state[rule[6]], f = state[rule[7]];
            int temporary = state[rule[5]] + Integer.rotateRight(ProtocolTables.ROUND_CONSTANTS[index & 63], (rotation + 1) & 255) + schedule[index & 127]
                    + ((d ^ c) & a ^ c) + (Integer.rotateLeft(a,26) ^ Integer.rotateLeft(a,21) ^ Integer.rotateLeft(a,7));
            int next = temporary + (Integer.rotateLeft(b,30) ^ Integer.rotateLeft(b,19) ^ Integer.rotateLeft(b,10)) + ((e & b) | ((e | b) & f));
            int sum = state[rule[9]] + temporary;
            int last = state[7];
            System.arraycopy(state, 0, state, 1, 7); state[0] = last;
            state[rule[10]] = next; state[rule[8]] = sum;
        }
        byte[] combined = new byte[32];
        for (int i = 0; i < 8; i++) System.arraycopy(be32(initial[i] + state[i]), 0, combined, i * 4, 4);
        byte[] digest = new byte[16]; for (int i = 0; i < 16; i++) digest[i] = (byte) (combined[i] ^ combined[i + 16]);
        int check = 539100192;
        for (int i = 0; i < 12; i++) check = ((i & 1) == 0 ? (check << 7) ^ (digest[i] & 255) : ~((check << 11) | (digest[i] & 255))) ^ ((check >>> ((i & 1) == 0 ? 3 : 5)) ^ check);
        return concat(digest, le32((check | 4) ^ 16777216));
    }

    private static byte[] environment(Map<String, String> profile, String version, long seconds, RandomSource random) throws IOException {
        String brand = get(profile, "device_brand", "Redmi"); String lower = brand.toLowerCase(Locale.ROOT);
        String manufacturer = lower.contains("samsung") ? "Samsung" : lower.contains("redmi") || lower.contains("xiaomi") ? "Xiaomi" : brand;
        String model = get(profile, "device_type", "25053RT47C");
        byte[] device = proto(number(1,1), number(2,2), text(3,"8662"), text(4,get(profile,"device_id","0")), text(5,"Ai6svO3PyrwDOUSmO6ZcResxu"),
                text(6,"!noperm!"), number(7,-888888), number(8,-888888), number(9,3), number(10,-888888), text(11,"!notset!"), text(12,"Asia/Shanghai,8"), text(13,"zh_CN"), number(14,4),
                floating(16,255.24993896484375), floating(17,35.58599090576172), floating(18,3.467449188232422), floating(19,3.467449188232422), floating(20,255.1754913330078), floating(21,42.17544174194336),
                text(22,get(profile,"os_version","16")), number(23,41), number(24,integer(get(profile,"os_api","36"),36)), number(25,1728388016635L), number(26,1728388016635L), number(27,1728388016635L), number(28,1728388016637L),
                number(29,-1), text(30,model), text(31,brand), text(32,model), text(33,model), text(34,manufacturer), text(35,brand), text(36,brand), number(38,31));
        return proto(number(1,(int)(random.getAsDouble()*21)+100), number(2,146331399), number(3,146331396), number(5,7), text(6,"v04.06.04.03-bugfix"),
                number(7,(int)(random.getAsDouble()*2000)+10001), bytes(12,device), bytes(13,proto(number(1,seconds),number(2,-2),number(4,200))), text(14,version));
    }

    private static byte[] medusa(byte[] payload, byte[] queryHash, byte[] digest, int timestamp, int nonce, int extraNonce) throws IOException {
        byte[] secret = unhex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
        byte[] hash = sm3(concat(secret, le32(nonce), secret));
        int high = nonce >>> 16; int lowHigh = high & 255;
        byte[] mask = le32(~(lowHigh ^ ((lowHigh << 11) | (nonce >>> 24)) ^ (lowHigh >>> 5)));
        int[] values = new int[payload.length];
        for (int i = 0; i < payload.length; i++) {
            int position = (i * 4) & 28, a = hash[position] & 255, b = hash[position + 1] & 255, value = payload[i] & 255;
            int next = (~((((value >>> 4) | (value << 4)) & 255) + a) ^ b) & 255;
            values[payload.length - 1 - i] = ~(((((next >>> 5) | (next << 3)) & 255) + b) & 255 ^ a) & 255;
        }
        int sum = values[values.length - 1] ^ values[values.length - 2], first = values[0];
        values[0] = (~sum + first) & 255;
        values[1] = ((values[0] ^ values[values.length - 1] ^ 254) + values[1]) & 255;
        values[2] = (values[2] + ((sum - first) ^ (((values[1] << 3) | (values[1] >>> 5)) & 255) ^ 2)) & 255;
        for (int i = 0; i < values.length - 4; i++) values[i + 3] = (~((((values[i + 2] >>> 5) | (values[i + 2] << 3)) & 255) ^ values[i + 1] ^ (i + 3)) + values[i + 3]) & 255;
        values[values.length - 1] ^= values[values.length - 2];
        int firstXor = values[0] ^ values[1], total = 0; for (int i = 1; i < values.length; i++) total += values[i];
        values[0] = (total + firstXor) & 255;
        byte[] transformed = new byte[values.length]; for (int i = 0; i < values.length; i++) transformed[i] = (byte) values[i];
        byte[] reversed = concat(unhex("4001000000000000"), transformed);
        for (int i = 0, j = reversed.length - 1; i < j; i++, j--) { byte swap = reversed[i]; reversed[i] = reversed[j]; reversed[j] = swap; }
        for (int i = 0; i < reversed.length; i++) reversed[i] ^= mask[(~i) & 3];
        int tag = ((queryHash[0] & 63) << 14) | 402653185 | ((digest[0] & 63) << 8);
        byte[] plain = concat(new byte[]{53}, le32(extraNonce), le32(tag), reversed, new byte[]{(byte) high, (byte) (nonce >> 24)});
        byte[] encrypted = new SignatureCipher(timestamp).encrypt(plain, unhex("1fe109a4125283f418de9e051a969e12"));
        byte[] prefix = unhex("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
        for (int i = 0; i < prefix.length; i += 4) System.arraycopy(le32(intAt(prefix,i,true) ^ timestamp), 0, prefix, i, 4);
        return concat(prefix, new byte[]{(byte) nonce, (byte) (nonce >> 8), 0, 1}, encrypted);
    }

    private static byte[] proto(byte[]... fields) { return concat(fields); }
    private static byte[] number(int field, long value) { return concat(varint((long) field << 3), varint((value << 1) ^ (value >> 63))); }
    private static byte[] text(int field, String value) { return bytes(field, utf8(value)); }
    private static byte[] bytes(int field, byte[] value) { return value.length == 0 ? new byte[0] : concat(varint(((long) field << 3) | 2), varint(value.length), value); }
    private static byte[] floating(int field, double value) { return concat(varint(((long) field << 3) | 5), le32(Float.floatToIntBits((float) value))); }
    private static byte[] varint(long value) { ByteArrayOutputStream out = new ByteArrayOutputStream(); while ((value & ~127L) != 0) { out.write(((int) value & 127) | 128); value >>>= 7; } out.write((int) value); return out.toByteArray(); }

    static byte[] sm3(byte[] input) {
        int[] state = {0x7380166f,0x4914b2b9,0x172442d7,0xda8a0600,0xa96f30bc,0x163138aa,0xe38dee4d,0xb0fb0e4e};
        int length = ((input.length + 9 + 63) / 64) * 64; byte[] padded = Arrays.copyOf(input,length); padded[input.length] = (byte) 128;
        ByteBuffer.wrap(padded, length - 8, 8).putLong((long) input.length * 8);
        int[] words = new int[68];
        for (int offset = 0; offset < length; offset += 64) {
            for (int i = 0; i < 16; i++) words[i] = intAt(padded, offset + i * 4, false);
            for (int i = 16; i < 68; i++) { int value = words[i-16] ^ words[i-9] ^ Integer.rotateLeft(words[i-3],15); words[i] = value ^ Integer.rotateLeft(value,15) ^ Integer.rotateLeft(value,23) ^ Integer.rotateLeft(words[i-13],7) ^ words[i-6]; }
            int a=state[0],b=state[1],c=state[2],d=state[3],e=state[4],f=state[5],g=state[6],h=state[7];
            for (int i = 0; i < 64; i++) {
                int rotatedA=Integer.rotateLeft(a,12), first=Integer.rotateLeft(rotatedA+e+Integer.rotateLeft(i<16?0x79cc4519:0x7a879d8a,i),7), second=first^rotatedA;
                int t1=(i<16 ? a^b^c : (a&b)|(a&c)|(b&c))+d+second+(words[i]^words[i+4]);
                int t2=(i<16 ? e^f^g : (e&f)|(~e&g))+h+first+words[i];
                d=c;c=Integer.rotateLeft(b,9);b=a;a=t1;h=g;g=Integer.rotateLeft(f,19);f=e;e=t2^Integer.rotateLeft(t2,9)^Integer.rotateLeft(t2,17);
            }
            int[] next={a,b,c,d,e,f,g,h}; for(int i=0;i<8;i++) state[i]^=next[i];
        }
        byte[] output=new byte[32];for(int i=0;i<8;i++)System.arraycopy(be32(state[i]),0,output,i*4,4);return output;
    }

    static byte[] concat(byte[]... values) { int length=0; for(byte[] value:values)length+=value.length; byte[] result=new byte[length];int at=0;for(byte[] value:values){System.arraycopy(value,0,result,at,value.length);at+=value.length;}return result; }
    static String hex(byte[] input) { char[] digits="0123456789abcdef".toCharArray(), output=new char[input.length*2];for(int i=0;i<input.length;i++){output[i*2]=digits[(input[i]>>>4)&15];output[i*2+1]=digits[input[i]&15];}return new String(output); }
    static byte[] unhex(String value) { byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes; }
    private static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static byte[] md5(byte[] input)throws Exception{return MessageDigest.getInstance("MD5").digest(input);}
    private static byte[] le32(int value){return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();}
    private static byte[] be32(int value){return ByteBuffer.allocate(4).putInt(value).array();}
    private static byte[] le64(long value){return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();}
    private static long longAt(byte[] value,int at){return ByteBuffer.wrap(value,at,8).order(ByteOrder.LITTLE_ENDIAN).getLong();}
    private static int intAt(byte[] value,int at,boolean little){return ByteBuffer.wrap(value,at,4).order(little?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN).getInt();}
    private static String b64(byte[] value){return Base64.encodeToString(value,Base64.NO_WRAP);}
    private static String get(Map<String,String> profile,String name,String fallback){String value=profile.get(name);return value==null||value.trim().isEmpty()?fallback:value;}
    private static int integer(String value,int fallback){try{return Integer.parseInt(value);}catch(NumberFormatException ignored){return fallback;}}
    interface RandomSource { double getAsDouble(); }

    /** Signed wire request; its headers can contain credentials and must not be logged. */
    public static final class Signed {
        public final String url;
        public final LinkedHashMap<String,String> headers;
        public final byte[] body;
        public final String method;
        Signed(String url,LinkedHashMap<String,String> headers,byte[] body,String method){this.url=url;this.headers=headers;this.body=body;this.method=method;}
    }
}
