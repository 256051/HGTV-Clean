package com.hgtv.clean;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Host-only HTTP checks exercise range validation with a loopback synthetic media server. */
public final class MediaNetworkCheck {
    /** Runs without remote requests, credentials, or real video files. */
    public static void main(String[] args) throws Exception {
        byte[][] fixture = MediaProtocolCheck.fixture(false, false);
        byte[] resource = Arrays.copyOf(fixture[0], fixture[0].length + 8 + fixture[2].length);
        ByteBuffer.wrap(resource).putInt(fixture[0].length, fixture[2].length + 8)
                .putInt(fixture[0].length + 4, 0x6d646174);
        System.arraycopy(fixture[2], 0, resource, fixture[0].length + 8, fixture[2].length);
        byte[] key = new byte[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            String[] limits = range.substring(6).split("-");
            int start = Integer.parseInt(limits[0]);
            int end = Math.min(Integer.parseInt(limits[1]), resource.length - 1);
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + resource.length);
            exchange.sendResponseHeaders(206, end - start + 1);
            exchange.getResponseBody().write(resource, start, end - start + 1);
            exchange.close();
        });
        server.createContext("/wrong", exchange -> { exchange.sendResponseHeaders(200, 1); exchange.getResponseBody().write(0); exchange.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            try (PreparedMedia media = new PreparedMedia(base + "/video", key, "synthetic-test")) {
                media.prepare();
                if (media.length() != resource.length) throw new AssertionError("range-backed source length");
                if (!Arrays.equals(media.read(fixture[0].length + 8 + 39, 21), Arrays.copyOfRange(fixture[1], 39, 60)))
                    throw new AssertionError("range-backed seek");
                media.close();
                try { media.read(0, 1); throw new AssertionError("closed source"); } catch (IOException expected) { }
            }
            try (PreparedMedia media = new PreparedMedia(base + "/wrong", key, "test")) {
                try { media.prepare(); throw new AssertionError("non-range HTTP"); } catch (IOException expected) { }
            }
        } finally { server.stop(0); }
        System.out.println("Media HTTP checks passed: length, seek, cancellation, non-range response");
    }
}
