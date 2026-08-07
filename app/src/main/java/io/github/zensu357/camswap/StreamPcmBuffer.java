package io.github.zensu357.camswap;

import java.util.Arrays;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * 直播流 PCM 环形缓冲：供 RTMP/网络流的音轨写入，麦克风 Hook 读取。
 *
 * <p>典型路径：
 * <ul>
 *   <li>Ijk/Exo 解码出的 PCM 经 {@link AudioTrackWriteHook} 或 TeeAudio 写入；</li>
 *   <li>{@link MicrophoneHandler} / {@link NativeAudioHook} 在
 *       {@code video_sync} 且流模式时从此缓冲取数注入 {@code AudioRecord}。</li>
 * </ul>
 *
 * <p>线程安全；读侧在数据不足时填 0（静音），避免直播卡顿时出现噪声。
 */
public final class StreamPcmBuffer {

    private static final String TAG = "【CS】【pcm】";
    /** 约 3 秒 @ 48kHz mono 16-bit；立体声时减半时长，足够覆盖网络抖动 */
    private static final int CAPACITY_BYTES = 48_000 * 2 * 3;

    private static final Object LOCK = new Object();
    private static final byte[] BUF = new byte[CAPACITY_BYTES];
    private static int writePos;
    private static int available;
    private static int sampleRate = 44100;
    private static int channels = 1;
    private static volatile boolean active;
    private static long totalWritten;
    private static long totalRead;
    private static long lastLogMs;

    private StreamPcmBuffer() {
    }

    /** 开流时调用：清空并标记活跃。 */
    public static void start(int srcSampleRate, int srcChannels) {
        synchronized (LOCK) {
            writePos = 0;
            available = 0;
            totalWritten = 0;
            totalRead = 0;
            if (srcSampleRate > 0) {
                sampleRate = srcSampleRate;
            }
            if (srcChannels > 0) {
                channels = srcChannels;
            }
            active = true;
            Arrays.fill(BUF, (byte) 0);
            LogUtil.log(TAG + "start rate=" + sampleRate + " ch=" + channels);
        }
    }

