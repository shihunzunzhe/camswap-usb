package io.github.zensu357.camswap;

import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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

            if (outputSurface != null) {
                player.setVideoSurface(outputSurface);
            }

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        reconnectAttempts = 0;
                        if (listener != null) listener.onReady();
                    } else if (playbackState == Player.STATE_ENDED) {
                        if (listener != null) listener.onCompletion();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    LogUtil.log("【CS】ExoPlayer 播放错误: " + error.getMessage()
                            + " code=" + error.errorCode);
                    if (listener != null) {
                        listener.onError(error.getMessage(), error);
                        listener.onDisconnected();
                    }
                    if (currentSource != null && currentSource.autoReconnect) {
                        scheduleReconnect();
                    }
                }
            });

            MediaSource mediaSource = buildMediaSource(source);
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();

            LogUtil.log("【CS】ExoPlayer 开始播放: " + source.streamUrl);
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

        if ("rtmp".equalsIgnoreCase(scheme)) {
            // Media3 的 RTMP 是 DataSource，配合 ProgressiveMediaSource 使用
            try {
                return new ProgressiveMediaSource.Factory(new RtmpDataSource.Factory())
                        .createMediaSource(MediaItem.fromUri(uri));
            } catch (Throwable t) {
                LogUtil.log("【CS】RTMP DataSource 不可用: " + t);
                throw new IllegalStateException("RTMP 不可用，请确认已打包 media3-datasource-rtmp", t);
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

    @Override
    public void restart() {
        if (currentSource == null) return;
        reconnectAttempts = 0;
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
                player.stop();
                player.release();
            } catch (Exception e) {
                LogUtil.log("【CS】ExoPlayer release 异常: " + e);
            }
            player = null;
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
