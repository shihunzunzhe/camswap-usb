package io.github.zensu357.camswap;

import android.media.AudioFormat;
import android.media.AudioTrack;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Hook {@link AudioTrack#write}：当写入来自我们跟踪的直播播放器
 * （Ijk/Exo 的 audioSessionId）时，把 PCM 旁路进 {@link StreamPcmBuffer}，
 * 供麦克风 Hook 在流模式下做「视频同步」音轨替换。
 *
 * <p>Ijk 没有公开 PCM 回调；它最终仍走 {@code AudioTrack.write}，因此这是
 * 在目标进程内无损拿到解码 PCM 的最稳路径。播放器音量可设 0（防外放），
 * {@code write} 仍会收到满幅 PCM。
 */
public final class AudioTrackWriteHook {

    private static final String TAG = "【CS】【at-hook】";
    private static final Map<Integer, Boolean> WATCHED_SESSIONS = new ConcurrentHashMap<>();
    private static volatile boolean installed;
    /** 首次拿到真实 AudioTrack 格式后置位，避免重复 reconcile / 重复打印。 */
    private static volatile boolean formatReconciled;

    /**
     * 采集重入保护：{@code write(byte[],off,len)} 在 framework 内部会再调
     * {@code write(byte[],off,len,mode)}，两个重载都被 Hook 时会重复采集，导致
     * 写入速率翻倍、环形缓冲永久溢出、读侧跳采爆音。用线程内的重入标记保证
     * 每次逻辑 write 只在最外层采集一次。
     */
    private static final ThreadLocal<Boolean> CAPTURING = new ThreadLocal<>();

    private AudioTrackWriteHook() {
    }

    /** @return true 表示当前是最外层调用，应执行采集；false 表示重入（跳过采集）。 */
    private static boolean enterCapture() {
        if (Boolean.TRUE.equals(CAPTURING.get())) {
            return false;
        }
        CAPTURING.set(Boolean.TRUE);
        return true;
    }

    private static void exitCapture() {
        CAPTURING.set(Boolean.FALSE);
    }

    /** 开始监视某个 AudioSession（Ijk/Exo onPrepared 后调用）。 */
    public static void watchSession(int audioSessionId) {
        if (audioSessionId > 0) {
            WATCHED_SESSIONS.put(audioSessionId, Boolean.TRUE);
            LogUtil.log(TAG + "watch session=" + audioSessionId
                    + " total=" + WATCHED_SESSIONS.size());
        }
    }

    public static void unwatchSession(int audioSessionId) {
        if (audioSessionId > 0) {
            WATCHED_SESSIONS.remove(audioSessionId);
        }
    }

    public static void clear() {
        WATCHED_SESSIONS.clear();
        formatReconciled = false;
    }

    public static void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        synchronized (AudioTrackWriteHook.class) {
            if (installed) {
                return;
            }
            try {
                hookWrites(classLoader);
                installed = true;
                LogUtil.log(TAG + "AudioTrack.write hooks installed");
            } catch (Throwable t) {
                LogUtil.log(TAG + "install failed: " + t);
            }
        }
    }

    /** 采集回调签名：(thiz, args, result)。 */
    private interface Capturer {
        void capture(Object thiz, Object[] args, Object result);
    }

    private static void hookWrites(ClassLoader cl) throws Exception {
        Class<?> at = Class.forName("android.media.AudioTrack", false, cl);

        // write(byte[], int, int)
        hook(at, "write", new Class<?>[] { byte[].class, int.class, int.class },
                AudioTrackWriteHook::tryCaptureBytes);
        // write(byte[], int, int, int) API 23+
        hookOptional(at, new Class<?>[] { byte[].class, int.class, int.class, int.class },
                AudioTrackWriteHook::tryCaptureBytes);

        // write(short[], int, int)
        hook(at, "write", new Class<?>[] { short[].class, int.class, int.class },
                AudioTrackWriteHook::tryCaptureShorts);
        // write(short[], int, int, int)
        hookOptional(at, new Class<?>[] { short[].class, int.class, int.class, int.class },
                AudioTrackWriteHook::tryCaptureShorts);

        // write(float[], int, int, int) —— float PCM 输出的播放器走这里
        hookOptional(at, new Class<?>[] { float[].class, int.class, int.class, int.class },
                AudioTrackWriteHook::tryCaptureFloats);

        // write(ByteBuffer, int, int) API 21+
        hookOptional(at, new Class<?>[] { ByteBuffer.class, int.class, int.class },
                AudioTrackWriteHook::tryCaptureBuffer);
    }

    /** 注册一个 write 重载：proceed 后仅在最外层采集一次（重入保护）。 */
    private static void hook(Class<?> at, String name, Class<?>[] params, Capturer capturer)
            throws Exception {
        Method m = at.getDeclaredMethod(name, params);
        Api101Runtime.requireModule().hook(m).intercept(chain -> {
            Object[] args = chain.getArgs().toArray(new Object[0]);
            boolean outer = enterCapture();
            try {
                Object result = chain.proceed(args);
                if (outer) {
                    capturer.capture(chain.getThisObject(), args, result);
                }
                return result;
            } finally {
                if (outer) {
                    exitCapture();
                }
            }
        });
    }

    /** 可选重载：不存在则忽略。 */
    private static void hookOptional(Class<?> at, Class<?>[] params, Capturer capturer) {
        try {
            hook(at, "write", params, capturer);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            LogUtil.log(TAG + "hookOptional 失败: " + e);
        }
    }

    private static boolean isWatched(Object audioTrack) {
        if (!(audioTrack instanceof AudioTrack) || WATCHED_SESSIONS.isEmpty()) {
            return false;
        }
        try {
            int sid = ((AudioTrack) audioTrack).getAudioSessionId();
            return WATCHED_SESSIONS.containsKey(sid);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 确保缓冲已激活，并把声明的采样率/声道<b>对齐真实 AudioTrack</b>。
     * <p>Ijk/Exo 会提前用默认值 start 缓冲，若不 reconcile，读侧重采样会参照错误采样率
     * 导致变调/杂音；这里在首次拿到真实格式时校正一次。
     */
    private static void ensureFormat(AudioTrack t) {
        int rate = safeRate(t);
        int ch = channelCount(t);
        if (!StreamPcmBuffer.isActive()) {
            StreamPcmBuffer.start(rate, ch);
            formatReconciled = true;
            logFormat(t, rate, ch);
        } else if (!formatReconciled) {
            StreamPcmBuffer.reconcileFormat(rate, ch);
            formatReconciled = true;
            logFormat(t, rate, ch);
        }
        // Magisk HAL 模式：在「硬件音量层」把该播放器音轨静音，防外放/回授。
        // setVolume 只影响后续混音输出，不改变本次 write 的入参 → 采集(旁路 PCM)仍为满幅。
        // 每次采集都重置一次，抵消 Exo/Ijk 可能的音量回写。
        if (ConfigManager.isMagiskHalMode()) {
            try {
                t.setVolume(0f);
            } catch (Throwable ignored) {
            }
        }
    }

    private static int safeRate(AudioTrack t) {
        try {
            int r = t.getSampleRate();
            if (r > 0) {
                return r;
            }
        } catch (Throwable ignored) {
        }
        return 44100;
    }

    /** 读取 AudioTrack 的 PCM 编码（16-bit / 8-bit / float）。 */
    private static int encodingOf(AudioTrack t) {
        try {
            return t.getAudioFormat();
        } catch (Throwable ignored) {
            return AudioFormat.ENCODING_PCM_16BIT;
        }
    }

    private static void logFormat(AudioTrack t, int rate, int ch) {
        int enc = encodingOf(t);
        String name = enc == AudioFormat.ENCODING_PCM_FLOAT ? "PCM_FLOAT(需转16bit)"
                : enc == AudioFormat.ENCODING_PCM_8BIT ? "PCM_8BIT(需转16bit)"
                : enc == AudioFormat.ENCODING_PCM_16BIT ? "PCM_16BIT" : ("enc=" + enc);
        LogUtil.log(TAG + "AudioTrack 实际格式 rate=" + rate + " ch=" + ch + " encoding=" + name);
    }

    private static int channelCount(AudioTrack t) {
        try {
            int ch = t.getChannelCount();
            if (ch > 0) {
                return ch;
            }
        } catch (Throwable ignored) {
        }
        try {
            int cfg = t.getChannelConfiguration();
            if (cfg == AudioFormat.CHANNEL_OUT_MONO) {
                return 1;
            }
            if (cfg == AudioFormat.CHANNEL_OUT_STEREO) {
                return 2;
            }
        } catch (Throwable ignored) {
        }
        return 2;
    }

    private static int intOf(Object o) {
        return o instanceof Integer ? (Integer) o : 0;
    }

    /** 把采集到的原始字节（可能是 float/8-bit）归一化成 16-bit 后写入缓冲。 */
    private static void writeBytesAsPcm16(AudioTrack t, byte[] data, int offset, int lenBytes) {
        int enc = encodingOf(t);
        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
            byte[] pcm16 = PcmConvert.floatLeToPcm16(data, offset, lenBytes);
            StreamPcmBuffer.write(pcm16, 0, pcm16.length);
        } else if (enc == AudioFormat.ENCODING_PCM_8BIT) {
            byte[] pcm16 = PcmConvert.pcm8ToPcm16(data, offset, lenBytes);
            StreamPcmBuffer.write(pcm16, 0, pcm16.length);
        } else {
            StreamPcmBuffer.write(data, offset, lenBytes);
        }
    }

    private static void tryCaptureBytes(Object thiz, Object[] args, Object result) {
        if (!isWatched(thiz) || !(args[0] instanceof byte[])) {
            return;
        }
        int writtenBytes = intOf(result); // write(byte[]) 返回写入字节数
        if (writtenBytes <= 0) {
            return;
        }
        AudioTrack t = (AudioTrack) thiz;
        ensureFormat(t);
        writeBytesAsPcm16(t, (byte[]) args[0], intOf(args[1]), writtenBytes);
    }

    private static void tryCaptureShorts(Object thiz, Object[] args, Object result) {
        if (!isWatched(thiz) || !(args[0] instanceof short[])) {
            return;
        }
        int writtenShorts = intOf(result); // write(short[]) 返回写入的 short 个数
        if (writtenShorts <= 0) {
            return;
        }
        AudioTrack t = (AudioTrack) thiz;
        ensureFormat(t);
        // short[] 恒为 16-bit 样本，直接写
        StreamPcmBuffer.writeShorts((short[]) args[0], intOf(args[1]), writtenShorts);
    }

    private static void tryCaptureFloats(Object thiz, Object[] args, Object result) {
        if (!isWatched(thiz) || !(args[0] instanceof float[])) {
            return;
        }
        int writtenFloats = intOf(result); // write(float[]) 返回写入的 float 个数
        if (writtenFloats <= 0) {
            return;
        }
        AudioTrack t = (AudioTrack) thiz;
        ensureFormat(t);
        byte[] pcm16 = PcmConvert.floatArrayToPcm16((float[]) args[0], intOf(args[1]), writtenFloats);
        StreamPcmBuffer.write(pcm16, 0, pcm16.length);
    }

    private static void tryCaptureBuffer(Object thiz, Object[] args, Object result) {
        if (!isWatched(thiz) || !(args[0] instanceof ByteBuffer)) {
            return;
        }
        int writtenBytes = intOf(result); // write(ByteBuffer) 返回写入字节数
        if (writtenBytes <= 0) {
            return;
        }
        AudioTrack t = (AudioTrack) thiz;
        ensureFormat(t);
        ByteBuffer buf = (ByteBuffer) args[0];
        // AudioTrack.write(ByteBuffer) 会推进 position；hook 在 proceed 之后，
        // 需要回看刚写入的那一段。
        int pos = buf.position();
        int start = Math.max(0, pos - writtenBytes);
        ByteBuffer dup = buf.duplicate();
        dup.position(start);
        dup.limit(start + writtenBytes);
        byte[] tmp = new byte[writtenBytes];
        dup.get(tmp);
        writeBytesAsPcm16(t, tmp, 0, writtenBytes);
    }
}
