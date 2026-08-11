package io.github.zensu357.camswap.vmic;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;

/**
 * VirtualMicSender —— Magisk Virtual Audio HAL 的 PCM 推送客户端。
 *
 * <p>把解码后的 <b>48000Hz / 2ch / S16LE</b> PCM 通过抽象 UDS
 * {@code @virtual_mic_socket} 推给底层 HAL;HAL 侧收流→重采样→替换目标 App 麦克风。
 *
 * <p>后台线程负责连接/发送/断线重连(指数退避);{@link #sendPcm} 非阻塞,
 * 缓冲满时丢最旧(背压),不阻塞解码线程、不无限堆积。
 */
public final class VirtualMicSender {

    private static final String TAG = "VmicSender";

    /** 必须与 HAL 端 VMIC_SOCKET_NAME 一致。Android 抽象命名空间会自动加前导 '\0'。 */
    public static final String DEFAULT_NAME = "virtual_mic_socket";

    /** 源格式约定(调用方须按此推送)。 */
    public static final int SRC_SAMPLE_RATE = 48000;
    public static final int SRC_CHANNELS = 2;

    private static final int BACKOFF_START_MS = 100;
    private static final int BACKOFF_MAX_MS = 2000;

    private final String name;
    private final int capacityBytes;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int queuedBytes = 0;
    private final Object lock = new Object();

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private Thread thread;

    private long pushed = 0;
    private long sent = 0;
    private long dropped = 0;

    public VirtualMicSender() {
        this(DEFAULT_NAME, 512 * 1024);
    }

    public VirtualMicSender(String socketName, int capacityBytes) {
        this.name = socketName;
        this.capacityBytes = Math.max(capacityBytes, 8192);
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::threadLoop, "VmicSender");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    /**
     * 便捷入口:推入从 0 开始、长度 length 的 PCM(48000/2ch/S16LE)。非阻塞。
     */
    public int sendPcm(byte[] pcmData, int length) {
        return push(pcmData, 0, length);
    }

    /**
     * 解码线程调用:推入 PCM。非阻塞;缓冲满时丢最旧(背压)。返回实际接收字节数。
     */
    public int push(byte[] pcm, int offset, int len) {
        if (pcm == null || len <= 0) return 0;
        byte[] copy = new byte[len];
        System.arraycopy(pcm, offset, copy, 0, len);
        synchronized (lock) {
            while (queuedBytes + len > capacityBytes && !queue.isEmpty()) {
                byte[] old = queue.pollFirst();
                queuedBytes -= old.length;
                dropped += old.length;
            }
            queue.addLast(copy);
            queuedBytes += len;
            pushed += len;
            lock.notifyAll();
        }
        return len;
    }

    public boolean isConnected() {
        return connected;
    }

    public long bytesPushed() {
        synchronized (lock) { return pushed; }
    }

    public long bytesSent() {
        synchronized (lock) { return sent; }
    }

    public long bytesDropped() {
        synchronized (lock) { return dropped; }
    }

    // ------------------------------------------------------------------------

    private void threadLoop() {
        Log.i(TAG, "sender thread started, target @" + name);
        int backoff = BACKOFF_START_MS;

        while (running) {
            LocalSocket sock = connectOnce();
            if (sock == null) {
                connected = false;
                sleepInterruptible(backoff);
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
                continue;
            }
            Log.i(TAG, "connected @" + name);
            connected = true;
            backoff = BACKOFF_START_MS;
            clearQueue();

            OutputStream os = null;
            try {
                os = sock.getOutputStream();
                while (running) {
                    byte[] chunk = takeChunk();
                    if (chunk == null) continue;
                    os.write(chunk);
                    synchronized (lock) { sent += chunk.length; }
                }
            } catch (IOException e) {
                Log.w(TAG, "send failed, reconnect: " + e.getMessage());
            } finally {
                closeQuietly(os);
                closeQuietly(sock);
                connected = false;
            }
        }
        Log.i(TAG, "sender thread exiting");
    }

    private LocalSocket connectOnce() {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            return s;
        } catch (IOException e) {
            closeQuietly(s);
            return null;
        }
    }

    private byte[] takeChunk() {
        synchronized (lock) {
            if (queue.isEmpty()) {
                try {
                    lock.wait(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (queue.isEmpty()) return null;
            byte[] c = queue.pollFirst();
            queuedBytes -= c.length;
            return c;
        }
    }

    private void clearQueue() {
        synchronized (lock) {
            queue.clear();
            queuedBytes = 0;
        }
    }

    private void sleepInterruptible(int ms) {
        synchronized (lock) {
            if (!running) return;
            try {
                lock.wait(ms);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
