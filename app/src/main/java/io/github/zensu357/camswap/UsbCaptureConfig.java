package io.github.zensu357.camswap;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * USB 采集卡（UVC）配置的值对象，负责 usb_* 配置项的 JSON 序列化与反序列化。
 * <p>
 * 对应 {@code cs_config.json} 中的字段：
 *
 * <pre>
 * {
 *   "media_source_type": "usb_capture",
 *   "usb_device_name": "/dev/bus/usb/001/003",
 *   "usb_width": 1280,
 *   "usb_height": 720,
 *   "usb_fps": 30,
 *   "usb_auto_reconnect": true
 * }
 * </pre>
 *
 * 该类同时被宿主进程（设置界面、UsbCaptureService）与目标进程（Hook 侧）使用，
 * 因此不持有任何 Context / Android 组件引用，可安全跨进程重建。
 */
public final class UsbCaptureConfig {

    /** 合法分辨率下限，避免用户/配置文件写入 0 或负数导致 UVC 开流崩溃 */
    private static final int MIN_DIMENSION = 16;
    /** 合法分辨率上限，UVC 采集卡目前不会超过 4K */
    private static final int MAX_DIMENSION = 4096;
    private static final int MIN_FPS = 1;
    private static final int MAX_FPS = 240;

    /** UVC 设备名（UsbDevice#getDeviceName）；空字符串表示自动选择第一个可用 UVC 设备 */
    public final String deviceName;
    public final int width;
    public final int height;
    public final int fps;
    public final boolean autoReconnect;

    public UsbCaptureConfig(String deviceName, int width, int height, int fps, boolean autoReconnect) {
        this.deviceName = deviceName != null ? deviceName : "";
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.autoReconnect = autoReconnect;
    }

    /** 全默认配置：自动选择设备、1280x720@30、开启自动重连。 */
    public static UsbCaptureConfig defaults() {
        return new UsbCaptureConfig("", ConfigManager.DEFAULT_USB_WIDTH, ConfigManager.DEFAULT_USB_HEIGHT,
                ConfigManager.DEFAULT_USB_FPS, ConfigManager.DEFAULT_USB_AUTO_RECONNECT);
    }

    /** 是否指定了具体设备（否则由服务自动挑选）。 */
    public boolean hasExplicitDevice() {
        return deviceName != null && !deviceName.isEmpty();
    }

    /**
     * 返回一个所有字段都已夹紧到合法区间的副本。
     * 非法值（0、负数、超大值）一律回落到默认值，保证 UVC 开流参数永远可用。
     */
    public UsbCaptureConfig normalized() {
        int w = clamp(width, MIN_DIMENSION, MAX_DIMENSION, ConfigManager.DEFAULT_USB_WIDTH);
        int h = clamp(height, MIN_DIMENSION, MAX_DIMENSION, ConfigManager.DEFAULT_USB_HEIGHT);
        int f = clamp(fps, MIN_FPS, MAX_FPS, ConfigManager.DEFAULT_USB_FPS);
        if (w == width && h == height && f == fps) {
            return this;
        }
        return new UsbCaptureConfig(deviceName, w, h, f, autoReconnect);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }

    // =====================================================================
    // 反序列化
    // =====================================================================

    /**
     * 从完整配置对象（cs_config.json 的根节点）中读取 usb_* 字段。
     * 缺失字段使用默认值，不抛异常。
     */
    public static UsbCaptureConfig fromConfigJson(JSONObject config) {
        if (config == null) {
            return defaults();
        }
        return new UsbCaptureConfig(
                config.optString(ConfigManager.KEY_USB_DEVICE_NAME, ""),
                config.optInt(ConfigManager.KEY_USB_WIDTH, ConfigManager.DEFAULT_USB_WIDTH),
                config.optInt(ConfigManager.KEY_USB_HEIGHT, ConfigManager.DEFAULT_USB_HEIGHT),
                config.optInt(ConfigManager.KEY_USB_FPS, ConfigManager.DEFAULT_USB_FPS),
                config.optBoolean(ConfigManager.KEY_USB_AUTO_RECONNECT,
                        ConfigManager.DEFAULT_USB_AUTO_RECONNECT))
                .normalized();
    }

    /**
     * 从独立的 USB 配置 JSON（{@link #toJson()} 的产物）中反序列化。
     * 同时兼容直接传入完整 cs_config.json 根节点的情况。
     */
    public static UsbCaptureConfig fromJson(JSONObject json) {
        return fromConfigJson(json);
    }

    /**
     * 从 JSON 字符串反序列化；字符串非法时返回默认配置。
     */
    public static UsbCaptureConfig fromJsonString(String json) {
        if (json == null || json.isEmpty()) {
            return defaults();
        }
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return defaults();
        }
    }

    // =====================================================================
    // 序列化
    // =====================================================================

    /**
     * 序列化为独立 JSON 对象，包含 media_source_type 与全部 usb_* 字段。
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_USB);
            writeTo(json);
        } catch (JSONException e) {
            // JSONObject.put 仅在 key 为 null / value 为 NaN 时抛出，此处不可能发生
        }
        return json;
    }

    /**
     * 将 usb_* 字段写入给定配置对象（原地修改，不改动其它配置项）。
     */
    public void writeTo(JSONObject config) throws JSONException {
        if (config == null) {
            return;
        }
        config.put(ConfigManager.KEY_USB_DEVICE_NAME, deviceName);
        config.put(ConfigManager.KEY_USB_WIDTH, width);
        config.put(ConfigManager.KEY_USB_HEIGHT, height);
        config.put(ConfigManager.KEY_USB_FPS, fps);
        config.put(ConfigManager.KEY_USB_AUTO_RECONNECT, autoReconnect);
    }

    @Override
    public String toString() {
        return "UsbCaptureConfig{device=" + (hasExplicitDevice() ? deviceName : "<auto>")
                + ", " + width + "x" + height + "@" + fps
                + ", autoReconnect=" + autoReconnect + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UsbCaptureConfig)) {
            return false;
        }
        UsbCaptureConfig other = (UsbCaptureConfig) o;
        return width == other.width
                && height == other.height
                && fps == other.fps
                && autoReconnect == other.autoReconnect
                && deviceName.equals(other.deviceName);
    }

    @Override
    public int hashCode() {
        int result = deviceName.hashCode();
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + fps;
        result = 31 * result + (autoReconnect ? 1 : 0);
        return result;
    }
}
