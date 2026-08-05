package io.github.zensu357.camswap;

import android.hardware.usb.UsbDevice;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Root 免授权直连器 —— 彻底绕过 Android 的 UsbManager 授权框架。
 *
 * <p>
 * <b>原理：</b> herohan UVCCamera 底层用 {@code nativeConnect(nativePtr, fd, quirks)} 连接设备，
 * 其中 fd 正常来自需要授权的 {@code UsbManager.openDevice()}。而 UVC 设备节点路径就是
 * {@link UsbDevice#getDeviceName()}（形如 {@code /dev/bus/usb/001/003}）。于是这里：
 * <ol>
 * <li>用 root 放开该设备节点的读写权限（{@link RootShell#chmodUsbNode}）；</li>
 * <li>用 {@link ParcelFileDescriptor#open} 直接打开节点拿到 fd——完全不经过 UsbManager；</li>
 * <li>反射复现 {@code UVCCamera.open()} 的核心调用序列（nativeConnect → updateSupportedFormats
 *     → setPreviewSize），把这个 fd 喂给 native 层。</li>
 * </ol>
 *
 * <p>
 * <b>风险：</b> 依赖 herohan 1.0.13 的私有 native 方法签名，库升级可能失效；native 调用出错
 * 可能导致进程崩溃。全程 try-catch，失败返回 null 由调用方回退到标准授权路径。
 */
public final class UsbRootConnector {

    private static final String TAG_PREFIX = "【CS】【usb】【root】";

    /** 直连结果：持有 camera 与 pfd（pfd 必须与 camera 同生命周期，关闭即 fd 失效）。 */
    public static final class Connection {
        public final UVCCamera camera;
        private final ParcelFileDescriptor pfd;

        Connection(UVCCamera camera, ParcelFileDescriptor pfd) {
            this.camera = camera;
            this.pfd = pfd;
        }

        public void release() {
            try {
                camera.stopPreview();
            } catch (Throwable ignored) {
            }
            try {
                camera.destroy();
            } catch (Throwable ignored) {
            }
            closePfd();
        }

        void closePfd() {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private UsbRootConnector() {
    }

    /**
     * 用 root + fd 直连方式打开 UVC 设备并开始预览。
     *
     * @param device        目标 UVC 设备（提供节点路径）
     * @param width/height/fps 期望预览参数
     * @param outputSurface 渲染输出目标（RendererHolder 主 Surface）
     * @return 成功返回 Connection；任何一步失败返回 null
     */
    public static Connection openAndStart(UsbDevice device, int width, int height, int fps,
            Surface outputSurface) {
        if (device == null || outputSurface == null) {
            return null;
        }
        String node = device.getDeviceName();
        log("尝试 root 直连: " + node + " 期望 " + width + "x" + height + "@" + fps);

        // 1) root 放开设备节点权限
        if (!RootShell.chmodUsbNode(node)) {
            log("chmod 失败，无法直连（设备可能未 root 或节点不可写）");
            return null;
        }

        ParcelFileDescriptor pfd = null;
        UVCCamera camera = null;
        try {
            // 2) 直接打开设备节点拿 fd（绕过 UsbManager）
            pfd = ParcelFileDescriptor.open(new File(node), ParcelFileDescriptor.MODE_READ_WRITE);
            int fd = pfd.getFd();
            if (fd <= 0) {
                log("打开节点得到非法 fd=" + fd);
                pfd.close();
                return null;
            }
            log("已打开设备节点 fd=" + fd);

            // 3) 构造 UVCCamera（内部 nativeCreate 得到 mNativePtr）
            int quirks = UVCCamera.getRecommendedPlatformQuirks();
            camera = new UVCCamera(new UVCParam(null, quirks));

            // 4) 反射 nativeConnect(mNativePtr, fd, quirks)
            long ptr = readNativePtr(camera);
            if (ptr == 0L) {
                log("mNativePtr 为 0，nativeCreate 可能失败");
                camera.destroy();
                pfd.close();
                return null;
            }
            int rc = invokeNativeConnect(camera, ptr, fd, quirks);
            log("nativeConnect 返回 " + rc);
            if (rc != 0) {
                log("nativeConnect 失败 rc=" + rc);
                camera.destroy();
                pfd.close();
                return null;
            }

            // 5) 复现 open() 的后续：刷新支持的格式（供 setPreviewSize 校验）
            invokeUpdateSupportedFormats(camera);

            // 6) 设置预览尺寸（public API，内部 nativeSetPreviewSize）
            try {
                camera.setPreviewSize(new Size(UVCCamera.FRAME_FORMAT_MJPEG, width, height, fps,
                        new java.util.ArrayList<Integer>()));
            } catch (Throwable t) {
                log("按 MJPEG " + width + "x" + height + "@" + fps + " 设置失败，改用设备默认: " + t);
                try {
                    camera.setPreviewSize(width, height);
                } catch (Throwable t2) {
                    log("setPreviewSize(w,h) 亦失败，沿用底层默认: " + t2);
                }
            }

            // 7) 挂输出 Surface 并开流
            camera.setPreviewDisplay(outputSurface);
            camera.startPreview();
            log("root 直连开流成功: " + node);
            return new Connection(camera, pfd);
        } catch (Throwable t) {
            log("root 直连异常: " + android.util.Log.getStackTraceString(t));
            if (camera != null) {
                try {
                    camera.destroy();
                } catch (Throwable ignored) {
                }
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    // =====================================================================
    // 反射细节（herohan 1.0.13）
    // =====================================================================

    private static long readNativePtr(UVCCamera camera) throws Exception {
        Field f = UVCCamera.class.getDeclaredField("mNativePtr");
        f.setAccessible(true);
        return f.getLong(camera);
    }

    /** 反射调 private native int nativeConnect(long, int, int)。 */
    private static int invokeNativeConnect(UVCCamera camera, long ptr, int fd, int quirks)
            throws Exception {
        Method m = UVCCamera.class.getDeclaredMethod("nativeConnect", long.class, int.class, int.class);
        m.setAccessible(true);
        Object r = m.invoke(camera, ptr, fd, quirks);
        return r instanceof Integer ? (Integer) r : 0;
    }

    /** 反射调 private void updateSupportedFormats()；失败不致命。 */
    private static void invokeUpdateSupportedFormats(UVCCamera camera) {
        try {
            Method m = UVCCamera.class.getDeclaredMethod("updateSupportedFormats");
            m.setAccessible(true);
            m.invoke(camera);
        } catch (Throwable t) {
            log("updateSupportedFormats 反射失败（可忽略）: " + t);
        }
    }

    private static void log(String message) {
        LogUtil.log(TAG_PREFIX + message);
    }
}
