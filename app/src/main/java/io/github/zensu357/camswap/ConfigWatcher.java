package io.github.zensu357.camswap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Watches for configuration changes via ContentObserver, FileObserver, and
 * BroadcastReceiver, then notifies via {@link Callback}.
 */
public final class ConfigWatcher {

    public interface Callback {
        void onMediaSourceChanged();

        void onRotationChanged(int degrees);
    }

    private final Callback callback;
    private android.database.ContentObserver configObserver;
    private FileObserver configFileObserver;

    public ConfigWatcher(Callback callback) {
        this.callback = callback;
    }

    /**
     * Register all observers and receivers.
     * Must be called from a thread with a Looper (typically main thread).
     */
    public void init(final Context context) {
        if (configObserver != null)
            return; // already initialized

        LogUtil.log("【CS】初始化配置监听");
        configObserver = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                LogUtil.log("【CS】Provider 配置变更");
                VideoManager.getConfig().forceReload();
                VideoManager.updateVideoPath(false);
                callback.onMediaSourceChanged();
            }
        };

        boolean observerRegistered = false;
        try {
            context.getContentResolver().registerContentObserver(IpcContract.URI_CONFIG, true, configObserver);
            observerRegistered = true;
        } catch (Exception e) {
            LogUtil.log("【CS】注册 ContentObserver 失败: " + e);
        }

        // Fallback: FileObserver when Provider unavailable
        if (!observerRegistered) {
            LogUtil.log("【CS】降级到 FileObserver 监听");
            try {
                String configDir = ConfigManager.DEFAULT_CONFIG_DIR;
                configFileObserver = new FileObserver(configDir,
                        FileObserver.MODIFY | FileObserver.CREATE | FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path != null && path.endsWith(".json")) {
                            LogUtil.log("【CS】文件变更: " + path);
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                VideoManager.getConfig().forceReload();
                                VideoManager.updateVideoPath(false);
                                callback.onMediaSourceChanged();
                            }, 200);
                        }
                    }
                };
                configFileObserver.startWatching();
                LogUtil.log("【CS】FileObserver 已启动: " + configDir);
            } catch (Exception e) {
                LogUtil.log("【CS】FileObserver 启动失败: " + e);
            }

            // Active Config Request
            // request config via broadcast
            new Handler(Looper.getMainLooper()).postDelayed(() -> VideoManager.getConfig().requestConfig(context),
                    1000);
        }

        // BroadcastReceiver for control signals
        registerBroadcastReceiver(context);
    }

    private void registerBroadcastReceiver(final Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    // received broadcast action

                    if (IpcContract.ACTION_UPDATE_CONFIG.equals(action)) {
                        handleConfigUpdate(intent);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(IpcContract.ACTION_UPDATE_CONFIG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            LogUtil.log("【CS】广播接收器已注册");
        } catch (Exception e) {
            LogUtil.log("【CS】注册广播接收器失败: " + e);
        }
    }

    private void handleConfigUpdate(Intent intent) {
        ConfigManager config = VideoManager.getConfig();
        String configJson = intent.getStringExtra(IpcContract.EXTRA_CONFIG_JSON);
        if (configJson == null)
            return;

        // Snapshot old values
        String oldVideo = config.getString(ConfigManager.KEY_SELECTED_VIDEO, "");
        String oldImage = config.getString(ConfigManager.KEY_SELECTED_IMAGE, "");
        String oldMode = config.getString(ConfigManager.KEY_REPLACE_MODE, ConfigManager.REPLACE_MODE_VIDEO);
        boolean oldFpd = config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
        int oldRotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        // Stream config snapshots
        String oldSourceType = config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL);
        String oldStreamUrl = config.getString(ConfigManager.KEY_STREAM_URL, "");
        // USB capture config snapshot
        UsbCaptureConfig oldUsbConfig = config.getUsbCaptureConfig();

        config.updateConfigFromJSON(configJson);

        // Snapshot new values
        String newVideo = config.getString(ConfigManager.KEY_SELECTED_VIDEO, "");
        String newImage = config.getString(ConfigManager.KEY_SELECTED_IMAGE, "");
        String newMode = config.getString(ConfigManager.KEY_REPLACE_MODE, ConfigManager.REPLACE_MODE_VIDEO);
        boolean newFpd = config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
        int newRotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        // Stream config new values
        String newSourceType = config.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL);
        String newStreamUrl = config.getString(ConfigManager.KEY_STREAM_URL, "");
        UsbCaptureConfig newUsbConfig = config.getUsbCaptureConfig();

        boolean usbChanged = !oldUsbConfig.equals(newUsbConfig);
        boolean mediaChanged = !oldVideo.equals(newVideo) ||
                !oldImage.equals(newImage) ||
                !oldMode.equals(newMode) ||
                (oldFpd != newFpd) ||
                !oldSourceType.equals(newSourceType) ||
                !oldStreamUrl.equals(newStreamUrl) ||
                usbChanged;

        if (usbChanged) {
            LogUtil.log("【CS】【usb】配置更新: " + oldUsbConfig + " -> " + newUsbConfig);
        }

        if (mediaChanged) {
            // Handle Binder-based video file transfer
            if (config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false)) {
                extractVideoFromBinder(intent);
            }
            VideoManager.updateVideoPath(false);
            callback.onMediaSourceChanged();
            LogUtil.log("【CS】配置更新: 媒体源变化，重启播放器");
        } else if (oldRotation != newRotation) {
            LogUtil.log("【CS】配置更新: 旋转 " + newRotation + "°");
            callback.onRotationChanged(newRotation);
        } else {
            LogUtil.log("【CS】配置更新: 无变化");
        }
    }

    private void extractVideoFromBinder(Intent intent) {
        android.os.Bundle bundle = intent.getBundleExtra(IpcContract.EXTRA_VIDEO_BUNDLE);
        if (bundle == null) {
            return;
        }
        android.os.IBinder binder = bundle.getBinder(IpcContract.EXTRA_VIDEO_BINDER);
        if (binder == null) {
            LogUtil.log("【CS】video_binder 为 null");
            return;
        }
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            binder.transact(1, data, reply, 0);
            reply.readException();
            int hasFd = reply.readInt();
            if (hasFd != 0) {
                android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.CREATOR.createFromParcel(reply);
                if (pfd != null) {
                    LogUtil.log("【CS】Binder 视频 FD 已获取，拷贝到私有目录");
                    VideoManager.copyToPrivateDir(pfd);
                    pfd.close();
                } else {
                    LogUtil.log("【CS】Binder PFD 为 null");
                }
            }
        } catch (Exception e) {
            LogUtil.log("【CS】Binder 获取 FD 失败: " + e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

}
