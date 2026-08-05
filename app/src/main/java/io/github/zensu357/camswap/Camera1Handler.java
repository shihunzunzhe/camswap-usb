package io.github.zensu357.camswap;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Collections;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.zensu357.camswap.api101.Api101Runtime;

import io.github.zensu357.camswap.utils.VideoManager;
import io.github.zensu357.camswap.utils.LogUtil;

public class Camera1Handler implements ICameraHandler {
    private static final Set<String> hookedPreviewCallbackClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Object previewFrameLock = new Object();
    private static final long PREVIEW_FRAME_WAIT_MS = 100L;

    @Override
    public void init(final Api101PackageContext packageContext) {
        final ClassLoader classLoader = packageContext.classLoader;
        final String packageName = packageContext.packageName;
        hookSetPreviewTexture(classLoader, packageName);
        hookStartPreview(classLoader, packageName);
        hookSetPreviewDisplay(classLoader, packageName);
        hookSetDisplayOrientation(classLoader);
        hookSetPreviewCallbackWithBuffer(classLoader);
        hookAddCallbackBuffer(classLoader);
        hookSetPreviewCallback(classLoader);
        hookSetOneShotPreviewCallback(classLoader);
        hookStopPreview(classLoader);
        hookRelease(classLoader);
        hookTakePicture(classLoader);
    }

    private void hookSetPreviewTexture(ClassLoader classLoader, String packageName) {
        hookCameraMethod(classLoader, "setPreviewTexture", new Class<?>[] { SurfaceTexture.class }, chain -> {
            Object[] args = toArgs(chain.getArgs());
            try {
                if (HookMain.origin_preview_camera == null
                        || !HookMain.origin_preview_camera.equals(chain.getThisObject())) {
                    VideoManager.updateVideoPath(true);
                }
                File file = HookGuards.getCurrentVideoFile();
                if (!HookGuards.shouldBypass(packageName, file)) {
                    if (HookMain.is_hooked) {
                        HookMain.is_hooked = false;
                        return chain.proceed(args);
                    }
                    if (args[0] == null) {
                        return chain.proceed(args);
                    }
                    if (args[0].equals(HookMain.c1_fake_texture)) {
                        return chain.proceed(args);
                    }

                    if (HookMain.origin_preview_camera != null
                            && HookMain.origin_preview_camera.equals(chain.getThisObject())) {
                        args[0] = HookMain.fake_SurfaceTexture;
                        LogUtil.log("【CS】发现重复" + HookMain.origin_preview_camera.toString());
                        return chain.proceed(args);
                    } else {
                        LogUtil.log("【CS】创建预览");
                    }

                    HookMain.origin_preview_camera = (Camera) chain.getThisObject();
                    HookMain.mSurfacetexture = (SurfaceTexture) args[0];
                    if (HookMain.fake_SurfaceTexture == null) {
                        HookMain.fake_SurfaceTexture = new SurfaceTexture(10);
                    } else {
                        HookMain.fake_SurfaceTexture.release();
                        HookMain.fake_SurfaceTexture = new SurfaceTexture(10);
                    }
                    args[0] = HookMain.fake_SurfaceTexture;
                }
            } catch (Throwable t) {
                LogUtil.log("【CS】setPreviewTexture before 异常: " + t);
            }
            return chain.proceed(args);
        });
    }

