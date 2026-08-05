package io.github.zensu357.camswap;

import android.content.Context;
import android.view.Surface;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * 目标进程（被 Hook 侧）的 usb_capture 接入端。
 *
 * <p>
 * {@link Camera1Handler} / {@link Camera2Handler} 在建立预览时先调用本类的 {@code attachXxx}：
 * 若当前媒体源为 {@code usb_capture} 且处理成功，则返回 {@code true}，调用方跳过原有的
 * MediaPlayer / ExoPlayer 播放逻辑。
 *
 * <p>
 * 画面链路（零拷贝）：
 *
 * <pre>
 *  宿主进程                                    目标进程
 *  UVCCamera --&gt; RendererHolder(主 Surface)
 *                    |  addSlaveSurface(跨进程 Surface)
 *                    v
 *              GLVideoRenderer.getInputSurface()  &lt;-- OES 纹理 + SurfaceTexture
 *                    |  GL 绘制
 *                    v
 *              目标 App 的预览 / ImageReader Surface
 * </pre>
 *
 * 目标进程本地保留 {@link GLVideoRenderer}（内部即 OES 外部纹理），
 * 使得旋转、YUV 截帧（WhatsApp/LINE 兼容桥）、拍照替换等既有能力在 USB 模式下继续可用。
 */
public final class CameraHandlerPatch {

    private static final String TAG_PREFIX = "【CS】【usb】";

    private CameraHandlerPatch() {
    }

