package com.lmq8267.ledclient;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.ssl.SSLSocketFactory;

final class WsClient {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;

    interface Listener {
        void onOpen();
        void onText(String text);
        void onClosed(Exception error);
    }

    private final String[] urls;
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean openedInAttempt;
    private volatile boolean open;
    private int preferredUrlIndex = 0;
    private Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;

    WsClient(String url, Listener listener) {
        this.urls = normalizeCandidates(url);
        this.listener = listener;
    }

    void connectLoop() {
        running = true;
        while (running) {
            Exception error = null;
            for (int n = 0; n < urls.length && running; n++) {
                int i = (preferredUrlIndex + n) % urls.length;
                try {
                    openedInAttempt = false;
                    connectOnce(urls[i]);
                    error = null;
                    break;
                } catch (Exception e) {
                    error = e;
                    close();
                    if (openedInAttempt) {
                        preferredUrlIndex = i;
                        break;
                    }
                }
            }
            if (listener != null) listener.onClosed(error);
            sleep(5000);
        }
    }

    void stop() {
        running = false;
        close();
    }

    boolean isOpen() {
        return open;
    }

    void reconnect() {
        close();
    }

    synchronized boolean send(JSONObject object) {
        try {
            sendText(object.toString());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void connectOnce(String url) throws Exception {
        URI uri = new URI(url);
        boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        socket = secure
                ? SSLSocketFactory.getDefault().createSocket()
                : new Socket();
        socket.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String path = uri.getRawPath();
        if (path == null || path.length() == 0) path = "/client";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + uri.getHost() + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n\r\n";
        out.write(request.getBytes("UTF-8"));
        out.flush();
        String response = readHttpHeader();
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            throw new IOException("websocket handshake failed");
        }
        openedInAttempt = true;
        open = true;
        if (listener != null) listener.onOpen();
        readFrames();
    }

    private void readFrames() throws IOException {
        while (running) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new IOException("closed");
            int opcode = b0 & 0x0f;
            int len = b1 & 0x7f;
            if (len == 126) {
                int hi = in.read();
                int lo = in.read();
                if (hi < 0 || lo < 0) throw new IOException("closed");
                len = (hi << 8) | lo;
            }
            else if (len == 127) {
                long longLen = 0;
                for (int i = 0; i < 8; i++) {
                    int b = in.read();
                    if (b < 0) throw new IOException("closed");
                    longLen = (longLen << 8) | b;
                }
                if (longLen > 1024 * 1024) throw new IOException("frame too large");
                len = (int) longLen;
            }
            byte[] mask = null;
            if ((b1 & 0x80) != 0) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] payload = new byte[len];
            readFully(payload);
            if (mask != null) for (int i = 0; i < len; i++) payload[i] ^= mask[i % 4];
            if (opcode == 8) throw new IOException("closed");
            if (opcode == 1 && listener != null) listener.onText(new String(payload, "UTF-8"));
        }
    }

    private synchronized void sendText(String text) throws IOException {
        if (out == null) throw new IOException("not connected");
        byte[] payload = text.getBytes("UTF-8");
        out.write(0x81);
        if (payload.length < 126) out.write(0x80 | payload.length);
        else if (payload.length <= 65535) {
            out.write(0x80 | 126);
            out.write((payload.length >> 8) & 0xff);
            out.write(payload.length & 0xff);
        } else {
            out.write(0x80 | 127);
            long len = payload.length;
            for (int i = 7; i >= 0; i--) out.write((int) ((len >> (8 * i)) & 0xff));
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        out.write(mask);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i % 4]);
        out.flush();
    }

    private String readHttpHeader() throws IOException {
        StringBuilder header = new StringBuilder();
        int matched = 0;
        int[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int b = in.read();
            if (b < 0) throw new IOException("handshake failed");
            header.append((char) b);
            if (header.length() > 8192) throw new IOException("handshake too large");
            matched = b == end[matched] ? matched + 1 : 0;
        }
        return header.toString();
    }

    private void readFully(byte[] bytes) throws IOException {
        int off = 0;
        while (off < bytes.length) {
            int n = in.read(bytes, off, bytes.length - off);
            if (n < 0) throw new IOException("closed");
            off += n;
        }
    }

    private void close() {
        open = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        in = null;
        out = null;
    }

    static String normalize(String raw) {
        String[] candidates = normalizeCandidates(raw);
        if (candidates.length == 1) return candidates[0];
        return candidates[0] + " / " + candidates[1];
    }

    static String[] normalizeHttpAckCandidates(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) return new String[] {""};
        String lower = value.toLowerCase(Locale.US);
        String scheme = "";
        if (lower.startsWith("https://")) {
            scheme = "https";
            value = value.substring(8);
        } else if (lower.startsWith("http://")) {
            scheme = "http";
            value = value.substring(7);
        } else if (lower.startsWith("wss://")) {
            scheme = "https";
            value = value.substring(6);
        } else if (lower.startsWith("ws://")) {
            scheme = "http";
            value = value.substring(5);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        if ("https".equals(scheme)) return new String[] {"https://" + value + "/client-ack"};
        if ("http".equals(scheme)) return new String[] {"http://" + value + "/client-ack"};
        return new String[] {"http://" + value + "/client-ack", "https://" + value + "/client-ack"};
    }

    private static String[] normalizeCandidates(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) return new String[] {""};
        String lower = value.toLowerCase(Locale.US);
        String scheme = "";
        if (lower.startsWith("https://")) {
            scheme = "wss";
            value = value.substring(8);
        } else if (lower.startsWith("http://")) {
            scheme = "ws";
            value = value.substring(7);
        } else if (lower.startsWith("wss://")) {
            scheme = "wss";
            value = value.substring(6);
        } else if (lower.startsWith("ws://")) {
            scheme = "ws";
            value = value.substring(5);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        if ("wss".equals(scheme)) return new String[] {"wss://" + value + "/client"};
        if ("ws".equals(scheme)) return new String[] {"ws://" + value + "/client"};
        return new String[] {"ws://" + value + "/client", "wss://" + value + "/client"};
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
