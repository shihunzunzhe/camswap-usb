package io.github.zensu357.camswap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Surface;

import com.serenegiant.opengl.renderer.RendererHolder;
import com.serenegiant.opengl.renderer.RendererHolderCallback;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;
import com.serenegiant.utils.UVCUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.zensu357.camswap.utils.LogUtil;

/**
 * 宿主进程（CamSwap 应用）内的 USB 采集卡（UVC）管理服务。
 *
 * <p>
 * 职责：
 * <ol>
 * <li>持有 USB 权限、发现并打开 UVC 设备（MS2131 等标准 UVC 采集卡）；</li>
 * <li>把 UVC 画面开流到内部 {@link RendererHolder} 的主 Surface；</li>
 * <li>通过 {@link IUsbCaptureService} 接收目标进程跨进程传来的 Surface，
 * 作为 RendererHolder 的从属 Surface 直接挂载，实现零拷贝画面分发；</li>
 * <li>监听 USB 热插拔广播，掉线后按退避策略自动重连，且不影响目标进程存活。</li>
 * </ol>
 *
 * <p>
 * 全部日志统一使用 {@code 【CS】【usb】} 前缀。
 */
public class UsbCaptureService extends Service {

    // ---- 服务状态（与 IUsbCaptureService#getUvcState 对应） ----
    public static final int STATE_IDLE = 0;
    public static final int STATE_WAITING_DEVICE = 1;
    public static final int STATE_WAITING_PERMISSION = 2;
    public static final int STATE_STREAMING = 3;
    public static final int STATE_RECONNECTING = 4;
    public static final int STATE_ERROR = 5;

    private static final String TAG_PREFIX = "【CS】【usb】";
    private static final String CHANNEL_ID = "camswap_usb_channel";
    private static final int NOTIFICATION_ID = 1003;

    /** 重连退避：初始 1s，指数增长，上限 10s */
    private static final long RECONNECT_BASE_DELAY_MS = 1000L;
    private static final long RECONNECT_MAX_DELAY_MS = 10000L;
    /** 开流后超过该时间没有新帧则判定为假连接，触发重连 */
    private static final long FRAME_WATCHDOG_TIMEOUT_MS = 6000L;
    private static final long WATCHDOG_INTERVAL_MS = 2000L;
    /** 清理已死亡目标进程遗留 Surface 的周期 */
    private static final long SWEEP_INTERVAL_MS = 5000L;
    /** 授权请求发出后，若这么久还没等到结果就认为回调丢失，解除 in-flight 锁允许重试 */
    private static final long PERMISSION_REQUEST_TIMEOUT_MS = 30000L;

    // ---- 线程 ----
    private HandlerThread workerThread;
    private Handler workerHandler;
    private Handler mainHandler;

    // ---- USB / UVC ----
    private UsbManager usbManager;
    private USBMonitor usbMonitor;
    private volatile UVCCamera uvcCamera;
    private volatile RendererHolder rendererHolder;
    private volatile UsbRootConnector.Connection rootConnection;
    private volatile boolean rootBypass;
    private volatile UsbDevice activeDevice;
    private volatile String activeDeviceName = "";
    private volatile int activeWidth;
    private volatile int activeHeight;
    private volatile int activeFps;

    private volatile int state = STATE_IDLE;
    /** 最近一次进入 STATE_ERROR 的原因，用于通知栏与日志定位 */
    private volatile String lastErrorReason = "";
    /**
     * 是否允许弹出 USB 授权对话框。
     * <p>
     * 只在"用户前台操作"（onStartCommand / 设置界面点击）或"设备物理插入"（ACTION_USB_DEVICE_ATTACHED）
     * 时置 true。目标应用在后台 bindService 拉起服务时保持 false——因为 Android 12+ 从后台
     * 调 UsbManager.requestPermission 往往不弹窗、直接回一个"取消"，会白白打满拒绝计数。
     */
    private volatile boolean allowPermissionPrompt;
    /** 一次授权请求是否正在进行（等待 onDeviceOpen/onCancel 回调），防止对话框期间重复请求 */
    private volatile boolean permissionRequestInFlight;
    /** 上次发起授权请求的时刻，用于给 in-flight 状态兜底超时 */
    private volatile long permissionRequestAtMs;
    private volatile long lastFrameAtMs;
    /** 宿主 GL 主 Surface 累计收到的帧数（用于诊断：宿主是否真的拿到画面） */
    private volatile long hostFrameCount;
    /** libuvc native 回调累计帧数（用于诊断：设备/fd 是否真的在出帧，区分「无帧」与「有帧但没渲染」） */
    private volatile long nativeFrameCount;
    /** 本轮开流是否已打过首帧日志，避免刷屏 */
    private volatile boolean firstFrameLogged;
    private int reconnectAttempt;
    /** 连续被拒绝授权的次数，仅用于提示，不再驱动自动重试 */
    private int permissionDeniedCount;
    private volatile boolean released;

    private ConfigManager configManager;
    private volatile UsbCaptureConfig usbConfig = UsbCaptureConfig.defaults();

    // ---- 目标进程 Surface 注册表 ----
    private final Object surfaceLock = new Object();
    /** key = (pid << 8) | slotId */
    private final Map<Integer, ClientSurface> clientSurfaces = new LinkedHashMap<>();
    private final AtomicInteger rendererIdSeq = new AtomicInteger(1);

    private static final class ClientSurface {
        final int pid;
        final int slotId;
        final Surface surface;
        final int rendererId;
        final IBinder clientToken;
        volatile boolean attached;

        ClientSurface(int pid, int slotId, Surface surface, int rendererId, IBinder clientToken) {
            this.pid = pid;
            this.slotId = slotId;
            this.surface = surface;
            this.rendererId = rendererId;
            this.clientToken = clientToken;
        }
    }

