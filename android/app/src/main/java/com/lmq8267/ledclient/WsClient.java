package com.lmq8267.ledclient;

import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class WsClient {
    private static final String TAG = "LedOverlay";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int HEARTBEAT_INTERVAL_MS = 20000;
    /** 连续 3 个心跳周期都没收到 pong 才判定链路已死，避免一次抖动就重连。 */
    private static final long PONG_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * 3L;
    /** 即使旧服务端不回 pong，也不能无限期读阻塞：80 秒没有任何入站帧就重连。 */
    private static final int IDLE_READ_TIMEOUT_MS = 80000;

    interface Listener {
        void onOpen();
        void onText(String text);
        void onClosed(Exception error);
    }

    private final String[] urls;
    private final Listener listener;
    private volatile boolean running;
    /** stop() 之后线程可能还没真正启动；这个标记负责挡住“刚停就被 connectLoop 重新拉起”。 */
    private volatile boolean stopped;
    private volatile boolean openedInAttempt;
    private volatile boolean open;
    private int preferredUrlIndex = 0;
    private Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private Thread heartbeatThread;
    /** 每次连接/关闭都递增，旧心跳线程看到代号变化就退出，避免它误关新连接的 socket。 */
    private volatile long heartbeatGeneration;
    /** 老版本服务端不回应 ping；只有确认对方会发 pong 后才启用超时重连，避免误伤旧服务端。 */
    private volatile boolean pongSeen;
    private volatile long lastPongAt;

    WsClient(String url, Listener listener) {
        this.urls = normalizeCandidates(url);
        this.listener = listener;
    }

    void connectLoop() {
        synchronized (this) {
            if (stopped) return;
            running = true;
        }
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
        synchronized (this) {
            stopped = true;
            running = false;
        }
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
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        raw.setTcpNoDelay(true);
        raw.setKeepAlive(true);
        if (secure) {
            // 必须设置 SNI 和主机名校验：旧实现用未连接的 SSLSocket + connect()，
            // 既不发 SNI，也不校验证书域名，任何自签/中间人证书都能握手成功。
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault()
                    .createSocket(raw, uri.getHost(), port, true);
            // setEndpointIdentificationAlgorithm 是 API 24 才有的；minSdk 14 必须短路。
            if (Build.VERSION.SDK_INT >= 24) {
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
            }
            socket = ssl;
        } else {
            socket = raw;
        }
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
        socket.setSoTimeout(IDLE_READ_TIMEOUT_MS);
        openedInAttempt = true;
        open = true;
        // 每次新连接都重新认定 pong：断线期间残留的旧时间戳不能用来判断新链路。
        pongSeen = false;
        lastPongAt = SystemClock.elapsedRealtime();
        startHeartbeat();
        if (listener != null) listener.onOpen();
        readFrames();
    }

    private void startHeartbeat() {
        Thread previous = heartbeatThread;
        if (previous != null) previous.interrupt();
        final long generation = ++heartbeatGeneration;
        heartbeatThread = new Thread(new Runnable() {
            @Override public void run() {
                while (running && open && generation == heartbeatGeneration) {
                    sleep(HEARTBEAT_INTERVAL_MS);
                    if (!running || !open || generation != heartbeatGeneration) break;
                    long now = SystemClock.elapsedRealtime();
                    if (pongSeen && now - lastPongAt > PONG_TIMEOUT_MS) {
                        Log.w(TAG, "pong 超时，主动重连");
                        if (generation == heartbeatGeneration) close();
                        break;
                    }
                    try {
                        sendText("{\"type\":\"ping\"}");
                    } catch (Exception e) {
                        // 旧心跳线程可能在新连接建立后才从阻塞写里返回，
                        // 这时不能让它 close 掉新连接的 socket。
                        if (generation == heartbeatGeneration) close();
                        break;
                    }
                }
            }
        }, "led-ws-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void readFrames() throws IOException {
        ByteArrayOutputStream fragmented = null;
        while (running) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new IOException("closed");
            boolean fin = (b0 & 0x80) != 0;
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
                if (longLen < 0 || longLen > 1024 * 1024) throw new IOException("frame too large");
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

            // 控制帧可以夹在分片中间；标准 ping 必须回 pong，否则反代可能主动断开。
            if (opcode == 0x9) {
                if (len <= 125) sendControl(0xA, payload);
                continue;
            }
            if (opcode == 0xA) {
                pongSeen = true;
                lastPongAt = SystemClock.elapsedRealtime();
                continue;
            }
            if (opcode == 0x8) throw new IOException("closed");

            if (opcode == 0x0) {
                if (fragmented == null) continue;
                if (fragmented.size() + len > 1024 * 1024) throw new IOException("message too large");
                fragmented.write(payload, 0, len);
                if (fin) {
                    byte[] complete = fragmented.toByteArray();
                    fragmented = null;
                    handleText(complete);
                }
            } else if (opcode == 0x1) {
                fragmented = null;
                if (!fin) {
                    fragmented = new ByteArrayOutputStream();
                    fragmented.write(payload, 0, len);
                } else {
                    handleText(payload);
                }
            }
            // 二进制帧不在本协议内，忽略；继续读下一帧。
        }
    }

    private void handleText(byte[] payload) {
        String text = new String(payload, java.nio.charset.Charset.forName("UTF-8"));
        // pong 是链路层信号，不交给业务层，避免把心跳写进消息日志。
        // 必须精确匹配，不能只看是否包含 "pong"：任务正文刚好是 pong 时会被误吞。
        if ("{\"type\":\"pong\"}".equals(text.trim())) {
            pongSeen = true;
            lastPongAt = SystemClock.elapsedRealtime();
            return;
        }
        if (listener != null) listener.onText(text);
    }

    private synchronized void sendControl(int opcode, byte[] payload) throws IOException {
        if (out == null) throw new IOException("not connected");
        if (payload.length > 125) payload = new byte[0];
        out.write(0x80 | opcode);
        out.write(0x80 | payload.length);
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        out.write(mask);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i % 4]);
        out.flush();
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
        heartbeatGeneration++;
        Thread thread = heartbeatThread;
        heartbeatThread = null;
        if (thread != null) thread.interrupt();
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
