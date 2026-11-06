package com.hgtv.clean;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small UI models; platform credentials, media URLs and content keys stay inside the adapter. */
public final class Models {
    private Models() { }

    /** A drama card returned by a content API. */
    public static final class Drama {
        public final String id;
        public final String title;
        public final String cover;
        public final String subtitle;
        public final int episodeCount;

        /** Creates a card without any account-specific state. */
        public Drama(String id, String title, String cover, String subtitle, int episodeCount) {
            this.id = id;
            this.title = title;
            this.cover = cover;
            this.subtitle = subtitle;
            this.episodeCount = episodeCount;
        }
    }

    /** A playable episode; index is zero-based. */
    public static final class Episode {
        public final String id;
        public final String title;
        public final int index;

        /** Creates an episode with its stable platform identifier. */
        public Episode(String id, String title, int index) {
            this.id = id;
            this.title = title;
            this.index = index;
        }
    }

    /** A prepared media source, owned by one playback generation. */
    public static final class Playback implements Closeable {
        public final PreparedMedia source;
        public final String quality;
        private final AtomicBoolean closed = new AtomicBoolean();

        /** Wraps a newly prepared source; sources must never be reused after close. */
        public Playback(PreparedMedia source, String quality) {
            this.source = source;
            this.quality = quality;
        }

        /** Cancels the source's active reads when its playback generation is released. */
        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            source.close();
        }
    }
}