    /** 已注册讣告的客户端令牌，避免对同一 token 重复 linkToDeath */
    private final Map<IBinder, IBinder.DeathRecipient> clientTokens = new LinkedHashMap<>();

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(io.github.zensu357.camswap.utils.LocaleHelper.INSTANCE.onAttach(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        log("服务创建");

        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("CS-UsbCapture");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        // UVCAndroid 内部通过 UVCUtils.getApplication() 取 Context，显式初始化避免反射失败
        try {
            UVCUtils.init(getApplicationContext());
        } catch (Throwable t) {
            log("UVCUtils 初始化失败(可忽略): " + t);
        }

        createNotificationChannel();

        configManager = new ConfigManager(false);
        configManager.setSkipProviderReload(true);
        configManager.setContext(this);
        configManager.forceReload();
        usbConfig = configManager.getUsbCaptureConfig();
        rootBypass = configManager.getBoolean(ConfigManager.KEY_USB_ROOT_BYPASS, false);
        log("加载配置: " + usbConfig);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        usbMonitor = new USBMonitor(this, deviceConnectListener, workerHandler);
        usbMonitor.register();

        registerHotplugReceiver();

        setState(STATE_WAITING_DEVICE);
        workerHandler.post(this::scanAndOpen);
        workerHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
        workerHandler.postDelayed(sweepRunnable, SWEEP_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafely();
        // onStartCommand 只在"用户前台点击"或"设备物理插入拉起"时触发
        // （目标应用后台 bindService 走 onBind，不会到这里），是允许弹授权窗的可靠时机。
        armPermissionPrompt();
        if (configManager != null) {
            configManager.forceReload();
            applyConfigIfChanged(configManager.getUsbCaptureConfig());
            boolean __newRoot = configManager.getBoolean(ConfigManager.KEY_USB_ROOT_BYPASS, false);
            if (__newRoot != rootBypass) { rootBypass = __newRoot;
                if (workerHandler != null) workerHandler.post(this::stopUvc); }
        }
        // 配置未变化时 applyConfigIfChanged 不会重扫，这里兜底触发一次
        if (state != STATE_STREAMING && workerHandler != null) {
            workerHandler.post(this::scanAndOpen);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 注意：onBind 运行在主线程而非 Binder 事务中，此处不能用 Binder.getCallingUid()/Pid()
        // （拿到的是本进程自己的 uid/pid）。调用方身份在各 Stub 方法里获取。
        log("收到 bindService");
        // 目标进程绑定时同样尝试前台化，保证宿主进程不被系统回收导致画面中断
        startForegroundSafely();
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // 允许后续 rebind，onBind 会被再次调用
        return true;
    }

    @Override
    public void onDestroy() {
        log("服务销毁");
        released = true;
        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
            workerHandler.post(this::stopUvc);
        }
        unregisterHotplugReceiver();
        if (usbMonitor != null) {
            try {
                usbMonitor.unregister();
                usbMonitor.destroy();
            } catch (Throwable t) {
                log("USBMonitor 释放异常: " + t);
            }
            usbMonitor = null;
        }
        synchronized (surfaceLock) {
            clientSurfaces.clear();
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        super.onDestroy();
    }

    // =====================================================================
    // AIDL 实现
    // =====================================================================

    private final IUsbCaptureService.Stub binder = new IUsbCaptureService.Stub() {

        @Override
        public boolean registerTargetSurface(Surface surface, int width, int height) throws RemoteException {
            return registerTargetSurfaceSlot(surface, width, height, 0);
        }

        @Override
        public boolean registerTargetSurfaceSlot(Surface surface, int width, int height, int slotId)
                throws RemoteException {
            if (!enforceCaller()) {
                return false;
            }
            return doRegisterSurface(Binder.getCallingPid(), slotId, surface, width, height, null);
        }

        @Override
        public boolean registerTargetSurfaceWithToken(Surface surface, int width, int height, int slotId,
                IBinder clientToken) throws RemoteException {
            if (!enforceCaller()) {
                return false;
            }
            return doRegisterSurface(Binder.getCallingPid(), slotId, surface, width, height, clientToken);
        }

        @Override
        public void unregisterTargetSurface() throws RemoteException {
            if (!enforceCaller()) {
                return;
            }
            removeClientSurfaces(Binder.getCallingPid(), null);
        }

        @Override
        public void unregisterTargetSurfaceSlot(int slotId) throws RemoteException {
            if (!enforceCaller()) {
                return;
            }
            removeClientSurfaces(Binder.getCallingPid(), slotId);
        }

        @Override
        public boolean isUvcConnected() throws RemoteException {
            if (!enforceCaller()) {
                return false;
            }
            return state == STATE_STREAMING && uvcCamera != null;
        }

        @Override
        public String getUvcDeviceName() throws RemoteException {
            if (!enforceCaller()) {
                return "";
            }
            return activeDeviceName != null ? activeDeviceName : "";
        }

        @Override
        public int[] getUvcPreviewSize() throws RemoteException {
            if (!enforceCaller()) {
                return new int[] { 0, 0, 0 };
            }
            if (state != STATE_STREAMING) {
                return new int[] { 0, 0, 0 };
            }
            return new int[] { activeWidth, activeHeight, activeFps };
        }

        @Override
        public void requestReconnect() throws RemoteException {
            if (!enforceCaller()) {
                return;
            }
            log("目标进程请求重连");
            if (workerHandler != null) {
                workerHandler.post(() -> {
                    stopUvc();
                    reconnectAttempt = 0;
                    permissionDeniedCount = 0;
                    scheduleReconnect();
                });
            }
        }

        @Override
        public int getUvcState() throws RemoteException {
            if (!enforceCaller()) {
                return STATE_IDLE;
            }
            return state;
        }
    };

    /**
     * 校验调用方是否为本模块的目标应用。
     * 规则与 Hook 侧一致：target_packages 为空表示"对所有应用生效"，此时放行。
     */
    private boolean enforceCaller() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) {
            return true;
        }
        String[] packages = getPackageManager().getPackagesForUid(callingUid);
        if (packages == null || packages.length == 0) {
            log("拒绝调用：uid=" + callingUid + " 无法解析包名");
            return false;
        }
        Set<String> allowed = new HashSet<>();
        if (configManager != null) {
            allowed.addAll(configManager.getTargetPackages());
        }
        if (allowed.isEmpty()) {
            // 未配置目标应用 == 模块对所有应用生效，与 HookMain.handleLoadPackage 的语义保持一致
            return true;
        }
        allowed.add(getPackageName());
        for (String pkg : packages) {
            if (allowed.contains(pkg)) {
                return true;
            }
        }
        log("拒绝非目标应用调用: " + java.util.Arrays.toString(packages));
        return false;
    }

    // =====================================================================
    // Surface 注册 / 挂载
    // =====================================================================

    private boolean doRegisterSurface(int pid, int slotId, Surface surface, int width, int height,
            IBinder clientToken) {
        if (surface == null || !surface.isValid()) {
            log("注册 Surface 失败：surface 为空或已失效 pid=" + pid + " slot=" + slotId);
            return false;
        }
        int key = surfaceKey(pid, slotId);
        ClientSurface old;
        ClientSurface entry = new ClientSurface(pid, slotId, surface,
                rendererIdSeq.getAndIncrement(), clientToken);
        synchronized (surfaceLock) {
            old = clientSurfaces.put(key, entry);
        }
        linkClientToken(pid, clientToken);
        if (old != null) {
            detachSlave(old);
        }
        log("注册目标 Surface: pid=" + pid + " slot=" + slotId
                + " 期望尺寸=" + width + "x" + height + " rendererId=" + entry.rendererId);
        attachSlave(entry);

        // 目标进程刚接入且当前无设备时，立刻触发一次扫描，缩短首帧等待
        if (state != STATE_STREAMING && workerHandler != null) {
            workerHandler.post(this::scanAndOpen);
        }
        return true;
    }

    /** slotId 为 null 表示移除该进程的全部槽位。 */
    private void removeClientSurfaces(int pid, Integer slotId) {
        List<ClientSurface> removed = new ArrayList<>();
        synchronized (surfaceLock) {
            if (slotId != null) {
                ClientSurface cs = clientSurfaces.remove(surfaceKey(pid, slotId));
                if (cs != null) {
                    removed.add(cs);
                }
            } else {
                java.util.Iterator<Map.Entry<Integer, ClientSurface>> it = clientSurfaces.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, ClientSurface> e = it.next();
                    if (e.getValue().pid == pid) {
                        removed.add(e.getValue());
                        it.remove();
                    }
                }
            }
        }
        for (ClientSurface cs : removed) {
            detachSlave(cs);
        }
        if (!removed.isEmpty()) {
            log("注销目标 Surface: pid=" + pid
                    + (slotId != null ? " slot=" + slotId : " (全部)")
                    + " 共 " + removed.size() + " 路");
        }
    }

