package io.github.zensu357.camswap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;

import androidx.core.content.ContextCompat;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * 目标进程（被 Hook 侧）内的 UVC 状态接收器。
 *
 * <p>
 * 监听两类事件：
 * <ul>
 * <li>{@link IpcContract#ACTION_USB_STATE_CHANGED}：宿主 UsbCaptureService 的开流状态变化。
 * 宿主重启或重新开流后，本进程需要重放一次 Surface 注册，否则画面不会恢复。</li>
 * <li>USB_DEVICE_ATTACHED / DETACHED：采集卡插拔。插入时主动拉起绑定并请求重连；
 * 拔出时仅记录日志——目标进程不做任何释放动作，保证不会因采集卡掉线而崩溃。</li>
 * </ul>
 *
 * <p>
 * 该接收器只在 media_source_type = usb_capture 时才需要工作，但注册本身是幂等且极轻量的，
 * 因此在 Application.onCreate 阶段就随 Hook 一起注册，避免用户切换模式后需要重启目标应用。
 */
public final class HookUvcReceiver extends BroadcastReceiver {

    private static final String TAG_PREFIX = "【CS】【usb】";

    private static volatile HookUvcReceiver sInstance;
    private static volatile boolean sRegistered;

    /** 最近一次已知的宿主状态，供 Hook 侧快速判断是否已开流。 */
    private static volatile int sLastState = UsbCaptureService.STATE_IDLE;
    private static volatile boolean sLastConnected;

    /**
     * 在目标进程注册接收器（幂等）。
     */
    public static void register(Context context) {
        if (context == null || sRegistered) {
            return;
        }
        synchronized (HookUvcReceiver.class) {
            if (sRegistered) {
                return;
            }
            try {
                HookUvcReceiver receiver = new HookUvcReceiver();
                IntentFilter filter = new IntentFilter();
                filter.addAction(IpcContract.ACTION_USB_STATE_CHANGED);
                filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
                filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                // 宿主进程与系统都会发来广播，必须声明为 EXPORTED；
                // ContextCompat 会在 Android 13 以下自动忽略该 flag。
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
                sInstance = receiver;
                sRegistered = true;
                log("目标进程 UVC 状态接收器已注册");
            } catch (Throwable t) {
                log("注册 UVC 状态接收器失败: " + t);
            }
        }
    }

    public static void unregister(Context context) {
        HookUvcReceiver receiver = sInstance;
        if (context == null || receiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (Exception ignored) {
            // 已注销
        }
        sInstance = null;
        sRegistered = false;
    }

    /** 宿主最近上报的状态（UsbCaptureService.STATE_*）。 */
    public static int getLastState() {
        return sLastState;
    }

    /** 宿主最近上报的开流状态。 */
    public static boolean isLastConnected() {
        return sLastConnected;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        try {
            if (IpcContract.ACTION_USB_STATE_CHANGED.equals(action)) {
                handleHostStateChanged(context, intent);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("目标进程感知到 USB 设备插入");
                UsbCaptureClient client = UsbCaptureClient.get(context);
                if (client != null) {
                    client.ensureBound();
                    client.forceResync();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // 仅记录：宿主负责重连，目标进程保持既有渲染管线不动，画面停在最后一帧
                log("目标进程感知到 USB 设备拔出，等待宿主自动重连");
                sLastConnected = false;
            }
        } catch (Throwable t) {
            // Hook 进程内绝不允许异常逃逸
            log("处理广播异常 action=" + action + ": " + t);
        }
    }

    private void handleHostStateChanged(Context context, Intent intent) {
        int state = intent.getIntExtra(IpcContract.EXTRA_USB_STATE, UsbCaptureService.STATE_IDLE);
        boolean connected = intent.getBooleanExtra(IpcContract.EXTRA_USB_CONNECTED, false);
        String deviceName = intent.getStringExtra(IpcContract.EXTRA_USB_DEVICE_NAME);

        int previous = sLastState;
        sLastState = state;
        sLastConnected = connected;
        log("宿主 USB 状态: " + previous + " -> " + state
                + " connected=" + connected + " device=" + deviceName);

        if (!connected) {
            return;
        }
        // 宿主重新开流：RendererHolder 是新建的，旧的从属 Surface 已随之释放；
        // 同时真实分辨率此刻才可知，需要按其重设本地输入缓冲区并重放注册。
        UsbCaptureClient.get(context);
        CameraHandlerPatch.onUvcStreamStarted();
    }

    private static void log(String message) {
        LogUtil.log(TAG_PREFIX + message);
    }
}
