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
    /** 物理容量：4 秒 @ 48kHz 立体声 16-bit（=8 秒 @ 单声道）。需大于最大延迟以容纳 2 秒预攒。 */
    private static final int CAPACITY_BYTES = 48_000 * 2 * 2 * 4;

    /**
     * 低延迟"只读最新"策略：把积压（未被读走的音频）控制在很低的水平。
     * <ul>
     *   <li>{@link #TARGET_LATENCY_MS}：重同步后保留的音频时长（正常延迟）；</li>
     *   <li>{@link #MAX_LATENCY_MS}：积压硬上限，一旦超过就丢弃过旧数据、重同步到目标。</li>
     * </ul>
     * 用滞回（hard cap → target）避免每次写都丢导致连续卡顿：正常播放连续无跳，
     * 只有当积压堆到上限（网络突发/卡顿后补帧）才丢一次历史、跳到最新。
     * <b>只丢历史、不加速</b>——读侧仍按目标 App 的真实节奏 1:1 消费，音调正常。
     *
     * <p><b>首要目标：绝不卡顿</b>。因此固定约 2 秒延迟——开流先攒够 2 秒再放音，
     * 2 秒缓冲垫足以吸收几乎所有网络/解码/时钟抖动；只有积压超过上限才丢历史回到 2 秒。
     */
    private static final int TARGET_LATENCY_MS = 300;
    private static final int MAX_LATENCY_MS = 1200;

    private static final Object LOCK = new Object();
    private static final byte[] BUF = new byte[CAPACITY_BYTES];
    private static int writePos;
    private static int available;
    private static int sampleRate = 44100;
    private static int channels = 1;
    private static volatile boolean active;
    private static long totalWritten;
    private static long totalRead;
    private static long totalDropped;
    private static long lastLogMs;
    /**
     * 抖动缓冲状态：true=攒数期（缓冲不足，读侧先给静音、不消费，直到攒够
     * {@link #TARGET_LATENCY_MS}）；false=放音期。读空后重新进入攒数，避免缓冲被读到
     * 只剩 1ms → 一抖就欠载 → 断断续续。
     */
    private static boolean priming = true;

    /** hold-fill：保存最近输出的音频尾部，欠载时用它重复填充（消除「无声空档」），比静音连续。 */
    private static final byte[] HOLD_TAIL = new byte[8192];
    private static int holdTailLen;

    /**
     * PCM 旁路监听：每次 {@link #write(byte[], int, int)} 写入(16-bit LE)时回调。
     * 供 {@link VmicAudioBridge} 在 Magisk HAL 模式下把解码 PCM 转推 {@code @virtual_mic_socket}。
     * 挂在这个唯一的汇聚点上即可覆盖 Ijk/Exo 等所有解码后端。
     */
    public interface PcmTap {
        void onPcm16(byte[] data, int offset, int length, int sampleRate, int channels);
    }

    private static volatile PcmTap pcmTap;

    /** 设置/清除 PCM 旁路监听(null 清除)。 */
    public static void setTap(PcmTap tap) {
        pcmTap = tap;
    }

    private StreamPcmBuffer() {
    }

    /** 开流时调用：清空并标记活跃。 */
    public static void start(int srcSampleRate, int srcChannels) {
        synchronized (LOCK) {
            writePos = 0;
            available = 0;
            totalWritten = 0;
            totalRead = 0;
            totalDropped = 0;
            priming = true; // 开流先攒够目标延迟再放音
            holdTailLen = 0;
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

    /** 当前积压字节数（未被读走的音频）。测试/诊断用。 */
    static int availableBytes() {
        synchronized (LOCK) {
            return available;
        }
    }

    /** 测试用：跳过抖动缓冲攒数，直接进入放音状态。 */
    static void beginPlaybackForTest() {
        synchronized (LOCK) {
            priming = false;
        }
    }

    /** 测试用：当前配置下的目标/上限延迟字节数与物理容量。 */
    static int targetLatencyBytesForTest() {
        synchronized (LOCK) {
            return bytesForMs(TARGET_LATENCY_MS);
        }
    }

    static int maxLatencyBytesForTest() {
        synchronized (LOCK) {
            return bytesForMs(MAX_LATENCY_MS);
        }
    }

    static int capacityBytesForTest() {
        return CAPACITY_BYTES;
    }

    /**
     * 校正声明的采样率/声道（不清空数据）。用于缓冲被提前以默认值 start 后，
     * 首次拿到真实 {@code AudioTrack} 格式时对齐——否则读侧重采样参照错误，导致变调/杂音。
     * 数据本身恒为 16-bit，改声明值不影响已存字节的含义。
     */
    public static void reconcileFormat(int srcSampleRate, int srcChannels) {
        synchronized (LOCK) {
            boolean changed = false;
            if (srcSampleRate > 0 && srcSampleRate != sampleRate) {
                sampleRate = srcSampleRate;
                changed = true;
            }
            if (srcChannels > 0 && srcChannels != channels) {
                channels = srcChannels;
                changed = true;
            }
            if (changed) {
                LogUtil.log(TAG + "reconcile rate=" + sampleRate + " ch=" + channels);
            }
        }
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
        int rateSnap;
        int chSnap;
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
            trimBacklog(); // 只保留最新音频：积压超限则丢历史、重同步到目标延迟
            maybeLog();
            rateSnap = sampleRate;
            chSnap = channels;
        }
        // PCM 旁路(锁外调用，避免占用写锁)：Magisk HAL 模式下转推 @virtual_mic_socket。
        PcmTap t = pcmTap;
        if (t != null) {
            try {
                t.onPcm16(data, offset, len, rateSnap, chSnap);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 积压超过 {@link #MAX_LATENCY_MS} 时，丢弃过旧数据、把读取窗口重同步到
     * {@link #TARGET_LATENCY_MS}——即把 {@code readPos} 直接跳到 {@code writePos - target}，
     * 保证下次读到的是最新音频。仅在积压堆高时触发（滞回），平时不动，避免连续跳音。
     * 调用方需持有 {@link #LOCK}。
     */
    private static void trimBacklog() {
        int hardCap = bytesForMs(MAX_LATENCY_MS);
        if (available > hardCap) {
            int target = bytesForMs(TARGET_LATENCY_MS);
            totalDropped += (available - target);
            available = target; // readPos = writePos - available，等效丢弃最旧的 (available-target) 字节
        }
    }

    /** 按当前采样率/声道，把毫秒时长换算成 16-bit PCM 字节数（偶数、不超容量）。 */
    private static int bytesForMs(int ms) {
        long bytesPerSec = (long) sampleRate * channels * 2L; // 16-bit
        long b = bytesPerSec * ms / 1000L;
        if (b < 2) {
            b = 2;
        }
        if (b > CAPACITY_BYTES) {
            b = CAPACITY_BYTES;
        }
        return (int) (b & ~1L);
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
            // 抖动缓冲：攒够目标延迟再放音，避免缓冲被读空导致断续。
            // 攒数期间给静音、不消费缓冲，让写入侧把积压堆到目标水位。
            if (priming) {
                if (available < bytesForMs(TARGET_LATENCY_MS)) {
                    Arrays.fill(out, offset, offset + want, (byte) 0);
                    return want;
                }
                priming = false;
                LogUtil.log(TAG + "prime done, available=" + available
                        + " (~" + latencyMs(available) + "ms)");
            }
            // 采样率/声道简单处理：若不一致做最近邻（够直播用，避免复杂 resampler）
            if (targetRate <= 0) {
                targetRate = sampleRate;
            }
            if (targetChannels <= 0) {
                targetChannels = channels;
            }
            // 欠载由 readExact 的 hold-fill（重复最近音频）保持连续，不再重新攒数——
            // 目标 App 快读会持续欠载，若一欠载就重攒会造成周期性静音；hold-fill 让内容按
            // 生产侧 1 倍速前进、无声空档消失。真正断流由 stop()（active=false）回落静音。
            return (targetRate == sampleRate && targetChannels == channels)
                    ? readExact(out, offset, want)
                    : readResampled(out, offset, want, targetRate, targetChannels);
        }
    }

    private static long latencyMs(int bytes) {
        long bytesPerSec = Math.max(1L, (long) sampleRate * channels * 2L);
        return bytes * 1000L / bytesPerSec;
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
            // 欠载：不填静音，改用「重复最近音频」保持连续，消除快读造成的无声空档
            holdFill(out, offset, toCopy, want);
        }
        updateHoldTail(out, offset, want);
        return want;
    }

    /** 用「本次已拷贝的真实音频」或「历史尾部」循环重复，填满 out[offset+filled, offset+want)。 */
    private static void holdFill(byte[] out, int offset, int filled, int want) {
        int shortfall = want - filled;
        if (shortfall <= 0) {
            return;
        }
        byte[] src;
        int srcOff;
        int srcLen;
        if (filled >= 2) {
            src = out;
            srcOff = offset;
            srcLen = filled & ~1;
        } else if (holdTailLen >= 2) {
            src = HOLD_TAIL;
            srcOff = 0;
            srcLen = holdTailLen & ~1;
        } else {
            Arrays.fill(out, offset + filled, offset + want, (byte) 0); // 无历史可重复 → 静音
            return;
        }
        int dst = offset + filled;
        int end = offset + want;
        int i = 0;
        while (dst < end) {
            out[dst] = src[srcOff + (i % srcLen)];
            dst++;
            i++;
        }
    }

    private static void updateHoldTail(byte[] out, int offset, int want) {
        int len = Math.min(want, HOLD_TAIL.length) & ~1;
        if (len <= 0) {
            return;
        }
        System.arraycopy(out, offset + want - len, HOLD_TAIL, 0, len);
        holdTailLen = len;
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
            long bytesPerSec = Math.max(1L, (long) sampleRate * channels * 2L);
            long latencyMs = available * 1000L / bytesPerSec; // 当前音频延迟（积压时长）
            LogUtil.log(TAG + "buf available=" + available + " (~" + latencyMs + "ms)"
                    + " written=" + totalWritten + " read=" + totalRead
                    + " dropped=" + totalDropped
                    + " rate=" + sampleRate + " ch=" + channels);
        }
    }
}