    private static int surfaceKey(int pid, int slotId) {
        return (pid << 8) | (slotId & 0xFF);
    }

    private void attachSlave(ClientSurface cs) {
        // 注意：RendererHolder.isRunning() 在 UVCAndroid 1.0.13 中是一个从未被赋值的
        // 遗留字段（恒为 false），不能用它做就绪判断，否则永远挂不上从属 Surface。
        RendererHolder holder = rendererHolder;
        if (holder == null) {
            // UVC 还没开流，等 startUvc 时统一挂载
            return;
        }
        if (cs.attached) {
            return;
        }
        try {
            holder.addSlaveSurface(cs.rendererId, cs.surface, false);
            cs.attached = true;
            log("已挂载从属 Surface rendererId=" + cs.rendererId + " pid=" + cs.pid + " slot=" + cs.slotId);
        } catch (Exception e) {
            log("挂载从属 Surface 失败 rendererId=" + cs.rendererId + ": " + e);
        }
    }

    private void detachSlave(ClientSurface cs) {
        RendererHolder holder = rendererHolder;
        if (holder != null && cs.attached) {
            try {
                holder.removeSlaveSurface(cs.rendererId);
            } catch (Exception e) {
                log("移除从属 Surface 异常 rendererId=" + cs.rendererId + ": " + e);
            }
        }
        cs.attached = false;
    }

    private void attachAllSlaves() {
        List<ClientSurface> snapshot;
        synchronized (surfaceLock) {
            snapshot = new ArrayList<>(clientSurfaces.values());
        }
        for (ClientSurface cs : snapshot) {
            cs.attached = false;
            attachSlave(cs);
        }
    }

    private void markAllSlavesDetached() {
        synchronized (surfaceLock) {
            for (ClientSurface cs : clientSurfaces.values()) {
                cs.attached = false;
            }
        }
    }

    /**
     * 对客户端令牌注册讣告，目标进程死亡时立即清理它的全部 Surface。
     * <p>
     * 这里刻意不去探测 /proc/&lt;pid&gt;：Android 9 起 /proc 以 hidepid=2 挂载，
     * 应用无法看到其它应用的 pid 目录，那种探测对存活进程也会返回"已死亡"，
     * 会把正在出图的 Surface 误删掉。
     */
    private void linkClientToken(final int pid, IBinder clientToken) {
        if (clientToken == null) {
            return;
        }
        synchronized (surfaceLock) {
            if (clientTokens.containsKey(clientToken)) {
                return;
            }
            IBinder.DeathRecipient recipient = () -> {
                log("目标进程已死亡(pid=" + pid + ")，清理其注册的 Surface");
                removeClientSurfaces(pid, null);
                synchronized (surfaceLock) {
                    clientTokens.remove(clientToken);
                }
            };
            try {
                clientToken.linkToDeath(recipient, 0);
                clientTokens.put(clientToken, recipient);
            } catch (Exception e) {
                log("注册客户端讣告失败 pid=" + pid + ": " + e);
            }
        }
    }

    /** 周期清理：仅清理 Surface 本身已失效的注册项（进程存活与否由讣告负责）。 */
    private final Runnable sweepRunnable = new Runnable() {
        @Override
        public void run() {
            if (released) {
                return;
            }
            List<ClientSurface> dead = new ArrayList<>();
            synchronized (surfaceLock) {
                java.util.Iterator<Map.Entry<Integer, ClientSurface>> it = clientSurfaces.entrySet().iterator();
                while (it.hasNext()) {
                    ClientSurface cs = it.next().getValue();
                    if (!cs.surface.isValid()) {
                        dead.add(cs);
                        it.remove();
                    }
                }
            }
            for (ClientSurface cs : dead) {
                detachSlave(cs);
                log("清理已失效 Surface: pid=" + cs.pid + " slot=" + cs.slotId);
            }
            if (workerHandler != null && !released) {
                workerHandler.postDelayed(this, SWEEP_INTERVAL_MS);
            }
        }
    };

    // =====================================================================
    // USB 热插拔广播（ATTACHED / DETACHED）
    // =====================================================================

    private boolean hotplugReceiverRegistered;

