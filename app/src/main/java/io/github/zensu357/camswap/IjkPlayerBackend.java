package io.github.zensu357.camswap;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.zensu357.camswap.utils.LogUtil;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkLibLoader;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * 基于 IjkMediaPlayer（FFmpeg）的网络流后端，对标 VCAMPRO 的 RTMP 实现。
 *
 * <p>为何 RTMP 优先走 Ijk 而不是 Media3：
 * <ul>
 *   <li>VCAMPRO 已验证 {@code IjkMediaPlayer + setSurface(相机Surface)} 能稳定出画；</li>
 *   <li>Media3 {@code RtmpDataSource} 依赖 {@code librtmp-jni.so}，在被 Hook 的目标进程
 *       ClassLoader 下经常 {@link UnsatisfiedLinkError} / 明文策略拦截；</li>
 *   <li>Ijk 自带完整 FFmpeg RTMP 协议栈（{@code libijkffmpeg.so}），参数可调低延迟。</li>
 * </ul>
 *
 * <p>注入链路与 {@link ExoPlayerBackend} 相同：输出到 {@link GLVideoRenderer} 的输入 Surface，
 * 再由 GL 画到目标 App 的预览 / ImageReader Surface——不直接 toast 流地址，降低特征暴露。
 */
public final class IjkPlayerBackend implements SurfacePlayerBackend {

    private static final String TAG = "【CS】【ijk】";
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long BASE_RECONNECT_DELAY_MS = 2000L;
    private static final AtomicBoolean LIBS_READY = new AtomicBoolean(false);
    private static final Object LIBS_LOCK = new Object();

    private IjkMediaPlayer player;
    private Surface outputSurface;
    private Listener listener;
    private MediaSourceDescriptor currentSource;
    private float volume = 0f;

    private HandlerThread playerThread;
    private Handler playerHandler;
    private int reconnectAttempts;
    private volatile boolean released;

    public IjkPlayerBackend() {
    }

