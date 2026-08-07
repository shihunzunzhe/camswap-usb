package io.github.zensu357.camswap;

import android.os.Environment;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ConfigManager {
    public static final String CONFIG_FILE_NAME = "cs_config.json";
    public static final String DEFAULT_CONFIG_DIR;
    static {
        String path;
        try {
            path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/";
        } catch (Throwable e) {
            path = "/sdcard/DCIM/Camera1/";
        }
        DEFAULT_CONFIG_DIR = path;
    }

    // Config Keys
    public static final String KEY_DISABLE_MODULE = "disable_module";
    public static final String KEY_PLAY_VIDEO_SOUND = "play_video_sound";
    public static final String KEY_FORCE_PRIVATE_DIR = "force_private_dir";
    public static final String KEY_DISABLE_TOAST = "disable_toast";
    public static final String KEY_ENABLE_RANDOM_PLAY = "enable_random_play";
    public static final String KEY_TARGET_PACKAGES = "target_packages";
    public static final String KEY_SELECTED_VIDEO = "selected_video";
    public static final String KEY_ORIGINAL_VIDEO_NAME = "original_video_name";
    public static final String KEY_SELECTED_IMAGE = "selected_image";
    public static final String KEY_REPLACE_MODE = "replace_mode";
    public static final String KEY_ENABLE_MIC_HOOK = "enable_mic_hook";
    public static final String KEY_MIC_HOOK_MODE = "mic_hook_mode"; // "mute" | "replace" | "video_sync" | "stream"
    public static final String KEY_SELECTED_AUDIO = "selected_audio"; // 音频文件名
    public static final String KEY_NOTIFICATION_CONTROL_ENABLED = "notification_control_enabled";
    public static final String KEY_OVERLAY_CONTROL_ENABLED = "overlay_control_enabled";
    public static final String MIC_MODE_MUTE = "mute";
    public static final String MIC_MODE_REPLACE = "replace";
    public static final String MIC_MODE_VIDEO_SYNC = "video_sync";
    /** 仅推流音频：麦克风只输出直播推流(RTMP/网络流)的声音，屏蔽真实麦克风；未推流时静音。 */
    public static final String MIC_MODE_STREAM = "stream";
    public static final String REPLACE_MODE_VIDEO = "video";
    public static final String REPLACE_MODE_IMAGE = "image";
    public static final String KEY_VIDEO_ROTATION_OFFSET = "video_rotation_offset"; // 视频旋转偏移角度
    public static final String KEY_ENABLE_PHOTO_FAKE = "enable_photo_fake"; // 启用拍照替换 (动态防御)
    public static final String KEY_ENABLE_WHATSAPP_CAMERA2_COMPAT = "enable_whatsapp_camera2_compat";

    // Stream media source keys
    public static final String KEY_MEDIA_SOURCE_TYPE = "media_source_type";       // "local" | "stream"
    public static final String KEY_STREAM_URL = "stream_url";                     // rtsp://... etc.
    public static final String KEY_STREAM_AUTO_RECONNECT = "stream_auto_reconnect";
    public static final String KEY_STREAM_LOCAL_FALLBACK = "stream_enable_local_fallback";
    public static final String KEY_STREAM_TRANSPORT_HINT = "stream_transport_hint"; // "auto" | "tcp" | "udp"
    public static final String KEY_STREAM_TIMEOUT_MS = "stream_timeout_ms";
    public static final String MEDIA_SOURCE_LOCAL = "local";
    public static final String MEDIA_SOURCE_STREAM = "stream";

    // USB capture card (UVC) media source keys
    /** media_source_type 取值：USB 采集卡（UVC）实时输入 */
    public static final String MEDIA_SOURCE_USB = "usb_capture";
    /** 与 {@link #MEDIA_SOURCE_USB} 等价的别名，保持与需求文档命名一致 */
    public static final String SOURCE_TYPE_USB = MEDIA_SOURCE_USB;
    /** UVC 设备名（UsbDevice#getDeviceName，如 /dev/bus/usb/001/002）；为空表示自动选择第一个 UVC 设备 */
    public static final String KEY_USB_DEVICE_NAME = "usb_device_name";
    public static final String KEY_USB_WIDTH = "usb_width";
    public static final String KEY_USB_HEIGHT = "usb_height";
    public static final String KEY_USB_FPS = "usb_fps";
    public static final String KEY_USB_AUTO_RECONNECT = "usb_auto_reconnect";
    /** root 免授权直连开关：开启后用 root 放开设备节点 + fd 直连，绕过 Android USB 授权 */
    public static final String KEY_USB_ROOT_BYPASS = "usb_root_bypass";

    public static final int DEFAULT_USB_WIDTH = 1280;
    public static final int DEFAULT_USB_HEIGHT = 720;
    public static final int DEFAULT_USB_FPS = 30;
    public static final boolean DEFAULT_USB_AUTO_RECONNECT = true;

    // Broadcast Actions
    public static final String ACTION_UPDATE_CONFIG = IpcContract.ACTION_UPDATE_CONFIG;
    public static final String ACTION_REQUEST_CONFIG = IpcContract.ACTION_REQUEST_CONFIG;
    public static final String EXTRA_CONFIG_JSON = IpcContract.EXTRA_CONFIG_JSON;

    // Fallback switch
    public static boolean ENABLE_LEGACY_FILE_ACCESS = true;

    private final AtomicReference<JSONObject> configData = new AtomicReference<>(new JSONObject());
    private volatile long lastLoadedTime = 0;
    private volatile android.content.Context context; // Context for remote loading
    private volatile boolean skipProviderReload = false;
    private final Object configWriteLock = new Object();

    public ConfigManager() {
        this(true);
    }

    public ConfigManager(boolean initReload) {
        if (initReload) {
            reload();
        }
    }

    public void setSkipProviderReload(boolean skip) {
        this.skipProviderReload = skip;
    }

    public void setContext(android.content.Context context) {
        this.context = context;
        reload(); // Reload with context
    }

    public JSONObject getConfigData() {
        return copyConfig(getConfigSnapshot());
    }

    private final AtomicLong lastReloadTime = new AtomicLong(0);
    private static final long MIN_RELOAD_INTERVAL_MS = 1000; // 1 second debounce

    private interface ConfigMutation {
        void apply(JSONObject config) throws JSONException;
    }

    private JSONObject getConfigSnapshot() {
        JSONObject snapshot = configData.get();
        return snapshot != null ? snapshot : new JSONObject();
    }

    private static JSONObject copyConfig(JSONObject source) {
        if (source == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(source.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void setConfigSnapshot(JSONObject snapshot) {
        configData.set(snapshot != null ? snapshot : new JSONObject());
    }

    private void updateConfigAndSave(ConfigMutation mutation) {
        synchronized (configWriteLock) {
            try {
                JSONObject updated = copyConfig(getConfigSnapshot());
                mutation.apply(updated);
                setConfigSnapshot(updated);
                save(updated);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void reload() {
        long now = System.currentTimeMillis();
        while (true) {
            long last = lastReloadTime.get();
            if (now - last < MIN_RELOAD_INTERVAL_MS) {
                return;
            }
            if (lastReloadTime.compareAndSet(last, now)) {
                break;
            }
        }

        boolean providerSuccess = false;
        if (context != null && !skipProviderReload) {
            providerSuccess = reloadFromProvider();
        }

        if (!providerSuccess && ENABLE_LEGACY_FILE_ACCESS) {
            reloadFromFile();
        }
    }

    /**
     * 强制重新加载配置，忽略防抖时间限制和文件修改时间检查。
     * 用于 ContentObserver.onChange() 等需要立即读取最新配置的场景。
     */
    public void forceReload() {
        lastReloadTime.set(0); // 重置防抖
        lastLoadedTime = 0; // 重置文件时间戳，强制重读文件
        reload();
    }

    private boolean reloadFromProvider() {
        android.net.Uri uri = IpcContract.URI_CONFIG;
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null) {
                JSONObject newConfig = new JSONObject();
                while (cursor.moveToNext()) {
                    String key = cursor.getString(0);
                    String valueStr = cursor.getString(1);
                    String type = cursor.getString(2);

                    try {
                        if ("boolean".equals(type)) {
                            newConfig.put(key, Boolean.parseBoolean(valueStr));
                        } else if ("int".equals(type)) {
                            newConfig.put(key, Integer.parseInt(valueStr));
                        } else if ("long".equals(type)) {
                            newConfig.put(key, Long.parseLong(valueStr));
                        } else if ("json_array".equals(type)) {
                            newConfig.put(key, new JSONArray(valueStr));
                        } else {
                            newConfig.put(key, valueStr);
                        }
                    } catch (Exception e) {
                        newConfig.put(key, valueStr);
                    }
                }

                if (newConfig.length() > 0) {
                    setConfigSnapshot(newConfig);
                    io.github.zensu357.camswap.utils.LogUtil.log("【CS】配置已通过 Provider 加载 (" + newConfig.length() + " keys)");
                    return true;
                } else {
                    io.github.zensu357.camswap.utils.LogUtil
                            .log("【CS】Provider Cursor 为空 (0 行), 降级到文件读取");
                }
            } else {
                io.github.zensu357.camswap.utils.LogUtil.log("【CS】Provider Cursor 为空, 降级到文件读取");
            }
        } catch (Exception e) {
            io.github.zensu357.camswap.utils.LogUtil.log("【CS】配置 Provider 错误: " + e);
        }
        return false;
    }

    /**
     * Request config from host app via broadcast.
     * Useful for cold start of target app when provider/file is inaccessible.
     */
    public void requestConfig(Context context) {
        try {
            android.content.Intent intent = new android.content.Intent(IpcContract.ACTION_REQUEST_CONFIG);
            intent.setPackage("io.github.zensu357.camswap"); // Explicit intent to wake up host receiver
            intent.putExtra(IpcContract.EXTRA_REQUESTER_PACKAGE, context.getPackageName());
            context.sendBroadcast(intent);
            io.github.zensu357.camswap.utils.LogUtil.log("【CS】已发送配置请求广播 config request broadcast sent");
        } catch (Exception e) {
            io.github.zensu357.camswap.utils.LogUtil.log("【CS】发送配置请求广播失败: " + e);
        }
    }

    /**
     * Send current config via broadcast.
     */
    public void sendConfigBroadcast(Context context) {
        sendConfigBroadcast(context, null);
    }

    public void sendConfigBroadcast(Context context, String explicitTargetPackage) {
        Set<String> targetPackages = new HashSet<>();
        if (explicitTargetPackage != null && !explicitTargetPackage.isEmpty()) {
            targetPackages.add(explicitTargetPackage);
        } else {
            targetPackages.addAll(getTargetPackages());
        }

        if (targetPackages.isEmpty()) {
            sendConfigBroadcastInternal(context, null);
            return;
        }

        for (String targetPackage : targetPackages) {
            sendConfigBroadcastInternal(context, targetPackage);
        }
    }

    private void sendConfigBroadcastInternal(Context context, String targetPackage) {
        try {
            android.content.Intent intent = new android.content.Intent(IpcContract.ACTION_UPDATE_CONFIG);
            if (targetPackage != null && !targetPackage.isEmpty()) {
                intent.setPackage(targetPackage);
            }
            intent.putExtra(IpcContract.EXTRA_CONFIG_JSON, getConfigSnapshot().toString());

            if (getBoolean(KEY_FORCE_PRIVATE_DIR, false)) {
                String videoName = getString(KEY_SELECTED_VIDEO, "Cam.mp4");
                File videoFile = null;
                if (videoName != null && !videoName.isEmpty()) {
                    videoFile = new File(DEFAULT_CONFIG_DIR, videoName);
                }
                if (videoFile == null || !videoFile.exists()) {
                    File[] files = new File(DEFAULT_CONFIG_DIR)
                            .listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4"));
                    if (files != null && files.length > 0) {
                        videoFile = files[0];
                    }
                }
                if (videoFile != null && !videoFile.exists()) {
                    videoFile = new File(DEFAULT_CONFIG_DIR, "Cam.mp4");
                }
                if (videoFile != null && videoFile.exists()) {
                    try {
                        final File finalVideoFile = videoFile;
                        android.os.Bundle bundle = new android.os.Bundle();
                        // attach video binder for private dir copy
                        bundle.putBinder(IpcContract.EXTRA_VIDEO_BINDER, new android.os.Binder() {
                            @Override
                            protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply,
                                    int flags) throws android.os.RemoteException {
                                // Binder transact request
                                if (code == 1) { // 1 = Get FD
                                    reply.writeNoException();
                                    try {
                                        android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor
                                                .open(finalVideoFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                                        reply.writeInt(1);
                                        pfd.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                                        // PFD written to reply
                                    } catch (Exception e) {
                                        io.github.zensu357.camswap.utils.LogUtil.log("【CS】Binder PFD 失败: " + e);
                                        reply.writeInt(0);
                                    }
                                    return true;
                                }
                                return super.onTransact(code, data, reply, flags);
                            }
                        });
                        intent.putExtra(IpcContract.EXTRA_VIDEO_BUNDLE, bundle);
                    } catch (Exception e) {
                        io.github.zensu357.camswap.utils.LogUtil.log("【CS】广播附加 video_bundle 失败: " + e);
                    }
                }
            }

            context.sendBroadcast(intent);
            if (targetPackage != null && !targetPackage.isEmpty()) {
                io.github.zensu357.camswap.utils.LogUtil.log("【CS】配置广播已发送到: " + targetPackage);
            } else {
                io.github.zensu357.camswap.utils.LogUtil.log("【CS】配置广播已发送");
            }
        } catch (Exception e) {
            io.github.zensu357.camswap.utils.LogUtil.log("【CS】广播配置失败: " + e);
        }
    }

    private void reloadFromFile() {
        File configFile = new File(DEFAULT_CONFIG_DIR, CONFIG_FILE_NAME);
        if (configFile.exists()) {
            long fileModTime = configFile.lastModified();
            // fileModTime==0 means we couldn't get modification time (external storage
            // restriction).
            // When lastLoadedTime==0 (forceReload triggered), always read regardless of
            // timestamp.
            boolean shouldRead = (lastLoadedTime == 0) || (fileModTime > 0 && fileModTime > lastLoadedTime);
            if (shouldRead) {
                try {
                    StringBuilder stringBuilder = new StringBuilder();
                    try (BufferedReader bufferedReader = new BufferedReader(
                            new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = bufferedReader.readLine()) != null) {
                            stringBuilder.append(line);
                        }
                    }
                    setConfigSnapshot(new JSONObject(stringBuilder.toString()));
                    lastLoadedTime = (fileModTime > 0) ? fileModTime : System.currentTimeMillis();
                    io.github.zensu357.camswap.utils.LogUtil
                            .log("【CS】配置已从文件加载: " + configFile.getName());
                } catch (Exception e) {
                    io.github.zensu357.camswap.utils.LogUtil.log("【CS】Config file read error: " + e);
                    setConfigSnapshot(getConfigSnapshot());
                }
            } else {
                // Config file unchanged, skip read
            }
        } else {
            io.github.zensu357.camswap.utils.LogUtil.log("【CS】Config file not found: " + configFile.getAbsolutePath());
            setConfigSnapshot(getConfigSnapshot());
        }
    }

    public boolean getBoolean(String key, boolean defValue) {
        return getConfigSnapshot().optBoolean(key, defValue);
    }

    public int getInt(String key, int defValue) {
        return getConfigSnapshot().optInt(key, defValue);
    }

    public void setInt(String key, int value) {
        updateConfigAndSave(config -> config.put(key, value));
    }

    public void setBoolean(String key, boolean value) {
        updateConfigAndSave(config -> config.put(key, value));
    }

    public Set<String> getTargetPackages() {
        Set<String> packages = new HashSet<>();
        JSONArray jsonArray = getConfigSnapshot().optJSONArray(KEY_TARGET_PACKAGES);
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length(); i++) {
                try {
                    packages.add(jsonArray.getString(i));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        return packages;
    }

    public void setTargetPackages(Set<String> packages) {
        JSONArray jsonArray = new JSONArray();
        for (String pkg : packages) {
            jsonArray.put(pkg);
        }
        updateConfigAndSave(config -> config.put(KEY_TARGET_PACKAGES, jsonArray));
    }

    public void addTargetPackage(String pkg) {
        Set<String> packages = getTargetPackages();
        packages.add(pkg);
        setTargetPackages(packages);
    }

    public void removeTargetPackage(String pkg) {
        Set<String> packages = getTargetPackages();
        packages.remove(pkg);
        setTargetPackages(packages);
    }

    public long getLong(String key, long defValue) {
        return getConfigSnapshot().optLong(key, defValue);
    }

    public void setLong(String key, long value) {
        updateConfigAndSave(config -> config.put(key, value));
    }

    public String getString(String key, String defValue) {
        return getConfigSnapshot().optString(key, defValue);
    }

    public void setString(String key, String value) {
        updateConfigAndSave(config -> config.put(key, value));
    }

    private void save() {
        save(getConfigSnapshot());
    }

    private void save(JSONObject snapshot) {
        File dir = new File(DEFAULT_CONFIG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File configFile = new File(dir, CONFIG_FILE_NAME);
        try {
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                fos.write(snapshot.toString(4).getBytes(StandardCharsets.UTF_8));
            }

            // Set world-readable so hook processes (inside target apps) can read
            // the config file via direct path when ContentProvider is unavailable.
            try {
                configFile.setReadable(true, false);
                configFile.setWritable(true, true); // Keep write restricted to owner
                // Also chmod parents so directory is traversable
                dir.setExecutable(true, false);
                dir.setReadable(true, false);
            } catch (Exception ignored) {
                // Best-effort
            }

            // Notify ContentObserver and broadcast changes
            if (context != null) {
                try {
                    context.getContentResolver().notifyChange(IpcContract.URI_CONFIG, null);
                } catch (Exception ignored) {
                }
                sendConfigBroadcast(context);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Migration logic
    public boolean migrateIfNeeded() {
        boolean migrated = false;
        File dir = new File(DEFAULT_CONFIG_DIR);

        // Map old files to new keys
        String[][] fileToKey = {
                { "disable.jpg", KEY_DISABLE_MODULE },
                { "no-silent.jpg", KEY_PLAY_VIDEO_SOUND },
                { "private_dir.jpg", KEY_FORCE_PRIVATE_DIR },
                { "no_toast.jpg", KEY_DISABLE_TOAST }
        };

        for (String[] map : fileToKey) {
            File oldFile = new File(dir, map[0]);
            if (oldFile.exists()) {
                setBoolean(map[1], true);
                oldFile.delete();
                migrated = true;
            }
        }

        return migrated;
    }

    public void resetToDefault() {
        synchronized (configWriteLock) {
            JSONObject updated = new JSONObject();
            setConfigSnapshot(updated);
            save(updated);
        }
    }

    public String exportConfig() {
        return getConfigSnapshot().toString();
    }

    public void importConfig(String json) throws JSONException {
        synchronized (configWriteLock) {
            JSONObject updated = new JSONObject(json);
            setConfigSnapshot(updated);
            save(updated);
        }
    }

    // =====================================================================
    // USB capture (UVC) configuration — JSON 序列化 / 反序列化
    // =====================================================================

    /** 当前 media_source_type 是否为 usb_capture。 */
    public boolean isUsbCaptureMode() {
        return MEDIA_SOURCE_USB.equals(getString(KEY_MEDIA_SOURCE_TYPE, MEDIA_SOURCE_LOCAL));
    }

    /**
     * 从当前配置快照中反序列化出 USB 采集卡配置。
     * 缺失字段一律回落到默认值（1280x720@30，自动重连开启）。
     */
    public UsbCaptureConfig getUsbCaptureConfig() {
        return UsbCaptureConfig.fromConfigJson(getConfigSnapshot());
    }

    /**
     * 将 USB 采集卡配置写回配置文件（单次写盘，避免逐字段多次落盘与多次广播）。
     */
    public void setUsbCaptureConfig(UsbCaptureConfig usbConfig) {
        if (usbConfig == null) {
            return;
        }
        final UsbCaptureConfig normalized = usbConfig.normalized();
        updateConfigAndSave(config -> normalized.writeTo(config));
    }

    /**
     * 导出 USB 相关配置为独立 JSON 字符串（仅包含 usb_* 与 media_source_type 字段）。
     */
    public String exportUsbConfig() {
        return getUsbCaptureConfig().toJson().toString();
    }

    /**
     * 从 JSON 字符串导入 USB 配置并落盘。
     *
     * @return 解析成功返回 true；JSON 非法返回 false（此时配置保持不变）
     */
    public boolean importUsbConfig(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        try {
            UsbCaptureConfig parsed = UsbCaptureConfig.fromJson(new JSONObject(json));
            setUsbCaptureConfig(parsed);
            return true;
        } catch (JSONException e) {
            io.github.zensu357.camswap.utils.LogUtil.log("【CS】【usb】解析 USB 配置 JSON 失败: " + e);
            return false;
        }
    }

    /**
     * Parse config from JSON string and update memory cache.
     * Does NOT save to file to avoid EACCES errors in target app.
     */
    public void updateConfigFromJSON(String json) {
        try {
            JSONObject updated = new JSONObject(json);
            setConfigSnapshot(updated);
            // Update timestamps to prevent reloadFromFile from overwriting
            long now = System.currentTimeMillis();
            lastLoadedTime = now;
            lastReloadTime.set(now);
            io.github.zensu357.camswap.utils.LogUtil.log("【CS】已通过广播更新内存配置");
        } catch (JSONException e) {
            io.github.zensu357.camswap.utils.LogUtil.log("【CS】解析广播配置失败: " + e);
        }
    }
}
