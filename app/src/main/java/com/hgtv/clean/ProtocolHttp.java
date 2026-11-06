package com.hgtv.clean;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Bounded HTTPS transport with cancellation scoped to this client. */
public final class ProtocolHttp implements Closeable {
    private final Set<HttpURLConnection> active = Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());
    private volatile boolean closed;

    /** Sends one request without automatic redirects or shared cookie storage. */
    public Response request(String method, String address, Map<String, String> headers, byte[] body, int maxBytes) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IOException("接口连接必须使用 HTTPS");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        active.add(connection);
        try {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey().indexOf('\r') >= 0 || header.getKey().indexOf('\n') >= 0
                        || header.getValue().indexOf('\r') >= 0 || header.getValue().indexOf('\n') >= 0)
                    throw new IOException("请求头无效");
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>(connection.getHeaderFields());
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = new byte[0];
            if (stream != null) {
                String encoding = connection.getContentEncoding();
                if ("gzip".equalsIgnoreCase(encoding)) stream = new GZIPInputStream(stream);
                else if ("deflate".equalsIgnoreCase(encoding)) stream = new InflaterInputStream(stream);
                try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
                        if (output.size() > maxBytes - count) throw new IOException("接口响应超过大小限制");
                        output.write(buffer, 0, count);
                    }
                    bytes = output.toByteArray();
                }
            }
            return new Response(status, responseHeaders, bytes);
        } finally {
            active.remove(connection);
            connection.disconnect();
        }
    }

    /** Cancels pending calls; subsequent requests may still be made. */
    public void cancel() { for (HttpURLConnection connection : active) connection.disconnect(); }

    /** Cancels pending calls and prevents new requests. */
    @Override public void close() { closed = true; cancel(); }

    /** Encodes a query/form value using UTF-8 form encoding. */
    public static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    /** Preserves parameter order because the signature covers the exact encoded bytes. */
    public static String query(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(encode(value.getKey())).append('=').append(encode(value.getValue()));
        }
        return result.toString();
    }

    /** Formats cookies without global cookie-manager side effects. */
    public static String cookie(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(value.getKey()).append('=').append(value.getValue());
        }
        return result.toString();
    }

    /** The complete bounded response; credential-bearing fields must never be logged. */
    public static final class Response {
        public final int status;
        public final Map<String, List<String>> headers;
        public final byte[] body;
        Response(int status, Map<String, List<String>> headers, byte[] body) {
            this.status = status; this.headers = Collections.unmodifiableMap(headers); this.body = body;
        }
        /** Decodes the platform's UTF-8 response. */
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        /** Requires a successful status and parses a JSON object. */
        public JSONObject json() throws IOException {
            if (status < 200 || status >= 300) throw new IOException("接口 HTTP " + status);
            if (body.length == 0) throw new IOException("接口返回空响应体");
            try { return new JSONObject(text()); }
            catch (org.json.JSONException error) { throw new IOException("接口响应格式无效", error); }
        }
        /** Returns a header value using a case-insensitive lookup. */
        public String header(String name) {
            for (Map.Entry<String, List<String>> header : headers.entrySet())
                if (name.equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) return header.getValue().get(0);
            return "";
        }
        /** Merges Set-Cookie name/value pairs into the caller's isolated jar. */
        public void cookies(Map<String, String> jar) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (!"set-cookie".equalsIgnoreCase(header.getKey())) continue;
                for (String cookie : header.getValue()) {
                    String pair = cookie.split(";", 2)[0]; int equals = pair.indexOf('=');
                    if (equals > 0) jar.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
                }
            }
        }
    }
}
