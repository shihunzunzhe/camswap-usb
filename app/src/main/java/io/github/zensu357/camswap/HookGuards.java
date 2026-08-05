package io.github.zensu357.camswap;

import java.io.File;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

public final class HookGuards {
    private HookGuards() {
    }

    public static File resolveVideoFile(boolean forceRandom) {
        VideoManager.updateVideoPath(forceRandom);
        return getCurrentVideoFile();
    }

    public static File getCurrentVideoFile() {
        String currentPath = VideoManager.getCurrentVideoPath();
        if (currentPath == null || currentPath.isEmpty()) {
            return new File(VideoManager.video_path, VideoManager.CAM_VIDEO_NAME);
        }
        return new File(currentPath);
    }

    // ---- Legacy overload (File-based) — kept for compatibility ----

    public static boolean shouldBypass(String packageName, File videoFile) {
        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            return true;
        }
        // Stream / USB capture mode: delegate to MediaSourceDescriptor-based check
        if (VideoManager.isStreamMode() || VideoManager.isUsbCaptureMode()) {
            return shouldBypass(packageName, VideoManager.getCurrentMediaSource());
        }
        return shouldBypassMissingVideo(packageName, videoFile);
    }

    // ---- New overload (MediaSourceDescriptor-based) ----

    public static boolean shouldBypass(String packageName, MediaSourceDescriptor source) {
        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            return true;
        }
        HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);

        if (source == null || !source.isValid()) {
            logMissingMediaSource(packageName);
            return true;
        }
        // USB capture mode: 设备在线与否由宿主服务负责重连，Hook 侧一律放行，
        // 否则采集卡短暂掉线就会导致目标应用回落到真实摄像头。
        if (source.isUsbCapture()) {
            return false;
        }
        // Local mode: check file existence
        if (!source.isStream()) {
            return shouldBypassMissingVideo(packageName, source.localPath != null ? new File(source.localPath) : null);
        }
        // Stream mode: URL is non-empty, allow through (connection state handled by player)
        return false;
    }

    public static boolean shouldBypassMissingVideo(String packageName, File videoFile) {
        HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
        if (HookMain.toast_content != null) {
            try {
                File privateVideo = new File(HookMain.toast_content.getFilesDir(), "vcam_private.mp4");
                if (privateVideo.exists() && privateVideo.isFile() && privateVideo.length() > 0) {
                    return false;
                }
            } catch (Exception ignored) {
            }
        }
        if (videoFile != null && videoFile.exists()) {
            return false;
        }
        logMissingVideo(packageName, videoFile);
        return true;
    }

    public static void logMissingVideo(String packageName, File videoFile) {
        if (HookMain.toast_content == null || !HookMain.need_to_show_toast) {
            return;
        }

        String resolvedPackageName = packageName;
        if (resolvedPackageName == null || resolvedPackageName.isEmpty()) {
            resolvedPackageName = HookMain.toast_content.getPackageName();
        }

        try {
            LogUtil.log("【CS】不存在替换视频: " + resolvedPackageName + " 当前路径：" + getDisplayPath(videoFile));
        } catch (Exception e) {
            LogUtil.log("【CS】[toast]" + e);
        }
    }

    private static void logMissingMediaSource(String packageName) {
        if (HookMain.toast_content == null || !HookMain.need_to_show_toast) {
            return;
        }
        String resolvedPackageName = packageName;
        if (resolvedPackageName == null || resolvedPackageName.isEmpty()) {
            resolvedPackageName = HookMain.toast_content.getPackageName();
        }
        try {
            LogUtil.log("【CS】无可用媒体源: " + resolvedPackageName);
        } catch (Exception e) {
            LogUtil.log("【CS】[toast]" + e);
        }
    }

    private static String getDisplayPath(File videoFile) {
        if (videoFile != null) {
            return videoFile.getAbsolutePath();
        }
        return VideoManager.video_path;
    }
}
