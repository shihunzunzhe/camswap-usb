package io.github.zensu357.camswap.vmic;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;

/**
 * VirtualMicSender —— Virtual Mic HAL 的 PCM 推送客户端(Java 示例)。
 *
 * <p>用法：RTMP 拉流端解码出 PCM 后调用 {@link #push} 推入;内部后台线程负责连接
 * HAL 的抽象 UDS(@virtual_mic_socket)并稳定发送,断线自动重连(指数退避)。
 *
 * <p>源格式约定：48000Hz / 2ch / S16LE(必须与 HAL 端 VMIC_SRC_* 一致)。
 *
 * <p>背压(Backpressure)：{@link #push} 非阻塞,写入有界队列;发送跟不上生产时,
 * 队列满则丢弃最旧数据(realtime 音频保低延迟),不阻塞解码线程、不无限堆积。
 * 发送用阻塞 write,内核缓冲填满时自然回压,叠加队列丢旧,双重限住延迟。
 *
 * <p>注意：Android 的 {@link LocalSocketAddress.Namespace#ABSTRACT} 会自动为名字
 * 加前导 '\0',与 HAL 端 bind 的抽象名一致,无需手动处理。
 */
public final class VirtualMicSender {

    private static final String TAG = "VmicSender";

    /** 必须与 HAL 端 VMIC_SOCKET_NAME 一致。 */
    public static final String DEFAULT_NAME = "virtual_mic_socket";

    /** 源格式约定(仅供调用方参考,推送数据须为此格式)。 */
    public static final int SRC_SAMPLE_RATE = 48000;
    public static final int SRC_CHANNELS = 2;

    private static final int BACKOFF_START_MS = 100;
    private static final int BACKOFF_MAX_MS = 2000;

    private final String name;
    private final int capacityBytes;

    // 有界 FIFO：分块队列 + 总字节计数;满则丢最旧。
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int queuedBytes = 0;
    private final Object lock = new Object();

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private Thread thread;

    // 统计(lock 保护)。
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
     * 解码线程调用：推入 PCM。非阻塞;缓冲满时丢最旧(背压)。
     *
     * @return 实际接收字节数(通常 = len)。
     */
    public int push(byte[] pcm, int offset, int len) {
        if (pcm == null || len <= 0) return 0;
        byte[] copy = new byte[len];
        System.arraycopy(pcm, offset, copy, 0, len);
        synchronized (lock) {
            // 背压：丢最旧直到能放下(单块可能仍大于容量,允许放入,由后续挤出)。
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
            clearQueue();   // 连上丢陈旧,发送新鲜音频。

            OutputStream os = null;
            try {
                os = sock.getOutputStream();
                while (running) {
                    byte[] chunk = takeChunk();   // 阻塞至有数据/超时/停止
                    if (chunk == null) continue;
                    os.write(chunk);              // 阻塞写 -> 背压自然回传
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
            closeQuietly(s);   // HAL 未起/被拒,交给退避重连
            return null;
        }
    }

    /** 取一批待发数据;无数据时最多等 100ms 后返回 null(便于检查 running/断线)。 */
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
