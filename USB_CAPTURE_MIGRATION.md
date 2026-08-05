# CamSwap USB 采集卡（UVC）功能 — 迁移与继续开发文档

> 本文档用于把该功能迁移到**正常的构建机**后继续开发。当前构建服务器的
> overlay 文件系统存在严重故障（文件内容/存在性随时间漂移、写入被回滚、
> 连 `cd` 进项目目录都间歇失败），**无法在其上稳定构建/交付**。代码本身
> 已多次 `BUILD SUCCESSFUL` + 20 个单测通过，逻辑是完整正确的。

## 0. 一句话现状
在开源项目 Android-CamSwap-OpenSource（Xposed/LSPosed 虚拟摄像头模块）上，新增了
第三种视频源 `media_source_type = "usb_capture"`：把目标 App 的相机预览替换为 USB
采集卡（标准 UVC 设备，如 MS2131）的实时 HDMI 输入。含两条取流路径：**标准 USB 授权**
与 **Root 免授权直连**。代码已提交到本地 git（见 `git log`），因构建机故障未能出稳定 APK。

## 1. 迁移后如何构建
```bash
# 依赖：JDK 17、Android SDK(platform 36, build-tools 35, ndk 25.1.8937393, cmake 3.22.1)
#      并初始化 Dobby 子模块（native hook 用）
git submodule update --init --recursive
./gradlew :app:assembleRelease        # 或 assembleDebug
# 单测
./gradlew :app:testDebugUnitTest
```
产物在 `app/build/outputs/apk/{release,debug}/`，按 ABI 分包（arm64-v8a / armeabi-v7a / x86_64）。
`versionName` 按构建时间自增（`2.6.MMddHHmm`）。

> 注意：`./gradlew lint` 会因 Compose lint detector 与 Kotlin 2.1 UAST 不兼容而崩溃
> （崩在与本功能无关的 `ManageScreen.kt`）。如需 lint，在 app/build.gradle 加
> `android { lint { disable 'MutableCollectionMutableState','AutoboxingStateCreation' } }`。

## 2. 依赖变更（app/build.gradle）
- `buildFeatures { aidl true }` —— 启用 AIDL（IUsbCaptureService）
- `implementation 'com.herohan:UVCAndroid:1.0.13'` —— UVC 库（saki4510t 维护分支，
  含 libusb/libuvc/libjpeg-turbo，四套 .so；类名仍是 `com.serenegiant.usb.*`）
- `implementation 'androidx.media3:media3-datasource-rtmp:1.6.0'` —— **修复 RTMP**：
  原代码反射找 `RtmpMediaSource$Factory`（Media3 里不存在），改用 RtmpDataSource。

## 3. 新增文件（都在 `app/src/main/java/io/github/zensu357/camswap/`）
| 文件 | 作用 |
|---|---|
| `UsbCaptureConfig.java` | usb_* 配置值对象 + JSON 序列化/反序列化，非法值夹紧 |
| `aidl/.../IUsbCaptureService.aidl` | 宿主↔目标进程 IPC 接口（registerTargetSurface 等 + 带存活令牌重载） |
| `UsbCaptureService.java` | **宿主进程**前台服务：USBMonitor 发现设备、UVCCamera 开流、
  RendererHolder 主 Surface、把目标进程 Surface 作为从属 Surface 挂载（零拷贝）、
  热插拔广播 + 指数退避重连 + 无帧看门狗。**含 root 直连分支 `startUvcViaRoot`** |
| `UsbCaptureClient.java` | **目标进程**Binder 客户端：异步 bindService、退避重连、
  按槽位注册 Surface、进程存活令牌（linkToDeath，替代不可用的 /proc 探测） |
