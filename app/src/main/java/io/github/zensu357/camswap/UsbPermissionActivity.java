package io.github.zensu357.camswap;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * 采集卡插入入口 Activity（无界面）。
 *
 * <p>
 * 仅负责一件事：当用户物理插入 UVC 采集卡时，系统按 {@code usb_device_filter} 匹配到本
 * Activity 的 {@code USB_DEVICE_ATTACHED} intent-filter，把它拉到前台；本 Activity 立即
 * 委托 {@link UsbPermissionHelper} 在前台请求授权（此刻进程在前台，弹窗可靠），然后结束。
 *
 * <p>
 * 应用内主动授权（用户在设置界面点击）不走这里，而是由设置界面直接同步调用
 * {@link UsbPermissionHelper#requestAndStart(Context)}——避免 Activity 跳转带来的时序问题。
 */
public class UsbPermissionActivity extends Activity {

    private static final String TAG_PREFIX = "【CS】【usb】";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(io.github.zensu357.camswap.utils.LocaleHelper.INSTANCE.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            UsbDevice device = null;
            if (getIntent() != null) {
                device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            LogUtil.log(TAG_PREFIX + "采集卡已插入: "
                    + (device != null ? device.getDeviceName() : "unknown")
                    + "，前台请求授权");
            // 本 Activity 处于前台，helper 会同步在前台请求授权
            UsbPermissionHelper.requestAndStart(this, device);
        } catch (Throwable t) {
            LogUtil.log(TAG_PREFIX + "处理 USB 插入事件失败: " + t);
        } finally {
            // requestPermission 已同步发起，弹窗由系统独立管理，结果接收器在 application
            // 上下文注册，不依赖本 Activity，因此可以立即结束
            finish();
        }
    }

    /**
     * 启动（或刷新）宿主 USB 采集卡服务。仅在调用方处于前台时可靠。
     */
    public static void startCaptureService(Context context) {
        if (context == null) {
            return;
        }
        try {
            // minSdk 26，startForegroundService 始终可用
            context.startForegroundService(new Intent(context, UsbCaptureService.class));
        } catch (Throwable t) {
            LogUtil.log(TAG_PREFIX + "启动 UsbCaptureService 失败: " + t);
        }
    }

    /**
     * 停止宿主 USB 采集卡服务。
     */
    public static void stopCaptureService(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, UsbCaptureService.class));
        } catch (Throwable t) {
            LogUtil.log(TAG_PREFIX + "停止 UsbCaptureService 失败: " + t);
        }
    }
}
