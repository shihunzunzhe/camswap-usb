package io.github.zensu357.camswap;

import android.media.MediaPlayer;
import android.os.SystemClock;
import android.view.Surface;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Manages all player backends, GLVideoRenderer, and SurfaceRelay instances.
 * Centralizes player lifecycle, restart, rotation, and release logic.
 * <p>
 * In local mode each slot gets its own {@link AndroidMediaPlayerBackend}.
 * In stream mode all slots share frames from a single {@link ExoPlayerBackend}
 * routed through their respective GL renderers.
 */
public final class MediaPlayerManager {
    private final Object mediaLock = new Object();
    private String currentPackageName;
    private volatile long lastCamera2PlaybackStartRealtimeMs;
    private volatile int lastCamera2PlaybackDurationMs;

    // ---- Camera1 players (created by Camera1Handler) ----
    MediaPlayer mplayer1;
    MediaPlayer mMediaPlayer;
    GLVideoRenderer c1_renderer_holder;
    GLVideoRenderer c1_renderer_texture;

    // ---- Camera2 preview players ----
    MediaPlayer c2_player;
    MediaPlayer c2_player_1;
    GLVideoRenderer c2_renderer;
    GLVideoRenderer c2_renderer_1;
    SurfaceRelay c2_relay;
    SurfaceRelay c2_relay_1;

    // ---- Camera2 reader players ----
    MediaPlayer c2_reader_player;
    MediaPlayer c2_reader_player_1;
    GLVideoRenderer c2_reader_renderer;
    GLVideoRenderer c2_reader_renderer_1;
    SurfaceRelay c2_reader_relay;
    SurfaceRelay c2_reader_relay_1;

    // Per-slot surface tracking: skip re-init when surface unchanged
    private Surface lastC2ReaderSurface, lastC2ReaderSurface1;
    private Surface lastC2PreviewSurface, lastC2PreviewSurface1;

    // ---- Stream mode: single shared ExoPlayerBackend ----
    private SurfacePlayerBackend streamBackend;
    /** Camera1 流模式使用独立的 backend（Camera1 与 Camera2 不会同时活跃） */
    private SurfacePlayerBackend c1StreamBackend;
    /** 已经因流不可用切到本地兜底，避免反复来回切 */
    private volatile boolean streamFellBackToLocal;

    /** Set current package name (future per-app video). */
    public void setPackageName(String packageName) {
        this.currentPackageName = packageName;
    }

    /**
     * Central video path query.
     */
    String getVideoPath() {
        return VideoManager.getCurrentVideoPath();
    }

    /** Get current media source descriptor from config. */
    MediaSourceDescriptor getMediaSource() {
        return VideoManager.getCurrentMediaSource();
    }

    /** Whether we are currently in stream mode. */
    boolean isStreamMode() {
        return VideoManager.isStreamMode();
    }

    /** Whether we are currently in USB capture card (UVC) mode. */
    boolean isUsbCaptureMode() {
        return VideoManager.isUsbCaptureMode();
    }

    /** 流 backend 是否仍存活（供 Camera2SessionHook 判断是否需要强制重建）。 */
    boolean hasActiveStreamBackend() {
        return streamBackend != null || c1StreamBackend != null;
    }

    /**
     * 流播放音量：play_video_sound 开 → 1；
     * 或 mic hook 为 video_sync → 1（保证 PCM 旁路非零）；
     * 否则 0。
     */
    private static float resolveStreamVolume() {
        try {
            ConfigManager cfg = VideoManager.getConfig();
            if (cfg.getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false)) {
                return 1.0f;
            }
            if (cfg.getBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, false)
                    && ConfigManager.MIC_MODE_VIDEO_SYNC.equals(
                            cfg.getString(ConfigManager.KEY_MIC_HOOK_MODE, ConfigManager.MIC_MODE_MUTE))) {
                return 1.0f;
            }
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    // =====================================================================
    // Camera2 player initialization
    // =====================================================================