    @Override
    public void setOutputSurface(Surface surface) {
        this.outputSurface = surface;
        postOnPlayerThread(() -> {
            if (player != null && surface != null && surface.isValid()) {
                try {
                    player.setSurface(surface);
                    LogUtil.log(TAG + "setSurface valid=" + surface.isValid());
                } catch (Throwable t) {
                    LogUtil.log(TAG + "setSurface 失败: " + t);
                }
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
        if (released) {
            return;
        }
        releasePlayerInternal();

        Context appContext = resolveContext();
        if (appContext == null) {
            LogUtil.log(TAG + "无法获取 Context");
            if (listener != null) {
                listener.onError("No Context available", null);
            }
            return;
        }

        if (!ModuleNativeLoader.ensureIjk(appContext) && !ensureIjkLibraries(appContext)) {
            String msg = "Ijk native 库加载失败（ijkffmpeg/ijksdl/ijkplayer）。"
                    + "请确认已安装匹配 ABI 的 CamSwap，并重启目标 App";
            LogUtil.log(TAG + msg);
            toast(msg);
            if (listener != null) {
                listener.onError(msg, null);
                listener.onPermanentFailure(msg);
            }
            return;
        }
        // 即便 ensureIjk 已 load so，也要让 IjkMediaPlayer 内部 mIsLibLoaded=true，
        // 否则构造时仍会再走 System.loadLibrary 并在目标 ClassLoader 下失败。
        ensureIjkLibraries(appContext);

        // 明文 / INTERNET 预检（Ijk 同样跑在目标进程，受其网络策略约束）
        try {
            NetworkPolicyBypass.install(appContext.getClassLoader());
        } catch (Throwable ignored) {
        }
        String preflight = NetworkPolicyBypass.preflight(appContext, source.streamUrl);
        if (preflight != null) {
            LogUtil.log(TAG + "流预检警告: " + preflight);
            // 不 toast 完整流地址，只提示策略问题，降低被目标 App 监测到的特征
            toast(preflight);
        }

        if (outputSurface == null || !outputSurface.isValid()) {
            LogUtil.log(TAG + "警告：输出 Surface 无效，必然黑屏");
        }

        try {
            IjkMediaPlayer ijk = new IjkMediaPlayer();
            applyRtmpOptions(ijk, source);
            ijk.setOnPreparedListener(mp -> {
                LogUtil.log(TAG + "onPrepared，开始播放");
                try {
                    if (outputSurface != null && outputSurface.isValid()) {
                        mp.setSurface(outputSurface);
                    }
                    // 外放音量可静音；AudioTrack.write 仍有满幅 PCM，供 mic 替换
                    mp.setVolume(volume, volume);
                    try {
                        int sid = mp.getAudioSessionId();
                        AudioTrackWriteHook.watchSession(sid);
                        // 先按常见直播参数打开缓冲，真正 rate/ch 在首次 write 时校正
                        if (!StreamPcmBuffer.isActive()) {
                            StreamPcmBuffer.start(44100, 2);
                        }
                        LogUtil.log(TAG + "audioSessionId=" + sid + " → StreamPcmBuffer 已就绪");
                    } catch (Throwable t) {
                        LogUtil.log(TAG + "注册 audioSession 失败: " + t);
                    }
                    mp.start();
                    reconnectAttempts = 0;
                    if (listener != null) {
                        listener.onReady();
                    }
                } catch (Throwable t) {
                    LogUtil.log(TAG + "onPrepared 启动失败: " + t);
                    if (listener != null) {
                        listener.onError("start failed", t);
                    }
                }
            });
            ijk.setOnErrorListener((mp, what, extra) -> {
                LogUtil.log(TAG + "播放错误 what=" + what + " extra=" + extra);
                if (listener != null) {
                    listener.onError("Ijk error what=" + what + " extra=" + extra, null);
                    listener.onDisconnected();
                }
                if (currentSource != null && currentSource.autoReconnect && !released) {
                    scheduleReconnect();
                }
                return true; // 已处理，避免 Ijk 内部再 reset
            });
            ijk.setOnInfoListener((mp, what, extra) -> {
                // 10001/10002 等为缓冲与渲染信息，仅打关键节点
                if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    LogUtil.log(TAG + "首帧已渲染到 Surface ✅");
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    LogUtil.log(TAG + "缓冲开始");
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    LogUtil.log(TAG + "缓冲结束");
                } else if (what == IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED) {
                    LogUtil.log(TAG + "视频旋转角=" + extra);
                }
                return true;
            });
            ijk.setOnCompletionListener(mp -> {
                LogUtil.log(TAG + "播放完成（直播流通常不会走到这里）");
                if (listener != null) {
                    listener.onCompletion();
                }
                // 直播异常结束时尝试重连
                if (currentSource != null && currentSource.autoReconnect && !released) {
                    scheduleReconnect();
                }
            });
            ijk.setOnVideoSizeChangedListener((mp, width, height, sarNum, sarDen) -> {
                LogUtil.log(TAG + "视频尺寸: " + width + "x" + height
                        + " sar=" + sarNum + ":" + sarDen
                        + "（收到=解码器已出画）");
                // 尺寸就绪后再 setSurface 一次，并尽量把默认缓冲对齐真实分辨率，
                // 避免 SurfaceTexture 仍按 1x1/旧尺寸只吃首帧。
                if (width > 0 && height > 0 && outputSurface != null && outputSurface.isValid()) {
                    try {
                        mp.setSurface(outputSurface);
                    } catch (Throwable t) {
                        LogUtil.log(TAG + "尺寸变化后 setSurface 失败: " + t);
                    }
                }
            });

            // 先挂 Surface 再 prepare：部分 Ijk 版本若 prepare 时无 Surface 会选错 overlay 路径
            if (outputSurface != null && outputSurface.isValid()) {
                ijk.setSurface(outputSurface);
            }
            ijk.setVolume(volume, volume);
            ijk.setDataSource(source.streamUrl);
            ijk.prepareAsync();

            player = ijk;
            LogUtil.log(TAG + "开始准备: " + redactUrl(source.streamUrl)
                    + " autoReconnect=" + source.autoReconnect
                    + " surface=" + (outputSurface == null ? "null" : ("valid=" + outputSurface.isValid())));
        } catch (Throwable t) {
            LogUtil.log(TAG + "初始化失败: " + android.util.Log.getStackTraceString(t));
            if (listener != null) {
                listener.onError("Ijk init failed", t);
            }
        }
    }

    /**
     * RTMP / 低延迟直播参数，对齐 VCAMPRO {@code VideoPlayer.initRTMPStreamPlayer}，
     * 并针对「输出到 SurfaceTexture（GL 输入面）」做兼容。
     *
     * <p>卡一帧的常见原因：软解默认 overlay 格式与 SurfaceTexture 不匹配，
     * 只成功提交首帧。强制 {@code overlay-format=RV32}（RGBA）可稳定连帧。
     */
    private static void applyRtmpOptions(IjkMediaPlayer ijk, MediaSourceDescriptor source) {
        // 软解：目标机型/目标 App 进程里硬解兼容性差，VCAMPRO 默认也是 mediacodec=0
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec_mpeg4", 1);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 30L);
        // 输出到 SurfaceTexture 时必须用 RV32，否则易「只出一帧」
        // SDL_FCC_RV32 = 'RV32' = 0x32335652
        try {
            ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format",
                    IjkMediaPlayer.SDL_FCC_RV32);
        } catch (Throwable t) {
            ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", 0x32335652L);
        }
        // 不启用 overlay 渲染到独立窗口；走 ANativeWindow（我们的 Surface）
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay", 0);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        // 直播：关掉 packet-buffering，降低延迟；分析窗口给足以完成 codec 探测
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "flush_packets", 1L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 2L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15 * 1024 * 1024L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 5000L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 32 * 1024L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer");
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flags", "low_delay");
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5);
        // RTMP 超时（微秒）
        long timeoutUs = Math.max(3_000_000L, source.timeoutMs * 1000L);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", timeoutUs);
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", timeoutUs);
        // 部分推流端需要显式协议白名单
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist",
                "async,cache,crypto,file,http,https,ijkhttphook,ijkinject,ijklivehook,"
                        + "ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,rtmp,rtsp,concat");
    }

    /**
     * 预加载 Ijk 三件套 so，并通过 {@link IjkMediaPlayer#loadLibrariesOnce} 标记已加载，
     * 避免后续构造时再走目标 App ClassLoader 的 {@code System.loadLibrary} 失败。
     */
    static boolean ensureIjkLibraries(Context context) {
        if (LIBS_READY.get()) {
            return true;
        }
        synchronized (LIBS_LOCK) {
            if (LIBS_READY.get()) {
                return true;
            }
            // 先装 loadLibrary hook，再按依赖顺序加载
            boolean okFfmpeg = ModuleNativeLoader.loadLibrary(context, "ijkffmpeg");
            boolean okSdl = ModuleNativeLoader.loadLibrary(context, "ijksdl");
            boolean okPlayer = ModuleNativeLoader.loadLibrary(context, "ijkplayer");
            if (!(okFfmpeg && okSdl && okPlayer)) {
                LogUtil.log(TAG + "so 加载结果 ffmpeg=" + okFfmpeg
                        + " sdl=" + okSdl + " player=" + okPlayer);
                return false;
            }
            try {
                // 自定义 loader：已 load 的 so 直接成功，防止 Ijk 内部再 loadLibrary 抛错
                IjkLibLoader loader = libName -> {
                    if (!ModuleNativeLoader.loadLibrary(context, libName)) {
                        // 回退系统路径（LSPosed 若已注入模块 lib 目录）
                        System.loadLibrary(libName);
                    }
                };
                IjkMediaPlayer.loadLibrariesOnce(loader);
                LIBS_READY.set(true);
                LogUtil.log(TAG + "Ijk native 库就绪");
                return true;
            } catch (Throwable t) {
                LogUtil.log(TAG + "loadLibrariesOnce 失败: " + t);
                // so 其实已经 System.load 进进程了，仍可尝试直接 new
                try {
                    LIBS_READY.set(true);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
    }

    private void scheduleReconnect() {
        if (released || playerHandler == null) {
            return;
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            LogUtil.log(TAG + "重连次数已达上限 (" + MAX_RECONNECT_ATTEMPTS + ")");
            if (listener != null) {
                listener.onPermanentFailure("重连 " + MAX_RECONNECT_ATTEMPTS + " 次仍失败");
            }
            return;
        }
        reconnectAttempts++;
        long delay = BASE_RECONNECT_DELAY_MS * reconnectAttempts;
        LogUtil.log(TAG + "将在 " + delay + "ms 后第 " + reconnectAttempts + " 次重连");
        playerHandler.postDelayed(() -> {
            if (released || currentSource == null) {
                return;
            }
            openInternal(currentSource);
            if (listener != null) {
                listener.onReconnected();
            }
        }, delay);
    }

    @Override
    public void restart() {
        if (currentSource == null) {
            return;
        }
        reconnectAttempts = 0;
        postOnPlayerThread(() -> openInternal(currentSource));
    }

    @Override
    public void stop() {
        postOnPlayerThread(() -> {
            if (player != null) {
                try {
                    player.stop();
                } catch (Throwable t) {
                    LogUtil.log(TAG + "stop 异常: " + t);
                }
            }
        });
    }

    @Override
    public void release() {
        released = true;
        CountDownLatch latch = new CountDownLatch(1);
        if (playerHandler != null) {
            playerHandler.post(() -> {
                try {
                    releasePlayerInternal();
                } finally {
                    latch.countDown();
                }
            });
        } else {
            latch.countDown();
        }
        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (playerThread != null) {
            playerThread.quitSafely();
            try {
                playerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playerThread = null;
        }
        playerHandler = null;
    }

    private void releasePlayerInternal() {
        IjkMediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                AudioTrackWriteHook.unwatchSession(p.getAudioSessionId());
            } catch (Throwable ignored) {
            }
            try {
                p.setSurface(null);
            } catch (Throwable ignored) {
            }
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
            try {
                p.reset();
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable t) {
                LogUtil.log(TAG + "release 异常: " + t);
            }
        }
        // 仅当没有其它流在写时停缓冲
        if (!released) {
            // openInternal 重建前的临时释放：保持 buffer，避免 mic 断音
        } else {
            StreamPcmBuffer.stop();
            AudioTrackWriteHook.clear();
        }
    }

    @Override
    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public long getCurrentPositionMs() {
        try {
            return player != null ? player.getCurrentPosition() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public long getDurationMs() {
        try {
            if (player == null) {
                return -1L;
            }
            long d = player.getDuration();
            return d <= 0 ? -1L : d;
        } catch (Throwable t) {
            return -1L;
        }
    }

    @Override
    public void setLooping(boolean looping) {
        // 直播流不循环
        postOnPlayerThread(() -> {
            if (player != null) {
                try {
                    player.setLooping(looping);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public void setVolume(float volume) {
        this.volume = volume;
        postOnPlayerThread(() -> {
            if (player != null) {
                try {
                    player.setVolume(volume, volume);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void ensurePlayerThread() {
        if (playerThread == null || !playerThread.isAlive()) {
            playerThread = new HandlerThread("IjkPlayerBackend");
            playerThread.start();
            playerHandler = new Handler(playerThread.getLooper());
        }
    }

    private void postOnPlayerThread(Runnable r) {
        if (playerHandler != null) {
            playerHandler.post(r);
        } else if (Looper.myLooper() != null) {
            r.run();
        }
    }

    private static Context resolveContext() {
        Context ctx = HookMain.toast_content;
        if (ctx != null) {
            return ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 日志里打码流地址，避免完整 URL 进 logcat 被目标 App 扫到。 */
    private static String redactUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "***";
        }
        int slash = url.indexOf('/', scheme + 3);
        if (slash < 0) {
            return url.substring(0, Math.min(url.length(), scheme + 3 + 8)) + "…";
        }
        return url.substring(0, Math.min(slash + 1, url.length())) + "…";
    }

    private static void toast(String message) {
        try {
            HookMain.showToast("【CamSwap】" + message);
        } catch (Throwable ignored) {
        }
    }
}
