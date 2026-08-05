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

/** Root 免授权直连器：nativeConnectFd(mNativePtr, fd)，绕过 UsbManager 授权。 */
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

    public static Connection openAndStart(UsbDevice device, int width, int height, int fps, Surface outputSurface) {
        if (device == null || outputSurface == null) { log("参数为空"); return null; }
        String node = device.getDeviceName();
        int appUid = android.os.Process.myUid();
        log("尝试 root 直连: node=" + node + " appUid=" + appUid + " 期望 " + width + "x" + height + "@" + fps);
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
            long rc = invokeNativeConnectFd(camera, ptr, fd);
            log("nativeConnectFd 返回=" + rc);
            invokeUpdateSupportedFormats(camera);
            boolean sized = false;
            try {
                camera.setPreviewSize(new Size(UVCCamera.FRAME_FORMAT_MJPEG, width, height, fps, new java.util.ArrayList<Integer>()));
                sized = true;
            } catch (Throwable t) {
                log("MJPEG " + width + "x" + height + "@" + fps + " 失败: " + t);
                try { camera.setPreviewSize(width, height); sized = true; } catch (Throwable t2) { log("setPreviewSize(w,h) 失败: " + t2); }
            }
            log("setPreviewSize ok=" + sized);
            camera.setPreviewDisplay(outputSurface);
            camera.startPreview();
            log("root 直连开流成功: " + node);
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

    private static long invokeNativeConnectFd(UVCCamera camera, long ptr, int fd) throws Exception {
        Method m = UVCCamera.class.getDeclaredMethod("nativeConnectFd", long.class, int.class);
        m.setAccessible(true);
        Object r = m.invoke(camera, ptr, fd);
        return r instanceof Long ? (Long) r : 0L;
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
