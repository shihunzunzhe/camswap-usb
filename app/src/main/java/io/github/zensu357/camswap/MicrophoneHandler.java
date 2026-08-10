package io.github.zensu357.camswap;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.zensu357.camswap.api101.Api101Runtime;

import io.github.zensu357.camswap.utils.AudioDataProvider;
import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * 麦克风 Hook 处理器 —— 支持三种模式
 * <ul>
 * <li><b>静音模式 (mute)</b>：方案 A，将音频数据替换为全零</li>
 * <li><b>替换模式 (replace)</b>：方案 B，注入本地音频文件的 PCM 数据</li>
 * <li><b>视频同步 (video_sync)</b>：方案 C，从当前视频提取音轨，与视频帧同步播放</li>
 * </ul>
 * <p>
 * 所有 Hook 在执行替换前都会实时检查 {@link ConfigManager#KEY_ENABLE_MIC_HOOK} 配置值，
 * 当配置为 {@code false} 时不做任何操作，保证运行时可热切换。
 */
public class MicrophoneHandler implements ICameraHandler {

    private static final String TAG = "【CS】[Mic]";

    // 方案 B 时长校验：是否已提醒过用户
    private static final AtomicBoolean durationWarningShown = new AtomicBoolean(false);

    // 诊断日志计数器：audioPath 为 null 时限制日志数量
    private static final AtomicInteger audioPathNullLogCount = new AtomicInteger(0);

    // 视频同步模式：记住上次已知的播放位置，避免播放器暂时不可用时回退到 0
    private static final AtomicLong lastKnownPlaybackPositionMs = new AtomicLong(0);

    // 异步加载标记：使用 AtomicBoolean 防止竞态条件导致重复提交加载任务
    private static final AtomicBoolean asyncLoadingInProgress = new AtomicBoolean(false);

    // 只打印一次流注入格式（目标 AudioRecord 与流缓冲的 rate/ch/编码），便于核实是否格式对齐
    private static final AtomicBoolean streamFormatLogged = new AtomicBoolean(false);

    // 输出节拍器：把注入音频限速到 1 倍实时，避免目标 App 拉取过快造成「快放」
    private static final StreamAudioPacer STREAM_PACER = new StreamAudioPacer();

    /**
     * 存储每个 AudioRecord 实例的构造参数
     * 使用 ConcurrentHashMap 防止 GC 过早回收导致参数丢失，
     * 在 AudioRecord.release() 时手动清理
     */
    private static final Map<Object, AudioRecordParams> recordParamsMap = new ConcurrentHashMap<>();

    /**
     * AudioRecord 构造参数
     */
    private static class AudioRecordParams {
        final int audioSource;
        final int sampleRate;
        final int channelConfig;
        final int audioFormat;
        final int bufferSize;
        final int channelCount;

        AudioRecordParams(int audioSource, int sampleRate, int channelConfig,
                int audioFormat, int bufferSize) {
            this.audioSource = audioSource;
            this.sampleRate = sampleRate;
            this.channelConfig = channelConfig;
            this.audioFormat = audioFormat;
            this.bufferSize = bufferSize;
            this.channelCount = getChannelCount(channelConfig);
        }

        private static int getChannelCount(int channelConfig) {
            switch (channelConfig) {
                case AudioFormat.CHANNEL_IN_MONO:
                    return 1;
                case AudioFormat.CHANNEL_IN_STEREO:
                    return 2;
                default:
                    return Integer.bitCount(channelConfig);
            }
        }
    }

    // ================================================================
    // 配置读取
    // ================================================================

    private static boolean isMicHookEnabled() {
        try {
            return VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getMicHookMode() {
        try {
            return VideoManager.getConfig().getString(
                    ConfigManager.KEY_MIC_HOOK_MODE, ConfigManager.MIC_MODE_MUTE);
        } catch (Exception e) {
            return ConfigManager.MIC_MODE_MUTE;
        }
    }

    // ================================================================
    // 模式判断 + 数据加载
    // ================================================================

    /**
     * 检查是否为替换模式，并确保音频数据已加载。
     * 如果数据尚未就绪，触发异步加载并返回 false（本次 read 用静音填充，下次再替换）。
     */
    private static boolean isReplaceMode() {
        if (!ConfigManager.MIC_MODE_REPLACE.equals(getMicHookMode())) {
            return false;
        }
        String audioPath = AudioDataProvider.getAudioFilePath();
        if (audioPath == null) {
            // 仅在前 3 次打印此日志，避免日志洪泛
            int count = audioPathNullLogCount.getAndIncrement();
            if (count < 3) {
                LogUtil.log(TAG + " ⚠ isReplaceMode: audioPath 为 null，音频文件未找到！"
                        + " video_path=" + VideoManager.video_path);
            }
            return false;
        }

        // 检查是否需要（重新）加载：未就绪 或 文件已切换
        String loadedPath = AudioDataProvider.getCurrentFilePath();
        if (!AudioDataProvider.isReady() || !audioPath.equals(loadedPath)) {
            // 异步加载，不阻塞音频线程
            final String pathToLoad = audioPath;
            preloadAudioFileAsync(pathToLoad);
            return false; // 数据还没准备好，本次用静音
        }
        return true;
    }

    /**
     * 检查是否为视频同步模式，并确保视频音轨数据已加载。
     * 如果数据尚未就绪，触发异步加载并返回 false。
     * <p>
     * 流模式：改为从 {@link StreamPcmBuffer} 实时取 RTMP/网络流 PCM
     * （由 {@link AudioTrackWriteHook} 旁路写入），不再降级静音。
     */
    private static boolean isVideoSyncMode() {
        if (!ConfigManager.MIC_MODE_VIDEO_SYNC.equals(getMicHookMode())) {
            return false;
        }
        if (VideoManager.isStreamMode()) {
            // 流音轨：只要缓冲已激活就可用；未激活时返回 false → 本次填静音，等 Ijk onPrepared
            return StreamPcmBuffer.isActive();
        }
        String videoPath = VideoManager.getCurrentVideoPath();
        if (videoPath == null)
            return false;

        String loadedPath = AudioDataProvider.getCurrentFilePath();
        if (!AudioDataProvider.isReady() || !videoPath.equals(loadedPath)) {
            // 异步加载，不阻塞音频线程
            final String pathToLoad = videoPath;
            preloadAudioFileAsync(pathToLoad);
            return false;
        }
        return true;
    }

    /**
     * 是否应从 {@link StreamPcmBuffer} 取推流音频。
     * <p>覆盖两种意图：{@code stream}（仅推流音频）与流模式下的 {@code video_sync}。
     * 缓冲未就绪时返回 false，由调用方退化为静音——保证真实麦克风永不泄露。
     * 决策集中在 {@link MicAudioRouter}，与 {@link NativeAudioHook} 保持一致。
     */
    private static boolean shouldUseStreamPcm() {
        return MicAudioRouter.usesStreamPcm(isMicHookEnabled(), getMicHookMode(),
                VideoManager.isStreamMode(), StreamPcmBuffer.isActive());
    }

    /**
     * 从流缓冲取推流音频，<b>按目标 AudioRecord 的实际编码</b>（16-bit / float / 8-bit）
     * 写入 {@code dst[offset, offset+n)}。
     * <p>关键：缓冲内部统一 16-bit，若目标录音是 float/8-bit 而我们直接写 16-bit，
     * 会被目标 App 按错误位深解释成纯杂音——这里按 {@code p.audioFormat} 做位深转换。
     */
    private static void fillStreamInto(byte[] dst, int offset, int n, AudioRecordParams p) {
        logStreamFormatOnce(p);
        int enc = p.audioFormat;
        int bytesPerSample = enc == AudioFormat.ENCODING_PCM_FLOAT ? 4
                : enc == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
        int samples = n / bytesPerSample;
        if (samples <= 0) {
            Arrays.fill(dst, offset, offset + n, (byte) 0);
            return;
        }
        // 先按目标采样率/声道取 16-bit，再转成目标位深
        byte[] pcm16 = new byte[samples * 2];
        StreamPcmBuffer.read(pcm16, 0, pcm16.length, p.sampleRate, p.channelCount);
        byte[] outBytes;
        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
            outBytes = PcmConvert.pcm16ToFloatLe(pcm16, 0, pcm16.length);
        } else if (enc == AudioFormat.ENCODING_PCM_8BIT) {
            outBytes = PcmConvert.pcm16ToPcm8u(pcm16, 0, pcm16.length);
        } else {
            outBytes = pcm16;
        }
        int copy = Math.min(n, outBytes.length);
        System.arraycopy(outBytes, 0, dst, offset, copy);
        if (copy < n) {
            Arrays.fill(dst, offset + copy, offset + n, (byte) 0);
        }
        // 关键：把注入输出限速到严格 1 倍实时。目标 App 若拉取快于实时，这里 sleep 补齐，
        // 避免把预攒音频瞬间抽干造成「每 2 秒快放一次」。
        paceToRealtime(n, p);
    }

    /** 按 wall-clock 将本次注入限速到 1 倍实时（拉取过快则 sleep）。 */
    private static void paceToRealtime(int bytes, AudioRecordParams p) {
        try {
            int enc = p.audioFormat;
            int bytesPerSample = enc == AudioFormat.ENCODING_PCM_FLOAT ? 4
                    : enc == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
            int bytesPerSec = p.sampleRate * p.channelCount * bytesPerSample;
            long sleepMs = STREAM_PACER.onServed(bytes, bytesPerSec,
                    android.os.SystemClock.elapsedRealtime());
            if (sleepMs > 0) {
                if (sleepMs > 300) {
                    sleepMs = 300; // 单次最多 sleep 300ms，防御异常
                }
                Thread.sleep(sleepMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    private static void logStreamFormatOnce(AudioRecordParams p) {
        if (streamFormatLogged.compareAndSet(false, true)) {
            STREAM_PACER.reset(); // 新一轮注入，重锚节拍器墙钟
            LogUtil.log(TAG + " 流注入格式对齐: 目标AudioRecord rate=" + p.sampleRate
                    + " ch=" + p.channelCount + " encoding=" + encName(p.audioFormat)
                    + " ；流缓冲 rate=" + StreamPcmBuffer.getSampleRate()
                    + " ch=" + StreamPcmBuffer.getChannels());
        }
    }

    private static String encName(int enc) {
        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
            return "PCM_FLOAT";
        }
        if (enc == AudioFormat.ENCODING_PCM_8BIT) {
            return "PCM_8BIT";
        }
        if (enc == AudioFormat.ENCODING_PCM_16BIT) {
            return "PCM_16BIT";
        }
        return "enc(" + enc + ")";
    }

    /**
     * 异步预加载音频数据（根据当前模式决定加载什么文件）
     */
    private static void preloadAudioAsync() {
        if (!isMicHookEnabled())
            return;
        String mode = getMicHookMode();
        String pathToLoad = null;
        if (ConfigManager.MIC_MODE_REPLACE.equals(mode)) {
            pathToLoad = AudioDataProvider.getAudioFilePath();
        } else if (ConfigManager.MIC_MODE_VIDEO_SYNC.equals(mode)) {
            pathToLoad = VideoManager.getCurrentVideoPath();
        }
        if (pathToLoad != null) {
            preloadAudioFileAsync(pathToLoad);
        }
    }

    /**
     * 在后台线程中加载指定音频文件，避免阻塞音频回调线程。
     * 使用 asyncLoadingInProgress 标记防止重复提交。
     */
    private static void preloadAudioFileAsync(final String filePath) {
        if (filePath == null)
            return;
        // 已经加载了同一文件，无需重复
        if (filePath.equals(AudioDataProvider.getCurrentFilePath()) && AudioDataProvider.isReady()) {
            return;
        }
        if (!asyncLoadingInProgress.compareAndSet(false, true))
            return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LogUtil.log(TAG + " 异步加载音频: " + filePath);
                    AudioDataProvider.loadAudioFile(filePath);
                    durationWarningShown.set(false);
                    checkDurationMismatch();
                    LogUtil.log(TAG + " 异步加载完成: " + filePath
                            + " ready=" + AudioDataProvider.isReady());
                } catch (Exception e) {
                    LogUtil.log(TAG + " 异步加载失败: " + e);
                } finally {
                    asyncLoadingInProgress.set(false);
                }
            }
        }, "CS-AudioPreload").start();
    }

    /**
     * 获取当前视频 MediaPlayer 的播放位置（毫秒）
     * 按优先级尝试所有可能的 MediaPlayer 实例。
     * 当所有播放器都不在播放时，返回上次已知位置而非 0，防止同步失效。
     */
    private static long getVideoPlaybackPositionMs() {
        MediaPlayer[] players = {
                HookMain.playerManager.c2_player, HookMain.playerManager.c2_player_1,
                HookMain.playerManager.mMediaPlayer, HookMain.playerManager.mplayer1
        };
        for (MediaPlayer mp : players) {
            try {
                if (mp != null && mp.isPlaying()) {
                    long pos = mp.getCurrentPosition();
                    lastKnownPlaybackPositionMs.set(pos);
                    return pos;
                }
            } catch (Exception ignored) {
            }
        }
        // 返回上次已知位置，避免在播放器暂时不可用时音频跳回开头
        return lastKnownPlaybackPositionMs.get();
    }

    /**
     * 获取指定 AudioRecord 实例的参数
     */
    private static AudioRecordParams getParams(Object audioRecord) {
        AudioRecordParams params = recordParamsMap.get(audioRecord);
        if (params != null)
            return params;
        // 回退：尝试从 AudioRecord 实例动态获取参数
        try {
            AudioRecord typedRecord = (AudioRecord) audioRecord;
            int sampleRate = typedRecord.getSampleRate();
            int channelConfig = typedRecord.getChannelConfiguration();
            int audioFormat = typedRecord.getAudioFormat();
            params = new AudioRecordParams(0, sampleRate, channelConfig, audioFormat, 4096);
            recordParamsMap.put(audioRecord, params);
            LogUtil.log(TAG + " 动态获取 AudioRecord 参数: sampleRate=" + sampleRate
                    + " channelConfig=" + channelConfig + " audioFormat=" + audioFormat);
            return params;
        } catch (Exception e) {
            LogUtil.log(TAG + " 动态获取参数失败，使用默认值: " + e);
        }
        return new AudioRecordParams(0, 44100,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096);
    }

    /**
     * 方案 B 时长校验：对比音频文件与视频文件时长
     */
    private static void checkDurationMismatch() {
        if (durationWarningShown.get())
            return;

        try {
            long audioDuration = AudioDataProvider.getDurationMs();
            if (audioDuration <= 0)
                return;

            // 尝试获取视频时长
            long videoDuration = -1;
            MediaPlayer[] players = {
                    HookMain.playerManager.c2_player, HookMain.playerManager.c2_player_1,
                    HookMain.playerManager.mMediaPlayer, HookMain.playerManager.mplayer1
            };
            for (MediaPlayer mp : players) {
                try {
                    if (mp != null) {
                        videoDuration = mp.getDuration();
                        if (videoDuration > 0)
                            break;
                    }
                } catch (Exception ignored) {
                }
            }

            if (videoDuration <= 0)
                return;

            // 超过 2 秒差异时警告
            long diff = Math.abs(audioDuration - videoDuration);
            if (diff > 2000) {
                String msg = "【CS】⚠ 音频文件时长(" + (audioDuration / 1000) + "s)与视频时长("
                        + (videoDuration / 1000) + "s)不一致，可能导致音画不同步";
                LogUtil.log(msg);
                VideoManager.showToast("音频与视频时长不一致\n音频: " + (audioDuration / 1000)
                        + "s  视频: " + (videoDuration / 1000) + "s");
                durationWarningShown.set(true);
            }
        } catch (Exception e) {
            LogUtil.log(TAG + " 时长校验异常: " + e);
        }
    }

    // ================================================================
    // 供 NativeAudioHook 调用的 package-visible 静态方法
    // ================================================================

    static boolean isMicHookEnabledStatic() {
        return isMicHookEnabled();
    }

    static String getMicHookModeStatic() {
        return getMicHookMode();
    }

    static long getVideoPlaybackPositionMsStatic() {
        return getVideoPlaybackPositionMs();
    }

    static void preloadAudioAsyncStatic() {
        preloadAudioAsync();
    }

    private static void replaceByteArrayResult(Object audioRecord, byte[] buffer, int offset, int result,
            String methodTag) {
        if (!isMicHookEnabled() || result <= 0 || buffer == null) {
            return;
        }
        AudioRecordParams p = getParams(audioRecord);

        logReadCall(result, methodTag);

        try {
            if (shouldUseStreamPcm()) {
                fillStreamInto(buffer, offset, result, p);
            } else if (isVideoSyncMode()) {
                long posMs = getVideoPlaybackPositionMs();
                AudioDataProvider.fillBytesAtPosition(buffer, offset, result,
                        p.sampleRate, p.channelCount, posMs);
            } else if (isReplaceMode()) {
                AudioDataProvider.fillBytes(buffer, offset, result,
                        p.sampleRate, p.channelCount);
            } else {
                Arrays.fill(buffer, offset, offset + result, (byte) 0);
            }
        } catch (Throwable t) {
            LogUtil.log(TAG + " 替换 byte[] 异常，回退静音: " + t);
            fillSilenceBytes(buffer, offset, result); // 出错也绝不透传真实麦克风
        }
    }

    private static void replaceShortArrayResult(Object audioRecord, short[] buffer, int offset, int result,
            String methodTag) {
        if (!isMicHookEnabled() || result <= 0 || buffer == null) {
            return;
        }
        AudioRecordParams p = getParams(audioRecord);

        logReadCall(result, methodTag);

        try {
            if (shouldUseStreamPcm()) {
                StreamPcmBuffer.readShorts(buffer, offset, result, p.sampleRate, p.channelCount);
            } else if (isVideoSyncMode()) {
                long posMs = getVideoPlaybackPositionMs();
                AudioDataProvider.fillShortsAtPosition(buffer, offset, result,
                        p.sampleRate, p.channelCount, posMs);
            } else if (isReplaceMode()) {
                AudioDataProvider.fillShorts(buffer, offset, result,
                        p.sampleRate, p.channelCount);
            } else {
                Arrays.fill(buffer, offset, offset + result, (short) 0);
            }
        } catch (Throwable t) {
            LogUtil.log(TAG + " 替换 short[] 异常，回退静音: " + t);
            fillSilenceShorts(buffer, offset, result);
        }
    }

    // ================================================================
    // Hook 初始化
    // ================================================================

    @Override
    public void init(final Api101PackageContext packageContext) {
        final ClassLoader classLoader = packageContext.classLoader;
        LogUtil.log(TAG + " 初始化麦克风 Hook");

        hookAudioRecordConstructor(classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookAudioRecordBuilderBuild(classLoader);
        }
        hookAudioRecordRelease(classLoader);
        hookAudioRecordStartRecording(classLoader);

        hookReadByteArray(classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookReadByteArrayReadMode(classLoader);
        }
        hookReadShortArray(classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookReadShortArrayReadMode(classLoader);
        }
        hookReadByteBuffer(classLoader);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookReadFloatArray(classLoader);
            hookReadByteBufferReadMode(classLoader);
        }

        hookMediaRecorderSetAudioSource(classLoader);

        LogUtil.log(TAG + " 麦克风 Hook 初始化完成");
    }

    private void hookAudioRecordConstructor(ClassLoader classLoader) {
        hookConstructor(classLoader, "android.media.AudioRecord",
                new Class<?>[] { int.class, int.class, int.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    try {
                        int audioSource = (int) args[0];
                        int sampleRate = (int) args[1];
                        int channelConfig = (int) args[2];
                        int audioFormat = (int) args[3];
                        int bufferSize = (int) args[4];

                        LogUtil.log(TAG + " AudioRecord 创建: audioSource=" + audioSource
                                + " sampleRate=" + sampleRate
                                + " channelConfig=" + channelConfig
                                + " audioFormat=" + audioFormat
                                + " bufferSize=" + bufferSize);

                        recordParamsMap.put(chain.getThisObject(),
                                new AudioRecordParams(audioSource, sampleRate, channelConfig, audioFormat, bufferSize));
                        preloadAudioAsync();
                    } catch (Throwable t) {
                        LogUtil.log(TAG + " AudioRecord 构造函数 after 异常: " + t);
                    }
                    return result;
                }, "AudioRecord 构造函数");
    }

    private void hookAudioRecordBuilderBuild(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord$Builder", "build", new Class<?>[0], chain -> {
            Object[] args = toArgs(chain.getArgs());
            Object result = chain.proceed(args);
            if (!(result instanceof AudioRecord)) {
                return result;
            }

            try {
                AudioRecord typedRecord = (AudioRecord) result;
                int sampleRate = typedRecord.getSampleRate();
                int channelConfig = typedRecord.getChannelConfiguration();
                int audioFormat = typedRecord.getAudioFormat();
                int bufferSize = typedRecord.getBufferSizeInFrames();

                LogUtil.log(TAG + " AudioRecord.Builder.build(): sampleRate=" + sampleRate
                        + " channelConfig=" + channelConfig
                        + " audioFormat=" + audioFormat
                        + " bufferSize=" + bufferSize);

                recordParamsMap.put(result,
                        new AudioRecordParams(0, sampleRate, channelConfig, audioFormat, bufferSize));
                preloadAudioAsync();
            } catch (Throwable t) {
                LogUtil.log(TAG + " 获取 Builder 创建的 AudioRecord 参数失败: " + t);
            }
            return result;
        }, "AudioRecord.Builder.build()");
    }

    private void hookAudioRecordRelease(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "release", new Class<?>[0], chain -> {
            Object[] args = toArgs(chain.getArgs());
            Object result = chain.proceed(args);
            recordParamsMap.remove(chain.getThisObject());
            return result;
        }, "AudioRecord.release()");
    }

    private void hookAudioRecordStartRecording(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "startRecording", new Class<?>[0], chain -> {
            Object[] args = toArgs(chain.getArgs());
            try {
                LogUtil.log(TAG + " AudioRecord.startRecording() 被调用, micHook="
                        + isMicHookEnabled() + " mode=" + getMicHookMode());
                preloadAudioAsync();
            } catch (Throwable t) {
                LogUtil.log(TAG + " startRecording before 异常: " + t);
            }
            return chain.proceed(args);
        }, "AudioRecord.startRecording()");
    }

    private void hookReadByteArray(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { byte[].class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceByteArrayResult(chain.getThisObject(), (byte[]) args[0], (int) args[1], intResult(result),
                            "byte[]");
                    return result;
                }, "AudioRecord.read(byte[], int, int)");
    }

    private void hookReadByteArrayReadMode(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { byte[].class, int.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceByteArrayResult(chain.getThisObject(), (byte[]) args[0], (int) args[1], intResult(result),
                            "byte[](readMode)");
                    return result;
                }, "AudioRecord.read(byte[], int, int, int)");
    }

    private void hookReadShortArray(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { short[].class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceShortArrayResult(chain.getThisObject(), (short[]) args[0], (int) args[1], intResult(result),
                            "short[]");
                    return result;
                }, "AudioRecord.read(short[], int, int)");
    }

    private void hookReadShortArrayReadMode(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { short[].class, int.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceShortArrayResult(chain.getThisObject(), (short[]) args[0], (int) args[1], intResult(result),
                            "short[](readMode)");
                    return result;
                }, "AudioRecord.read(short[], int, int, int)");
    }

    private void hookReadByteBuffer(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { ByteBuffer.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceByteBufferResult(chain.getThisObject(),
                            args[0] instanceof ByteBuffer ? (ByteBuffer) args[0] : null,
                            intResult(result), "ByteBuffer");
                    return result;
                }, "AudioRecord.read(ByteBuffer, int)");
    }

    private void hookReadFloatArray(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { float[].class, int.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceFloatArrayResult(chain.getThisObject(), (float[]) args[0], (int) args[1], intResult(result),
                            "float[]");
                    return result;
                }, "AudioRecord.read(float[], int, int, int)");
    }

    private void hookReadByteBufferReadMode(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.AudioRecord", "read",
                new Class<?>[] { ByteBuffer.class, int.class, int.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    Object result = chain.proceed(args);
                    replaceByteBufferResult(chain.getThisObject(),
                            args[0] instanceof ByteBuffer ? (ByteBuffer) args[0] : null,
                            intResult(result), "ByteBuffer(int,int)");
                    return result;
                }, "AudioRecord.read(ByteBuffer, int, int)");
    }

    private void hookMediaRecorderSetAudioSource(ClassLoader classLoader) {
        hookMethod(classLoader, "android.media.MediaRecorder", "setAudioSource", new Class<?>[] { int.class },
                chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    try {
                        LogUtil.log(TAG + " MediaRecorder.setAudioSource: " + args[0]
                                + " (micHook=" + isMicHookEnabled()
                                + " mode=" + getMicHookMode() + ")");
                    } catch (Throwable t) {
                        LogUtil.log(TAG + " MediaRecorder.setAudioSource before 异常: " + t);
                    }
                    return chain.proceed(args);
                }, "MediaRecorder.setAudioSource(int)");
    }

    /**
     * 替换 {@code AudioRecord.read(ByteBuffer, ...)} 读到的数据（腾讯 LiteAV 走此路径）。
     *
     * <p>崩溃安全 + 泄露安全：
     * <ul>
     *   <li>录音数据落在 direct ByteBuffer 的<b>绝对 [0, result)</b>（framework 用基址写、忽略
     *       position）。用 {@link MicByteBufferWriter} 的<b>绝对 put</b> 从 0 覆盖，全程不碰
     *       position，彻底消除旧代码 {@code position(pos - result)} 的 {@code Bad position} 崩溃；</li>
     *   <li>组装替换音频的任何异常都<b>回退静音</b>，绝不把真实麦克风交还目标 App；</li>
     *   <li>音源选择与 byte[]/short[] 一致：{@code stream}/流模式 video_sync 注入 RTMP 音频，
     *       缓冲未就绪或静音模式填 0。</li>
     * </ul>
     */
    private static void replaceByteBufferResult(Object audioRecord, ByteBuffer buffer,
            int result, String methodTag) {
        if (!isMicHookEnabled() || result <= 0 || buffer == null) {
            return;
        }
        AudioRecordParams p = getParams(audioRecord);
        logReadCall(result, methodTag);

        int n = Math.min(result, buffer.capacity());
        if (n <= 0) {
            return;
        }
        byte[] tmp = new byte[n]; // 默认全 0 → 静音兜底
        try {
            if (shouldUseStreamPcm()) {
                fillStreamInto(tmp, 0, n, p);
            } else if (isVideoSyncMode()) {
                long posMs = getVideoPlaybackPositionMs();
                AudioDataProvider.fillBytesAtPosition(tmp, 0, n, p.sampleRate, p.channelCount, posMs);
            } else if (isReplaceMode()) {
                AudioDataProvider.fillBytes(tmp, 0, n, p.sampleRate, p.channelCount);
            }
            // else: mute / stream 缓冲未就绪 → tmp 保持全 0
        } catch (Throwable t) {
            LogUtil.log(TAG + " 组装 ByteBuffer 替换音频异常，回退静音: " + t);
            Arrays.fill(tmp, (byte) 0); // 出错也绝不透传真实麦克风
        }
        MicByteBufferWriter.overwriteFromStart(buffer, tmp, n);
    }

    private static void replaceFloatArrayResult(Object audioRecord, float[] buffer, int offset, int result,
            String methodTag) {
        if (!isMicHookEnabled() || result <= 0 || buffer == null) {
            return;
        }
        AudioRecordParams p = getParams(audioRecord);

        logReadCall(result, methodTag);

        try {
            if (shouldUseStreamPcm()) {
                fillFloatsFromStream(buffer, offset, result, p.sampleRate, p.channelCount);
            } else if (isVideoSyncMode()) {
                long posMs = getVideoPlaybackPositionMs();
                AudioDataProvider.fillFloatsAtPosition(buffer, offset, result,
                        p.sampleRate, p.channelCount, posMs);
            } else if (isReplaceMode()) {
                AudioDataProvider.fillFloats(buffer, offset, result,
                        p.sampleRate, p.channelCount);
            } else {
                Arrays.fill(buffer, offset, offset + result, 0.0f);
            }
        } catch (Throwable t) {
            LogUtil.log(TAG + " 替换 float[] 异常，回退静音: " + t);
            fillSilenceFloats(buffer, offset, result);
        }
    }

    /** 从流 PCM（16-bit）读取并转成 float[-1,1] 覆盖到目标数组。 */
    private static void fillFloatsFromStream(float[] buffer, int offset, int result,
            int sampleRate, int channels) {
        int n = Math.min(result, buffer.length - offset);
        if (n <= 0) {
            return;
        }
        byte[] tmp = new byte[n * 2];
        StreamPcmBuffer.read(tmp, 0, tmp.length, sampleRate, channels);
        for (int i = 0; i < n; i++) {
            int lo = tmp[i * 2] & 0xFF;
            int hi = tmp[i * 2 + 1] << 8;
            buffer[offset + i] = (short) (hi | lo) / 32768.0f;
        }
    }

    // ---- 静音兜底（越界安全，异常也吞掉，保证绝不透传真实麦克风） ----

    private static void fillSilenceBytes(byte[] buffer, int offset, int result) {
        try {
            int end = Math.min(offset + result, buffer.length);
            if (offset >= 0 && offset < end) {
                Arrays.fill(buffer, offset, end, (byte) 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void fillSilenceShorts(short[] buffer, int offset, int result) {
        try {
            int end = Math.min(offset + result, buffer.length);
            if (offset >= 0 && offset < end) {
                Arrays.fill(buffer, offset, end, (short) 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void fillSilenceFloats(float[] buffer, int offset, int result) {
        try {
            int end = Math.min(offset + result, buffer.length);
            if (offset >= 0 && offset < end) {
                Arrays.fill(buffer, offset, end, 0.0f);
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookMethod(ClassLoader classLoader, String className, String methodName, Class<?>[] parameterTypes,
            XposedInterface.Hooker hooker, String label) {
        try {
            Method method = resolveMethod(classLoader, className, methodName, parameterTypes);
            Api101Runtime.requireModule().hook(method).intercept(hooker);
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook " + label + " 失败: " + t);
        }
    }

    private void hookConstructor(ClassLoader classLoader, String className, Class<?>[] parameterTypes,
            XposedInterface.Hooker hooker, String label) {
        try {
            Constructor<?> constructor = resolveConstructor(classLoader, className, parameterTypes);
            Api101Runtime.requireModule().hook(constructor).intercept(hooker);
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook " + label + " 失败: " + t);
        }
    }

    private static Method resolveMethod(ClassLoader classLoader, String className, String methodName,
            Class<?>... parameterTypes) throws Exception {
        Class<?> clazz = Class.forName(className, false, classLoader);
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(className + "#" + methodName);
    }

    private static Constructor<?> resolveConstructor(ClassLoader classLoader, String className,
            Class<?>... parameterTypes) throws Exception {
        Class<?> clazz = Class.forName(className, false, classLoader);
        Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    private static Object[] toArgs(List<Object> args) {
        return args.toArray(new Object[0]);
    }

    private static int intResult(Object result) {
        return result instanceof Integer ? (Integer) result : 0;
    }

    private static volatile int readHookCount = 0;

    private static void logReadCall(int result, String method) {
        if (readHookCount < 10) {
            readHookCount++;
            LogUtil.log(TAG + " AudioRecord.read(" + method + ") 被调用 #" + readHookCount
                    + " result=" + result + " micHookEnabled=" + isMicHookEnabled()
                    + " mode=" + getMicHookMode());
            if (readHookCount == 10) {
                LogUtil.log(TAG + " AudioRecord.read 调用日志已达到 10 次上限，后续调用不再打印。");
            }
        }
    }
}