    /**
     * Initialize Camera2 players for the given surfaces.
     * In stream mode, creates a single ExoPlayerBackend for the primary preview
     * and routes frames to reader surfaces via GL renderers.
     */
    void initCamera2Players(Surface readerSurface, Surface readerSurface1,
            Surface previewSurface, Surface previewSurface1) {

        MediaSourceDescriptor source = getMediaSource();
        streamFellBackToLocal = false;

        if (source.isUsbCapture()) {
            // USB 采集卡模式：本地只建 GL 渲染器，画面由宿主 UsbCaptureService 跨进程直推
            releaseStreamBackend();
            if (CameraHandlerPatch.attachCamera2(this, readerSurface, readerSurface1,
                    previewSurface, previewSurface1)) {
                lastC2ReaderSurface = readerSurface;
                lastC2ReaderSurface1 = readerSurface1;
                lastC2PreviewSurface = previewSurface;
                lastC2PreviewSurface1 = previewSurface1;
                return;
            }
            LogUtil.log("【CS】【usb】Camera2 接管失败，回退到本地视频模式");
            initCamera2PlayersLocal(readerSurface, readerSurface1,
                    previewSurface, previewSurface1);
        } else if (source.isStream()) {
            initCamera2PlayersStream(readerSurface, readerSurface1,
                    previewSurface, previewSurface1, source);
        } else {
            initCamera2PlayersLocal(readerSurface, readerSurface1,
                    previewSurface, previewSurface1);
        }
    }

    private void initCamera2PlayersLocal(Surface readerSurface, Surface readerSurface1,
            Surface previewSurface, Surface previewSurface1) {
        if (readerSurface != null && readerSurface != lastC2ReaderSurface) {
            c2_reader_player = recreatePlayer(c2_reader_player);
            GLVideoRenderer[] r = { c2_reader_renderer };
            SurfaceRelay[] rr = { c2_reader_relay };
            setupMediaPlayer(c2_reader_player, r, rr, readerSurface, "c2_reader", false);
            c2_reader_renderer = r[0];
            c2_reader_relay = rr[0];
            lastC2ReaderSurface = readerSurface;
        }
        if (readerSurface1 != null && readerSurface1 != lastC2ReaderSurface1) {
            c2_reader_player_1 = recreatePlayer(c2_reader_player_1);
            GLVideoRenderer[] r = { c2_reader_renderer_1 };
            SurfaceRelay[] rr = { c2_reader_relay_1 };
            setupMediaPlayer(c2_reader_player_1, r, rr, readerSurface1, "c2_reader_1", false);
            c2_reader_renderer_1 = r[0];
            c2_reader_relay_1 = rr[0];
            lastC2ReaderSurface1 = readerSurface1;
        }

        boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);

