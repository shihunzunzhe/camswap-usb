package io.github.zensu357.camswap;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * 目标进程（被 Hook 侧）与宿主 {@link UsbCaptureService} 的 Binder 客户端。
 *
 * <p>
 * 负责：
 * <ul>
 * <li>异步 bindService 到宿主服务，失败按退避重试，任何异常都不会抛到 Hook 调用栈；</li>
 * <li>把本地 GLVideoRenderer 的输入 Surface 按槽位注册给宿主；</li>
 * <li>服务断开（宿主进程被杀 / USB 服务重启）后自动重连并重放全部已注册 Surface。</li>
 * </ul>
 *
 * <p>
 * 该类是进程内单例，所有公开方法都可从任意线程调用。
 */
public final class UsbCaptureClient {

    private static final String TAG_PREFIX = "【CS】【usb】";

    /** 槽位定义，与 MediaPlayerManager / Camera1Handler 的渲染器一一对应 */
    public static final int SLOT_C1_HOLDER = 1;
    public static final int SLOT_C1_TEXTURE = 2;
    public static final int SLOT_C2_PREVIEW = 3;
    public static final int SLOT_C2_PREVIEW_1 = 4;
    public static final int SLOT_C2_READER = 5;
    public static final int SLOT_C2_READER_1 = 6;

    private static final long BIND_RETRY_BASE_MS = 800L;
    private static final long BIND_RETRY_MAX_MS = 8000L;

    private static volatile UsbCaptureClient sInstance;

    private final Context appContext;
    private final Handler handler;
    private final Object lock = new Object();

    /** slotId -> 待注册 / 已注册的 Surface 信息 */
    private final Map<Integer, Registration> registrations = new LinkedHashMap<>();

    /**
     * 进程级存活令牌：交给宿主 linkToDeath。本进程一旦被杀，宿主立刻清理它注册的 Surface。
     * 必须是强引用且与进程同生命周期，否则会被 GC 后误判为进程死亡。
     */
    private final android.os.Binder clientToken = new android.os.Binder();

    private volatile IUsbCaptureService service;
    private volatile boolean binding;
    private volatile boolean bound;
    private int bindAttempt;
    private volatile long lastBindFailLogMs;

    private static final class Registration {
        final Surface surface;
        final int width;
        final int height;
        boolean synced;

        Registration(Surface surface, int width, int height) {
            this.surface = surface;
            this.width = width;
            this.height = height;
        }
    }

