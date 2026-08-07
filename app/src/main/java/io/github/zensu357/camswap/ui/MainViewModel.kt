package io.github.zensu357.camswap.ui

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zensu357.camswap.ConfigManager
import io.github.zensu357.camswap.UsbCaptureConfig
import io.github.zensu357.camswap.UsbCaptureService
import io.github.zensu357.camswap.UsbPermissionActivity
import io.github.zensu357.camswap.UsbPermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MainUiState(
    val isModuleDisabled: Boolean = false,
    val playVideoSound: Boolean = false,
    val forcePrivateDir: Boolean = false,
    val disableToast: Boolean = false,
    val enableRandomPlay: Boolean = false,
    val enableMicHook: Boolean = false,
    val micHookMode: String = "mute",
    val enablePhotoFake: Boolean = false,

    val notificationControlEnabled: Boolean = false,
    val overlayControlEnabled: Boolean = false,
    val hasPermission: Boolean = false,
    val isXposedActive: Boolean = false,
    val targetAppsCount: Int = 0,
    val originalVideoName: String? = null,
    val latestVersion: String? = null,

    // Stream mode
    val mediaSourceType: String = ConfigManager.MEDIA_SOURCE_LOCAL,
    val streamUrl: String = "",
    val streamAutoReconnect: Boolean = true,
    val streamLocalFallback: Boolean = true,
    val streamTransportHint: String = "auto",
    val streamTimeoutMs: Long = 8000L,

    // USB capture card (UVC) mode
    val usbDeviceName: String = "",
    val usbWidth: Int = ConfigManager.DEFAULT_USB_WIDTH,
    val usbHeight: Int = ConfigManager.DEFAULT_USB_HEIGHT,
    val usbFps: Int = ConfigManager.DEFAULT_USB_FPS,
    val usbAutoReconnect: Boolean = ConfigManager.DEFAULT_USB_AUTO_RECONNECT,
    /** root 免授权直连开关 */
    val usbRootBypass: Boolean = false,
    /** 当前已插入的 UVC 设备列表，用于设备下拉框 */
    val usbDevices: List<UsbDeviceOption> = emptyList()
)

