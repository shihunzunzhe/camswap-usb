package io.github.zensu357.camswap;

import android.hardware.usb.UsbDevice;
import android.os.ParcelFileDescriptor;

import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCControl;
import com.serenegiant.usb.UVCParam;
import com.serenegiant.usb.USBMonitor;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Root 免授权直连器：复现 {@code UVCCamera.open(UsbControlBlock)} 的核心调用
 * {@code nativeConnect(mNativePtr, fd, quirks)}，绕过 UsbManager 授权。
 *
 * <p><b>注意（历史坑）：</b>
 * <ul>
 *   <li>herohan UVCAndroid 1.0.13 的 blob 里根本没有 {@code nativeConnectFd}，
 *       真正的私有 native 方法是 {@code private native int nativeConnect(long, int, int)}。</li>
 *   <li>{@code UVCCamera.startPreview()}/{@code stopPreview()} 都用
 *       {@code if (mCtrlBlock != null)} 做守卫；root 直连若只调 nativeConnect、不装上
 *       {@code mCtrlBlock}/{@code mControl}，则 {@code startPreview()} 会静默空返回，
 *       表现为「通知栏 STREAMING ↔ 重连 来回跳、画面始终黑屏、native 帧数恒为 0」。</li>
 * </ul>
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
     * 装上 {@code mCtrlBlock}/{@code mControl}（让后续 {@code startPreview} 真正生效）→
     * {@code updateSupportedFormats}。返回一个已连接、但<b>尚未设置分辨率、尚未开预览</b>的
     * {@link Connection}。
     *
     * <p>分辨率协商与开预览由调用方（{@code UsbCaptureService.startUvcViaRoot}）负责，
     * 逻辑与标准授权路径 {@code startUvc} 对齐。
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

            // 关键：把 open() 在 nativeConnect 成功后做的 Java 侧状态补齐。
            // 缺 mCtrlBlock → startPreview()/stopPreview() 直接 return，native 一帧都不会出。
            // 缺 mControl   → isOpened() 恒为 false（诊断/状态会误导）。
            if (!installOpenState(camera, device, ptr)) {
                log("installOpenState 失败——startPreview 仍可能空转，放弃 root 直连");
                camera.destroy();
                pfd.close();
                return null;
            }

            // 刷新设备上报的支持格式，供调用方 getSupportedSizeList() 挑选真实可用分辨率
            invokeUpdateSupportedFormats(camera);
            log("root 直连连接成功（未设分辨率/未开预览）: " + node
                    + " isOpened=" + camera.isOpened()
                    + " hasCtrlBlock=" + hasCtrlBlock(camera));
            return new Connection(camera, pfd);
        } catch (Throwable t) {
            log("root 直连异常: " + android.util.Log.getStackTraceString(t));
            if (camera != null) { try { camera.destroy(); } catch (Throwable ignored) {} }
            if (pfd != null) { try { pfd.close(); } catch (Throwable ignored) {} }
            return null;
        }
    }

    /**
     * 模拟 {@code UVCCamera.open()} 在 nativeConnect 成功后的 Java 状态：
     * <ol>
     *   <li>{@code mControl = new UVCControl(nativeGetControl(ptr))} —— 让 {@code isOpened()} 为 true；</li>
     *   <li>{@code mCtrlBlock = 未 open 的 UsbControlBlock 哨兵} —— 让 {@code startPreview()}
     *       真正调用到 {@code nativeStartPreview}。哨兵不持有 UsbDeviceConnection，
     *       {@code close()} 时因 mConnection==null 是空操作，不会误关我们自己的 pfd。</li>
     * </ol>
     */
    private static boolean installOpenState(UVCCamera camera, UsbDevice device, long ptr) {
        try {
            // mControl
            Method nativeGetControl = UVCCamera.class.getDeclaredMethod("nativeGetControl", long.class);
            nativeGetControl.setAccessible(true);
            Object controlPtrObj = nativeGetControl.invoke(camera, ptr);
            long controlPtr = controlPtrObj instanceof Long ? (Long) controlPtrObj : 0L;
            if (controlPtr == 0L) {
                log("nativeGetControl 返回 0——设备控制接口异常，继续尝试仅装 mCtrlBlock");
            } else {
                UVCControl control = new UVCControl(controlPtr);
                Field mControl = UVCCamera.class.getDeclaredField("mControl");
                mControl.setAccessible(true);
                mControl.set(camera, control);
                log("已装 mControl ptr=" + controlPtr);
            }

            // mCtrlBlock 哨兵：构造函数是 USBMonitor.UsbControlBlock(USBMonitor, UsbDevice) private
            Constructor<?> ctor = USBMonitor.UsbControlBlock.class
                    .getDeclaredConstructor(USBMonitor.class, UsbDevice.class);
            ctor.setAccessible(true);
            // monitor 传 null：WeakReference 允许；我们不会对这个 block 调 open()/getFileDescriptor()
            Object ctrlBlock = ctor.newInstance(null, device);
            Field mCtrlBlock = UVCCamera.class.getDeclaredField("mCtrlBlock");
            mCtrlBlock.setAccessible(true);
            mCtrlBlock.set(camera, ctrlBlock);
            log("已装 mCtrlBlock 哨兵（未 open 的 UsbControlBlock，仅用于放开 startPreview 守卫）");
            return true;
        } catch (Throwable t) {
            log("installOpenState 异常: " + android.util.Log.getStackTraceString(t));
            return false;
        }
    }

    private static boolean hasCtrlBlock(UVCCamera camera) {
        try {
            Field f = UVCCamera.class.getDeclaredField("mCtrlBlock");
            f.setAccessible(true);
            return f.get(camera) != null;
        } catch (Throwable t) {
            return false;
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