    private UsbCaptureClient(Context context) {
        this.appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        HandlerThread thread = new HandlerThread("CS-UsbClient");
        thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /**
     * 获取单例；context 为 null 且尚未初始化时返回 null。
     */
    public static UsbCaptureClient get(Context context) {
        UsbCaptureClient local = sInstance;
        if (local != null) {
            return local;
        }
        if (context == null) {
            return null;
        }
        synchronized (UsbCaptureClient.class) {
            if (sInstance == null) {
                sInstance = new UsbCaptureClient(context);
                log("客户端已初始化，宿主服务=" + IpcContract.USB_SERVICE_CLASS_NAME);
            }
            return sInstance;
        }
    }

    /** 返回已初始化的单例，未初始化时返回 null（不触发初始化）。 */
    public static UsbCaptureClient peek() {
        return sInstance;
    }

    // =====================================================================
    // 绑定管理
    // =====================================================================

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IUsbCaptureService remote = IUsbCaptureService.Stub.asInterface(binder);
            synchronized (lock) {
                service = remote;
                bound = true;
                binding = false;
                bindAttempt = 0;
            }
            log("已连接宿主 USB 服务");
            linkToDeath(binder);
            handler.post(UsbCaptureClient.this::syncAllRegistrations);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            log("宿主 USB 服务连接断开，将自动重连");
            handleDisconnected();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            log("宿主 USB 服务绑定失效，将自动重连");
            handleDisconnected();
            // 绑定已死，必须先 unbind 再重新 bind
            safeUnbind();
            scheduleBind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            log("宿主 USB 服务返回空 Binder（可能被拒绝），稍后重试");
            handleDisconnected();
            safeUnbind();
            scheduleBind();
        }
    };

    private void linkToDeath(IBinder binder) {
        if (binder == null) {
            return;
        }
        try {
            binder.linkToDeath(() -> {
                log("宿主进程已死亡，重置连接");
                handleDisconnected();
                safeUnbind();
                scheduleBind();
            }, 0);
        } catch (Exception e) {
            log("linkToDeath 失败: " + e);
        }
    }

    private void handleDisconnected() {
        synchronized (lock) {
            service = null;
            bound = false;
            for (Registration registration : registrations.values()) {
                registration.synced = false;
            }
        }
    }

    private void safeUnbind() {
        try {
            appContext.unbindService(connection);
        } catch (Exception ignored) {
            // 未绑定或已解绑
        }
        synchronized (lock) {
            binding = false;
        }
    }

    /** 确保已发起绑定；已绑定时立即返回。 */
    public void ensureBound() {
        synchronized (lock) {
            if (bound || binding) {
                return;
            }
            binding = true;
        }
        handler.post(this::doBind);
    }

    private void scheduleBind() {
        long delay;
        synchronized (lock) {
            if (bound || binding) {
                return;
            }
            binding = true;
            delay = Math.min(BIND_RETRY_MAX_MS, BIND_RETRY_BASE_MS * (1L << Math.min(bindAttempt, 4)));
            bindAttempt++;
        }
        handler.postDelayed(this::doBind, delay);
    }

    private void doBind() {
        boolean ok = false;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(IpcContract.HOST_PACKAGE_NAME,
                    IpcContract.USB_SERVICE_CLASS_NAME));
            ok = appContext.bindService(intent, connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_ABOVE_CLIENT);
        } catch (Throwable t) {
            logThrottled("bindService 异常: " + t);
        }
        if (!ok) {
            synchronized (lock) {
                binding = false;
            }
            logThrottled("bindService 失败（宿主未安装/不可见/被拒绝），稍后重试");
            scheduleBind();
        }
    }

    // =====================================================================
    // Surface 注册
    // =====================================================================

    /**
     * 注册（或替换）某个槽位的 Surface。若服务尚未连接，会先缓存并在连接后自动补注册。
     */
    public void registerSurface(int slotId, Surface surface, int width, int height) {
        if (surface == null || !surface.isValid()) {
            log("跳过无效 Surface 注册 slot=" + slotId);
            return;
        }
        synchronized (lock) {
            registrations.put(slotId, new Registration(surface, width, height));
        }
        log("登记 Surface slot=" + slotId + " " + width + "x" + height);
        ensureBound();
        handler.post(this::syncAllRegistrations);
    }

    /** 注销某个槽位。 */
    public void unregisterSurface(int slotId) {
        Registration removed;
        synchronized (lock) {
            removed = registrations.remove(slotId);
        }
        if (removed == null) {
            return;
        }
        final IUsbCaptureService remote = service;
        if (remote == null) {
            return;
        }
        handler.post(() -> {
            try {
                remote.unregisterTargetSurfaceSlot(slotId);
                log("已注销 Surface slot=" + slotId);
            } catch (Exception e) {
                log("注销 Surface 失败 slot=" + slotId + ": " + e);
            }
        });
    }

    /** 注销本进程全部槽位（Camera 关闭 / 会话释放时调用）。 */
    public void unregisterAll() {
        boolean had;
        synchronized (lock) {
            had = !registrations.isEmpty();
            registrations.clear();
        }
        final IUsbCaptureService remote = service;
        if (remote == null || !had) {
            return;
        }
        handler.post(() -> {
            try {
                remote.unregisterTargetSurface();
                log("已注销本进程全部 Surface");
            } catch (Exception e) {
                log("注销全部 Surface 失败: " + e);
            }
        });
    }

    private void syncAllRegistrations() {
        IUsbCaptureService remote = service;
        if (remote == null) {
            return;
        }
        List<Map.Entry<Integer, Registration>> pending = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<Integer, Registration> entry : registrations.entrySet()) {
                if (!entry.getValue().synced) {
                    pending.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
                }
            }
        }
        for (Map.Entry<Integer, Registration> entry : pending) {
            Registration registration = entry.getValue();
            if (!registration.surface.isValid()) {
                synchronized (lock) {
                    registrations.remove(entry.getKey());
                }
                continue;
            }
            try {
                boolean ok = remote.registerTargetSurfaceWithToken(registration.surface,
                        registration.width, registration.height, entry.getKey(), clientToken);
                registration.synced = ok;
                log("注册 Surface 到宿主 slot=" + entry.getKey() + " 结果=" + ok);
                if (!ok) {
                    log("宿主拒绝注册（请确认已在设置中把本应用加入目标应用列表）");
                }
            } catch (Exception e) {
                log("注册 Surface 到宿主失败 slot=" + entry.getKey() + ": " + e);
                handleDisconnected();
                scheduleBind();
                return;
            }
        }
    }

    /** 宿主服务重启后由 {@link HookUvcReceiver} 调用，强制重放全部注册。 */
    public void forceResync() {
        synchronized (lock) {
            for (Registration registration : registrations.values()) {
                registration.synced = false;
            }
        }
        ensureBound();
        handler.post(this::syncAllRegistrations);
    }

    // =====================================================================
    // 状态查询
    // =====================================================================

    /** 是否已成功绑定宿主服务。 */
    public boolean isBound() {
        return bound && service != null;
    }

    /** UVC 是否已连接并开流；查询失败一律返回 false，绝不抛异常到 Hook 调用栈。 */
    public boolean isUvcConnected() {
        IUsbCaptureService remote = service;
        if (remote == null) {
            return false;
        }
        try {
            return remote.isUvcConnected();
        } catch (Exception e) {
            handleDisconnected();
            scheduleBind();
            return false;
        }
    }

    /** 返回 {width, height, fps}；不可用时返回 {0,0,0}。 */
    public int[] getPreviewSize() {
        IUsbCaptureService remote = service;
        if (remote == null) {
            return new int[] { 0, 0, 0 };
        }
        try {
            int[] size = remote.getUvcPreviewSize();
            return size != null && size.length >= 3 ? size : new int[] { 0, 0, 0 };
        } catch (Exception e) {
            return new int[] { 0, 0, 0 };
        }
    }

    /** 请求宿主重连 UVC 设备。 */
    public void requestReconnect() {
        IUsbCaptureService remote = service;
        if (remote == null) {
            ensureBound();
            return;
        }
        try {
            remote.requestReconnect();
        } catch (Exception e) {
            log("请求重连失败: " + e);
        }
    }

    private void logThrottled(String message) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBindFailLogMs < 5000L) {
            return;
        }
        lastBindFailLogMs = now;
        log(message);
    }

    private static void log(String message) {
        LogUtil.log(TAG_PREFIX + message);
    }
}
