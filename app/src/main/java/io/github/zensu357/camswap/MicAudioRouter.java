package io.github.zensu357.camswap;

/**
 * 麦克风 Hook 注入音源决策（纯逻辑，无 Android 依赖，便于 JVM 单元测试）。
 *
 * <p>根据「麦克风 Hook 开关 + 当前模式 + 是否处于网络流模式 + 流 PCM 缓冲是否就绪」，
 * 给出目标 App 的 {@code AudioRecord.read} / native 录音回调应当填充的音源类型。
 * {@link MicrophoneHandler}（Java AudioRecord 路径）与 {@link NativeAudioHook}
 * （OpenSL ES / AAudio 路径）共用本决策，保证行为一致、单点可测。
 *
 * <p>关键不变量：一旦麦克风 Hook 开启，<b>任何模式都不会把真实麦克风数据交给目标 App</b>；
 * 流音频未就绪时退化为 {@link Source#STREAM_SILENCE}（静音）而非透传真实录音。
 */
final class MicAudioRouter {

    /** 注入音源类型。 */
    enum Source {
        /** 不替换，保留目标 App 的真实录音（麦克风 Hook 未开启）。 */
        PASSTHROUGH,
        /** 注入直播推流（RTMP/网络流）的音频，来自 {@link StreamPcmBuffer}。 */
        STREAM_PCM,
        /** 处于「流音频」意图但缓冲尚未就绪 → 填静音，绝不泄露真实麦克风。 */
        STREAM_SILENCE,
        /** 注入本地视频的同步音轨（本地视频同步模式）。 */
        VIDEO_SYNC_FILE,
        /** 注入本地替换音频文件。 */
        REPLACE_FILE,
        /** 静音模式。 */
        SILENCE
    }

    private MicAudioRouter() {
    }

    /**
     * 计算当前应注入的音源。
     *
     * @param micHookEnabled    麦克风 Hook 总开关
     * @param mode              当前模式（{@link ConfigManager#MIC_MODE_STREAM} 等）
     * @param streamModeActive  当前媒体源是否为网络流（RTMP/RTSP/...）
     * @param streamBufferActive 流 PCM 缓冲是否已激活且可读
     */
    static Source decide(boolean micHookEnabled, String mode,
            boolean streamModeActive, boolean streamBufferActive) {
        if (!micHookEnabled || mode == null) {
            return Source.PASSTHROUGH;
        }
        switch (mode) {
            case ConfigManager.MIC_MODE_STREAM:
                // 「仅推流音频」：无条件屏蔽真麦克风，有流音频就用，没有就静音
                return streamBufferActive ? Source.STREAM_PCM : Source.STREAM_SILENCE;
            case ConfigManager.MIC_MODE_VIDEO_SYNC:
                // 流模式下的视频同步等价于「读推流音频」；本地模式下读视频文件音轨
                if (streamModeActive) {
                    return streamBufferActive ? Source.STREAM_PCM : Source.STREAM_SILENCE;
                }
                return Source.VIDEO_SYNC_FILE;
            case ConfigManager.MIC_MODE_REPLACE:
                return Source.REPLACE_FILE;
            case ConfigManager.MIC_MODE_MUTE:
            default:
                return Source.SILENCE;
        }
    }

    /** 是否应从 {@link StreamPcmBuffer} 取数（缓冲已就绪的流音频）。 */
    static boolean usesStreamPcm(boolean micHookEnabled, String mode,
            boolean streamModeActive, boolean streamBufferActive) {
        return decide(micHookEnabled, mode, streamModeActive, streamBufferActive)
                == Source.STREAM_PCM;
    }
}
