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

    private AudioTrackWriteHook() {
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

    private static void hookWrites(ClassLoader cl) throws Exception {
        Class<?> at = Class.forName("android.media.AudioTrack", false, cl);

        // write(byte[], int, int)
        hook(at, "write", new Class<?>[] { byte[].class, int.class, int.class }, chain -> {
            Object[] args = chain.getArgs().toArray(new Object[0]);
            Object result = chain.proceed(args);
            tryCaptureBytes(chain.getThisObject(), args, result);
            return result;
        });

        // write(byte[], int, int, int) API 23+
        try {
            hook(at, "write", new Class<?>[] { byte[].class, int.class, int.class, int.class }, chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                tryCaptureBytes(chain.getThisObject(), args, result);
                return result;
            });
        } catch (NoSuchMethodException ignored) {
        }

        // write(short[], int, int)
        hook(at, "write", new Class<?>[] { short[].class, int.class, int.class }, chain -> {
            Object[] args = chain.getArgs().toArray(new Object[0]);
            Object result = chain.proceed(args);
            tryCaptureShorts(chain.getThisObject(), args, result);
            return result;
        });

        // write(short[], int, int, int)
        try {
            hook(at, "write", new Class<?>[] { short[].class, int.class, int.class, int.class }, chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                tryCaptureShorts(chain.getThisObject(), args, result);
                return result;
            });
        } catch (NoSuchMethodException ignored) {
        }

        // write(ByteBuffer, int, int) API 21+
        try {
            hook(at, "write", new Class<?>[] { ByteBuffer.class, int.class, int.class }, chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                tryCaptureBuffer(chain.getThisObject(), args, result);
                return result;
            });
        } catch (NoSuchMethodException ignored) {
        }
    }

    private static void hook(Class<?> clazz, String name, Class<?>[] params,
            io.github.libxposed.api.XposedInterface.Hooker hooker) throws Exception {
        Method m = clazz.getDeclaredMethod(name, params);
        Api101Runtime.requireModule().hook(m).intercept(hooker);
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

    private static void ensureFormat(Object audioTrack) {
        if (!(audioTrack instanceof AudioTrack)) {
            return;
        }
        if (StreamPcmBuffer.isActive()) {
            return;
        }
        try {
            AudioTrack t = (AudioTrack) audioTrack;
            int rate = t.getSampleRate();
            int ch = channelCount(t);
            StreamPcmBuffer.start(rate > 0 ? rate : 44100, ch > 0 ? ch : 1);
        } catch (Throwable t) {
            StreamPcmBuffer.start(44100, 1);
        }
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

    private static void tryCaptureBytes(Object thiz, Object[] args, Object result) {
        if (!isWatched(thiz) || !(args[0] instanceof byte[])) {
            return;
        }
        int written = result instanceof Integer ? (Integer) result : 0;
        if (written <= 0) {
            return;
        }
        ensureFormat(thiz);
        int offset = args[1] instanceof Integer ? (Integer) args[1] : 0;
        StreamPcmBuffer.write((byte[]) args[0], offset, written);
    }

    private static void tryCaptureShorts(Object thiz, Object[] args, Object result) {
        if (!isWatched(thiz) || !(args[0] instanceof short[])) {
            return;
        }
        int written = result instanceof Integer ? (Integer) result : 0;
        if (written <= 0) {
            return;
        }
        ensureFormat(thiz);
        int offset = args[1] instanceof Integer ? (Integer) args[1] : 0;
        StreamPcmBuffer.writeShorts((short[]) args[0], offset, written);
    }

    private static void tryCaptureBuffer(Object thiz, Object[] args, Object result) {
        if (!isWatched(thiz) || !(args[0] instanceof ByteBuffer)) {
            return;
        }
        int written = result instanceof Integer ? (Integer) result : 0;
        if (written <= 0) {
            return;
        }
        ensureFormat(thiz);
        ByteBuffer buf = (ByteBuffer) args[0];
        // AudioTrack.write(ByteBuffer) 会推进 position；hook 在 proceed 之后，
        // 需要回看刚写入的那一段。
        int pos = buf.position();
        int start = Math.max(0, pos - written);
        ByteBuffer dup = buf.duplicate();
        dup.position(start);
        dup.limit(start + written);
        byte[] tmp = new byte[written];
        dup.get(tmp);
        StreamPcmBuffer.write(tmp, 0, written);
    }
}
