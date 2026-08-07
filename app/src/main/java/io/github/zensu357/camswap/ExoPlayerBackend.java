package io.github.zensu357.camswap;

import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.rtmp.RtmpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import io.github.zensu357.camswap.utils.LogUtil;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Network stream playback backend using ExoPlayer (Media3).
 * Supports RTSP, RTMP, HLS, DASH, and plain HTTP/HTTPS video streams.
 * <p>
 * ExoPlayer must be created and used on a single Looper thread.
 * This backend creates a dedicated HandlerThread for that purpose.
 */
public final class ExoPlayerBackend implements SurfacePlayerBackend {

    private ExoPlayer player;
    private Surface outputSurface;
    private Listener listener;
    private MediaSourceDescriptor currentSource;

    private HandlerThread playerThread;
    private Handler playerHandler;

    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long BASE_RECONNECT_DELAY_MS = 3000L;

    /** 是否已彻底释放（区分「重连前的临时释放」与「最终释放」，决定是否停流音频缓冲）。 */
    private volatile boolean released = false;

    public ExoPlayerBackend() {
    }

    @Override
    public void setOutputSurface(Surface surface) {
        this.outputSurface = surface;
        postOnPlayerThread(() -> {
            if (player != null) {
                player.setVideoSurface(surface);
            }
        });
    }

    @Override
    public void open(MediaSourceDescriptor source) {
        this.currentSource = source;
        this.reconnectAttempts = 0;
        this.released = false;
        ensurePlayerThread();
        playerHandler.post(() -> openInternal(source));
    }