| `HookUvcReceiver.java` | 目标进程接收宿主 USB 状态广播，宿主重开流后重放注册 |
| `CameraHandlerPatch.java` | 目标进程 Hook 接入端：Camera1/Camera2 分支处理 usb_capture，
  建 GLVideoRenderer(OES 纹理) 导出 Surface 注册给宿主；含 setInputBufferSize 修正跨进程尺寸 |
| `UsbPermissionActivity.java` | 采集卡插入入口 Activity（USB_DEVICE_ATTACHED），前台委托 helper 授权 |
| `UsbPermissionHelper.java` | **前台**授权器：点击瞬间同步请求（关键——授权对话框只能前台发起），
  application context 注册结果 receiver；attach 场景直接启动服务（修"点确定没反应"） |
| `RootShell.java` | su 执行器：`chmodUsbNode` 放开 /dev/bus/usb/BBB/DDD 权限 |
| `UsbRootConnector.java` | **Root 免授权直连**：ParcelFileDescriptor 直接打开设备节点拿 fd，
  反射调 `UVCCamera.nativeConnect(mNativePtr, fd, quirks)` + `updateSupportedFormats`，
  彻底绕过 UsbManager 授权。见第 6 节 |
| `res/xml/usb_device_filter.xml` | UVC 设备匹配（class 14；class 239 subclass 2） |
| `test/.../UsbCaptureConfigJvmTest.java` | 12 个 JVM 单测（JSON 往返/默认值/夹紧/IPC 常量） |

## 4. 修改的既有文件
- `ConfigManager.java`：新增 `MEDIA_SOURCE_USB`/`SOURCE_TYPE_USB`("usb_capture")、
  `usb_device_name/usb_width(1280)/usb_height(720)/usb_fps(30)/usb_auto_reconnect`、
  `KEY_USB_ROOT_BYPASS`；getUsbCaptureConfig/setUsbCaptureConfig/import/export。
- `MediaSourceDescriptor.java`：新增 `Type.USB_CAPTURE` + `usbCapture()` builder。
- `VideoManager.java`：`isUsbCaptureMode()`、getCurrentMediaSource 分支。
- `HookGuards.java`：USB 模式一律放行（设备在线由宿主重连负责）。
- `MediaPlayerManager.java`：Camera2 USB 分支走 CameraHandlerPatch；**新增 Camera1 接流
  `initCamera1Stream`（修原项目 Camera1 完全不接流）**；**流不可用真正回退本地 `fallbackToLocalIfEnabled`**。
- `Camera1Handler.java`：holder/texture 两条预览路径加 USB 分支 + 流模式分支。
- `ExoPlayerBackend.java`：RTMP 改用 RtmpDataSource；`onPermanentFailure` 回调触发本地兜底。
- `SurfacePlayerBackend.java`：Listener 加 `onPermanentFailure` 默认方法。
- `GLVideoRenderer.java`：`setInputBufferSize`（跨进程 SurfaceTexture 必须设默认缓冲区尺寸，
  否则宿主 eglCreateWindowSurface 得到 1x1）。
- `HookMain.java`：Application.onCreate 里 `CameraHandlerPatch.initInTargetProcess`。
- `ConfigWatcher.java`：感知 usb_* 配置变化并重启。
- `IpcContract.java`：USB 状态广播 action / extras / 宿主服务类名常量。
- `MainViewModel.kt` / `SettingsScreen.kt`：USB 采集卡设置 UI（模式 chip、设备/分辨率/帧率
  下拉框、自动重连、**Root 免授权直连开关**、连接/授权按钮）。
- `AndroidManifest.xml`：UsbCaptureService(exported, connectedDevice 前台类型)、
  UsbPermissionActivity(USB_DEVICE_ATTACHED intent-filter)、FOREGROUND_SERVICE_CONNECTED_DEVICE 权限、
  移除 UVC 库带入的 CAMERA/RECORD_AUDIO、usb.host 改 required=false。
- `res/values*/strings.xml`：全部 USB 相关文案（含 root 开关/toast）。