    private void hookStartPreview(ClassLoader classLoader, String packageName) {
        hookCameraMethod(classLoader, "startPreview", new Class<?>[0], chain -> {
            Object[] args = toArgs(chain.getArgs());
            try {
                File file = HookGuards.getCurrentVideoFile();
                if (!HookGuards.shouldBypass(packageName, file)) {
                    HookMain.is_someone_playing = false;
                    LogUtil.log("【CS】开始预览");
                    HookMain.start_preview_camera = (Camera) chain.getThisObject();

                    try {
                        android.hardware.Camera.Parameters params = HookMain.start_preview_camera.getParameters();
                        android.hardware.Camera.Size size = params.getPreviewSize();
                        if (size != null) {
                            if (HookMain.mSurfacetexture != null) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                                    HookMain.mSurfacetexture.setDefaultBufferSize(size.width, size.height);
                                }
                                LogUtil.log("【CS】修正目标 SurfaceTexture 尺寸为: " + size.width + "x" + size.height);
                            }
                            if (HookMain.ori_holder != null) {
                                LogUtil.log("【CS】SurfaceHolder 保持原始尺寸，预览尺寸: " + size.width + "x" + size.height);
                            }
                        }
                    } catch (Exception e) {
                        LogUtil.log("【CS】修正 Surface 尺寸异常: " + e.getMessage());
                    }

                    if (HookMain.ori_holder != null) {
                        prepareHolderPreviewPlayer();
                    }

                    if (HookMain.mSurfacetexture != null) {
                        prepareTexturePreviewPlayer();
                    }
                }
            } catch (Throwable t) {
                LogUtil.log("【CS】startPreview before 异常: " + t);
            }
            return chain.proceed(args);
        });
    }

    private void hookSetPreviewDisplay(ClassLoader classLoader, String packageName) {
        hookCameraMethod(classLoader, "setPreviewDisplay", new Class<?>[] { SurfaceHolder.class }, chain -> {
            Object[] args = toArgs(chain.getArgs());
            try {
                LogUtil.log("【CS】添加Surfaceview预览");
                File file = HookGuards.resolveVideoFile(true);
                if (HookGuards.shouldBypass(packageName, file)) {
                    return chain.proceed(args);
                }
                HookMain.mcamera1 = (Camera) chain.getThisObject();
                HookMain.ori_holder = (SurfaceHolder) args[0];
                if (HookMain.mSurfacetexture != null) {
                    HookMain.mSurfacetexture = null;
                }
                if (HookMain.mSurface != null) {
                    HookMain.mSurface.release();
                    HookMain.mSurface = null;
                }
                if (HookMain.c1_fake_texture == null) {
                    HookMain.c1_fake_texture = new SurfaceTexture(11);
                } else {
                    HookMain.c1_fake_texture.release();
                    HookMain.c1_fake_texture = null;
                    HookMain.c1_fake_texture = new SurfaceTexture(11);
                }

                if (HookMain.c1_fake_surface == null) {
                    HookMain.c1_fake_surface = new Surface(HookMain.c1_fake_texture);
                } else {
                    HookMain.c1_fake_surface.release();
                    HookMain.c1_fake_surface = null;
                    HookMain.c1_fake_surface = new Surface(HookMain.c1_fake_texture);
                }
                HookMain.is_hooked = true;
                HookMain.mcamera1.setPreviewTexture(HookMain.c1_fake_texture);
                return null;
            } catch (Throwable t) {
                LogUtil.log("【CS】setPreviewDisplay before 异常: " + t);
                return chain.proceed(args);
            }
        });
    }

    private void hookTakePicture(ClassLoader classLoader) {
        hookCameraTakePicture(classLoader,
                new Class<?>[] { Camera.ShutterCallback.class, Camera.PictureCallback.class,
                        Camera.PictureCallback.class });
        hookCameraTakePicture(classLoader,
                new Class<?>[] { Camera.ShutterCallback.class, Camera.PictureCallback.class,
                        Camera.PictureCallback.class, Camera.PictureCallback.class });
    }

    private void hookCameraTakePicture(ClassLoader classLoader, Class<?>[] parameterTypes) {
        hookCameraMethod(classLoader, "takePicture", parameterTypes, chain -> {
            Object[] args = toArgs(chain.getArgs());
            if (handlePhotoFake((Camera) chain.getThisObject(), args)) {
                return null;
            }
            return chain.proceed(args);
        });
    }

    private void prepareHolderPreviewPlayer() {
        // USB 采集卡模式：不创建 MediaPlayer，直接把 GL 渲染器的输入 Surface 交给宿主服务
        if (CameraHandlerPatch.isUsbCaptureMode()) {
            if (HookMain.ori_holder == null || !HookMain.ori_holder.getSurface().isValid()) {
                LogUtil.log("【CS】【usb】Camera1 holder Surface 无效，跳过");
                return;
            }
            if (CameraHandlerPatch.attachCamera1Holder(HookMain.playerManager,
                    HookMain.ori_holder.getSurface())) {
                return;
            }
            LogUtil.log("【CS】【usb】Camera1 holder 接管失败，回退到本地视频");
        }

        // 网络流模式：由 ExoPlayer 驱动 GL 渲染器，同样不需要本地 MediaPlayer
        if (VideoManager.isStreamMode()) {
            if (HookMain.ori_holder == null || !HookMain.ori_holder.getSurface().isValid()) {
                LogUtil.log("【CS】Camera1 holder Surface 无效，跳过流模式");
                return;
            }
            if (HookMain.playerManager.initCamera1Stream(HookMain.ori_holder.getSurface(), true)) {
                return;
            }
            LogUtil.log("【CS】Camera1 holder 流模式初始化失败，回退到本地视频");
        }

        if (HookMain.playerManager.mplayer1 == null) {
            HookMain.playerManager.mplayer1 = new MediaPlayer();
        } else {
            HookMain.playerManager.mplayer1.release();
            HookMain.playerManager.mplayer1 = null;
            HookMain.playerManager.mplayer1 = new MediaPlayer();
        }
        if (HookMain.ori_holder == null || !HookMain.ori_holder.getSurface().isValid()) {
            return;
        }
        GLVideoRenderer.releaseSafely(HookMain.playerManager.c1_renderer_holder);
        GLVideoRenderer renderer = GLVideoRenderer.createSafely(HookMain.ori_holder.getSurface(), "c1_holder");
        HookMain.playerManager.c1_renderer_holder = renderer;
        if (renderer != null && renderer.isInitialized()) {
            HookMain.playerManager.mplayer1.setSurface(renderer.getInputSurface());
            int rotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
            renderer.setRotation(rotation);
        } else {
            HookMain.playerManager.mplayer1.setSurface(HookMain.ori_holder.getSurface());
        }
        boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);
        if (!(playSound && (!HookMain.is_someone_playing))) {
            HookMain.playerManager.mplayer1.setVolume(0, 0);
            HookMain.is_someone_playing = false;
        } else {
            HookMain.is_someone_playing = true;
        }
        HookMain.playerManager.mplayer1.setLooping(true);
        HookMain.playerManager.mplayer1.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                HookMain.playerManager.mplayer1.start();
            }
        });

        try {
            android.os.ParcelFileDescriptor pfd = HookMain.getVideoPFD();
            if (pfd != null) {
                HookMain.playerManager.mplayer1.setDataSource(pfd.getFileDescriptor());
                pfd.close();
            } else {
                HookMain.playerManager.mplayer1.setDataSource(VideoManager.getCurrentVideoPath());
            }
            HookMain.playerManager.mplayer1.prepare();
        } catch (Exception e) {
            LogUtil.log("【CS】mplayer1 prepare 异常: " + e.toString());
        }
    }

    private void prepareTexturePreviewPlayer() {
        if (HookMain.mSurface == null) {
            HookMain.mSurface = new Surface(HookMain.mSurfacetexture);
        } else {
            HookMain.mSurface.release();
            HookMain.mSurface = new Surface(HookMain.mSurfacetexture);
        }

        // USB 采集卡模式：不创建 MediaPlayer，直接把 GL 渲染器的输入 Surface 交给宿主服务
        if (CameraHandlerPatch.isUsbCaptureMode()) {
            if (CameraHandlerPatch.attachCamera1Texture(HookMain.playerManager, HookMain.mSurface)) {
                return;
            }
            LogUtil.log("【CS】【usb】Camera1 texture 接管失败，回退到本地视频");
        }

        // 网络流模式：由 ExoPlayer 驱动 GL 渲染器
        if (VideoManager.isStreamMode()) {
            if (HookMain.playerManager.initCamera1Stream(HookMain.mSurface, false)) {
                return;
            }
            LogUtil.log("【CS】Camera1 texture 流模式初始化失败，回退到本地视频");
        }

        if (HookMain.playerManager.mMediaPlayer == null) {
            HookMain.playerManager.mMediaPlayer = new MediaPlayer();
        } else {
            HookMain.playerManager.mMediaPlayer.release();
            HookMain.playerManager.mMediaPlayer = new MediaPlayer();
        }

        GLVideoRenderer.releaseSafely(HookMain.playerManager.c1_renderer_texture);
        GLVideoRenderer renderer = GLVideoRenderer.createSafely(HookMain.mSurface, "c1_texture");
        HookMain.playerManager.c1_renderer_texture = renderer;
        if (renderer != null && renderer.isInitialized()) {
            HookMain.playerManager.mMediaPlayer.setSurface(renderer.getInputSurface());
            int rotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
            renderer.setRotation(rotation);
        } else {
            HookMain.playerManager.mMediaPlayer.setSurface(HookMain.mSurface);
        }

        boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);
        if (!(playSound && (!HookMain.is_someone_playing))) {
            HookMain.playerManager.mMediaPlayer.setVolume(0, 0);
            HookMain.is_someone_playing = false;
        } else {
            HookMain.is_someone_playing = true;
        }
        HookMain.playerManager.mMediaPlayer.setLooping(true);
        HookMain.playerManager.mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                HookMain.playerManager.mMediaPlayer.start();
            }
        });

        try {
            android.os.ParcelFileDescriptor pfd = HookMain.getVideoPFD();
            if (pfd != null) {
                HookMain.playerManager.mMediaPlayer.setDataSource(pfd.getFileDescriptor());
                pfd.close();
            } else {
                HookMain.playerManager.mMediaPlayer.setDataSource(VideoManager.getCurrentVideoPath());
            }
            HookMain.playerManager.mMediaPlayer.prepare();
        } catch (Exception e) {
            LogUtil.log("【CS】mMediaPlayer prepare 异常: " + e.toString());
        }
    }

    private boolean handlePhotoFake(Camera camera, Object[] args) {
        if (!VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)) {
            return false;
        }
        LogUtil.log("【CS】Camera1 takePicture 触发，启动动态防御机制");

        Camera.PictureCallback jpegCallback = null;
        if (args.length == 3) {
            jpegCallback = (Camera.PictureCallback) args[2];
        } else if (args.length == 4) {
            jpegCallback = (Camera.PictureCallback) args[3];
        }

        if (jpegCallback != null) {
            byte[] jpegData = buildPhotoFakeJpeg(camera);
            if (jpegData != null) {
                try {
                    jpegCallback.onPictureTaken(jpegData, camera);
                } catch (Exception e) {
                    LogUtil.log("【CS】Camera1 主动回调 PictureCallback 失败: " + e);
                }
            }
        }
        return true;
    }

    private byte[] buildPhotoFakeJpeg(Camera camera) {
        ensureCameraSize(camera);

        byte[] nv21 = HookMain.data_buffer;
        byte[] jpegData = null;

        if (nv21 != null && nv21.length >= 1) {
            try {
                android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(nv21,
                        android.graphics.ImageFormat.NV21, HookMain.mwidth, HookMain.mhight, null);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, HookMain.mwidth, HookMain.mhight), 90, out);
                jpegData = out.toByteArray();
                LogUtil.log("【CS】Camera1 Photo Fake: 从 NV21 帧回调数据生成 JPEG");
            } catch (Exception e) {
                LogUtil.log("【CS】Camera1 截帧 JPEG 转换失败: " + e);
            }
        }

        if (jpegData == null || jpegData.length == 0) {
            jpegData = buildJpegFromCurrentVideoFrame();
        }

        if (jpegData == null || jpegData.length == 0) {
            jpegData = buildBlackFallbackJpeg();
        }

        return jpegData;
    }

    private void ensureCameraSize(Camera camera) {
        if (HookMain.mwidth > 0 && HookMain.mhight > 0) {
            return;
        }
        try {
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = params.getPreviewSize();
            if (size != null) {
                HookMain.mwidth = size.width;
                HookMain.mhight = size.height;
            }
        } catch (Exception e) {
            LogUtil.log("【CS】获取 Camera1 尺寸失败: " + e);
        }
        if (HookMain.mwidth <= 0) {
            HookMain.mwidth = 640;
        }
        if (HookMain.mhight <= 0) {
            HookMain.mhight = 480;
        }
    }

    private byte[] buildJpegFromCurrentVideoFrame() {
        // Stream / USB capture mode: MediaMetadataRetriever cannot work with URLs
        // or live UVC input. Try GL capture from renderer instead.
        if (VideoManager.isStreamMode() || VideoManager.isUsbCaptureMode()) {
            LogUtil.log("【CS】Camera1 实时源模式下跳过 MediaMetadataRetriever，尝试 GL 截帧");
            android.graphics.Bitmap glFrame = captureFrameFromGlRenderer();
            if (glFrame != null) {
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    glFrame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos);
                    byte[] jpegData = bos.toByteArray();
                    glFrame.recycle();
                    return jpegData;
                } catch (Exception e) {
                    LogUtil.log("【CS】Camera1 GL 截帧 JPEG 转换失败: " + e);
                }
            }
            return null;
        }
        try {
            long currentPosMs = 0;
            if (HookMain.playerManager.mplayer1 != null && HookMain.playerManager.mplayer1.isPlaying()) {
                currentPosMs = HookMain.playerManager.mplayer1.getCurrentPosition() * 1000L;
            } else if (HookMain.playerManager.mMediaPlayer != null && HookMain.playerManager.mMediaPlayer.isPlaying()) {
                currentPosMs = HookMain.playerManager.mMediaPlayer.getCurrentPosition() * 1000L;
            }

            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            String videoPath = VideoManager.getCurrentVideoPath();
            retriever.setDataSource(videoPath);
            android.graphics.Bitmap frame = retriever.getFrameAtTime(currentPosMs,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST);
            retriever.release();

            if (frame != null) {
                if (frame.getWidth() != HookMain.mwidth || frame.getHeight() != HookMain.mhight) {
                    android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                            frame, HookMain.mwidth, HookMain.mhight, true);
                    frame.recycle();
                    frame = scaled;
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos);
                byte[] jpegData = bos.toByteArray();
                frame.recycle();
                LogUtil.log("【CS】Camera1 Photo Fake: 从视频文件截取帧生成 JPEG ("
                        + HookMain.mwidth + "x" + HookMain.mhight + ")");
                return jpegData;
            }
        } catch (Exception e) {
            LogUtil.log("【CS】Camera1 从视频截帧失败: " + e);
        }
        return null;
    }

    private byte[] buildBlackFallbackJpeg() {
        try {
            android.graphics.Bitmap fallback = android.graphics.Bitmap.createBitmap(HookMain.mwidth,
                    HookMain.mhight, android.graphics.Bitmap.Config.ARGB_8888);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            fallback.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, bos);
            byte[] jpegData = bos.toByteArray();
            fallback.recycle();
            LogUtil.log("【CS】Camera1 Photo Fake: 使用纯黑兜底 JPEG");
            return jpegData;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private android.graphics.Bitmap captureFrameFromGlRenderer() {
        GLVideoRenderer renderer = HookMain.playerManager.c1_renderer_holder;
        if (renderer == null || !renderer.isInitialized()) {
            renderer = HookMain.playerManager.c1_renderer_texture;
        }
        if (renderer != null && renderer.isInitialized()) {
            int w = HookMain.mwidth > 0 ? HookMain.mwidth : 640;
            int h = HookMain.mhight > 0 ? HookMain.mhight : 480;
            return renderer.captureFrameWithRotation(w, h, -1);
        }
        return null;
    }

    private void hookSetDisplayOrientation(ClassLoader classLoader) {
        hookCameraMethod(classLoader, "setDisplayOrientation", new Class<?>[] { int.class }, chain -> {
            Object[] args = toArgs(chain.getArgs());
            try {
                int degrees = (int) args[0];
                HookMain.mDisplayOrientation = degrees;
                LogUtil.log("【CS】setDisplayOrientation: " + degrees);
            } catch (Throwable t) {
                LogUtil.log("【CS】setDisplayOrientation before 异常: " + t);
            }
            return chain.proceed(args);
        });
    }

    private void hookSetPreviewCallbackWithBuffer(ClassLoader classLoader) {
        hookCameraMethod(classLoader, "setPreviewCallbackWithBuffer",
                new Class<?>[] { Camera.PreviewCallback.class }, chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    if (args[0] != null) {
                        processCallbackRegistration(args[0]);
                    }
                    return chain.proceed(args);
                });
    }

    private void hookAddCallbackBuffer(ClassLoader classLoader) {
        hookCameraMethod(classLoader, "addCallbackBuffer", new Class<?>[] { byte[].class }, chain -> {
            Object[] args = toArgs(chain.getArgs());
            if (args[0] != null) {
                args[0] = new byte[((byte[]) args[0]).length];
            }
            return chain.proceed(args);
        });
    }

    private void hookSetPreviewCallback(ClassLoader classLoader) {
        hookCameraMethod(classLoader, "setPreviewCallback", new Class<?>[] { Camera.PreviewCallback.class }, chain -> {
            Object[] args = toArgs(chain.getArgs());
            if (args[0] != null) {
                processCallbackRegistration(args[0]);
            }
            return chain.proceed(args);
        });
    }

    private void hookSetOneShotPreviewCallback(ClassLoader classLoader) {
        hookCameraMethod(classLoader, "setOneShotPreviewCallback", new Class<?>[] { Camera.PreviewCallback.class },
                chain -> {
                    Object[] args = toArgs(chain.getArgs());
                    if (args[0] != null) {
                        processCallbackRegistration(args[0]);
                    }
                    return chain.proceed(args);
                });
    }

    private void hookStopPreview(ClassLoader classLoader) {
        hookCameraMethod(classLoader, "stopPreview", new Class<?>[0], chain -> {
            LogUtil.log("【CS】Camera1 stopPreview，释放播放器资源");
            HookMain.playerManager.releaseCamera1Resources();
            return chain.proceed(toArgs(chain.getArgs()));
        });
    }

    private void hookRelease(ClassLoader classLoader) {
        hookCameraMethod(classLoader, "release", new Class<?>[0], chain -> {
            LogUtil.log("【CS】Camera1 release，释放播放器资源");
            HookMain.playerManager.releaseCamera1Resources();
            HookMain.origin_preview_camera = null;
            HookMain.start_preview_camera = null;
            HookMain.camera_onPreviewFrame = null;
            return chain.proceed(toArgs(chain.getArgs()));
        });
    }

    private void hookCameraMethod(ClassLoader classLoader, String methodName, Class<?>[] parameterTypes,
            XposedInterface.Hooker hooker) {
        try {
            Method method = resolveMethod(classLoader, methodName, parameterTypes);
            Api101Runtime.requireModule().hook(method).intercept(hooker);
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook Camera." + methodName + " 失败: " + t);
        }
    }

    private static Method resolveMethod(ClassLoader classLoader, String methodName, Class<?>... parameterTypes)
            throws Exception {
        Class<?> clazz = Class.forName("android.hardware.Camera", false, classLoader);
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException("android.hardware.Camera#" + methodName);
    }

    private static Object[] toArgs(List<Object> args) {
        return args.toArray(new Object[0]);
    }

    /**
     * Hook onPreviewFrame to replace camera frame data with decoded video frames.
     * Moved from HookMain.process_callback().
     */
    private static void processCallbackRegistration(Object previewCallback) {
        Class<?> previewCallbackClass = previewCallback.getClass();
        if (!hookedPreviewCallbackClasses.add(previewCallbackClass.getName())) {
            return;
        }
        boolean needStop = HookGuards.shouldBypass(null, HookGuards.getCurrentVideoFile());
        try {
            Method method = previewCallbackClass.getDeclaredMethod("onPreviewFrame", byte[].class, Camera.class);
            method.setAccessible(true);
            Api101Runtime.requireModule().hook(method).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    Camera localcam = (Camera) args[1];
                    if (localcam.equals(HookMain.camera_onPreviewFrame)) {
                        awaitPreviewFrameBuffer();
                        if (HookMain.data_buffer != null) {
                            System.arraycopy(HookMain.data_buffer, 0, args[0], 0,
                                    Math.min(HookMain.data_buffer.length, ((byte[]) args[0]).length));
                        }
                    } else {
                        HookMain.camera_onPreviewFrame = localcam;
                        Camera.Parameters params = HookMain.camera_onPreviewFrame.getParameters();
                        Camera.Size previewSize = params != null ? params.getPreviewSize() : null;
                        HookMain.mwidth = previewSize != null ? previewSize.width : 640;
                        HookMain.mhight = previewSize != null ? previewSize.height : 480;
                        int frameRate = params != null ? params.getPreviewFrameRate() : 0;
                        LogUtil.log("【CS】帧预览回调初始化：宽：" + HookMain.mwidth + " 高：" + HookMain.mhight
                                + " 帧率：" + frameRate);
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                            try {
                                HookMain.showToast("发现预览\n宽：" + HookMain.mwidth + "\n高："
                                        + HookMain.mhight + "\n" + "需要视频分辨率与其完全相同");
                            } catch (Exception ee) {
                                LogUtil.log("【CS】[toast]" + ee.toString());
                            }
                        }
                        if (!needStop) {
                            if (HookMain.hw_decode_obj == null) {
                                HookMain.hw_decode_obj = new VideoToFrames();
                            }
                            HookMain.hw_decode_obj.setTargetSize(HookMain.mwidth, HookMain.mhight);
                            HookMain.hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                            try {
                                HookMain.hw_decode_obj.reset(VideoManager.getCurrentVideoPath());
                            } catch (Throwable t) {
                                LogUtil.log("【CS】" + t);
                            }
                            awaitPreviewFrameBuffer();
                            if (HookMain.data_buffer != null) {
                                System.arraycopy(HookMain.data_buffer, 0, args[0], 0,
                                        Math.min(HookMain.data_buffer.length, ((byte[]) args[0]).length));
                            }
                        }
                    }
                } catch (Throwable t) {
                    LogUtil.log("【CS】onPreviewFrame before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook PreviewCallback.onPreviewFrame 失败: " + t);
        }
    }

    private static void awaitPreviewFrameBuffer() {
        if (HookMain.data_buffer != null) {
            return;
        }
        synchronized (previewFrameLock) {
            if (HookMain.data_buffer != null) {
                return;
            }
            try {
                previewFrameLock.wait(PREVIEW_FRAME_WAIT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static void notifyPreviewFrameBufferReady() {
        synchronized (previewFrameLock) {
            previewFrameLock.notifyAll();
        }
    }
}