    /** 当前配置是否为 USB 采集卡模式。 */
    public static boolean isUsbCaptureMode() {
        try {
            return VideoManager.isUsbCaptureMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在目标进程完成一次性初始化：注册 UVC 状态接收器并发起绑定。
     * 由 {@link HookMain} 在 Application.onCreate 之后调用，幂等。
     */
    public static void initInTargetProcess(Context context) {
        if (context == null) {
            return;
        }
        try {
            // 接收器很轻量且需要感知"用户稍后切到 USB 模式"，因此总是注册；
            // 而 UsbCaptureClient 会创建后台线程并发起绑定，仅在 USB 模式下才创建。
            HookUvcReceiver.register(context);
            if (!isUsbCaptureMode()) {
                return;
            }
            UsbCaptureClient client = UsbCaptureClient.get(context);
            if (client != null) {
                client.ensureBound();
                log("USB 采集卡模式已启用，开始绑定宿主服务");
            }
        } catch (Throwable t) {
            log("初始化失败: " + t);
        }
    }

    // =====================================================================
    // Camera1
    // =====================================================================

    /**
     * Camera1 SurfaceHolder 预览路径。
     *
     * @return true 表示已由 USB 采集卡接管，调用方不应再创建 MediaPlayer
     */
    public static boolean attachCamera1Holder(MediaPlayerManager playerManager, Surface targetSurface) {
        if (!isUsbCaptureMode()) {
            return false;
        }
        GLVideoRenderer.releaseSafely(playerManager.c1_renderer_holder);
        playerManager.c1_renderer_holder = null;
        GLVideoRenderer renderer = createRenderer(targetSurface, "c1_holder_usb");
        playerManager.c1_renderer_holder = renderer;
        return attach(UsbCaptureClient.SLOT_C1_HOLDER, renderer, targetSurface, "c1_holder");
    }

    /**
     * Camera1 SurfaceTexture 预览路径。
     *
     * @return true 表示已由 USB 采集卡接管
     */
    public static boolean attachCamera1Texture(MediaPlayerManager playerManager, Surface targetSurface) {
        if (!isUsbCaptureMode()) {
            return false;
        }
        GLVideoRenderer.releaseSafely(playerManager.c1_renderer_texture);
        playerManager.c1_renderer_texture = null;
        GLVideoRenderer renderer = createRenderer(targetSurface, "c1_texture_usb");
        playerManager.c1_renderer_texture = renderer;
        return attach(UsbCaptureClient.SLOT_C1_TEXTURE, renderer, targetSurface, "c1_texture");
    }

    /** Camera1 释放：注销对应槽位。 */
    public static void releaseCamera1() {
        UsbCaptureClient client = UsbCaptureClient.peek();
        if (client == null) {
            return;
        }
        client.unregisterSurface(UsbCaptureClient.SLOT_C1_HOLDER);
        client.unregisterSurface(UsbCaptureClient.SLOT_C1_TEXTURE);
    }

    // =====================================================================
    // Camera2
    // =====================================================================

    /**
     * Camera2 会话路径：为每一路目标 Surface 建立本地 GL 渲染器并注册到宿主。
     *
     * @return true 表示已由 USB 采集卡接管，调用方不应再创建播放器
     */
    public static boolean attachCamera2(MediaPlayerManager playerManager,
            Surface readerSurface, Surface readerSurface1,
            Surface previewSurface, Surface previewSurface1) {
        if (!isUsbCaptureMode()) {
            return false;
        }
        if (readerSurface == null && readerSurface1 == null
                && previewSurface == null && previewSurface1 == null) {
            log("Camera2：无可用目标 Surface");
            return false;
        }

        boolean any = false;

        GLVideoRenderer.releaseSafely(playerManager.c2_renderer);
        playerManager.c2_renderer = null;
        if (previewSurface != null) {
            playerManager.c2_renderer = createRenderer(previewSurface, "c2_preview_usb");
            any |= attach(UsbCaptureClient.SLOT_C2_PREVIEW, playerManager.c2_renderer,
                    previewSurface, "c2_preview");
        }

        GLVideoRenderer.releaseSafely(playerManager.c2_renderer_1);
        playerManager.c2_renderer_1 = null;
        if (previewSurface1 != null) {
            playerManager.c2_renderer_1 = createRenderer(previewSurface1, "c2_preview_1_usb");
            any |= attach(UsbCaptureClient.SLOT_C2_PREVIEW_1, playerManager.c2_renderer_1,
                    previewSurface1, "c2_preview_1");
        }

        GLVideoRenderer.releaseSafely(playerManager.c2_reader_renderer);
        playerManager.c2_reader_renderer = null;
        if (readerSurface != null) {
            playerManager.c2_reader_renderer = createRenderer(readerSurface, "c2_reader_usb");
            any |= attach(UsbCaptureClient.SLOT_C2_READER, playerManager.c2_reader_renderer,
                    readerSurface, "c2_reader");
        }

        GLVideoRenderer.releaseSafely(playerManager.c2_reader_renderer_1);
        playerManager.c2_reader_renderer_1 = null;
        if (readerSurface1 != null) {
            playerManager.c2_reader_renderer_1 = createRenderer(readerSurface1, "c2_reader_1_usb");
            any |= attach(UsbCaptureClient.SLOT_C2_READER_1, playerManager.c2_reader_renderer_1,
                    readerSurface1, "c2_reader_1");
        }

        if (any) {
            log("Camera2 处理过程完全执行（USB 采集卡模式）");
        } else {
            log("Camera2：没有任何 Surface 注册成功");
        }
        return any;
    }

    /** Camera2 释放：注销对应槽位。 */
    public static void releaseCamera2() {
        UsbCaptureClient client = UsbCaptureClient.peek();
        if (client == null) {
            return;
        }
        client.unregisterSurface(UsbCaptureClient.SLOT_C2_PREVIEW);
        client.unregisterSurface(UsbCaptureClient.SLOT_C2_PREVIEW_1);
        client.unregisterSurface(UsbCaptureClient.SLOT_C2_READER);
        client.unregisterSurface(UsbCaptureClient.SLOT_C2_READER_1);
    }

    /** 注销本进程全部槽位。 */
    public static void releaseAll() {
        UsbCaptureClient client = UsbCaptureClient.peek();
        if (client != null) {
            client.unregisterAll();
        }
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 创建本地 GL 渲染器（内部会建立 OES 外部纹理 + SurfaceTexture + 输入 Surface）。
     * 失败返回 null，由调用方回退到直接注册目标 Surface。
     */
    private static GLVideoRenderer createRenderer(Surface targetSurface, String tag) {
        if (targetSurface == null || !targetSurface.isValid()) {
            return null;
        }
        GLVideoRenderer renderer = GLVideoRenderer.createSafely(targetSurface, tag);
        if (renderer != null) {
            // 与流模式保持一致：预览渲染器不叠加旋转，旋转偏移仅作用于 YUV 截帧
            renderer.setRotation(0);
        }
        return renderer;
    }

    /**
     * 把渲染器的输入 Surface（或渲染器创建失败时的原始目标 Surface）注册给宿主。
     */
    private static boolean attach(int slotId, GLVideoRenderer renderer, Surface fallbackSurface, String tag) {
        Context context = HookMain.toast_content;
        if (context == null) {
            log(tag + " 注册失败：目标进程 Context 尚未就绪");
            return false;
        }
        UsbCaptureClient client = UsbCaptureClient.get(context);
        if (client == null) {
            log(tag + " 注册失败：客户端不可用");
            return false;
        }

        int[] size = resolveCaptureSize(client);
        Surface surface;
        if (renderer != null && renderer.isInitialized() && renderer.getInputSurface() != null) {
            // 必须先设定默认缓冲区尺寸，宿主的 eglCreateWindowSurface 才能拿到正确分辨率
            renderer.setInputBufferSize(size[0], size[1]);
            surface = renderer.getInputSurface();
            log(tag + " 使用 GL 渲染器（OES 纹理）作为 UVC 输出目标，缓冲区 " + size[0] + "x" + size[1]);
        } else {
            surface = fallbackSurface;
            log(tag + " GL 渲染器不可用，直接把目标 Surface 交给宿主");
        }
        if (surface == null || !surface.isValid()) {
            log(tag + " 注册失败：Surface 无效");
            return false;
        }

        client.registerSurface(slotId, surface, size[0], size[1]);
        return true;
    }

    /**
     * 解析当前应使用的采集分辨率：优先取宿主已开流的真实分辨率，否则用配置值。
     */
    private static int[] resolveCaptureSize(UsbCaptureClient client) {
        if (client != null) {
            int[] actual = client.getPreviewSize();
            if (actual != null && actual.length >= 2 && actual[0] > 0 && actual[1] > 0) {
                return new int[] { actual[0], actual[1] };
            }
        }
        UsbCaptureConfig config = VideoManager.getConfig().getUsbCaptureConfig();
        return new int[] { config.width, config.height };
    }

    /**
     * 宿主开流成功后回调：把各渲染器的输入缓冲区尺寸更新为真实采集分辨率，
     * 并重放注册，让宿主用正确尺寸重建从属渲染面。
     */
    public static void onUvcStreamStarted() {
        UsbCaptureClient client = UsbCaptureClient.peek();
        if (client == null) {
            return;
        }
        int[] size = resolveCaptureSize(client);
        MediaPlayerManager playerManager = HookMain.playerManager;
        applyBufferSize(playerManager.c1_renderer_holder, size);
        applyBufferSize(playerManager.c1_renderer_texture, size);
        applyBufferSize(playerManager.c2_renderer, size);
        applyBufferSize(playerManager.c2_renderer_1, size);
        applyBufferSize(playerManager.c2_reader_renderer, size);
        applyBufferSize(playerManager.c2_reader_renderer_1, size);
        log("宿主已开流，输入缓冲区更新为 " + size[0] + "x" + size[1] + " 并重放注册");
        client.forceResync();
    }

    private static void applyBufferSize(GLVideoRenderer renderer, int[] size) {
        if (renderer != null && renderer.isInitialized()) {
            renderer.setInputBufferSize(size[0], size[1]);
        }
    }

    private static void log(String message) {
        LogUtil.log(TAG_PREFIX + message);
    }
}
