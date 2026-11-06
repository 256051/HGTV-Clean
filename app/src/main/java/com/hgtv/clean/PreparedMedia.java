package com.hgtv.clean;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Prepares the platform's MP4 index and serves cancellable, seekable media reads. */
public final class PreparedMedia implements Closeable {
    private static final int MAX_INDEX = 8 * 1024 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private final URL url;
    private final byte[] key;
    private final String userAgent;
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private volatile boolean closed;
    private volatile long size;
    private byte[] prefix = new byte[0];
    private volatile Index index;

    /** Uses only the source address and content key returned by a successful playback request. */
    public PreparedMedia(String address, byte[] contentKey, String userAgent) throws IOException {
        url = checkedUrl(new URL(address));
        if (contentKey != null && contentKey.length != 16) throw new IOException("内容密钥格式异常");
        key = contentKey == null ? null : contentKey.clone();
        this.userAgent = userAgent;
    }

    /** Fetches the bounded MP4 index; close can interrupt an in-flight preparation. */
    public synchronized void prepare() throws IOException {
        checkOpen();
        if (index != null) return;
        prefix = fetch(0, 262143);
        int end = indexLength(prefix, size);
        byte[] header = Arrays.copyOf(prefix, end);
        if (end > prefix.length) {
            byte[] rest = fetch(prefix.length, end - 1L);
            System.arraycopy(rest, 0, header, prefix.length, rest.length);
        }
        Index prepared = Index.parse(header, key != null, size);
        checkOpen();
        index = prepared;
    }

    /** Returns the resource size only after its index has been prepared. */
    public long length() throws IOException {
        checkOpen();
        if (index == null) throw new IOException("视频源尚未准备");
        return size;
    }

    /** Returns at most two MiB from an arbitrary byte offset, applying the indexed transformations. */
    public byte[] read(long position, int count) throws IOException {
        checkOpen();
        Index prepared = index;
        if (prepared == null) throw new IOException("视频源尚未准备");
        if (position < 0 || count < 0 || count > 2 * 1024 * 1024) throw new IOException("视频读取范围异常");
        if (position >= size || count == 0) return new byte[0];
        int length = (int) Math.min(count, size - position);
        byte[] result = new byte[length];
        int cached = position < prefix.length ? (int) Math.min(length, prefix.length - position) : 0;
        if (cached > 0) System.arraycopy(prefix, (int) position, result, 0, cached);
        if (cached < length) {
            byte[] tail = fetch(position + cached, position + length - 1);
            System.arraycopy(tail, 0, result, cached, tail.length);
        }
        checkOpen();
        prepared.apply(result, position, key);
        return result;
    }

    /** Cancels this source's connections without changing the user's saved session. */
    @Override public void close() {
        closed = true;
        HttpURLConnection[] pending;
        synchronized (connections) { pending = connections.toArray(new HttpURLConnection[0]); }
        for (HttpURLConnection connection : pending) connection.disconnect();
    }