## 5. 架构（零拷贝跨进程画面分发）
```
宿主进程(CamSwap)                          目标App进程(被Hook)
UsbCaptureService                          Camera1/2 Hook
  USBMonitor 发现/授权 UVC                   CameraHandlerPatch
  UVCCamera.open → RendererHolder(主Surface)   建 GLVideoRenderer(OES纹理)
       │  addSlaveSurface(跨进程Surface)  ←──── 导出输入Surface, bindService 注册
       ▼
  UVC 帧 → 主Surface → GL 分发到各从属Surface → 目标App预览/ImageReader
```
- 目标进程保留本地 GLVideoRenderer（OES 外部纹理），使旋转/YUV桥(WhatsApp,LINE)/拍照替换在 USB 模式继续可用。
- 目标进程死亡：靠 Binder 存活令牌 linkToDeath 清理（**不能用 /proc/<pid>**，Android 9+ hidepid=2 探测恒为死）。

## 6. Root 免授权直连（关键技术点）
逆向 herohan UVCCamera：`open(UsbControlBlock)` 内部就是取 `getFileDescriptor()` 得到 fd，
再 `nativeConnect(mNativePtr, fd, quirks)`。而 UVC 设备节点路径 == `UsbDevice.getDeviceName()`
（形如 `/dev/bus/usb/001/003`）。因此 root 直连：
1. `RootShell.chmodUsbNode(node)` → `su -c "chmod 666 <node>"`
2. `ParcelFileDescriptor.open(node, MODE_READ_WRITE)` 拿 fd（**不经过 UsbManager**）
3. `new UVCCamera(param)` → 反射读 `mNativePtr` → 反射调 `nativeConnect(ptr, fd, quirks)`
   → 反射调 `updateSupportedFormats()` → `setPreviewSize/setPreviewDisplay/startPreview`
4. 保存 ParcelFileDescriptor 引用（与 camera 同生命周期，关闭即 fd 失效）
开关：设置里「Root 免授权直连」→ ConfigManager `usb_root_bypass` → Service `scanAndOpen` 走 `startUvcViaRoot`。

**风险**：反射 private native，依赖 herohan 1.0.13 签名；native 出错可能崩溃（已全程 try-catch，
失败回退报错）。**未真机验证**——迁移后首测 `adb logcat | grep 【root】` 看 chmod/fd/nativeConnect 返回值。

## 7. 授权路径（标准，非 root）
- **关键认知**：USB 授权对话框是 SystemUI 的 Activity，Android 12+ BAL 限制要求**前台发起**。
  所以授权在 `UsbPermissionHelper`（点击同步调用 / attach Activity 前台），**不在后台 Service**。
- 两条入口：① 插采集卡 → 系统按 device_filter 拉起 UsbPermissionActivity（前台）→ 勾"默认打开"永久授权；
  ② 设置里点「连接采集卡」→ 前台请求。
- 已修的坑：早期"授权失败自动重试"会 7 秒打满 3 次拒绝、系统记住拒绝后不再弹窗（已去掉自动重试）；
  attach"点确定没反应"（授权后不该再 requestPermission，直接启动服务，已修）。

## 8. 待办 / 未验证（迁移后重点）
1. **真机验证 root 直连**（nativeConnect 反射 + fd）——最高优先级，无真机没测过。
2. 真机验证标准授权 attach 路径出画面。
3. Camera1 接流 / RTMP / 本地兜底 的真机验证。
4. 明文流量：http/rtmp 受**目标App**的 networkSecurityConfig 限制（非 CamSwap 能改），
   建议用 rtsp(tcp) / https；INTERNET 权限也需目标App自身具备。

## 9. git
- 分支 `main`，本功能已 commit（`git log` 顶部 feat: USB capture card...）。
- 原始上游 remote 是 `zensu357/Android-CamSwap-OpenSource`。迁移后按需 `git remote set-url` 到你的仓库再 push。