/** 设备下拉框的一项。deviceName 为空表示"自动选择"。 */
data class UsbDeviceOption(val deviceName: String, val label: String)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val configManager = ConfigManager()
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
        checkLatestVersion()
    }

    fun loadConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.reload()
            val usbConfig = configManager.usbCaptureConfig
            _uiState.update { currentState ->
                currentState.copy(
                    isModuleDisabled = configManager.getBoolean(ConfigManager.KEY_DISABLE_MODULE, false),
                    playVideoSound = configManager.getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false),
                    forcePrivateDir = configManager.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false),
                    disableToast = configManager.getBoolean(ConfigManager.KEY_DISABLE_TOAST, false),
                    enableRandomPlay = configManager.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false),
                    enableMicHook = configManager.getBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, false),
                    micHookMode = configManager.getString(ConfigManager.KEY_MIC_HOOK_MODE, ConfigManager.MIC_MODE_MUTE),
                    enablePhotoFake = configManager.getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false),
                    notificationControlEnabled = configManager.getBoolean(ConfigManager.KEY_NOTIFICATION_CONTROL_ENABLED, false),
                    overlayControlEnabled = configManager.getBoolean(ConfigManager.KEY_OVERLAY_CONTROL_ENABLED, false),
                    targetAppsCount = configManager.targetPackages.size,
                    originalVideoName = configManager.getString(ConfigManager.KEY_ORIGINAL_VIDEO_NAME, null),
                    // Stream config
                    mediaSourceType = configManager.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL).trim(),
                    streamUrl = configManager.getString(ConfigManager.KEY_STREAM_URL, "").trim(),
                    streamAutoReconnect = configManager.getBoolean(ConfigManager.KEY_STREAM_AUTO_RECONNECT, true),
                    streamLocalFallback = configManager.getBoolean(ConfigManager.KEY_STREAM_LOCAL_FALLBACK, true),
                    streamTransportHint = configManager.getString(ConfigManager.KEY_STREAM_TRANSPORT_HINT, "auto"),
                    streamTimeoutMs = configManager.getLong(ConfigManager.KEY_STREAM_TIMEOUT_MS, 8000L),
                    // USB capture config
                    usbDeviceName = usbConfig.deviceName,
                    usbWidth = usbConfig.width,
                    usbHeight = usbConfig.height,
                    usbFps = usbConfig.fps,
                    usbAutoReconnect = usbConfig.autoReconnect,
                    usbRootBypass = configManager.getBoolean(ConfigManager.KEY_USB_ROOT_BYPASS, false)
                )
            }
            refreshUsbDevices()
        }
    }

    fun setModuleDisabled(disabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_DISABLE_MODULE, disabled)
            _uiState.update { it.copy(isModuleDisabled = disabled) }
        }
    }

    fun setPlayVideoSound(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, enabled)
            _uiState.update { it.copy(playVideoSound = enabled) }
        }
    }

    fun setForcePrivateDir(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, enabled)
            _uiState.update { it.copy(forcePrivateDir = enabled) }
        }
    }

    fun setDisableToast(disabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_DISABLE_TOAST, disabled)
            _uiState.update { it.copy(disableToast = disabled) }
        }
    }

    fun setEnableRandomPlay(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, enabled)
            _uiState.update { it.copy(enableRandomPlay = enabled) }
        }
    }

    fun setEnableMicHook(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, enabled)
            _uiState.update { it.copy(enableMicHook = enabled) }
        }
    }

    fun setMicHookMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setString(ConfigManager.KEY_MIC_HOOK_MODE, mode)
            _uiState.update { it.copy(micHookMode = mode) }
        }
    }

    fun setEnablePhotoFake(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, enabled)
            _uiState.update { it.copy(enablePhotoFake = enabled) }
        }
    }

    fun setNotificationControlEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_NOTIFICATION_CONTROL_ENABLED, enabled)
            _uiState.update { it.copy(notificationControlEnabled = enabled) }
        }
    }

    fun setOverlayControlEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_OVERLAY_CONTROL_ENABLED, enabled)
            _uiState.update { it.copy(overlayControlEnabled = enabled) }
        }
    }

    // ---- Stream config setters ----

    fun setMediaSourceType(type: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = type.trim()
            configManager.setString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, normalized)
            _uiState.update { it.copy(mediaSourceType = normalized) }
        }
    }

    fun setStreamUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 去掉首尾空白与意外换行，避免 isNotBlank 判断失败导致首页「未设置/已暂停」
            val normalized = url.trim()
            configManager.setString(ConfigManager.KEY_STREAM_URL, normalized)
            _uiState.update { it.copy(streamUrl = normalized) }
        }
    }

    fun setStreamAutoReconnect(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_STREAM_AUTO_RECONNECT, enabled)
            _uiState.update { it.copy(streamAutoReconnect = enabled) }
        }
    }

    fun setStreamLocalFallback(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_STREAM_LOCAL_FALLBACK, enabled)
            _uiState.update { it.copy(streamLocalFallback = enabled) }
        }
    }

    fun setStreamTransportHint(hint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setString(ConfigManager.KEY_STREAM_TRANSPORT_HINT, hint)
            _uiState.update { it.copy(streamTransportHint = hint) }
        }
    }

    fun setStreamTimeoutMs(timeout: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setLong(ConfigManager.KEY_STREAM_TIMEOUT_MS, timeout)
            _uiState.update { it.copy(streamTimeoutMs = timeout) }
        }
    }

    // ---- USB capture card (UVC) config setters ----

    private fun currentUsbConfig(): UsbCaptureConfig {
        val state = _uiState.value
        return UsbCaptureConfig(
            state.usbDeviceName,
            state.usbWidth,
            state.usbHeight,
            state.usbFps,
            state.usbAutoReconnect
        )
    }

    private fun persistUsbConfig(config: UsbCaptureConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setUsbCaptureConfig(config)
            // 采集服务持有自己的 ConfigManager 实例，需要显式让它重新读取配置并按新参数重开流。
            // 此处应用处于前台，startForegroundService 不受 Android 12+ 后台启动限制。
            if (_uiState.value.mediaSourceType == ConfigManager.MEDIA_SOURCE_USB) {
                UsbPermissionActivity.startCaptureService(getApplication())
            }
        }
    }

    fun setUsbDeviceName(deviceName: String) {
        _uiState.update { it.copy(usbDeviceName = deviceName) }
        persistUsbConfig(currentUsbConfig())
    }

    fun setUsbResolution(width: Int, height: Int) {
        _uiState.update { it.copy(usbWidth = width, usbHeight = height) }
        persistUsbConfig(currentUsbConfig())
    }

    fun setUsbFps(fps: Int) {
        _uiState.update { it.copy(usbFps = fps) }
        persistUsbConfig(currentUsbConfig())
    }

    fun setUsbAutoReconnect(enabled: Boolean) {
        _uiState.update { it.copy(usbAutoReconnect = enabled) }
        persistUsbConfig(currentUsbConfig())
    }

    /** 切换 root 免授权直连；持久化后重启采集服务使其立即生效。 */
    fun setUsbRootBypass(enabled: Boolean, context: Context) {
        _uiState.update { it.copy(usbRootBypass = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_USB_ROOT_BYPASS, enabled)
            if (_uiState.value.mediaSourceType == ConfigManager.MEDIA_SOURCE_USB) {
                // 开启 root 直连不需要系统授权，直接（重新）启动服务即可
                UsbPermissionActivity.startCaptureService(context)
            }
        }
    }

    /** 枚举当前已插入的 UVC 设备，填充设备下拉框。 */
    fun refreshUsbDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            val options = mutableListOf<UsbDeviceOption>()
            try {
                val usbManager = getApplication<Application>()
                    .getSystemService(Context.USB_SERVICE) as? UsbManager
                usbManager?.deviceList?.values?.forEach { device ->
                    if (isUvcDevice(device)) {
                        val product = device.productName?.takeIf { it.isNotBlank() } ?: "UVC Device"
                        // 用 VID:PID 作为持久标识——设备节点路径每次插拔都会变
                        options.add(
                            UsbDeviceOption(
                                UsbCaptureService.deviceKey(device),
                                "$product  ${device.vendorId}:${device.productId}"
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                // 部分 ROM 在无 USB Host 时抛异常，忽略即可
            }
            _uiState.update { it.copy(usbDevices = options) }
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        return try {
            if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
            if (device.deviceClass == UsbConstants.USB_CLASS_MISC && device.deviceSubclass == 2) return true
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 请求 USB 授权并启动采集服务。
     *
     * USB 授权对话框能否弹出取决于「调用那一刻进程是否在前台」。本方法必须在用户点击回调里
     * 同步调用（此刻界面在前台），由 UsbPermissionHelper 直接请求授权，不做任何 Activity 跳转。
     * 传入当前界面的 Context 即可。
     */
    fun requestUsbPermission(context: Context) {
        UsbPermissionHelper.requestAndStart(context)
    }

    /** 兼容旧调用名。 */
    fun startUsbCaptureService(context: Context) {
        UsbPermissionHelper.requestAndStart(context)
    }

    /** 是否检测到已插入的 UVC 采集卡（供界面显示"连接"入口）。 */
    fun hasUvcDevice(context: Context): Boolean {
        return UsbPermissionHelper.hasUvcDevice(context)
    }

    /** 停止宿主 USB 采集卡服务。 */
    fun stopUsbCaptureService(context: Context) {
        UsbPermissionActivity.stopCaptureService(context)
    }

    fun updatePermissionStatus(hasPermission: Boolean) {
        _uiState.update { it.copy(hasPermission = hasPermission) }
    }

    fun updateXposedStatus(isActive: Boolean) {
        _uiState.update { it.copy(isXposedActive = isActive) }
    }

    fun setLanguage(context: Context, language: String) {
        io.github.zensu357.camswap.utils.LocaleHelper.setLocale(context, language)
        // Restart app to apply changes
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun checkLatestVersion() {
        _uiState.update { it.copy(latestVersion = null) }
    }
}
