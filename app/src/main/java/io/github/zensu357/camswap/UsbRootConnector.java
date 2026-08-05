package io.github.zensu357.camswap;

import android.hardware.usb.UsbDevice;
import android.os.ParcelFileDescriptor;

import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Root 免授权直连器：复现 {@code UVCCamera.open(UsbControlBlock)} 的核心调用
 * {@code nativeConnect(mNativePtr, fd, quirks)}，绕过 UsbManager 授权。
 *
 * <p><b>注意（历史坑）：</b>herohan UVCAndroid 1.0.13 的 blob 里根本没有
 * {@code nativeConnectFd} 这个方法（javap 反编译已核实），真正的私有 native 方法是
 * {@code private native int nativeConnect(long, int, int)}。早前误改为反射
 * {@code nativeConnectFd(long, int)} 会抛 {@link NoSuchMethodException}，被外层
 * try-catch 吞掉后返回 null，表现为「root direct-connect failed」——本类已改回正确签名。
 */
public final class UsbRootConnector {

    private static final String TAG = "【CS】【usb】【root】";

    public static final class Connection {
        public final UVCCamera camera;
        private final ParcelFileDescriptor pfd;
        Connection(UVCCamera camera, ParcelFileDescriptor pfd) { this.camera = camera; this.pfd = pfd; }
        public void release() {
            try { camera.stopPreview(); } catch (Throwable ignored) {}
            try { camera.destroy(); } catch (Throwable ignored) {}
            if (pfd != null) { try { pfd.close(); } catch (Throwable ignored) {} }
        }
    }

    private UsbRootConnector() {}

    /**
     * 仅完成「连接」：root 放开设备节点 → 直接打开 fd → {@code nativeConnect} →
     * {@code updateSupportedFormats}。返回一个已连接、但<b>尚未设置分辨率、尚未开预览</b>的
     * {@link Connection}。
     *
     * <p>分辨率协商（必须从 {@link UVCCamera#getSupportedSizeList()} 里挑设备真实支持的一档）
     * 与开预览由调用方（{@code UsbCaptureService.startUvcViaRoot}）负责，逻辑与标准授权路径
     * {@code startUvc} 完全一致——早前 root 路径把配置里的 720p 硬塞给 native、且把
     * {@code FRAME_FORMAT_MJPEG(=1)} 误当成 {@code Size.type}（native 需要 {@code UVC_VS_FRAME_MJPEG=7}），
     * 导致 native 开了预览却一帧都收不到，触发无限「开流→无帧看门狗→重连」黑屏循环。
     */
    public static Connection connect(UsbDevice device) {
        if (device == null) { log("参数为空"); return null; }
        String node = device.getDeviceName();
        int appUid = android.os.Process.myUid();
        log("尝试 root 直连: node=" + node + " appUid=" + appUid);
        if (!RootShell.prepareUsbNode(node, appUid)) {
            log("prepareUsbNode 失败——root 未授权 / 节点不可写。请在 Magisk 授予 CamSwap root。");
            return null;
        }
        ParcelFileDescriptor pfd = null;
        UVCCamera camera = null;
        try {
            try {
                pfd = ParcelFileDescriptor.open(new File(node), ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (Throwable openEx) {
                log("ParcelFileDescriptor.open 失败（多为 SELinux 拦截）: " + openEx);
                return null;
            }
            int fd = pfd.getFd();
            if (fd <= 0) { log("非法 fd=" + fd); pfd.close(); return null; }
            log("已打开设备节点 fd=" + fd);
            int quirks = UVCCamera.getRecommendedPlatformQuirks();
            camera = new UVCCamera(new UVCParam(null, quirks));
            long ptr = readNativePtr(camera);
            log("mNativePtr=" + ptr + " quirks=" + quirks);
            if (ptr == 0L) { log("mNativePtr 为 0"); camera.destroy(); pfd.close(); return null; }
            int rc = invokeNativeConnect(camera, ptr, fd, quirks);
            log("nativeConnect 返回=" + rc + " (0=成功)");
            if (rc != 0) {
                log("nativeConnect 失败 rc=" + rc + "——放弃 root 直连（fd 可能被其它进程占用/设备不兼容）");
                camera.destroy();
                pfd.close();
                return null;
            }
            // 刷新设备上报的支持格式，供调用方 getSupportedSizeList() 挑选真实可用分辨率
            invokeUpdateSupportedFormats(camera);
            log("root 直连连接成功（未设分辨率/未开预览）: " + node);
            return new Connection(camera, pfd);
        } catch (Throwable t) {
            log("root 直连异常: " + android.util.Log.getStackTraceString(t));
            if (camera != null) { try { camera.destroy(); } catch (Throwable ignored) {} }
            if (pfd != null) { try { pfd.close(); } catch (Throwable ignored) {} }
            return null;
        }
    }

    private static long readNativePtr(UVCCamera camera) throws Exception {
        Field f = UVCCamera.class.getDeclaredField("mNativePtr");
        f.setAccessible(true);
        return f.getLong(camera);
    }

    /**
     * 反射调用 herohan UVCCamera 1.0.13 的私有 native 方法
     * {@code private native int nativeConnect(long id, int fileDescriptor, int quirks)}。
     * 这正是 {@code UVCCamera.open()} 内部真正执行的连接调用，返回 0 表示成功。
     */
    private static int invokeNativeConnect(UVCCamera camera, long ptr, int fd, int quirks) throws Exception {
        Method m = UVCCamera.class.getDeclaredMethod("nativeConnect", long.class, int.class, int.class);
        m.setAccessible(true);
        Object r = m.invoke(camera, ptr, fd, quirks);
        return r instanceof Integer ? (Integer) r : -1;
    }

    private static void invokeUpdateSupportedFormats(UVCCamera camera) {
        try {
            Method m = UVCCamera.class.getDeclaredMethod("updateSupportedFormats");
            m.setAccessible(true);
            m.invoke(camera);
        } catch (Throwable t) { log("updateSupportedFormats 反射失败（可忽略）: " + t); }
    }

    private static void log(String message) { LogUtil.log(TAG + message); }
}
