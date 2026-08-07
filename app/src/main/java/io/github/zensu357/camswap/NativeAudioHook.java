package io.github.zensu357.camswap;

import io.github.zensu357.camswap.utils.AudioDataProvider;
import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Native 音频 Hook 桥接类。
 * <p>
 * 加载 {@code libcamswap-native-hook.so}，通过 Dobby 内联 Hook 拦截
 * OpenSL ES 和 AAudio 的原生录音函数，将录音缓冲区替换为假 PCM 数据。
 * <p>
 * Native 代码通过 JNI 回调 {@link #fillNativeBuffer(byte[], int, int, int)}
 * 获取替换数据，复用 {@link MicrophoneHandler} 的模式判断逻辑和
 * {@link AudioDataProvider} 的 PCM 数据。
 */
public class NativeAudioHook {

    private static final String TAG = "【CS】[NativeHook]";

    private static native boolean nativeInit();
    private static native void nativeRelease();

    /**
     * 初始化 native 音频 Hook。
     * 从 HookMain 的 Application.onCreate hook 中调用。
     */
    public static void init() {
        System.loadLibrary("camswap-native-hook");
        boolean ok = nativeInit();
        LogUtil.log(TAG + " init result=" + ok);
    }

    /**
     * 释放 native 资源。
     */
    public static void release() {
        try {
            nativeRelease();
        } catch (Throwable t) {
            LogUtil.log(TAG + " release failed: " + t);
        }
    }

    /**
     * Native 代码回调此方法获取假 PCM 数据。
     * 复用 MicrophoneHandler 的模式判断逻辑。
     *
     * @param buffer     目标缓冲区（PCM 16-bit 小端序）
     * @param size       缓冲区大小（字节）
     * @param sampleRate 采样率 (Hz)
     * @param channels   声道数
     * @return 填充的字节数，-1 表示 hook 未启用（不替换，保留原始录音）
     */
    public static int fillNativeBuffer(byte[] buffer, int size, int sampleRate, int channels) {
        try {
            // 检查总开关
            if (!MicrophoneHandler.isMicHookEnabledStatic()) {
                return -1; // 不替换，保留真实录音
            }

            String mode = MicrophoneHandler.getMicHookModeStatic();
            boolean streamModeActive =
                    io.github.zensu357.camswap.utils.VideoManager.isStreamMode();
            // 与 Java AudioRecord 路径共用同一决策，行为一致
            MicAudioRouter.Source src = MicAudioRouter.decide(
                    true, mode, streamModeActive, StreamPcmBuffer.isActive());

            switch (src) {
                case STREAM_PCM:
                    // 仅推流音频 / 流模式视频同步：注入 RTMP 音频
                    StreamPcmBuffer.read(buffer, 0, size, sampleRate, channels);
                    return size;

                case STREAM_SILENCE:
                    // 推流音频尚未就绪 → 静音，绝不透传真实麦克风
                    java.util.Arrays.fill(buffer, 0, size, (byte) 0);
                    return size;

                case VIDEO_SYNC_FILE: {
                    // 本地视频同步音轨
                    long posMs = MicrophoneHandler.getVideoPlaybackPositionMsStatic();
                    if (!AudioDataProvider.isReady()) {
                        MicrophoneHandler.preloadAudioAsyncStatic();
                        java.util.Arrays.fill(buffer, 0, size, (byte) 0);
                        return size;
                    }
                    AudioDataProvider.fillBytesAtPosition(buffer, 0, size, sampleRate, channels, posMs);
                    return size;
                }

                case REPLACE_FILE:
                    // 本地替换音频文件
                    if (!AudioDataProvider.isReady()) {
                        MicrophoneHandler.preloadAudioAsyncStatic();
                        java.util.Arrays.fill(buffer, 0, size, (byte) 0);
                        return size;
                    }
                    AudioDataProvider.fillBytes(buffer, 0, size, sampleRate, channels);
                    return size;

                case SILENCE:
                default:
                    java.util.Arrays.fill(buffer, 0, size, (byte) 0);
                    return size;
            }

        } catch (Throwable t) {
            LogUtil.log(TAG + " fillNativeBuffer error: " + t);
            // 出错时填充静音，避免噪音
            java.util.Arrays.fill(buffer, 0, size, (byte) 0);
            return size;
        }
    }
}
