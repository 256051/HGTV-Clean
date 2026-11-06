package com.hgtv.clean;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.EOFException;
import java.io.IOException;

/** Exposes a prepared MP4 source to Media3 without exposing its address or content key. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackDataSource extends BaseDataSource {
    private final PreparedMedia source;
    private Uri uri;
    private boolean opened;
    private long position;
    private long remaining;
    private byte[] buffer = new byte[0];
    private int offset;

    /** Creates an independent reader over a prepared source shared by one playback generation. */
    public PlaybackDataSource(PreparedMedia source) {
        super(true);
        if (source == null) throw new IllegalArgumentException("Missing playback source");
        this.source = source;
    }

    /** Opens the requested byte range and rejects positions outside the resource. */
    @Override public long open(DataSpec spec) throws IOException {
        close();
        transferInitializing(spec);
        long length = source.length();
        if (spec.position < 0 || spec.position > length) throw new IOException("读取位置超出视频长度");
        position = spec.position;
        remaining = length - position;
        if (spec.length != C.LENGTH_UNSET) remaining = Math.min(spec.length, remaining);
        uri = spec.uri;
        opened = true;
        transferStarted(spec);
        return remaining;
    }

    /** Reads bounded, seek-correct bytes and reports unexpected end-of-stream explicitly. */
    @Override public int read(byte[] target, int targetOffset, int length) throws IOException {
        if (targetOffset < 0 || length < 0 || targetOffset > target.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        if (!opened) throw new IOException("视频读取尚未打开");
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        if (offset >= buffer.length) {
            buffer = source.read(position, (int) Math.min(1024 * 1024L, remaining));
            offset = 0;
            if (buffer.length == 0) throw new EOFException("视频分段意外结束");
        }
        int count = (int) Math.min(length, Math.min(buffer.length - offset, remaining));
        System.arraycopy(buffer, offset, target, targetOffset, count);
        offset += count;
        position += count;
        remaining -= count;
        bytesTransferred(count);
        return count;
    }

    /** Returns the internal media identifier supplied by PlayerScreen. */
    @Override public Uri getUri() { return uri; }

    /** Releases this reader's buffer while keeping the prepared source available for seeking. */
    @Override public void close() {
        buffer = new byte[0]; offset = 0; remaining = 0; uri = null;
        if (opened) { opened = false; transferEnded(); }
    }
}