    private void checkOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("播放请求已取消");
    }

    private byte[] fetch(long start, long end) throws IOException {
        if (start < 0 || end < start || end - start >= MAX_INDEX) throw new IOException("视频分段范围异常");
        URL current = url;
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkOpen();
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Referer", "https://novel.snssdk.com/");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            synchronized (connections) {
                checkOpen();
                connections.add(connection);
            }
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new IOException("视频跳转地址缺失");
                    URL target = checkedUrl(new URL(current, next));
                    if ("https".equals(current.getProtocol()) && !"https".equals(target.getProtocol()))
                        throw new IOException("视频连接不允许降级");
                    current = target;
                    continue;
                }
                if (code != 206) throw new IOException("视频服务器不支持分段读取（HTTP " + code + "）");
                long[] range = checkedRange(connection.getHeaderField("Content-Range"), start, end, size);
                int expected = (int) (range[1] - start + 1);
                byte[] bytes = new byte[expected];
                try (InputStream stream = connection.getInputStream()) {
                    int consumed = 0;
                    while (consumed < expected) {
                        checkOpen();
                        int read = stream.read(bytes, consumed, expected - consumed);
                        if (read < 0) throw new EOFException("视频分段不完整");
                        consumed += read;
                    }
                    if (stream.read() != -1) throw new IOException("视频分段长度不匹配");
                }
                size = range[2];
                return bytes;
            } finally {
                synchronized (connections) { connections.remove(connection); }
                connection.disconnect();
            }
        }
        throw new IOException("视频跳转次数过多");
    }

    private static URL checkedUrl(URL value) throws IOException {
        if ((!"https".equals(value.getProtocol()) && !"http".equals(value.getProtocol()))
                || value.getHost().isEmpty() || value.getUserInfo() != null)
            throw new IOException("视频地址无效");
        return value;
    }

    static long[] checkedRange(String text, long start, long end, long knownLength) throws IOException {
        Matcher match = CONTENT_RANGE.matcher(text == null ? "" : text);
        if (!match.matches()) throw new IOException("视频分段响应异常");
        try {
            long first = Long.parseLong(match.group(1));
            long last = Long.parseLong(match.group(2));
            long total = Long.parseLong(match.group(3));
            if (total <= 0 || first != start || last != Math.min(end, total - 1) || last < first)
                throw new IOException("视频分段范围不匹配");
            if (knownLength != 0 && total != knownLength) throw new IOException("视频资源已变化，请重试");
            return new long[]{first, last, total};
        } catch (NumberFormatException error) { throw new IOException("视频分段响应异常", error); }
    }

    static int indexLength(byte[] bytes, long total) throws IOException {
        if (bytes.length < 16 || !type(bytes, 4, "ftyp")) throw new IOException("不支持的视频容器");
        long ftypEnd = u32(bytes, 0, bytes.length);
        if (ftypEnd < 8 || ftypEnd > bytes.length - 8 || !type(bytes, (int) ftypEnd + 4, "moov"))
            throw new IOException("视频索引不在文件开头");
        long moovSize = u32(bytes, (int) ftypEnd, bytes.length);
        long end = ftypEnd + moovSize;
        if (moovSize < 8 || end > total || end > MAX_INDEX) throw new IOException("视频索引大小异常");
        return (int) end;
    }

    static final class Index {
        final byte[] header;
        final List<Span> spans;

        private Index(byte[] header, List<Span> spans) { this.header = header; this.spans = spans; }

        static Index parse(byte[] bytes, boolean encrypted, long total) throws IOException {
            byte[] header = bytes.clone();
            List<Span> spans = new ArrayList<>();
            if (!encrypted) return new Index(header, spans);
            Box moov = box(bytes, (int) u32(bytes, 0, bytes.length), bytes.length);
            if (!"moov".equals(moov.type)) throw new IOException("视频索引不完整");
            for (Box track : children(bytes, moov)) {
                if (!"trak".equals(track.type)) continue;
                Box mdia = child(bytes, track, "mdia", false);
                Box minf = mdia == null ? null : child(bytes, mdia, "minf", false);
                Box stbl = minf == null ? null : child(bytes, minf, "stbl", false);
                if (stbl == null || child(bytes, stbl, "saiz", false) == null) continue;
                parseTrack(bytes, header, stbl, spans, total);
            }
            if (spans.isEmpty()) throw new IOException("视频加密索引不可用");
            Collections.sort(spans, (first, second) -> Long.compare(first.start, second.start));
            long previousEnd = 0;
            for (Span span : spans) {
                if (span.start < previousEnd) throw new IOException("视频样本重叠");
                previousEnd = span.end;
            }
            return new Index(header, spans);
        }

        private static void parseTrack(byte[] b, byte[] output, Box stbl, List<Span> spans, long total) throws IOException {
            Box sizes = child(b, stbl, "stsz", true);
            int count = count(b, sizes.data + 8, sizes.end);
            long fixedSize = u32(b, sizes.data + 4, sizes.end);
            if (fixedSize == 0) table(sizes.data + 12, count, 4, sizes.end);
            long[] sampleSizes = new long[count];
            for (int i = 0; i < count; i++) sampleSizes[i] = fixedSize == 0 ? u32(b, sizes.data + 12 + i * 4, sizes.end) : fixedSize;

            Box offsets = child(b, stbl, "stco", false);
            boolean wide = offsets == null;
            if (wide) offsets = child(b, stbl, "co64", true);
            int chunks = count(b, offsets.data + 4, offsets.end);
            int width = wide ? 8 : 4;
            table(offsets.data + 8, chunks, width, offsets.end);

            Box mapping = child(b, stbl, "stsc", true);
            int mappings = count(b, mapping.data + 4, mapping.end);
            if (mappings == 0) throw new IOException("视频块索引为空");
            table(mapping.data + 8, mappings, 12, mapping.end);
            long[] firstChunk = new long[mappings];
            long[] perChunk = new long[mappings];
            for (int i = 0; i < mappings; i++) {
                int p = mapping.data + 8 + i * 12;
                firstChunk[i] = u32(b, p, mapping.end);
                perChunk[i] = u32(b, p + 4, mapping.end);
                if (firstChunk[i] < 1 || (i == 0 && firstChunk[i] != 1) || (i > 0 && firstChunk[i] <= firstChunk[i - 1])
                        || firstChunk[i] > chunks || perChunk[i] == 0) throw new IOException("视频块索引异常");
            }

            Box auxiliarySizes = child(b, stbl, "saiz", true);
            int p = auxiliarySizes.data + 4 + (((int) u32(b, auxiliarySizes.data, auxiliarySizes.end) & 1) != 0 ? 8 : 0);
            require(p, 5, auxiliarySizes.end);
            int fixedAuxiliary = b[p] & 255;
            if (count(b, p + 1, auxiliarySizes.end) != count) throw new IOException("辅助索引数量不一致");
            int[] auxSizes = new int[count];
            if (fixedAuxiliary == 0) require(p + 5, count, auxiliarySizes.end);
            for (int i = 0; i < count; i++) auxSizes[i] = fixedAuxiliary == 0 ? b[p + 5 + i] & 255 : fixedAuxiliary;

            Box auxiliaryOffsets = child(b, stbl, "saio", true);
            p = auxiliaryOffsets.data + 4 + (((int) u32(b, auxiliaryOffsets.data, auxiliaryOffsets.end) & 1) != 0 ? 8 : 0);
            if (count(b, p, auxiliaryOffsets.end) != 1) throw new IOException("不支持的辅助索引布局");
            int version = b[auxiliaryOffsets.data] & 255;
            if (version > 1) throw new IOException("不支持的辅助索引版本");
            long auxiliary = version == 1 ? u64(b, p + 4, auxiliaryOffsets.end) : u32(b, p + 4, auxiliaryOffsets.end);
            long auxEnd = auxiliary;
            for (int size : auxSizes) auxEnd += size;
            if (auxiliary < 0 || auxEnd > b.length) throw new IOException("辅助数据超出索引范围");

            int sample = 0;
            int map = 0;
            for (int chunk = 0; chunk < chunks; chunk++) {
                while (map + 1 < mappings && firstChunk[map + 1] <= chunk + 1L) map++;
                if (perChunk[map] > count - sample) throw new IOException("样本与块数量不一致");
                p = offsets.data + 8 + chunk * width;
                long position = wide ? u64(b, p, offsets.end) : u32(b, p, offsets.end);
                for (int i = 0; i < perChunk[map]; i++, sample++) {
                    long sampleSize = sampleSizes[sample];
                    if (position < 0 || sampleSize <= 0 || position > total - sampleSize) throw new IOException("视频样本超出资源大小");
                    int auxSize = auxSizes[sample];
                    if (auxSize < 8) throw new IOException("不支持的加密样本格式");
                    p = (int) auxiliary;
                    require(p, auxSize, b.length);
                    byte[] iv = new byte[16];
                    System.arraycopy(b, p, iv, 0, 8);
                    if (auxSize == 8) {
                        addSpan(spans, position, position + sampleSize, iv, 0);
                    } else {
                        int subsamples = u16(b, p + 8, p + auxSize);
                        if (auxSize != 10 + subsamples * 6) throw new IOException("加密子样本索引异常");
                        long cursor = position;
                        long cipherOffset = 0;
                        for (int sub = 0; sub < subsamples; sub++) {
                            int entry = p + 10 + sub * 6;
                            cursor += u16(b, entry, p + auxSize);
                            long cipherBytes = u32(b, entry + 2, p + auxSize);
                            if (cursor > position + sampleSize - cipherBytes) throw new IOException("加密子样本越界");
                            if (cipherBytes > 0) addSpan(spans, cursor, cursor + cipherBytes, iv, cipherOffset);
                            cursor += cipherBytes;
                            cipherOffset += cipherBytes;
                        }
                        if (cursor != position + sampleSize) throw new IOException("加密子样本长度异常");
                    }
                    auxiliary += auxSize;
                    position += sampleSize;
                }
            }
            if (sample != count) throw new IOException("视频样本不完整");
            restoreSampleEntries(b, output, child(b, stbl, "stsd", true));
        }

        private static void addSpan(List<Span> spans, long start, long end, byte[] iv, long cipherOffset) throws IOException {
            if (spans.size() >= 1000000) throw new IOException("视频加密索引过大");
            spans.add(new Span(start, end, iv, cipherOffset));
        }

        private static void restoreSampleEntries(byte[] b, byte[] output, Box descriptions) throws IOException {
            int entries = count(b, descriptions.data + 4, descriptions.end);
            int position = descriptions.data + 8;
            for (int entry = 0; entry < entries; entry++) {
                Box sample = box(b, position, descriptions.end);
                if ("encv".equals(sample.type) || "enca".equals(sample.type)) {
                    Box protection = null;
                    for (int p = sample.data; p <= sample.end - 8; p++) {
                        if (type(b, p + 4, "sinf")) {
                            try { protection = box(b, p, sample.end); } catch (IOException ignored) { continue; }
                            break;
                        }
                    }
                    if (protection == null) throw new IOException("原始编码格式缺失");
                    Box format = child(b, protection, "frma", true);
                    require(format.data, 4, format.end);
                    System.arraycopy(b, format.data, output, sample.start + 4, 4);
                    byte[] free = "free".getBytes(StandardCharsets.US_ASCII);
                    System.arraycopy(free, 0, output, protection.start + 4, 4);
                    Arrays.fill(output, protection.data, protection.end, (byte) 0);
                }
                position = sample.end;
            }
        }

        void apply(byte[] bytes, long start, byte[] key) throws IOException {
            long end = start + bytes.length;
            if (start < header.length) {
                int count = (int) Math.min(bytes.length, header.length - start);
                System.arraycopy(header, (int) start, bytes, 0, count);
            }
            if (key == null) return;
            int low = 0;
            int high = spans.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (spans.get(mid).end <= start) low = mid + 1; else high = mid;
            }
            for (int i = low; i < spans.size(); i++) {
                Span span = spans.get(i);
                if (span.start >= end) break;
                long first = Math.max(start, span.start);
                long last = Math.min(end, span.end);
                if (first < last) crypt(bytes, (int) (first - start), (int) (last - first), key, span.iv,
                        span.cipherOffset + first - span.start);
            }
        }
    }

    static void crypt(byte[] bytes, int offset, int count, byte[] key, byte[] iv, long cipherOffset) throws IOException {
        byte[] counter = iv.clone();
        long blocks = cipherOffset / 16;
        for (int i = 15; i >= 0 && blocks != 0; i--) {
            long sum = (counter[i] & 255L) + (blocks & 255L);
            counter[i] = (byte) sum;
            blocks = (blocks >>> 8) + (sum >>> 8);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(counter));
            int skip = (int) (cipherOffset % 16);
            byte[] input = new byte[skip + count];
            System.arraycopy(bytes, offset, input, skip, count);
            byte[] result = cipher.doFinal(input);
            System.arraycopy(result, skip, bytes, offset, count);
        } catch (GeneralSecurityException error) { throw new IOException("视频样本处理失败", error); }
    }

    private static final class Span {
        final long start, end, cipherOffset;
        final byte[] iv;
        Span(long start, long end, byte[] iv, long cipherOffset) {
            this.start = start; this.end = end; this.iv = iv; this.cipherOffset = cipherOffset;
        }
    }

    private static final class Box {
        final int start, data, end;
        final String type;
        Box(int start, int data, int end, String type) { this.start = start; this.data = data; this.end = end; this.type = type; }
    }

    private static Box box(byte[] bytes, int start, int end) throws IOException {
        require(start, 8, end);
        long size = u32(bytes, start, end);
        int header = 8;
        if (size == 1) { size = u64(bytes, start + 8, end); header = 16; }
        if (size == 0) size = end - start;
        if (size < header || size > end - start) throw new IOException("视频索引截断");
        return new Box(start, start + header, start + (int) size,
                new String(bytes, start + 4, 4, StandardCharsets.US_ASCII));
    }

    private static List<Box> children(byte[] bytes, Box parent) throws IOException {
        List<Box> result = new ArrayList<>();
        for (int p = parent.data; p < parent.end;) {
            Box child = box(bytes, p, parent.end);
            result.add(child); p = child.end;
        }
        return result;
    }

    private static Box child(byte[] bytes, Box parent, String name, boolean required) throws IOException {
        for (Box value : children(bytes, parent)) if (name.equals(value.type)) return value;
        if (required) throw new IOException("视频索引缺少 " + name);
        return null;
    }

    private static boolean type(byte[] bytes, int p, String value) {
        return p >= 0 && p <= bytes.length - 4 && bytes[p] == value.charAt(0) && bytes[p + 1] == value.charAt(1)
                && bytes[p + 2] == value.charAt(2) && bytes[p + 3] == value.charAt(3);
    }

    private static int count(byte[] bytes, int p, int end) throws IOException {
        long value = u32(bytes, p, end);
        if (value > 1000000) throw new IOException("视频索引数量过大");
        return (int) value;
    }

    private static void table(int p, int count, int width, int end) throws IOException {
        if (p < 0 || p > end || (long) count * width > end - p) throw new IOException("视频索引表截断");
    }

    private static void require(int p, int length, int end) throws IOException {
        if (p < 0 || length < 0 || p > end - length) throw new IOException("视频索引截断");
    }

    private static int u16(byte[] bytes, int p, int end) throws IOException {
        require(p, 2, end);
        return ((bytes[p] & 255) << 8) | (bytes[p + 1] & 255);
    }

    private static long u32(byte[] bytes, int p, int end) throws IOException {
        require(p, 4, end);
        return ((bytes[p] & 255L) << 24) | ((bytes[p + 1] & 255L) << 16) | ((bytes[p + 2] & 255L) << 8) | (bytes[p + 3] & 255L);
    }

    private static long u64(byte[] bytes, int p, int end) throws IOException {
        long high = u32(bytes, p, end);
        long low = u32(bytes, p + 4, end);
        if ((high & 0x80000000L) != 0) throw new IOException("视频索引偏移溢出");
        return (high << 32) | low;
    }
}
