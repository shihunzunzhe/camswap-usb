package io.github.zensu357.camswap;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * USB 采集卡授权助手。
 *
 * <p>
 * 核心原则：USB 授权对话框能否弹出，取决于"请求那一刻 CamSwap 进程是否在前台"，
 * 与传入的 Context 类型无关。因此这里要求由用户正在交互的前台界面同步调用
 * （点击按钮的那一刻进程必然在前台），不做任何 Activity 跳转 / 异步延迟——
 * 跳转和延迟都会让"请求时刻"脱离前台，导致系统静默拒绝、弹不出窗。
 *
 * <p>
 * 授权结果广播用 application context 注册一次性接收器，不依赖任何 Activity 的生命周期，
 * 避免界面切换把接收器带走。授权成功后拉起 {@link UsbCaptureService}，
 * 服务此刻 {@code hasPermission()} 为 true，直接开流。
 */
public final class UsbPermissionHelper {

    private static final String TAG_PREFIX = "【CS】【usb】";
    /** 显式指向本包的授权结果 action，规避 Android 14 对隐式 PendingIntent 的限制 */
    private static final String ACTION_USB_PERMISSION = "io.github.zensu357.camswap.USB_PERMISSION";

    private static final Object lock = new Object();
    private static BroadcastReceiver resultReceiver;

    private UsbPermissionHelper() {
    }

    /** 请求授权并启动采集服务（自动挑选设备）。 */
    public static void requestAndStart(Context context) {
        requestAndStart(context, null);
    }

    /**
     * 请求授权并启动采集服务。
     *
     * @param context   必须由前台界面调用（点击回调），否则弹窗可能被系统拦截
     * @param preferred 优先授权的设备；为 null 时按配置自动挑选
     */
    public static void requestAndStart(Context context, UsbDevice preferred) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        UsbManager usbManager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            toast(context, context.getString(R.string.usb_toast_no_host));
            return;
        }
        // Root 免授权直连模式：不走系统授权，直接启动服务由 UsbCaptureService 走 chmod+fd 直连。
        try {
            ConfigManager __cfg = new ConfigManager(false);
            __cfg.setSkipProviderReload(true);
            __cfg.setContext(app);
            __cfg.forceReload();
            if (__cfg.getBoolean(ConfigManager.KEY_USB_ROOT_BYPASS, false)) {
                log("Root 免授权直连模式：跳过系统授权，直接启动采集服务");
                toast(context, "Root 直连模式，正在启动采集…");
                UsbPermissionActivity.startCaptureService(app);
                return;
            }
        } catch (Throwable __ignored) {
        }
        if (usbManager == null) {
            toast(context, context.getString(R.string.usb_toast_no_host));
            return;
        }

        UsbDevice device = (preferred != null && isUvcDevice(preferred))
                ? preferred
                : pickConfiguredUvcDevice(app, usbManager);

        if (device == null) {
            log("未检测到 UVC 采集卡");
            toast(context, context.getString(R.string.usb_toast_no_device));
            // 仍拉起服务，设备插入后会自动开流
            UsbPermissionActivity.startCaptureService(app);
            return;
        }

        if (usbManager.hasPermission(device)) {
            log("已有 USB 权限，直接启动采集服务: " + describe(device));
            toast(context, context.getString(R.string.usb_toast_granted));
            UsbPermissionActivity.startCaptureService(app);
            return;
        }

        try {
            registerResultReceiver(app);
            Intent permIntent = new Intent(ACTION_USB_PERMISSION).setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // USB 授权要求 PendingIntent 可变；显式 intent 无需 UNSAFE_IMPLICIT 标记
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(app, 0, permIntent, flags);
            log("前台请求 USB 授权: " + describe(device));
            toast(context, context.getString(R.string.usb_toast_requesting));
            // 同步调用 —— 此刻进程在前台，系统授权对话框可靠弹出
            usbManager.requestPermission(device, pi);
        } catch (Throwable t) {
            log("请求 USB 授权失败: " + t);
            unregisterResultReceiver(app);
            UsbPermissionActivity.startCaptureService(app);
        }
    }

    private static void registerResultReceiver(final Context app) {
        synchronized (lock) {
            if (resultReceiver != null) {
                return;
            }
            resultReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_USB_PERMISSION.equals(intent.getAction())) {
                        return;
                    }
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    log("USB 授权结果: " + (granted ? "已允许" : "被拒绝") + " " + describe(device));
                    if (granted) {
                        // 权限授予整个应用 UID，服务里的 USBMonitor.hasPermission() 随之为 true
                        UsbPermissionActivity.startCaptureService(app);
                    } else {
                        log("用户拒绝授权，可再次点击「连接采集卡」重试");
                    }
                    unregisterResultReceiver(app);
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 显式 PendingIntent 广播由系统 USB 服务发回本应用，NOT_EXPORTED 即可
                app.registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(resultReceiver, filter);
            }
        }
    }

    private static void unregisterResultReceiver(Context app) {
        synchronized (lock) {
            if (resultReceiver == null) {
                return;
            }
            try {
                app.unregisterReceiver(resultReceiver);
            } catch (Exception ignored) {
                // 已注销
            }
            resultReceiver = null;
        }
    }

    /** 按配置（VID:PID）挑选目标 UVC 设备；未指定时取第一个 UVC 设备。 */
    private static UsbDevice pickConfiguredUvcDevice(Context app, UsbManager usbManager) {
        List<UsbDevice> uvcDevices = new ArrayList<>();
        try {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                if (isUvcDevice(device)) {
                    uvcDevices.add(device);
                }
            }
        } catch (Throwable t) {
            log("枚举 USB 设备失败: " + t);
        }
        if (uvcDevices.isEmpty()) {
            return null;
        }

        String configured = readConfiguredDeviceKey(app);
        if (configured != null && !configured.isEmpty()) {
            for (UsbDevice device : uvcDevices) {
                if (configured.equals(UsbCaptureService.deviceKey(device))
                        || configured.equals(device.getDeviceName())
                        || configured.equals(device.getProductName())) {
                    return device;
                }
            }
        }
        return uvcDevices.get(0);
    }

    private static String readConfiguredDeviceKey(Context app) {
        try {
            ConfigManager config = new ConfigManager(false);
            config.setSkipProviderReload(true);
            config.setContext(app);
            config.forceReload();
            return config.getUsbCaptureConfig().deviceName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否存在已插入的 UVC 设备（供 UI 判断是否显示"连接"按钮）。 */
    public static boolean hasUvcDevice(Context context) {
        if (context == null) {
            return false;
        }
        try {
            UsbManager usbManager = (UsbManager) context.getApplicationContext()
                    .getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                return false;
            }
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                if (isUvcDevice(device)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 无 USB Host 或枚举失败
        }
        return false;
    }

    static boolean isUvcDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        try {
            if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
            if (device.getDeviceClass() == UsbConstants.USB_CLASS_MISC && device.getDeviceSubclass() == 2) {
                return true;
            }
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface usbInterface = device.getInterface(i);
                if (usbInterface != null && usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 判定失败按非 UVC 处理
        }
        return false;
    }

    private static void toast(Context context, String message) {
        try {
            Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Toast 失败无需处理
        }
    }

    private static String describe(UsbDevice device) {
        if (device == null) {
            return "null";
        }
        return device.getDeviceName() + " (VID=" + device.getVendorId()
                + " PID=" + device.getProductId() + ")";
    }

    private static void log(String message) {
        LogUtil.log(TAG_PREFIX + message);
    }
}