        if (previewSurface != null && previewSurface != lastC2PreviewSurface) {
            c2_player = recreatePlayer(c2_player);
            GLVideoRenderer[] r = { c2_renderer };
            SurfaceRelay[] rr = { c2_relay };
            setupMediaPlayer(c2_player, r, rr, previewSurface, "c2_preview", playSound);
            c2_renderer = r[0];
            c2_relay = rr[0];
            lastC2PreviewSurface = previewSurface;
        }
        if (previewSurface1 != null && previewSurface1 != lastC2PreviewSurface1) {
            c2_player_1 = recreatePlayer(c2_player_1);
            GLVideoRenderer[] r = { c2_renderer_1 };
            SurfaceRelay[] rr = { c2_relay_1 };
            setupMediaPlayer(c2_player_1, r, rr, previewSurface1, "c2_preview_1", playSound);
            c2_renderer_1 = r[0];
            c2_relay_1 = rr[0];
            lastC2PreviewSurface1 = previewSurface1;
        }
        LogUtil.log("【CS】Camera2处理过程完全执行（本地模式）");
    }

    private void initCamera2PlayersStream(Surface readerSurface, Surface readerSurface1,
            Surface previewSurface, Surface previewSurface1,
            MediaSourceDescriptor source) {
        // Release any old stream backend
        releaseStreamBackend();

        // 所有渲染器旋转为 0°，rotation_offset 仅通过 captureFrameForYuv 应用于 YUV 截帧

        // Choose primary surface: prefer preview, fallback to reader
        Surface primaryTarget = previewSurface != null ? previewSurface : readerSurface;
        if (primaryTarget == null) {
            LogUtil.log("【CS】流模式：无可用目标 Surface");
            return;
        }

        // Set up GL renderers for all surfaces (stream frames are shared)
        if (readerSurface != null) {
            GLVideoRenderer.releaseSafely(c2_reader_renderer);
            SurfaceRelay.releaseSafely(c2_reader_relay);
            c2_reader_renderer = GLVideoRenderer.createSafely(readerSurface, "c2_reader_stream");
        }
        if (readerSurface1 != null) {
            GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
            SurfaceRelay.releaseSafely(c2_reader_relay_1);
            c2_reader_renderer_1 = GLVideoRenderer.createSafely(readerSurface1, "c2_reader_1_stream");
        }
        if (previewSurface != null) {
            GLVideoRenderer.releaseSafely(c2_renderer);
            SurfaceRelay.releaseSafely(c2_relay);
            c2_renderer = GLVideoRenderer.createSafely(previewSurface, "c2_preview_stream");
        }
        if (previewSurface1 != null) {
            GLVideoRenderer.releaseSafely(c2_renderer_1);
            SurfaceRelay.releaseSafely(c2_relay_1);
            c2_renderer_1 = GLVideoRenderer.createSafely(previewSurface1, "c2_preview_1_stream");
        }

        // 流模式默认水平镜像：抵消微信等 App 前置预览的二次镜像，使 OBS 画面方向正确。
        // 本地文件模式不翻（用户素材方向由 rotation_offset 管）。
        applyStreamMirror(c2_renderer, true);
        applyStreamMirror(c2_renderer_1, true);
        applyStreamMirror(c2_reader_renderer, true);
        applyStreamMirror(c2_reader_renderer_1, true);

        // Create stream backend — output to primary GL renderer's input surface
        try {
            streamBackend = createStreamBackend(source);
            GLVideoRenderer primaryRenderer = (previewSurface != null) ? c2_renderer : c2_reader_renderer;
            Surface backendSurface;
            if (primaryRenderer != null && primaryRenderer.isInitialized()) {
                // 流模式也要设默认缓冲区尺寸，否则部分机型 SurfaceTexture 初始为 0x0，首帧黑屏
                int bw = Math.max(HookMain.c2_ori_width, 1280);
                int bh = Math.max(HookMain.c2_ori_height, 720);
                primaryRenderer.setInputBufferSize(bw, bh);
                primaryRenderer.setRotation(0);
                backendSurface = primaryRenderer.getInputSurface();
                LogUtil.log("【CS】流模式 primary GL 输入面 "
                        + bw + "x" + bh + " mirror=" + primaryRenderer.isMirrorHorizontal());
            } else {
                backendSurface = primaryTarget;
                LogUtil.log("【CS】流模式 GL 不可用，Ijk 直出目标 Surface（无镜像补偿）");
            }
            // 把其它槽位的渲染器也设上合理缓冲区，避免后续 YUV 截帧 / 副预览拿到 1x1
            applyDefaultStreamBuffer(c2_renderer);
            applyDefaultStreamBuffer(c2_renderer_1);
            applyDefaultStreamBuffer(c2_reader_renderer);
            applyDefaultStreamBuffer(c2_reader_renderer_1);
            streamBackend.setOutputSurface(backendSurface);
            // 外放：跟随 play_video_sound。
            // mic video_sync 依赖 AudioTrack.write 旁路 PCM——Ijk/Exo 的 setVolume(0)
            // 在部分实现里会在 write 前把采样乘 0，导致麦克风拿到静音；
            // 因此只要开了 mic+video_sync，就强制 volume=1，外放可用系统静音键关掉。
            streamBackend.setVolume(resolveStreamVolume());
            streamBackend.setLooping(false); // streams don't loop
            streamBackend.setListener(new SurfacePlayerBackend.Listener() {
                @Override
                public void onReady() {
                    LogUtil.log("【CS】流播放器就绪");
                    lastCamera2PlaybackStartRealtimeMs = SystemClock.elapsedRealtime();
                }

                @Override
                public void onError(String message, Throwable cause) {
                    LogUtil.log("【CS】流播放器错误: " + message
                            + (cause != null ? " " + cause : ""));
                }

                @Override
                public void onDisconnected() {
                    LogUtil.log("【CS】流断开连接");
                }

                @Override
                public void onReconnected() {
                    LogUtil.log("【CS】流重连成功");
                }

                @Override
                public void onCompletion() {
                    LogUtil.log("【CS】流播放完成");
                }

                @Override
                public void onPermanentFailure(String message) {
                    LogUtil.log("【CS】流已彻底不可用: " + message);
                    fallbackToLocalIfEnabled(source, message);
                }
            });
            streamBackend.open(source);
            LogUtil.log("【CS】Camera2处理过程完全执行（流模式: " + source.streamUrl + "）");
        } catch (Exception e) {
            LogUtil.log("【CS】流模式初始化失败: " + android.util.Log.getStackTraceString(e));
        }
    }

    // =====================================================================
    // Camera1 stream mode
    // =====================================================================

    /**
     * Camera1 流模式：把 ExoPlayer 输出挂到目标 Surface 的 GL 渲染器上。
     * <p>
     * Camera1 的两条预览路径（SurfaceHolder / SurfaceTexture）在实际调用中是互斥的，
     * 因此共用一个 backend，切换时先释放旧的。
     *
     * @param holderSlot true 表示 SurfaceHolder 路径，false 表示 SurfaceTexture 路径
     * @return true 表示已由流接管，调用方不应再创建 MediaPlayer
     */
    boolean initCamera1Stream(Surface targetSurface, boolean holderSlot) {
        MediaSourceDescriptor source = getMediaSource();
        if (!source.isStream()) {
            return false;
        }
        if (targetSurface == null || !targetSurface.isValid()) {
            LogUtil.log("【CS】Camera1 流模式：目标 Surface 无效");
            return false;
        }

        releaseCamera1StreamBackend();

        String tag = holderSlot ? "c1_holder_stream" : "c1_texture_stream";
        GLVideoRenderer renderer = GLVideoRenderer.createSafely(targetSurface, tag);
        if (holderSlot) {
            GLVideoRenderer.releaseSafely(c1_renderer_holder);
            c1_renderer_holder = renderer;
        } else {
            GLVideoRenderer.releaseSafely(c1_renderer_texture);
            c1_renderer_texture = renderer;
        }

        Surface output;
        if (renderer != null && renderer.isInitialized() && renderer.getInputSurface() != null) {
            renderer.setRotation(0);
            // Camera1 流同样做水平镜像，抵消前置预览二次镜像
            renderer.setMirrorHorizontal(true);
            renderer.setInputBufferSize(
                    Math.max(HookMain.mwidth, 1280),
                    Math.max(HookMain.mhight, 720));
            output = renderer.getInputSurface();
        } else {
            LogUtil.log("【CS】Camera1 流模式：GL 渲染器不可用，直接输出到目标 Surface");
            output = targetSurface;
        }

        try {
            c1StreamBackend = createStreamBackend(source);
            c1StreamBackend.setOutputSurface(output);
            c1StreamBackend.setVolume(resolveStreamVolume());
            c1StreamBackend.setLooping(false);
            c1StreamBackend.setListener(new SurfacePlayerBackend.Listener() {
                @Override
                public void onReady() {
                    LogUtil.log("【CS】Camera1 流播放器就绪");
                }

                @Override
                public void onError(String message, Throwable cause) {
                    LogUtil.log("【CS】Camera1 流播放器错误: " + message
                            + (cause != null ? " " + cause : ""));
                }

                @Override
                public void onDisconnected() {
                    LogUtil.log("【CS】Camera1 流断开连接");
                }

                @Override
                public void onReconnected() {
                    LogUtil.log("【CS】Camera1 流重连成功");
                }

                @Override
                public void onCompletion() {
                    LogUtil.log("【CS】Camera1 流播放完成");
                }

                @Override
                public void onPermanentFailure(String message) {
                    LogUtil.log("【CS】Camera1 流已彻底不可用: " + message);
                    fallbackToLocalIfEnabled(source, message);
                }
            });
            c1StreamBackend.open(source);
            LogUtil.log("【CS】Camera1 处理过程完全执行（流模式: " + source.streamUrl + "）");
            return true;
        } catch (Exception e) {
            LogUtil.log("【CS】Camera1 流模式初始化失败: " + android.util.Log.getStackTraceString(e));
            releaseCamera1StreamBackend();
            return false;
        }
    }

    private void releaseCamera1StreamBackend() {
        if (c1StreamBackend != null) {
            c1StreamBackend.release();
            c1StreamBackend = null;
        }
    }

    // =====================================================================
    // Stream → local fallback
    // =====================================================================

    /**
     * 流彻底不可用时切回本地视频。
     * 仅在配置开启"本地兜底"且确有可用本地视频时执行，且每个会话只切一次。
     */
    private void fallbackToLocalIfEnabled(MediaSourceDescriptor source, String reason) {
        if (source == null || !source.enableLocalFallback) {
            LogUtil.log("【CS】本地兜底未开启，保持当前画面");
            return;
        }
        if (streamFellBackToLocal) {
            return;
        }
        VideoManager.updateVideoPath(false);
        String localPath = VideoManager.getCurrentVideoPath();
        boolean providerBacked = VideoManager.isUsingProviderBackedVideo();
        if (!providerBacked && (localPath == null || localPath.isEmpty()
                || !new java.io.File(localPath).exists())) {
            LogUtil.log("【CS】本地兜底失败：没有可用的本地视频");
            return;
        }
        streamFellBackToLocal = true;
        LogUtil.log("【CS】流不可用(" + reason + ")，切换到本地视频兜底");

        synchronized (mediaLock) {
            releaseStreamBackend();
            releaseCamera1StreamBackend();

            // Camera1：把已建好的渲染器接回本地 MediaPlayer
            if (c1_renderer_holder != null && c1_renderer_holder.isInitialized()) {
                mplayer1 = recreatePlayer(mplayer1);
                startLocalPlayerOnRenderer(mplayer1, c1_renderer_holder, "c1_holder_fallback");
            }
            if (c1_renderer_texture != null && c1_renderer_texture.isInitialized()) {
                mMediaPlayer = recreatePlayer(mMediaPlayer);
                startLocalPlayerOnRenderer(mMediaPlayer, c1_renderer_texture, "c1_texture_fallback");
            }

            // Camera2：同理接回四路渲染器
            if (c2_renderer != null && c2_renderer.isInitialized()) {
                c2_player = recreatePlayer(c2_player);
                startLocalPlayerOnRenderer(c2_player, c2_renderer, "c2_preview_fallback");
            }
            if (c2_renderer_1 != null && c2_renderer_1.isInitialized()) {
                c2_player_1 = recreatePlayer(c2_player_1);
                startLocalPlayerOnRenderer(c2_player_1, c2_renderer_1, "c2_preview_1_fallback");
            }
            if (c2_reader_renderer != null && c2_reader_renderer.isInitialized()) {
                c2_reader_player = recreatePlayer(c2_reader_player);
                startLocalPlayerOnRenderer(c2_reader_player, c2_reader_renderer, "c2_reader_fallback");
            }
            if (c2_reader_renderer_1 != null && c2_reader_renderer_1.isInitialized()) {
                c2_reader_player_1 = recreatePlayer(c2_reader_player_1);
                startLocalPlayerOnRenderer(c2_reader_player_1, c2_reader_renderer_1, "c2_reader_1_fallback");
            }
        }
    }

    /** 用本地视频驱动一个已存在的 GL 渲染器。 */
    private void startLocalPlayerOnRenderer(MediaPlayer player, GLVideoRenderer renderer, String tag) {
        if (player == null || renderer == null) {
            return;
        }
        try {
            player.setSurface(renderer.getInputSurface());
            player.setLooping(true);
            if (!VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false)) {
                player.setVolume(0, 0);
            }
            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            if (pfd != null) {
                player.setDataSource(pfd.getFileDescriptor());
                pfd.close();
            } else {
                player.setDataSource(getVideoPath());
            }
            player.prepare();
            player.start();
            LogUtil.log("【CS】" + tag + " 已切换到本地视频");
        } catch (Exception e) {
            LogUtil.log("【CS】" + tag + " 本地兜底失败: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * 创建流播放后端。
     * <ul>
     *   <li>RTMP/RTMPS：优先 {@link IjkPlayerBackend}（与 VCAMPRO 相同引擎，目标进程更稳）；</li>
     *   <li>其它协议（RTSP/HLS/DASH/HTTP）：{@link ExoPlayerBackend}；</li>
     *   <li>都失败时退 {@link AndroidMediaPlayerBackend}（仅本地文件有意义）。</li>
     * </ul>
     */
    private SurfacePlayerBackend createStreamBackend() {
        return createStreamBackend(getMediaSource());
    }

    private SurfacePlayerBackend createStreamBackend(MediaSourceDescriptor source) {
        String url = source != null ? source.streamUrl : null;
        if (shouldPreferIjk(url)) {
            try {
                Class<?> clazz = Class.forName("io.github.zensu357.camswap.IjkPlayerBackend");
                SurfacePlayerBackend backend =
                        (SurfacePlayerBackend) clazz.getDeclaredConstructor().newInstance();
                LogUtil.log("【CS】流后端选用 IjkPlayerBackend（RTMP/低延迟直播）");
                return backend;
            } catch (Throwable t) {
                LogUtil.log("【CS】IjkPlayerBackend 不可用，回退 ExoPlayer: " + t);
            }
        }
        try {
            Class<?> clazz = Class.forName("io.github.zensu357.camswap.ExoPlayerBackend");
            SurfacePlayerBackend backend =
                    (SurfacePlayerBackend) clazz.getDeclaredConstructor().newInstance();
            LogUtil.log("【CS】流后端选用 ExoPlayerBackend");
            return backend;
        } catch (Exception e) {
            LogUtil.log("【CS】ExoPlayerBackend 不可用，回退到 AndroidMediaPlayerBackend: " + e);
            return new AndroidMediaPlayerBackend();
        }
    }

    /** RTMP 家族（及空 scheme 的直播地址）优先 Ijk；HLS/DASH/RTSP 仍走 Exo。 */
    private static boolean shouldPreferIjk(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String u = url.toLowerCase(java.util.Locale.ROOT).trim();
        if (u.startsWith("rtmp://") || u.startsWith("rtmps://") || u.startsWith("rtmpt://")) {
            return true;
        }
        // 没有明确后缀的直播常见地址也优先 Ijk（VCAMPRO 只支持 liveURL=任意 rtmp）
        if (u.startsWith("rtp://")) {
            return true;
        }
        return false;
    }

    private static void applyDefaultStreamBuffer(GLVideoRenderer renderer) {
        if (renderer == null || !renderer.isInitialized()) {
            return;
        }
        int w = Math.max(HookMain.c2_ori_width, 1280);
        int h = Math.max(HookMain.c2_ori_height, 720);
        try {
            renderer.setInputBufferSize(w, h);
        } catch (Throwable ignored) {
        }
    }

    private static void applyStreamMirror(GLVideoRenderer renderer, boolean mirror) {
        if (renderer == null || !renderer.isInitialized()) {
            return;
        }
        try {
            renderer.setMirrorHorizontal(mirror);
            renderer.setRotation(0);
        } catch (Throwable ignored) {
        }
    }

    private void releaseStreamBackend() {
        if (streamBackend != null) {
            streamBackend.release();
            streamBackend = null;
        }
    }

    private MediaPlayer recreatePlayer(MediaPlayer old) {
        if (old != null)
            old.release();
        return new MediaPlayer();
    }

    long getCamera2PlaybackPositionMs() {
        // Stream mode: query stream backend
        if (streamBackend != null) {
            try {
                long pos = streamBackend.getCurrentPositionMs();
                if (pos > 0) return pos;
            } catch (Exception ignored) {
            }
        }

        MediaPlayer[] players = {
                c2_player, c2_player_1,
                c2_reader_player, c2_reader_player_1,
                mplayer1, mMediaPlayer
        };
        for (MediaPlayer player : players) {
            if (player == null) {
                continue;
            }
            try {
                int position = player.getCurrentPosition();
                if (position > 0) {
                    return position;
                }
            } catch (Exception ignored) {
            }
        }
        if (lastCamera2PlaybackStartRealtimeMs > 0) {
            long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - lastCamera2PlaybackStartRealtimeMs);
            if (lastCamera2PlaybackDurationMs > 0) {
                return elapsedMs % lastCamera2PlaybackDurationMs;
            }
            return elapsedMs;
        }
        return 0;
    }

    private void markCamera2PlaybackStarted(MediaPlayer player, String tag) {
        if (tag == null || !tag.startsWith("c2_") || player == null) {
            return;
        }
        lastCamera2PlaybackStartRealtimeMs = SystemClock.elapsedRealtime();
        try {
            lastCamera2PlaybackDurationMs = Math.max(0, player.getDuration());
        } catch (Exception ignored) {
            lastCamera2PlaybackDurationMs = 0;
        }
    }

    // =====================================================================
    // Restart / rotation / release
    // =====================================================================

    /** Restart all active players with current video/stream. */
    void restartAll() {
        synchronized (mediaLock) {
            if (isUsbCaptureMode()) {
                // USB 模式无本地播放器：重放一次 Surface 注册即可，必要时让宿主重连设备
                UsbCaptureClient client = UsbCaptureClient.peek();
                if (client != null) {
                    client.forceResync();
                    if (!client.isUvcConnected()) {
                        client.requestReconnect();
                    }
                }
                LogUtil.log("【CS】【usb】媒体源变化：已重新同步 Surface 注册");
            } else if (isStreamMode()) {
                // Stream mode: 若 backend 还在就 restart；否则清 lastInit 缓存并靠
                // 下一次 build()/addTarget 触发 startPlayback 完整重建
                // （仅 restart 在 surface 已变/backend 已释放时不够，微信直播会黑屏）。
                streamFellBackToLocal = false;
                if (streamBackend != null) {
                    streamBackend.restart();
                    LogUtil.log("【CS】流模式 restartAll：已 restart 现有 streamBackend");
                } else if (c1StreamBackend != null) {
                    c1StreamBackend.restart();
                    LogUtil.log("【CS】流模式 restartAll：已 restart 现有 c1StreamBackend");
                } else {
                    LogUtil.log("【CS】流模式 restartAll：backend 为空，等待下次 startPlayback 重建");
                }
                // 即便 backend 还在，也确保 secondary 渲染器缓冲尺寸正确
                applyDefaultStreamBuffer(c2_renderer);
                applyDefaultStreamBuffer(c2_renderer_1);
                applyDefaultStreamBuffer(c2_reader_renderer);
                applyDefaultStreamBuffer(c2_reader_renderer_1);
            } else {
                // Local mode: restart individual MediaPlayers
                VideoManager.checkProviderAvailability();
                restartSinglePlayer(mplayer1, c1_renderer_holder, "mplayer1");
                restartSinglePlayer(mMediaPlayer, c1_renderer_texture, "mMediaPlayer");
                restartSinglePlayer(c2_reader_player, c2_reader_renderer, "c2_reader_player");
                restartSinglePlayer(c2_reader_player_1, c2_reader_renderer_1, "c2_reader_player_1");
                restartSinglePlayer(c2_player, c2_renderer, "c2_player");
                restartSinglePlayer(c2_player_1, c2_renderer_1, "c2_player_1");
            }
        }
    }

    private void restartSinglePlayer(MediaPlayer player, GLVideoRenderer renderer, String tag) {
        if (player == null)
            return;
        try {
            if (player.isPlaying())
                player.stop();
            player.reset();
            if (renderer != null && renderer.isInitialized()) {
                player.setSurface(renderer.getInputSurface());
            }
            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            if (pfd != null) {
                player.setDataSource(pfd.getFileDescriptor());
                pfd.close();
            } else {
                player.setDataSource(getVideoPath());
            }
            player.prepare();
            player.start();
        } catch (Exception e) {
            LogUtil.log("【CS】重启 " + tag + " 失败: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * 旋转偏移已更新的通知。渲染器保持 0° 旋转（应用自行处理预览旋转），
     * rotation_offset 仅在 captureFrameForYuv 中应用于 YUV 截帧/JPEG。
     */
    void updateRotation(int degrees) {
        LogUtil.log("【CS】旋转偏移已更新: " + degrees + "°（渲染器保持0°，仅影响截帧）");
    }

    /** Release all GL renderers. */
    void releaseAllRenderers() {
        GLVideoRenderer.releaseSafely(c2_reader_renderer);
        c2_reader_renderer = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
        c2_reader_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c2_renderer);
        c2_renderer = null;
        GLVideoRenderer.releaseSafely(c2_renderer_1);
        c2_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c1_renderer_holder);
        c1_renderer_holder = null;
        GLVideoRenderer.releaseSafely(c1_renderer_texture);
        c1_renderer_texture = null;
    }

    /** Release Camera1 players and renderers (called from stopPreview/release). */
    void releaseCamera1Resources() {
        CameraHandlerPatch.releaseCamera1();
        releaseCamera1StreamBackend();
        GLVideoRenderer.releaseSafely(c1_renderer_holder);
        c1_renderer_holder = null;
        GLVideoRenderer.releaseSafely(c1_renderer_texture);
        c1_renderer_texture = null;
        stopAndRelease(mplayer1);
        mplayer1 = null;
        stopAndRelease(mMediaPlayer);
        mMediaPlayer = null;
    }

    /** Release Camera2 players and renderers (called from onOpened). */
    void releaseCamera2Resources() {
        CameraHandlerPatch.releaseCamera2();
        releaseStreamBackend();
        GLVideoRenderer.releaseSafely(c2_renderer);
        c2_renderer = null;
        GLVideoRenderer.releaseSafely(c2_renderer_1);
        c2_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer);
        c2_reader_renderer = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
        c2_reader_renderer_1 = null;
        stopAndRelease(c2_player);
        c2_player = null;
        stopAndRelease(c2_reader_player_1);
        c2_reader_player_1 = null;
        stopAndRelease(c2_reader_player);
        c2_reader_player = null;
        stopAndRelease(c2_player_1);
        c2_player_1 = null;
        lastC2ReaderSurface = null;
        lastC2ReaderSurface1 = null;
        lastC2PreviewSurface = null;
        lastC2PreviewSurface1 = null;
    }

    private void stopAndRelease(MediaPlayer player) {
        if (player == null)
            return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
    }

    // =====================================================================
    // Private: three-tier surface rendering setup (local mode)
    // =====================================================================

    private void setupMediaPlayer(MediaPlayer player, GLVideoRenderer[] rendererRef,
            SurfaceRelay[] relayRef, Surface targetSurface, String tag, boolean playSound) {
        if (targetSurface == null)
            return;
        GLVideoRenderer.releaseSafely(rendererRef[0]);
        SurfaceRelay.releaseSafely(relayRef[0]);
        // 预览渲染器旋转固定为 0°：应用（如 WhatsApp）会对预览自行应用相机传感器旋转，
        // CamSwap 不应再叠加旋转，否则本机画面会被双重旋转。
        // video_rotation_offset 仅在 YUV 截帧时通过 captureFrameForYuv 应用，确保对方画面正确。
        rendererRef[0] = GLVideoRenderer.createSafely(targetSurface, tag);
        if (!playSound)
            player.setVolume(0, 0);
        player.setLooping(true);
        try {
            if (rendererRef[0] != null) {
                player.setSurface(rendererRef[0].getInputSurface());
                rendererRef[0].setRotation(0);
                LogUtil.log("【CS】【GL】" + tag + " 使用 GL 渲染器 (旋转:0°)");
            } else {
                LogUtil.log("【CS】【Relay】" + tag + " GL 失败，尝试 SurfaceTexture 中继");
                relayRef[0] = SurfaceRelay.createSafely(targetSurface, tag);
                if (relayRef[0] != null) {
                    player.setSurface(relayRef[0].getInputSurface());
                    relayRef[0].setRotation(0);
                    LogUtil.log("【CS】【Relay】" + tag + " 使用 Relay 渲染器 (旋转:0°)");
                } else {
                    player.setSurface(targetSurface);
                    LogUtil.log("【CS】" + tag + " 回退到直接 Surface（无旋转）");
                }
            }

            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            if (pfd != null) {
                player.setDataSource(pfd.getFileDescriptor());
                pfd.close();
            } else {
                player.setDataSource(getVideoPath());
            }
            player.setOnErrorListener((mp, what, extra) -> {
                LogUtil.log("【CS】[" + tag + "] MediaPlayer 错误: what=" + what + " extra=" + extra);
                return true;
            });
            player.setOnInfoListener((mp, what, extra) -> {
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                        || what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START
                        || what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    LogUtil.log("【CS】[" + tag + "] MediaPlayer info: what=" + what);
                }
                return false;
            });
            player.prepare();
            player.start();
            markCamera2PlaybackStarted(player, tag);
            LogUtil.log("【CS】" + tag + " 已启动播放");
        } catch (Exception e) {
            LogUtil.log("【CS】[" + tag + "] 初始化播放器异常: " + android.util.Log.getStackTraceString(e));
        }
    }
}