    /** 停流/释放时调用。 */
    public static void stop() {
        synchronized (LOCK) {
            active = false;
            available = 0;
            writePos = 0;
            LogUtil.log(TAG + "stop written=" + totalWritten + " read=" + totalRead);
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static int getSampleRate() {
        synchronized (LOCK) {
            return sampleRate;
        }
    }

    public static int getChannels() {
        synchronized (LOCK) {
            return channels;
        }
    }

    /** 写入 PCM 16-bit little-endian 交错数据。 */
    public static void write(byte[] data, int offset, int length) {
        if (data == null || length <= 0 || !active) {
            return;
        }
        if (offset < 0 || offset + length > data.length) {
            return;
        }
        // 只接受偶数长度（16-bit）
        int len = length & ~1;
        if (len <= 0) {
            return;
        }
        synchronized (LOCK) {
            int remaining = len;
            int src = offset;
            while (remaining > 0) {
                int chunk = Math.min(remaining, CAPACITY_BYTES - writePos);
                System.arraycopy(data, src, BUF, writePos, chunk);
                writePos = (writePos + chunk) % CAPACITY_BYTES;
                src += chunk;
                remaining -= chunk;
                available = Math.min(CAPACITY_BYTES, available + chunk);
            }
            totalWritten += len;
            maybeLog();
        }
    }

    public static void write(java.nio.ByteBuffer buffer, int length) {
        if (buffer == null || length <= 0 || !active) {
            return;
        }
        int len = Math.min(length, buffer.remaining()) & ~1;
        if (len <= 0) {
            return;
        }
        byte[] tmp = new byte[len];
        int pos = buffer.position();
        buffer.get(tmp, 0, len);
        buffer.position(pos); // 不消费调用方的 buffer
        write(tmp, 0, len);
    }

    public static void writeShorts(short[] data, int offset, int count) {
        if (data == null || count <= 0 || !active) {
            return;
        }
        int n = Math.min(count, data.length - offset);
        if (n <= 0) {
            return;
        }
        byte[] tmp = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short s = data[offset + i];
            tmp[i * 2] = (byte) (s & 0xFF);
            tmp[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        write(tmp, 0, tmp.length);
    }

    /**
     * 读取 PCM 到 byte[]（AudioRecord.read 风格）。
     * 数据不足时剩余部分填 0。返回请求的 size（与真实 mic 行为接近）。
     */
    public static int read(byte[] out, int offset, int size,
            int targetRate, int targetChannels) {
        if (out == null || size <= 0) {
            return 0;
        }
        int want = Math.min(size, out.length - offset) & ~1;
        if (want <= 0) {
            return 0;
        }
        synchronized (LOCK) {
            if (!active) {
                Arrays.fill(out, offset, offset + want, (byte) 0);
                return want;
            }
            // 采样率/声道简单处理：若不一致做最近邻（够直播用，避免复杂 resampler）
            if (targetRate <= 0) {
                targetRate = sampleRate;
            }
            if (targetChannels <= 0) {
                targetChannels = channels;
            }
            if (targetRate == sampleRate && targetChannels == channels) {
                return readExact(out, offset, want);
            }
            return readResampled(out, offset, want, targetRate, targetChannels);
        }
    }

    public static int readShorts(short[] out, int offset, int count,
            int targetRate, int targetChannels) {
        if (out == null || count <= 0) {
            return 0;
        }
        int n = Math.min(count, out.length - offset);
        byte[] tmp = new byte[n * 2];
        int got = read(tmp, 0, tmp.length, targetRate, targetChannels);
        int samples = got / 2;
        for (int i = 0; i < samples; i++) {
            int lo = tmp[i * 2] & 0xFF;
            int hi = tmp[i * 2 + 1] << 8;
            out[offset + i] = (short) (hi | lo);
        }
        return samples;
    }

    private static int readExact(byte[] out, int offset, int want) {
        int readPos = (writePos - available + CAPACITY_BYTES) % CAPACITY_BYTES;
        int toCopy = Math.min(want, available);
        int dst = offset;
        int left = toCopy;
        while (left > 0) {
            int chunk = Math.min(left, CAPACITY_BYTES - readPos);
            System.arraycopy(BUF, readPos, out, dst, chunk);
            readPos = (readPos + chunk) % CAPACITY_BYTES;
            dst += chunk;
            left -= chunk;
        }
        available -= toCopy;
        totalRead += toCopy;
        if (toCopy < want) {
            Arrays.fill(out, offset + toCopy, offset + want, (byte) 0);
        }
        return want;
    }

    private static int readResampled(byte[] out, int offset, int want,
            int targetRate, int targetChannels) {
        // 以目标格式需要的帧数反推源字节
        int bytesPerTargetFrame = 2 * targetChannels;
        int targetFrames = want / bytesPerTargetFrame;
        if (targetFrames <= 0) {
            Arrays.fill(out, offset, offset + want, (byte) 0);
            return want;
        }
        double ratio = (double) sampleRate / (double) targetRate;
        int srcFramesNeeded = Math.max(1, (int) Math.ceil(targetFrames * ratio) + 1);
        int srcBytesNeeded = srcFramesNeeded * 2 * channels;
        byte[] src = new byte[srcBytesNeeded];
        int got = readExact(src, 0, Math.min(srcBytesNeeded, available + (available & 1)));
        // readExact 会填 0；这里重新按 available 语义：上面已消费
        // 用 got 里的有效数据做最近邻
        int srcFrames = Math.max(1, got / (2 * channels));
        for (int tf = 0; tf < targetFrames; tf++) {
            int sf = Math.min(srcFrames - 1, (int) (tf * ratio));
            int srcBase = sf * channels;
            for (int c = 0; c < targetChannels; c++) {
                int sc = Math.min(c, channels - 1);
                int srcIdx = (srcBase + sc) * 2;
                int dstIdx = offset + (tf * targetChannels + c) * 2;
                if (srcIdx + 1 < src.length && dstIdx + 1 < offset + want) {
                    out[dstIdx] = src[srcIdx];
                    out[dstIdx + 1] = src[srcIdx + 1];
                }
            }
        }
        return want;
    }

    private static void maybeLog() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLogMs > 5000L) {
            lastLogMs = now;
            LogUtil.log(TAG + "buf available=" + available + "/" + CAPACITY_BYTES
                    + " written=" + totalWritten + " read=" + totalRead
                    + " rate=" + sampleRate + " ch=" + channels);
        }
    }
}