    private final BroadcastReceiver hotplugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("USB_DEVICE_ATTACHED: " + describe(device));
                if (device != null && !isUvcDevice(device)) {
                    return;
                }
                reconnectAttempt = 0;
                // 物理插入是系统场景，此时弹授权窗最可靠，放行一次弹窗
                armPermissionPrompt();
                if (workerHandler != null) {
                    // 设备刚插入时枚举/授权可能尚未就绪，稍作延迟
                    workerHandler.postDelayed(UsbCaptureService.this::scanAndOpen, 300L);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("USB_DEVICE_DETACHED: " + describe(device));
                UsbDevice current = activeDevice;
                boolean isCurrent = current != null && device != null
                        && current.getDeviceName().equals(device.getDeviceName());
                if (isCurrent || current == null) {
                    onDeviceLost("设备已拔出");
                }
            }
        }
    };

    private void registerHotplugReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(hotplugReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(hotplugReceiver, filter);
            }
            hotplugReceiverRegistered = true;
            log("USB 热插拔广播已注册");
        } catch (Exception e) {
            log("注册 USB 热插拔广播失败: " + e);
        }
    }

    private void unregisterHotplugReceiver() {
        if (!hotplugReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(hotplugReceiver);
        } catch (Exception ignored) {
            // 已注销
        }
        hotplugReceiverRegistered = false;
    }

    // =====================================================================
    // USBMonitor 回调
    // =====================================================================

    private final USBMonitor.OnDeviceConnectListener deviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            if (!isUvcDevice(device)) {
                return;
            }
            log("onAttach: " + describe(device));
            if (!matchesConfiguredDevice(device)) {
                log("非配置指定设备，忽略: " + describe(device));
                return;
            }
            // USBMonitor 的 onAttach 同样对应"设备刚插入"，放行一次弹窗
            armPermissionPrompt();
            requestPermissionOrOpen(device);
        }

        @Override
        public void onDetach(UsbDevice device) {
            log("onDetach: " + describe(device));
            UsbDevice current = activeDevice;
            if (current == null || (device != null && current.getDeviceName().equals(device.getDeviceName()))) {
                onDeviceLost("USBMonitor 上报设备移除");
            }
        }

        @Override
        public void onDeviceOpen(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            log("onDeviceOpen: " + describe(device) + " createNew=" + createNew);
            permissionRequestInFlight = false;
            permissionDeniedCount = 0;
            if (workerHandler != null) {
                workerHandler.post(() -> startUvc(device, ctrlBlock));
            }
        }

        @Override
        public void onDeviceClose(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            log("onDeviceClose: " + describe(device));
            if (workerHandler != null) {
                workerHandler.post(UsbCaptureService.this::stopUvc);
            }
        }

        @Override
        public void onCancel(UsbDevice device) {
            permissionDeniedCount++;
            permissionRequestInFlight = false;
            // 关键：授权失败绝不自动重试。自动重试会在用户来不及点"允许"时快速打满计数，
            // 也可能是 Android 12+ 后台调用被系统静默取消。这里停下来，等用户的下一步动作：
            //   - 重新插拔采集卡（触发 ACTION_USB_DEVICE_ATTACHED）
            //   - 在 CamSwap 设置里再次点击"启动/刷新采集服务”
            log("USB 授权未通过(累计 " + permissionDeniedCount + " 次): " + describe(device)
                    + "，已停止自动重试，等待用户重新授权");
            setError("未获得 USB 授权——请在系统弹窗中点\"允许\"（建议勾选\"默认打开\"），"
                    + "或重新插拔采集卡");
        }

        @Override
        public void onError(UsbDevice device, USBMonitor.USBException e) {
            log("USBMonitor 错误: " + describe(device) + " -> " + e);
            setError("USB 监听器错误: " + e);
            if (usbConfig.autoReconnect) {
                scheduleReconnect();
            }
        }
    };

    // =====================================================================
    // 设备发现 / 开流 / 停流
    // =====================================================================

    /** 扫描当前已连接的 UVC 设备并尝试开流（worker 线程）。 */
    private void scanAndOpen() {
        if (released || uvcCamera != null) {
            return;
        }
        UsbDevice target = findTargetDevice();
        if (target == null) {
            log("未发现可用 UVC 设备，等待插入");
            setState(STATE_WAITING_DEVICE);
            if (usbConfig.autoReconnect) {
                scheduleReconnect();
            }
            return;
        }
        if (rootBypass) {
            startUvcViaRoot(target);
        } else {
            requestPermissionOrOpen(target);
        }
    }

    private void startUvcViaRoot(UsbDevice device) {
        if (released || uvcCamera != null || rootConnection != null) return;

        // 1) 先只建立连接（root 放开节点 + 直接 fd + nativeConnect + updateSupportedFormats），
        //    此时还没设分辨率、没开预览。
        UsbRootConnector.Connection conn = UsbRootConnector.connect(device);
        if (conn == null) {
            setError("Root direct-connect failed (grant root; see log tag root)");
            if (usbConfig.autoReconnect) scheduleReconnect();
            return;
        }

        UVCCamera camera = conn.camera;
        RendererHolder holder = null;
        try {
            log("root 直连已连接: isOpened=" + safeIsOpened(camera));
            // 2) 分辨率协商：必须从设备真实上报的支持列表里挑（与标准路径 startUvc 完全一致）。
            //    早前 root 路径把配置里的尺寸 + FRAME_FORMAT_MJPEG(=1，本应是 UVC_VS_FRAME_MJPEG=7)
            //    硬塞给 native，设备不支持该组合 → 开了预览却零帧 → 无限重连黑屏。
            List<Size> supported = null;
            try { supported = camera.getSupportedSizeList(); } catch (Throwable t) { log("getSupportedSizeList 异常: " + t); }
            logSupportedSizes("root", supported);
            Size chosen = pickBestSize(supported, usbConfig);
            if (chosen == null) {
                chosen = camera.getSupportedSizeOne();
                log("pickBestSize 无匹配，getSupportedSizeOne=" + describeSize(chosen));
            } else {
                log("root 选定分辨率: " + describeSize(chosen)
                        + "（配置期望 " + usbConfig.width + "x" + usbConfig.height + "@" + usbConfig.fps + "）");
            }
            if (chosen != null) {
                try {
                    camera.setPreviewSize(chosen);
                } catch (Throwable t) {
                    log("root 设置分辨率 " + chosen.width + "x" + chosen.height + " 失败，沿用默认: " + t);
                }
            } else {
                log("设备未上报支持分辨率，回退 "
                        + UVCCamera.DEFAULT_PREVIEW_WIDTH + "x" + UVCCamera.DEFAULT_PREVIEW_HEIGHT
                        + "（type=UVC_VS_FRAME_MJPEG）");
                try {
                    camera.setPreviewSize(new Size(UVCCamera.UVC_VS_FRAME_MJPEG,
                            UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                            UVCCamera.DEFAULT_PREVIEW_FPS, new ArrayList<Integer>()));
                } catch (Throwable ignored) {
                    // 连默认值都设不上，仅记日志；startPreview 仍会用 native 内部默认尝试
                }
            }

            // 3) 用协商后的真实尺寸建 GL 渲染器（尺寸不匹配会让跨进程 SurfaceTexture 拿到错误缓冲区）。
            Size actual = camera.getPreviewSize();
            int width = actual != null && actual.width > 0 ? actual.width : usbConfig.width;
            int height = actual != null && actual.height > 0 ? actual.height : usbConfig.height;
            int fps = actual != null && actual.fps > 0 ? actual.fps : usbConfig.fps;

            holder = new RendererHolder(width, height, rendererHolderCallback);
            Surface primary = holder.getPrimarySurface();
            if (primary == null || !primary.isValid()) {
                log("RendererHolder 主 Surface 无效，放弃 root 开流");
                holder.release();
                conn.release();
                setError("GL 渲染器初始化失败");
                if (usbConfig.autoReconnect) scheduleReconnect();
                return;
            }

            // 4) 挂 Surface 并开预览。开流前清零帧计数，装上 native 帧回调用于诊断+看门狗兜底。
            hostFrameCount = 0;
            nativeFrameCount = 0;
            firstFrameLogged = false;
            camera.setPreviewDisplay(primary);
            try {
                camera.setFrameCallback(usbFrameCallback, UVCCamera.PIXEL_FORMAT_RAW);
                log("已装 native 帧回调（PIXEL_FORMAT_RAW），用于确认设备是否真在出帧");
            } catch (Throwable t) {
                log("setFrameCallback 失败（不影响预览，仅少一路诊断）: " + t);
            }
            camera.startPreview();
            log("root startPreview 已调用，等待首帧… 若 6s 内无帧看门狗会打印 native/宿主帧数");

            rootConnection = conn;
            uvcCamera = camera;
            rendererHolder = holder;
            activeDevice = device;
            activeDeviceName = device.getDeviceName();
            activeWidth = width;
            activeHeight = height;
            activeFps = fps;
            lastFrameAtMs = SystemClock.elapsedRealtime();
            reconnectAttempt = 0;
            permissionDeniedCount = 0;
            markAllSlavesDetached();
            attachAllSlaves();
            setState(STATE_STREAMING);
            log("root direct-connect streaming: " + describe(device) + " " + width + "x" + height + "@" + fps);
            mainHandler.post(this::startForegroundSafely);
            updateNotification();
        } catch (Throwable t) {
            log("root 开流异常: " + android.util.Log.getStackTraceString(t));
            if (holder != null) {
                try { holder.release(); } catch (Throwable ignored) {}
            }
            try { conn.release(); } catch (Throwable ignored) {}
            setError("root 开流异常: " + t);
            if (usbConfig.autoReconnect) scheduleReconnect();
        }
    }

    private void requestPermissionOrOpen(UsbDevice device) {
        if (device == null || usbMonitor == null) {
            return;
        }
        try {
            // 已授权：直接打开，不弹窗。这条路径与授权计数无关。
            if (usbMonitor.hasPermission(device)) {
                log("已有 USB 权限，打开设备: " + describe(device));
                permissionRequestInFlight = false;
                usbMonitor.requestPermission(device); // 已授权时内部直接走 processOpenDevice
                return;
            }

            // 无权限：授权是用户交互动作，绝不自动弹窗/自动重试，否则会在用户来不及点"允许"
            // 之前就把计数打满（这正是"连续第三次拒绝"的成因）。
            if (!allowPermissionPrompt) {
                // 后台被目标应用拉起时走到这里：只提示，等用户到设置里手动授权或重新插拔设备
                log("检测到未授权设备但当前不允许弹窗（后台上下文），等待用户操作: " + describe(device));
                setState(STATE_WAITING_PERMISSION);
                return;
            }

            long now = SystemClock.elapsedRealtime();
            // 一次授权请求在结果回来前只发一次，彻底避免对话框显示期间的重复请求
            if (permissionRequestInFlight && now - permissionRequestAtMs < PERMISSION_REQUEST_TIMEOUT_MS) {
                log("授权请求进行中，跳过重复请求: " + describe(device));
                return;
            }

            permissionRequestInFlight = true;
            permissionRequestAtMs = now;
            // 消费掉这次"可弹窗"许可：失败后不会自动再弹，必须由用户重新插拔或再次点击触发
            allowPermissionPrompt = false;
            log("请求 USB 权限: " + describe(device));
            setState(STATE_WAITING_PERMISSION);
            usbMonitor.requestPermission(device);
        } catch (Exception e) {
            permissionRequestInFlight = false;
            log("请求 USB 权限异常: " + e);
            setError("请求 USB 权限异常: " + e);
        }
    }

    /** 允许下一次授权弹窗（用户前台操作 / 设备物理插入时调用）。 */
    private void armPermissionPrompt() {
        allowPermissionPrompt = true;
        permissionRequestInFlight = false;
        permissionDeniedCount = 0;
    }

    private UsbDevice findTargetDevice() {
        List<UsbDevice> devices = new ArrayList<>();
        try {
            if (usbMonitor != null) {
                List<UsbDevice> monitored = usbMonitor.getDeviceList();
                if (monitored != null) {
                    devices.addAll(monitored);
                }
            }
        } catch (Exception e) {
            log("USBMonitor 枚举设备失败: " + e);
        }
        if (devices.isEmpty() && usbManager != null) {
            try {
                devices.addAll(usbManager.getDeviceList().values());
            } catch (Exception e) {
                log("UsbManager 枚举设备失败: " + e);
            }
        }

        UsbDevice firstUvc = null;
        UsbDevice anyUvc = null;
        for (UsbDevice device : devices) {
            if (!isUvcDevice(device)) {
                continue;
            }
            if (anyUvc == null) {
                anyUvc = device;
            }
            if (matchesConfiguredDevice(device)) {
                if (usbConfig.hasExplicitDevice()) {
                    return device;
                }
                if (firstUvc == null) {
                    firstUvc = device;
                }
            }
        }
        if (firstUvc == null && anyUvc != null && usbConfig.hasExplicitDevice()) {
            log("已插入 UVC 设备 " + describe(anyUvc) + "，但与配置的设备 \""
                    + usbConfig.deviceName + "\" 不匹配；请在设置里重新选择设备或改回自动选择");
        }
        return firstUvc;
    }

    /**
     * 配置未指定设备时匹配任意 UVC 设备；指定后按 VID:PID 匹配。
     * <p>
     * 注意不能用 UsbDevice#getDeviceName（形如 /dev/bus/usb/001/003）作为长期标识：
     * 设备号每次插拔都会递增，用它匹配会导致重新插上后永远找不到设备。
     * 这里同时兼容旧版本写入的设备节点路径 / 产品名配置。
     */
    private boolean matchesConfiguredDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (!usbConfig.hasExplicitDevice()) {
            return true;
        }
        String configured = usbConfig.deviceName;
        int sep = configured.indexOf(':');
        if (sep > 0) {
            try {
                int vid = Integer.parseInt(configured.substring(0, sep).trim());
                int pid = Integer.parseInt(configured.substring(sep + 1).trim());
                return device.getVendorId() == vid && device.getProductId() == pid;
            } catch (NumberFormatException ignored) {
                // 不是 vid:pid 形式，落到下面的兼容分支
            }
        }
        return configured.equals(device.getDeviceName())
                || configured.equals(device.getProductName());
    }

    /** 设备的稳定标识（VID:PID），供设置界面写入配置。 */
    public static String deviceKey(UsbDevice device) {
        return device == null ? "" : device.getVendorId() + ":" + device.getProductId();
    }

    /** 判断是否为标准 UVC 设备：接口类为 Video(0x0E)，或 Misc(0xEF)/subclass 2 复合设备。 */
    private boolean isUvcDevice(UsbDevice device) {
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
        } catch (Exception e) {
            log("判定 UVC 设备异常: " + e);
        }
        return false;
    }

    /** worker 线程：打开 UVCCamera 并开流。 */
    private void startUvc(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        if (released) {
            return;
        }
        if (uvcCamera != null) {
            log("已有开流中的 UVC 实例，先释放");
            stopUvc();
        }
        UVCCamera camera = null;
        try {
            camera = new UVCCamera(new UVCParam(null, UVCCamera.getRecommendedPlatformQuirks()));
            int result = camera.open(ctrlBlock);
            if (result != 0 || !camera.isOpened()) {
                String detail = describeUvcOpenError(result);
                log("UVCCamera.open 失败, code=" + result + " (" + detail + ")");
                camera.destroy();
                setError("打开 UVC 失败 code=" + result + " " + detail);
                if (usbConfig.autoReconnect) {
                    scheduleReconnect();
                }
                return;
            }

            Size chosen = pickBestSize(camera.getSupportedSizeList(), usbConfig);
            if (chosen != null) {
                try {
                    camera.setPreviewSize(chosen);
                } catch (Exception e) {
                    log("设置分辨率 " + chosen.width + "x" + chosen.height + "@" + chosen.fps
                            + " 失败，沿用设备默认: " + e);
                }
            } else {
                log("设备未上报可用分辨率，沿用 open() 自动选择的默认值");
            }

            Size actual = camera.getPreviewSize();
            int width = actual != null && actual.width > 0 ? actual.width : usbConfig.width;
            int height = actual != null && actual.height > 0 ? actual.height : usbConfig.height;
            int fps = actual != null && actual.fps > 0 ? actual.fps : usbConfig.fps;

            RendererHolder holder = new RendererHolder(width, height, rendererHolderCallback);
            Surface primary = holder.getPrimarySurface();
            if (primary == null || !primary.isValid()) {
                log("RendererHolder 主 Surface 无效，放弃开流");
                holder.release();
                camera.destroy();
                setError("GL 渲染器初始化失败");
                if (usbConfig.autoReconnect) {
                    scheduleReconnect();
                }
                return;
            }

            hostFrameCount = 0;
            nativeFrameCount = 0;
            firstFrameLogged = false;
            camera.setPreviewDisplay(primary);
            try {
                camera.setFrameCallback(usbFrameCallback, UVCCamera.PIXEL_FORMAT_RAW);
            } catch (Throwable t) {
                log("setFrameCallback 失败（不影响预览）: " + t);
            }
            camera.startPreview();

            uvcCamera = camera;
            rendererHolder = holder;
            activeDevice = device;
            activeDeviceName = device != null ? device.getDeviceName() : "";
            activeWidth = width;
            activeHeight = height;
            activeFps = fps;
            lastFrameAtMs = SystemClock.elapsedRealtime();
            reconnectAttempt = 0;
            permissionDeniedCount = 0;

            markAllSlavesDetached();
            attachAllSlaves();

            setState(STATE_STREAMING);
            log("UVC 开流成功: " + describe(device) + " " + width + "x" + height + "@" + fps);
            // Android 14+ 的 connectedDevice 前台服务类型要求"已获得某个 USB 设备的访问授权"，
            // 此刻该前提才真正成立，所以在开流成功后再前台化一次（onCreate 时可能被拒）。
            mainHandler.post(this::startForegroundSafely);
            updateNotification();
        } catch (Throwable t) {
            log("UVC 开流异常: " + android.util.Log.getStackTraceString(t));
            if (camera != null) {
                try {
                    camera.destroy();
                } catch (Throwable ignored) {
                    // 释放失败无需再处理
                }
            }
            uvcCamera = null;
            setError("开流异常: " + t);
            if (usbConfig.autoReconnect) {
                scheduleReconnect();
            }
        }
    }

    /** 把 libuvc/libusb 的错误码翻译成可读原因。 */
    private static String describeUvcOpenError(int code) {
        switch (code) {
            case UVCCamera.UVC_ERROR_BUSY:
                return "设备被占用——多为系统相机 HAL 已把该采集卡接管，"
                        + "先关闭所有正在用相机的应用再试";
            case -1:
                return "打开 USB 设备失败——授权可能已失效，重新插拔采集卡";
            case -3:
                return "无访问权限——请在系统弹窗中选择\"允许\"";
            case -4:
                return "设备不存在——采集卡可能已被拔出";
            case -5:
                return "设备无响应——换一根 USB 线或换个口试试";
            case -12:
                return "不支持的设备——该设备未提供标准 UVC 视频接口";
            default:
                return "libuvc 错误";
        }
    }

    /** worker 线程：停止开流并释放 UVC / 渲染资源。 */
    private void stopUvc() {
        UsbRootConnector.Connection rc = rootConnection;
        if (rc != null) { rootConnection = null; uvcCamera = null;
            try { rc.release(); } catch (Throwable t) { log("root release ex: " + t); } }
        UVCCamera camera = uvcCamera;
        uvcCamera = null;
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Throwable t) {
                log("stopPreview 异常: " + t);
            }
            try {
                camera.destroy();
            } catch (Throwable t) {
                log("destroy 异常: " + t);
            }
        }

        RendererHolder holder = rendererHolder;
        rendererHolder = null;
        markAllSlavesDetached();
        if (holder != null) {
            try {
                holder.removeSlaveSurfaceAll();
            } catch (Throwable ignored) {
                // holder 可能已停止
            }
            try {
                holder.release();
            } catch (Throwable t) {
                log("RendererHolder 释放异常: " + t);
            }
        }

        activeDevice = null;
        activeDeviceName = "";
        activeWidth = 0;
        activeHeight = 0;
        activeFps = 0;
        if (camera != null || holder != null) {
            log("UVC 已停止开流");
        }
    }

    // ---- 诊断辅助 ----

    private static boolean safeIsOpened(UVCCamera camera) {
        try { return camera != null && camera.isOpened(); } catch (Throwable t) { return false; }
    }

    private static String describeSize(Size s) {
        if (s == null) return "null";
        String fmt = s.type == UVCCamera.UVC_VS_FRAME_MJPEG ? "MJPEG" : ("type" + s.type);
        return s.width + "x" + s.height + "@" + s.fps + "(" + fmt + ")";
    }

    /** 把设备上报的支持分辨率列表打进日志——排查「设备到底支持哪些格式」的第一手依据。 */
    private void logSupportedSizes(String tag, List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            log("[" + tag + "] 设备未上报任何支持分辨率（getSupportedSizeList 为空——"
                    + "多为 updateSupportedFormats 未生效或设备非标准 UVC）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(tag).append("] 设备支持 ").append(sizes.size()).append(" 种分辨率: ");
        int n = 0;
        for (Size s : sizes) {
            if (n++ > 0) sb.append(", ");
            sb.append(describeSize(s));
            if (n >= 12) { sb.append(" …"); break; }
        }
        log(sb.toString());
    }

    /**
     * 从设备上报的分辨率列表里挑选与配置最接近的一项。
     * 优先级：分辨率完全匹配 &gt; 帧率匹配 &gt; MJPEG 格式 &gt; 面积接近。
     */
    static Size pickBestSize(List<Size> sizes, UsbCaptureConfig config) {
        if (sizes == null || sizes.isEmpty() || config == null) {
            return null;
        }
        Size best = null;
        long bestScore = Long.MIN_VALUE;
        for (Size size : sizes) {
            if (size == null || size.width <= 0 || size.height <= 0) {
                continue;
            }
            long score = 0;
            if (size.width == config.width && size.height == config.height) {
                score += 1_000_000L;
            } else {
                long targetArea = (long) config.width * config.height;
                long area = (long) size.width * size.height;
                // 面积越接近得分越高，最多扣 100000 分
                score -= Math.min(100_000L, Math.abs(area - targetArea) / 100L);
            }
            if (supportsFps(size, config.fps)) {
                score += 200_000L;
            } else {
                score -= Math.min(50_000L, Math.abs(size.fps - config.fps) * 1000L);
            }
            // 同等条件下优先 MJPEG：USB2.0 采集卡在 720p/1080p 下只有 MJPEG 能跑满帧率
            if (size.type == UVCCamera.UVC_VS_FRAME_MJPEG) {
                score += 10_000L;
            }
            if (score > bestScore) {
                bestScore = score;
                best = size;
            }
        }
        if (best == null) {
            return null;
        }
        // 命中的 Size 可能带有多档帧率，尽量把 fps 调整到配置值
        if (!supportsFps(best, best.fps) || best.fps != config.fps) {
            if (supportsFps(best, config.fps)) {
                Size tuned = best.clone();
                tuned.fps = config.fps;
                return tuned;
            }
        }
        return best;
    }

    private static boolean supportsFps(Size size, int fps) {
        if (size == null) {
            return false;
        }
        if (size.fps == fps) {
            return true;
        }
        return size.fpsList != null && size.fpsList.contains(fps);
    }

    // =====================================================================
    // 断线重连
    // =====================================================================

    private void onDeviceLost(String reason) {
        log("设备连接丢失: " + reason);
        if (workerHandler == null) {
            return;
        }
        workerHandler.post(() -> {
            stopUvc();
            if (usbConfig.autoReconnect) {
                setState(STATE_RECONNECTING);
                scheduleReconnect();
            } else {
                setState(STATE_IDLE);
                log("自动重连已关闭，保持空闲");
            }
            updateNotification();
        });
    }

    private void scheduleReconnect() {
        if (released || workerHandler == null) {
            return;
        }
        if (!usbConfig.autoReconnect) {
            return;
        }
        long delay = Math.min(RECONNECT_MAX_DELAY_MS,
                RECONNECT_BASE_DELAY_MS * (1L << Math.min(reconnectAttempt, 4)));
        reconnectAttempt++;
        workerHandler.postDelayed(reconnectRunnable, delay);
        log("已安排第 " + reconnectAttempt + " 次重连，延迟 " + delay + "ms");
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (released || uvcCamera != null) {
                return;
            }
            log("尝试重连 UVC 设备…");
            scanAndOpen();
        }
    };

    /** 开流后长时间无帧，判定为假连接并重连。 */
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (released) {
                return;
            }
            if (state == STATE_STREAMING && uvcCamera != null) {
                long idle = SystemClock.elapsedRealtime() - lastFrameAtMs;
                if (idle > FRAME_WATCHDOG_TIMEOUT_MS) {
                    // 详细诊断：把 native 帧数 / 宿主 GL 帧数 一并打出来，方便区分故障阶段：
                    //   nativeFrameCount==0            → 设备/libuvc/fd 根本没出帧（USB 源问题）
                    //   nativeFrameCount>0 且 host==0  → native 出帧了但没渲染到宿主 GL（预览显示/GL 问题）
                    //   host>0                          → 宿主有画面，问题在跨进程分发到目标 App
                    log("看门狗：已 " + idle + "ms 无新帧 → 触发重连"
                            + " [native帧=" + nativeFrameCount + " 宿主GL帧=" + hostFrameCount
                            + " 分辨率=" + activeWidth + "x" + activeHeight + "@" + activeFps
                            + " root直连=" + (rootConnection != null) + "]");
                    stopUvc();
                    reconnectAttempt = 0;
                    setState(STATE_RECONNECTING);
                    scheduleReconnect();
                }
            }
            if (workerHandler != null && !released) {
                workerHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }
    };

    private final RendererHolderCallback rendererHolderCallback = new RendererHolderCallback() {
        @Override
        public void onPrimarySurfaceCreate(Surface surface) {
            log("RendererHolder 主 Surface 已创建");
        }

        @Override
        public void onFrameAvailable() {
            lastFrameAtMs = SystemClock.elapsedRealtime();
            long n = ++hostFrameCount;
            if (n == 1) {
                log("宿主 GL 主 Surface 收到首帧（画面已到达宿主）"
                        + " [native帧=" + nativeFrameCount + "]");
            }
        }

        @Override
        public void onPrimarySurfaceDestroy() {
            log("RendererHolder 主 Surface 已销毁");
        }
    };

    /**
     * libuvc native 帧回调：仅用于诊断与看门狗兜底——证明设备/fd 是否真的在出帧。
     * 不消费 buffer 内容（PIXEL_FORMAT_RAW，开销最小）。同时刷新 lastFrameAtMs，
     * 这样即便宿主 GL 的 SurfaceTexture 回调因故不触发，只要 native 有帧看门狗就不会误重连。
     */
    private final IFrameCallback usbFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(java.nio.ByteBuffer frame) {
            lastFrameAtMs = SystemClock.elapsedRealtime();
            long n = ++nativeFrameCount;
            if (n == 1) {
                int sz = frame != null ? frame.remaining() : -1;
                log("libuvc native 收到首帧（设备/fd 正常出帧）大小=" + sz + " 字节");
            } else if (n % 150 == 0) {
                // 每约 5 秒（30fps）打一次心跳，确认持续出帧
                log("libuvc native 持续出帧: 累计 " + n + " 帧, 宿主GL " + hostFrameCount + " 帧");
            }
        }
    };

    // =====================================================================
    // 配置变更
    // =====================================================================

    private void applyConfigIfChanged(UsbCaptureConfig newConfig) {
        if (newConfig == null || newConfig.equals(usbConfig)) {
            return;
        }
        log("配置变更: " + usbConfig + " -> " + newConfig);
        usbConfig = newConfig;
        if (workerHandler != null) {
            workerHandler.post(() -> {
                stopUvc();
                reconnectAttempt = 0;
                permissionDeniedCount = 0;
                scanAndOpen();
            });
        }
    }

    // =====================================================================
    // 状态广播 / 通知
    // =====================================================================

    /** 进入错误态并记录可读原因（通知栏与日志都会带上）。 */
    private void setError(String reason) {
        lastErrorReason = reason != null ? reason : "";
        log("进入错误态: " + lastErrorReason);
        if (state == STATE_ERROR) {
            // 状态未变化时 setState 会短路，这里补一次通知刷新让原因可见
            mainHandler.post(this::updateNotification);
            return;
        }
        setState(STATE_ERROR);
    }

    private void setState(int newState) {
        if (state == newState) {
            return;
        }
        if (newState != STATE_ERROR) {
            lastErrorReason = "";
        }
        state = newState;
        broadcastState();
        mainHandler.post(this::updateNotification);
    }

    private void broadcastState() {
        try {
            Set<String> targets = configManager != null ? configManager.getTargetPackages() : null;
            Intent intent = new Intent(IpcContract.ACTION_USB_STATE_CHANGED);
            intent.putExtra(IpcContract.EXTRA_USB_STATE, state);
            intent.putExtra(IpcContract.EXTRA_USB_CONNECTED, state == STATE_STREAMING);
            intent.putExtra(IpcContract.EXTRA_USB_DEVICE_NAME, activeDeviceName);
            if (targets == null || targets.isEmpty()) {
                sendBroadcast(intent);
            } else {
                for (String pkg : targets) {
                    Intent scoped = new Intent(intent);
                    scoped.setPackage(pkg);
                    sendBroadcast(scoped);
                }
                Intent self = new Intent(intent);
                self.setPackage(getPackageName());
                sendBroadcast(self);
            }
        } catch (Exception e) {
            log("广播 USB 状态失败: " + e);
        }
    }

    private void startForegroundSafely() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            // Android 12+ 后台启动前台服务受限；此时降级为普通绑定服务，功能不受影响
            log("前台化失败，降级为普通绑定服务: " + t);
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception e) {
            log("更新通知失败: " + e);
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.usb_notif_title))
                .setContentText(describeState())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private String describeState() {
        switch (state) {
            case STATE_WAITING_DEVICE:
                return getString(R.string.usb_state_waiting_device);
            case STATE_WAITING_PERMISSION:
                return getString(R.string.usb_state_waiting_permission);
            case STATE_STREAMING:
                return getString(R.string.usb_state_streaming) + " "
                        + activeWidth + "x" + activeHeight + "@" + activeFps;
            case STATE_RECONNECTING:
                return getString(R.string.usb_state_reconnecting);
            case STATE_ERROR:
                return lastErrorReason.isEmpty()
                        ? getString(R.string.usb_state_error)
                        : getString(R.string.usb_state_error) + "：" + lastErrorReason;
            default:
                return getString(R.string.usb_state_idle);
        }
    }

    private void createNotificationChannel() {
        // minSdk 26，通知渠道始终需要创建
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.usb_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.usb_notif_channel_desc));
        channel.enableLights(false);
        channel.enableVibration(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

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
