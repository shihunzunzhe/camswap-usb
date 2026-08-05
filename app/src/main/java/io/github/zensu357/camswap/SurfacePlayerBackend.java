package io.github.zensu357.camswap;

import android.view.Surface;

/**
 * Abstraction over video playback engines.
 * {@link AndroidMediaPlayerBackend} handles local files via {@link android.media.MediaPlayer}.
 * {@link ExoPlayerBackend} handles network streams via ExoPlayer (Media3).
 */
public interface SurfacePlayerBackend {

    /** Set the output Surface (GL SurfaceTexture or direct Surface). */
    void setOutputSurface(Surface surface);

    /** Open a media source and start playback. */
    void open(MediaSourceDescriptor source);

    /** Restart playback (from beginning or reconnect). */
    void restart();

    /** Stop playback. */
    void stop();

    /** Release all resources. */
    void release();

    /** Whether the player is currently playing. */
    boolean isPlaying();

    /** Current playback position in milliseconds. Streams may return 0 or live timestamp. */
    long getCurrentPositionMs();

    /** Total duration in milliseconds. Live streams return -1 (TIME_UNSET). */
    long getDurationMs();

    /** Set looping (meaningful for local files; streams ignore this). */
    void setLooping(boolean looping);

    /** Set volume (0.0 = mute, 1.0 = full). */
    void setVolume(float volume);

    /** Set lifecycle listener. */
    void setListener(Listener listener);

    interface Listener {
        /** Player is prepared and can start playback. */
        void onReady();

        /** Playback error. */
        void onError(String message, Throwable cause);

        /** Stream disconnected (stream mode only). */
        void onDisconnected();

        /** Reconnected after disconnect (stream mode only). */
        void onReconnected();

        /** Playback completed (local file mode only). */
        void onCompletion();

        /**
         * 流已彻底不可用（重连次数耗尽），调用方应切换到本地兜底。
         * 默认空实现，保持既有实现类兼容。
         */
        default void onPermanentFailure(String message) {
        }
    }
}
