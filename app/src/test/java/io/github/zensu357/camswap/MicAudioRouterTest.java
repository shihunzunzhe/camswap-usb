package io.github.zensu357.camswap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MicAudioRouter} 决策矩阵测试（纯 JVM，无 Android 依赖）。
 *
 * <p>核心验证「仅推流音频(stream)」模式：目标 App 麦克风只读推流声音，
 * 缓冲未就绪时静音，且任何开启状态都不会透传真实麦克风。
 */
public class MicAudioRouterTest {

    // ---- stream 模式：只读推流音频 ----

    @Test
    public void streamMode_bufferReady_readsStreamPcm() {
        assertEquals(MicAudioRouter.Source.STREAM_PCM,
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_STREAM,
                        /*streamModeActive*/ true, /*bufferActive*/ true));
        assertTrue(MicAudioRouter.usesStreamPcm(true, ConfigManager.MIC_MODE_STREAM, true, true));
    }

    @Test
    public void streamMode_bufferNotReady_silencesNeverPassthrough() {
        // 推流尚未产生音频（缓冲未激活）→ 静音，绝不读真实麦克风
        assertEquals(MicAudioRouter.Source.STREAM_SILENCE,
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_STREAM, true, false));
    }

    @Test
    public void streamMode_evenWithoutStreamMediaSource_neverPassthrough() {
        // 即使当前不是网络流源（如误配为本地），stream 模式仍屏蔽真麦克风
        MicAudioRouter.Source s =
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_STREAM, false, false);
        assertEquals(MicAudioRouter.Source.STREAM_SILENCE, s);
        assertFalse(s == MicAudioRouter.Source.PASSTHROUGH);
    }

    // ---- video_sync 模式：流模式下等价读推流，本地读文件 ----

    @Test
    public void videoSync_streamMode_readsStreamPcm() {
        assertEquals(MicAudioRouter.Source.STREAM_PCM,
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_VIDEO_SYNC, true, true));
        assertEquals(MicAudioRouter.Source.STREAM_SILENCE,
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_VIDEO_SYNC, true, false));
    }

    @Test
    public void videoSync_localMode_readsVideoFile() {
        assertEquals(MicAudioRouter.Source.VIDEO_SYNC_FILE,
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_VIDEO_SYNC, false, false));
    }

    // ---- replace / mute ----

    @Test
    public void replaceMode_readsReplaceFile() {
        assertEquals(MicAudioRouter.Source.REPLACE_FILE,
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_REPLACE, false, false));
        // replace 模式不受流状态影响
        assertEquals(MicAudioRouter.Source.REPLACE_FILE,
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_REPLACE, true, true));
    }

    @Test
    public void muteMode_silence() {
        assertEquals(MicAudioRouter.Source.SILENCE,
                MicAudioRouter.decide(true, ConfigManager.MIC_MODE_MUTE, true, true));
    }

    @Test
    public void unknownMode_defaultsToSilence() {
        assertEquals(MicAudioRouter.Source.SILENCE,
                MicAudioRouter.decide(true, "no_such_mode", true, true));
    }

    // ---- 总开关 ----

    @Test
    public void hookDisabled_alwaysPassthrough() {
        assertEquals(MicAudioRouter.Source.PASSTHROUGH,
                MicAudioRouter.decide(false, ConfigManager.MIC_MODE_STREAM, true, true));
        assertEquals(MicAudioRouter.Source.PASSTHROUGH,
                MicAudioRouter.decide(false, ConfigManager.MIC_MODE_MUTE, false, false));
        assertFalse(MicAudioRouter.usesStreamPcm(false, ConfigManager.MIC_MODE_STREAM, true, true));
    }

    @Test
    public void nullMode_passthrough() {
        assertEquals(MicAudioRouter.Source.PASSTHROUGH,
                MicAudioRouter.decide(true, null, true, true));
    }

    /**
     * 不变量：只要 Hook 开启，无论何种模式/流状态，都不得返回 PASSTHROUGH
     * （即绝不把真实麦克风交给目标 App）。
     */
    @Test
    public void enabledHook_neverPassthrough_forAllModes() {
        String[] modes = {
                ConfigManager.MIC_MODE_STREAM, ConfigManager.MIC_MODE_VIDEO_SYNC,
                ConfigManager.MIC_MODE_REPLACE, ConfigManager.MIC_MODE_MUTE, "weird"
        };
        boolean[] bools = {true, false};
        for (String mode : modes) {
            for (boolean stream : bools) {
                for (boolean buf : bools) {
                    assertFalse("mode=" + mode + " stream=" + stream + " buf=" + buf,
                            MicAudioRouter.decide(true, mode, stream, buf)
                                    == MicAudioRouter.Source.PASSTHROUGH);
                }
            }
        }
    }
}