    private void openInternal(MediaSourceDescriptor source) {
        releasePlayerInternal();

        try {
            // Get application context — prefer HookMain.toast_content (hooked process),
            // fall back to ActivityThread.currentApplication() via reflection (hidden API)
            android.content.Context appContext = HookMain.toast_content;
            if (appContext == null) {
                try {
                    Class<?> atClass = Class.forName("android.app.ActivityThread");
                    java.lang.reflect.Method method = atClass.getMethod("currentApplication");
                    appContext = (android.content.Context) method.invoke(null);
                } catch (Exception ignored) {
                }
            }
            if (appContext == null) {
                LogUtil.log("【CS】ExoPlayer 无法获取 Context");
                if (listener != null) listener.onError("No Context available", null);
                return;
            }

            Looper looper = playerThread != null ? playerThread.getLooper() : Looper.getMainLooper();
            player = new ExoPlayer.Builder(appContext)
                    .setLooper(looper)
                    .build();

            LogUtil.log("【CS】ExoPlayer outputSurface="
                    + (outputSurface == null ? "null" : ("valid=" + outputSurface.isValid())));
            if (outputSurface != null) {
                player.setVideoSurface(outputSurface);
            } else {
                LogUtil.log("【CS】ExoPlayer 警告：输出 Surface 为 null，画面无处渲染（必然黑屏）");
            }

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    LogUtil.log("【CS】ExoPlayer 状态: " + stateName(playbackState));
                    if (playbackState == Player.STATE_READY) {
                        reconnectAttempts = 0;
                        // 旁路解码 PCM → StreamPcmBuffer，供麦克风 Hook 在「仅推流音频/流视频同步」
                        // 模式下取流音频（覆盖 RTMP 回退到 Exo，以及 RTSP/HLS/DASH 等非 Ijk 流）。
                        registerStreamAudioCapture();
                        if (listener != null) listener.onReady();
                    } else if (playbackState == Player.STATE_ENDED) {
                        if (listener != null) listener.onCompletion();
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize videoSize) {
                    // 收到有效视频尺寸 = 解码器已拿到视频轨并开始解码；仍黑屏则问题在渲染/Surface
                    LogUtil.log("【CS】ExoPlayer 视频尺寸: " + videoSize.width + "x" + videoSize.height
                            + "（收到=解码器已出画，若仍黑屏是渲染/Surface 问题）");
                }

                @Override
                public void onRenderedFirstFrame() {
                    // 首帧已渲染到 Surface —— 到这一步说明画面已经画到 GL 输入面，若目标 App 仍黑屏就是
                    // GL→目标 Surface 这一段（旋转/尺寸/OES）的问题，而非流本身。
                    LogUtil.log("【CS】ExoPlayer 首帧已渲染到输出 Surface ✅（流→GL 链路已通）");
                }

                @Override
                public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                    boolean hasVideo = false, hasAudio = false;
                    try {
                        for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
                            int t = g.getType();
                            if (t == androidx.media3.common.C.TRACK_TYPE_VIDEO) hasVideo = true;
                            if (t == androidx.media3.common.C.TRACK_TYPE_AUDIO) hasAudio = true;
                        }
                    } catch (Throwable ignored) {
                    }
                    LogUtil.log("【CS】ExoPlayer 轨道: 视频=" + hasVideo + " 音频=" + hasAudio
                            + (hasVideo ? "" : "（无视频轨！只有音频/无轨——确认该流含视频且编码可被 MediaCodec 解）"));
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    String friendly = classifyError(error);
                    LogUtil.log("【CS】ExoPlayer 播放错误: " + error.getMessage()
                            + " code=" + error.errorCode + " (" + error.getErrorCodeName() + ") → " + friendly);
                    toast("流播放失败: " + friendly);
                    if (listener != null) {
                        listener.onError(error.getMessage(), error);
                        listener.onDisconnected();
                    }
                    if (currentSource != null && currentSource.autoReconnect) {
                        scheduleReconnect();
                    }
                }
            });

            // 明文流量 / INTERNET 权限预检（ExoPlayer 跑在被 Hook 的目标 App 进程里，受其网络策略约束，
            // 这正是「VLC 能播、模块里黑屏」的头号原因）。NetworkPolicyBypass 会在流模式下 hook 放行明文。
            try {
                NetworkPolicyBypass.install(appContext.getClassLoader());
            } catch (Throwable ignored) {
            }
            String preflight = NetworkPolicyBypass.preflight(appContext, source.streamUrl);
            if (preflight != null) {
                LogUtil.log("【CS】流预检警告: " + preflight);
                toast(preflight);
            }
            // 兼容旧日志路径
            warnIfNetworkPolicyBlocks(appContext, source.streamUrl);

            // RTMP 依赖 librtmp-jni.so（打包在 CamSwap APK，不在目标 App 的 lib 目录）。
            // 目标进程直接 new RtmpClient() 会 UnsatisfiedLinkError → 黑屏。开流前先确保 so 已加载。
            if (source.streamUrl != null
                    && source.streamUrl.toLowerCase(java.util.Locale.ROOT).startsWith("rtmp")) {
                boolean rtmpOk = ModuleNativeLoader.ensureRtmpJni(appContext);
                if (!rtmpOk) {
                    String msg = "RTMP native 库(librtmp-jni.so)加载失败——目标进程找不到 CamSwap 的 so。"
                            + "请确认已安装 arm64 版 CamSwap，并授予目标 App 存储/网络权限后重试";
                    LogUtil.log("【CS】" + msg);
                    toast(msg);
                    if (listener != null) {
                        listener.onError(msg, null);
                        listener.onPermanentFailure(msg);
                    }
                    return;
                }
            }

            MediaSource mediaSource = buildMediaSource(source);
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();

            LogUtil.log("【CS】ExoPlayer 开始播放: " + source.streamUrl
                    + "（transport=" + source.transportHint + " autoReconnect=" + source.autoReconnect + "）");
        } catch (UnsatisfiedLinkError ule) {
            LogUtil.log("【CS】ExoPlayer native 库缺失: " + ule);
            toast("native 库加载失败: " + ule.getMessage());
            if (listener != null) {
                listener.onError("native lib missing: " + ule.getMessage(), ule);
                listener.onPermanentFailure("native lib missing");
            }
        } catch (Exception e) {
            LogUtil.log("【CS】ExoPlayer 初始化失败: " + e);
            if (listener != null) {
                listener.onError("ExoPlayer init failed", e);
            }
        }
    }

    @SuppressWarnings("UnstableApi")
    private MediaSource buildMediaSource(MediaSourceDescriptor source) {
        Uri uri = Uri.parse(source.streamUrl);
        String scheme = uri.getScheme();

        if ("rtsp".equalsIgnoreCase(scheme)) {
            RtspMediaSource.Factory factory = new RtspMediaSource.Factory();
            if ("tcp".equals(source.transportHint)) {
                factory.setForceUseRtpTcp(true);
            }
            factory.setTimeoutMs(source.timeoutMs);
            return factory.createMediaSource(MediaItem.fromUri(uri));
        }

        if ("rtmp".equalsIgnoreCase(scheme) || "rtmps".equalsIgnoreCase(scheme)) {
            // Media3 的 RTMP 是 DataSource，配合 ProgressiveMediaSource 使用。
            // so 已在 openInternal 里通过 ModuleNativeLoader 预加载。
            try {
                return new ProgressiveMediaSource.Factory(new RtmpDataSource.Factory())
                        .createMediaSource(MediaItem.fromUri(uri));
            } catch (Throwable t) {
                LogUtil.log("【CS】RTMP DataSource 不可用: " + t);
                throw new IllegalStateException("RTMP 不可用，请确认已打包 media3-datasource-rtmp 且 librtmp-jni.so 可加载", t);
            }
        }

        // HTTP/HTTPS — detect HLS (.m3u8) / DASH (.mpd) / progressive
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs((int) source.timeoutMs)
                .setReadTimeoutMs((int) source.timeoutMs);

        String path = uri.getPath();
        if (path != null && path.endsWith(".m3u8")) {
            return new HlsMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        if (path != null && path.endsWith(".mpd")) {
            return new DashMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(uri));
        }

        // Fallback: ProgressiveMediaSource (plain HTTP video)
        return new ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(uri));
    }

    private static String stateName(int s) {
        switch (s) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING(缓冲中)";
            case Player.STATE_READY: return "READY(就绪播放)";
            case Player.STATE_ENDED: return "ENDED(结束)";
            default: return "未知(" + s + ")";
        }
    }

    /** 把 ExoPlayer 错误码翻译成用户能看懂、能据此排查的原因。 */
    private static String classifyError(PlaybackException e) {
        int c = e.errorCode;
        if (c == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) {
            return "目标 App 禁止明文流量(http/rtmp)。请改用 https / rtsp(tcp)，"
                    + "或该 App 需在 manifest 允许 cleartext";
        }
        if (c == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || c == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return "网络连不上。多为【目标 App 缺 INTERNET 权限】(VLC 有该权限所以能播)，"
                    + "或地址/端口不通、需同一局域网";
        }
        if (c == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                || c == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            return "服务器返回异常(内容类型/状态码)，确认地址正确且流在推";
        }
        if (c == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || c == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            return "容器格式不支持/解析失败";
        }
        if (c == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || c == PlaybackException.ERROR_CODE_DECODING_FAILED) {
            return "解码器初始化/解码失败，编码可能不被本机 MediaCodec 支持";
        }
        return "错误码 " + e.getErrorCodeName();
    }

    /**
     * 预检目标进程的网络策略：若地址是明文协议(http/rtmp/rtsp)而目标 App 又禁止明文流量，
     * ExoPlayer 会静默失败或报 CLEARTEXT_NOT_PERMITTED——提前弹窗提示，省去用户猜。
     */
    private static void warnIfNetworkPolicyBlocks(android.content.Context ctx, String url) {
        if (url == null) return;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        boolean cleartext = u.startsWith("http://") || u.startsWith("rtmp://")
                || u.startsWith("rtsp://") || u.startsWith("rtp://");
        if (!cleartext) return;
        try {
            android.security.NetworkSecurityPolicy policy =
                    android.security.NetworkSecurityPolicy.getInstance();
            boolean permitted = policy.isCleartextTrafficPermitted();
            LogUtil.log("【CS】明文流量检查: 目标 App 允许明文=" + permitted + " url=" + url);
            if (!permitted) {
                String msg = "目标 App 禁止明文流量，" + u.substring(0, Math.min(7, u.length()))
                        + "… 可能被拦截。建议改用 https / rtsp(tcp)";
                LogUtil.log("【CS】⚠ " + msg);
                toast(msg);
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】明文流量检查异常(可忽略): " + t);
        }
    }

    private static void toast(String message) {
        try {
            HookMain.showToast("【CamSwap】" + message);
        } catch (Throwable ignored) {
            // Hook 环境不可用时忽略，日志里已有同样信息
        }
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            LogUtil.log("【CS】ExoPlayer 重连次数已达上限 (" + MAX_RECONNECT_ATTEMPTS + ")，停止重连");
            if (listener != null) {
                // 由 MediaPlayerManager 决定是否真正切回本地（取决于 enableLocalFallback 配置）
                listener.onPermanentFailure("重连 " + MAX_RECONNECT_ATTEMPTS + " 次仍失败");
            }
            return;
        }

        reconnectAttempts++;
        long delay = BASE_RECONNECT_DELAY_MS * reconnectAttempts;
        LogUtil.log("【CS】ExoPlayer 将在 " + delay + "ms 后尝试第 " + reconnectAttempts + " 次重连");

        postOnPlayerThread(() -> {
            if (player != null && currentSource != null) {
                try {
                    player.prepare();
                    player.play();
                    if (listener != null) listener.onReconnected();
                } catch (Exception e) {
                    LogUtil.log("【CS】ExoPlayer 重连失败: " + e);
                    scheduleReconnect();
                }
            }
        }, delay);
    }

    /**
     * 注册 ExoPlayer 的 audioSession 到 {@link AudioTrackWriteHook}，并确保
     * {@link StreamPcmBuffer} 就绪。ExoPlayer 内部同样通过 {@code AudioTrack.write}
     * 输出解码 PCM，被监视后即可旁路进环形缓冲供麦克风替换。
     * <p>真实采样率/声道在首帧 {@code AudioTrack.write} 时由 hook 校正，这里先给常见直播默认值。
     */
    private void registerStreamAudioCapture() {
        try {
            if (player == null) return;
            int sid = player.getAudioSessionId();
            if (sid != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET && sid != 0) {
                AudioTrackWriteHook.watchSession(sid);
                if (!StreamPcmBuffer.isActive()) {
                    StreamPcmBuffer.start(44100, 2);
                }
                LogUtil.log("【CS】ExoPlayer audioSessionId=" + sid + " → StreamPcmBuffer 已就绪");
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】ExoPlayer 注册 audioSession 失败: " + t);
        }
    }

    @Override
    public void restart() {
        if (currentSource == null) return;
        reconnectAttempts = 0;
        released = false;
        postOnPlayerThread(() -> openInternal(currentSource));
    }

    @Override
    public void stop() {
        postOnPlayerThread(() -> {
            if (player != null) {
                player.stop();
            }
        });
    }

    @Override
    public void release() {
        released = true;
        CountDownLatch releaseLatch = new CountDownLatch(1);
        if (playerHandler != null) {
            playerHandler.post(() -> {
                try {
                    releasePlayerInternal();
                } finally {
                    releaseLatch.countDown();
                }
            });
        } else {
            releaseLatch.countDown();
        }

        try {
            releaseLatch.await(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (playerThread != null) {
            playerThread.quitSafely();
            try {
                playerThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            playerThread = null;
        }
        playerHandler = null;
    }

    private void releasePlayerInternal() {
        if (player != null) {
            try {
                AudioTrackWriteHook.unwatchSession(player.getAudioSessionId());
            } catch (Throwable ignored) {
            }
            try {
                player.stop();
                player.release();
            } catch (Exception e) {
                LogUtil.log("【CS】ExoPlayer release 异常: " + e);
            }
            player = null;
        }
        // 仅在「最终释放」时停缓冲；重连前的临时释放保留缓冲，避免麦克风断音
        if (released) {
            StreamPcmBuffer.stop();
            AudioTrackWriteHook.clear();
        }
    }

    @Override
    public boolean isPlaying() {
        if (player == null) return false;
        try {
            return player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long getCurrentPositionMs() {
        if (player == null) return 0;
        try {
            return player.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long getDurationMs() {
        if (player == null) return -1;
        try {
            long duration = player.getDuration();
            return duration == androidx.media3.common.C.TIME_UNSET ? -1 : duration;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void setLooping(boolean looping) {
        postOnPlayerThread(() -> {
            if (player != null) {
                player.setRepeatMode(looping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
            }
        });
    }

    @Override
    public void setVolume(float volume) {
        postOnPlayerThread(() -> {
            if (player != null) {
                player.setVolume(volume);
            }
        });
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ---- Thread management ----

    private void ensurePlayerThread() {
        if (playerThread == null || !playerThread.isAlive()) {
            playerThread = new HandlerThread("ExoPlayerBackend");
            playerThread.start();
            playerHandler = new Handler(playerThread.getLooper());
        }
    }

    private void postOnPlayerThread(Runnable r) {
        if (playerHandler != null) {
            playerHandler.post(r);
        }
    }

    private void postOnPlayerThread(Runnable r, long delayMs) {
        if (playerHandler != null) {
            playerHandler.postDelayed(r, delayMs);
        }
    }
}
