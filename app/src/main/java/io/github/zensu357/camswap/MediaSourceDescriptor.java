package io.github.zensu357.camswap;

/**
 * Unified media source descriptor — abstracts local file, network stream and
 * USB capture card (UVC) sources.
 * Used by HookGuards, VideoManager, MediaPlayerManager, and player backends
 * to decide playback strategy without scattering type checks across the codebase.
 */
public final class MediaSourceDescriptor {
    public enum Type {
        LOCAL_FILE,
        STREAM_URL,
        /** USB 采集卡（UVC）实时输入，画面由宿主 UsbCaptureService 跨进程推送 */
        USB_CAPTURE
    }

    public final Type type;
    /** Local mode: video file path; stream mode: null */
    public final String localPath;
    /** Stream mode: stream URL (rtsp/rtmp/http/https); local mode: null */
    public final String streamUrl;
    /** Local mode: whether to use Provider PFD */
    public final boolean useProviderPfd;

    // ---- Stream mode parameters ----
    /** Auto-reconnect on stream disconnect */
    public final boolean autoReconnect;
    /** Fall back to local video when stream is unavailable */
    public final boolean enableLocalFallback;
    /** RTSP transport hint: auto / tcp / udp */
    public final String transportHint;
    /** Connection timeout in milliseconds */
    public final long timeoutMs;

    // ---- USB capture mode parameters ----
    /** USB 模式：采集卡配置（设备名 / 分辨率 / 帧率 / 自动重连）；其它模式为 null */
    public final UsbCaptureConfig usbConfig;

    private MediaSourceDescriptor(Builder builder) {
        this.type = builder.type;
        this.localPath = builder.localPath;
        this.streamUrl = builder.streamUrl;
        this.useProviderPfd = builder.useProviderPfd;
        this.autoReconnect = builder.autoReconnect;
        this.enableLocalFallback = builder.enableLocalFallback;
        this.transportHint = builder.transportHint;
        this.timeoutMs = builder.timeoutMs;
        this.usbConfig = builder.usbConfig;
    }

    public boolean isStream() {
        return type == Type.STREAM_URL;
    }

    public boolean isUsbCapture() {
        return type == Type.USB_CAPTURE;
    }

    public boolean isValid() {
        if (type == Type.LOCAL_FILE) {
            return localPath != null && !localPath.isEmpty();
        } else if (type == Type.USB_CAPTURE) {
            // USB 模式无需本地文件；设备是否在线由宿主服务负责，这里只校验参数完整
            return usbConfig != null && usbConfig.width > 0 && usbConfig.height > 0;
        } else {
            return streamUrl != null && !streamUrl.isEmpty();
        }
    }

    public static Builder localFile(String path) {
        return new Builder(Type.LOCAL_FILE).localPath(path);
    }

    public static Builder stream(String url) {
        return new Builder(Type.STREAM_URL).streamUrl(url);
    }

    public static Builder usbCapture(UsbCaptureConfig config) {
        return new Builder(Type.USB_CAPTURE)
                .usbConfig(config != null ? config : UsbCaptureConfig.defaults());
    }

    public static class Builder {
        Type type;
        String localPath;
        String streamUrl;
        boolean useProviderPfd;
        boolean autoReconnect = true;
        boolean enableLocalFallback = true;
        String transportHint = "auto";
        long timeoutMs = 8000L;
        UsbCaptureConfig usbConfig;

        Builder(Type type) {
            this.type = type;
        }

        public Builder usbConfig(UsbCaptureConfig v) {
            this.usbConfig = v;
            return this;
        }

        public Builder localPath(String v) {
            this.localPath = v;
            return this;
        }

        public Builder streamUrl(String v) {
            this.streamUrl = v;
            return this;
        }

        public Builder useProviderPfd(boolean v) {
            this.useProviderPfd = v;
            return this;
        }

        public Builder autoReconnect(boolean v) {
            this.autoReconnect = v;
            return this;
        }

        public Builder enableLocalFallback(boolean v) {
            this.enableLocalFallback = v;
            return this;
        }

        public Builder transportHint(String v) {
            this.transportHint = v;
            return this;
        }

        public Builder timeoutMs(long v) {
            this.timeoutMs = v;
            return this;
        }

        public MediaSourceDescriptor build() {
            return new MediaSourceDescriptor(this);
        }
    }
}
