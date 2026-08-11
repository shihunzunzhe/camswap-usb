# Virtual Mic HAL —— Magisk 系统级虚拟麦克风

系统级"物理麦克风静音 + 强制注入 RTMP 音频流"方案。与仓库现有的
**进程内 Hook**（Xposed + Dobby，`app/src/main/cpp/`）互补：本模块工作在
**vendor HAL 层**，App 走 Native 也绕不过去。

## 完整目录结构（阶段 0）

```
virtual-mic-hal/
├── README.md                     # 本文件（进度总览 + 构建说明）
├── hal/                          # 阶段 1-3：vendor HAL 代理 .so（独立 NDK/CMake 构建）
│   ├── CMakeLists.txt            # [构建] 首选：NDK 工具链 + CMake
│   ├── Android.mk                # [构建] 备选：ndk-build
│   ├── Application.mk            # [构建] ndk-build 全局配置
│   ├── include/
│   │   ├── vmic_config.h         # 全局共享常量（socket/格式/RingBuffer/积压上限）
│   │   └── aosp/                 # vendored AOSP HAL 头文件（独立构建用，ABI 与真机对齐）
│   │       ├── hardware/hardware.h
│   │       ├── hardware/audio.h
│   │       └── system/audio.h
│   ├── vmic_audio_util.h         # 音频格式辅助（bytes/sample、声道数、frameSize）
│   ├── virtual_audio_hal.h       # 内部声明 + 日志宏
│   ├── virtual_audio_hal.cpp     # [阶段1] audio_module + open/read 结构体劫持
│   │                             # [阶段2] 挂载 SocketServer + RingBuffer
│   ├── AudioRingBuffer.h/.cpp    # [阶段2] SPSC 无锁环形缓冲 ✅
│   ├── SocketServer.h/.cpp       # [阶段2] Abstract UDS 监听 + 收流 ✅
│   ├── Resampler.h/.cpp          # [阶段3] 重采样(插值+混音+位深转换) ✅
│   └── ClockPacer.h/.cpp         # [阶段3] 精准 1x 时钟控速 ✅
├── sender/                       # 阶段 4：RTMP PCM 推送客户端 ✅
│   ├── native/
│   │   ├── vmic_sender.h/.cpp    # C++ 客户端(+ 可选 CLI demo)
│   │   ├── Android.mk            # 构建 libvmicsender.so + vmic_sender_demo
│   │   └── Application.mk
│   └── java/io/github/zensu357/camswap/vmic/VirtualMicSender.java
├── magisk-module/                # Magisk 打包 ✅
│   ├── module.prop               # 模块信息
│   ├── customize.sh              # 探测 SoC / 备份原厂 / 放入代理(重装安全)
│   ├── sepolicy.rule             # SELinux 放行(UDS 服务端 + 客户端 connectto)
│   ├── post-fs-data.sh           # 开机完整性自检 + 日志
│   ├── service.sh                # late_start UDS 监听状态诊断
│   ├── META-INF/.../update-binary + updater-script   # Magisk 安装器桩
│   └── prebuilt/{arm64,arm,x64,x86}/audio.primary.vmicproxy.so  # NDK 产物(打包前放入)
└── docs/
    ├── ARCHITECTURE.md           # 架构 + 数据链路 ASCII 图 + 指针劫持/控速原理
    └── TROUBLESHOOTING.md        # 真机联调排障手册

（仓库根还有 .github/workflows/build.yml —— 云端一键编译打包,见下）
```

## 构建（独立 NDK / CMake）

产物为 `audio.primary.<soc>.so`；`<soc>` = 设备 `getprop ro.board.platform` 的值。

**首选：CMake + NDK 工具链**

```bash
cd hal
cmake -B build/arm64 -S . \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DVMIC_SOC=kona
cmake --build build/arm64
# 产物：build/arm64/audio.primary.kona.so
```

**备选：ndk-build**

```bash
cd hal
$ANDROID_NDK/ndk-build VMIC_SOC=kona
# 产物：libs/arm64-v8a/audio.primary.kona.so
```

> AOSP 关联头文件已 vendored 到 `hal/include/aosp/`，**无需 AOSP 源码树**即可编译。
> 这些头文件按 AOSP legacy audio HAL 的真实字段顺序复刻，保证代理"就地补丁函数指针"
> 时与真机 vendor HAL 的 ABI 严格对齐。

## 阶段进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| 0 | 项目目录树 | ✅ |
| 1 | HAL 骨架 + 代理拦截（`audio_module` / `audio_stream_in.read`） | ✅ |
| 2 | IPC Socket 监听 + 无锁 RingBuffer（+ 挂载对接） | ✅ |
| 3 | 重采样 + 1 倍速时钟控速 + 静音填充 | ✅ |
| 4 | RTMP 客户端推送示例（Native + Java，含重连/背压） | ✅ |
| 5 | Magisk 模块打包与部署（探测/备份/overlay/SELinux） | ✅ |
| 6 | 云端一键编译打包（GitHub Actions）+ 架构/排障文档 | ✅ |

**完整数据流（阶段 3 后）**：Sender(RTMP 端, 48000/2ch/S16) → `@virtual_mic_socket`
→ `SocketServer` 收流 → `AudioRingBuffer`(SPSC 无锁) → `proxy_read`：
① `discardOldestDownTo(≈300ms)` 只读最新控延迟 →
② `Resampler.fill()` 线性插值重采样 + 立体声混音 + 位深转换到 App 请求的
`rate/chmask/format`(如 16000/mono/S16) →
③ 不足补 `0x00` 静音 →
④ `ClockPacer.pace()` 按单调时钟绝对截止时刻精准锁 1x(快读被拖回, 长期无漂移,
落后超 50ms 重置基线防突发)。

**Resampler 说明**：轻量线性插值(低依赖/低延迟)，支持
S16 / FLOAT / 8-bit(无符号) / 32-bit / 8.24 / 24-bit-packed 输出，
立体声↔单声道下混。下采样(如 48k→16k)不含抗混叠低通；若需更高保真，
可将 `Resampler::fill` 内部换成 FFmpeg `libswresample`（`swr_convert`），
`proxy_read` 侧接口不变。

## Sender 客户端

**源格式约定**：`push()` 传入的 PCM 必须为 **48000Hz / 2ch / S16LE**（与 HAL 端
`VMIC_SRC_*` 一致）；重采样到 App 请求格式由 HAL 内部完成。socket 名须与
`VMIC_SOCKET_NAME`（`virtual_mic_socket`）一致。

**Native（C++）**：`VmicSender sender; sender.start(); sender.push(pcm, bytes);`
后台线程负责连接/发送/断线重连；`push()` 非阻塞，缓冲满丢最旧（背压）。
构建：`cd sender/native && $ANDROID_NDK/ndk-build`（得到 `libvmicsender.so`
与 CLI `vmic_sender_demo`）。真机快速验证：

```bash
# 设备上(root):把 48k/2ch/S16 裸 PCM 推给 HAL
./vmic_sender_demo /sdcard/test_48k_2ch_s16.pcm
```

**Java**：`sender/java/.../VirtualMicSender.java`，用法与 Native 对称
（`start()` / `push(byte[],off,len)` / `stop()`）；用 `LocalSocket` 抽象命名空间连接。
集成时把该文件挪到 `app/src/main/java` 下即可。

## 云端一键编译打包（阶段 6，推荐）

无需本地装 NDK。仓库根 `.github/workflows/build.yml` 会:
配置 NDK r26d → 编译 arm64-v8a 代理 HAL（校验 AArch64 + `HMI` 导出）→ 编译
`vmic_sender` 客户端 → 组装成 `VirtualMic_HAL.zip`（含 sha256）→ 作为 Artifacts 发布。

- **触发**：改动 `virtual-mic-hal/**` 自动跑；或在 GitHub 仓库 **Actions → Build Virtual Mic HAL →
  Run workflow** 手动一键触发。
- **下载（日常）**：运行结束在该次 run 的 Artifacts 里下载 `VirtualMic_HAL-magisk`（即刷入包）与
  `vmic-sender-arm64`（客户端）。
- **发版（固定下载链接）**：打 `v*` 标签即自动编译并创建 **GitHub Release**，把
  `VirtualMic_HAL.zip`（+sha256）与客户端二进制作为 Release 附件：
  ```bash
  git tag v1.0.0 && git push origin v1.0.0
  ```
- 下载后按下方"刷入与验证"操作即可（zip 已就绪，跳过本地编译/打包）。

> 若要本地手动编译打包，见下节。排障见 [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)，
> 原理见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## Magisk 打包与刷入（阶段 5，本地手动）

**1) 用 NDK 编译各 ABI 代理 `.so`（统一命名 `audio.primary.vmicproxy.so`，安装时改名）**

```bash
cd hal
for abi in arm64-v8a armeabi-v7a x86_64; do
  cmake -B build/$abi -S . \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-26 -DVMIC_SOC=vmicproxy
  cmake --build build/$abi
done
```

**2) 放入模块 `prebuilt/`（按 Magisk `$ARCH` 命名：arm64 / arm / x64 / x86）**

```bash
cd ..
cp hal/build/arm64-v8a/audio.primary.vmicproxy.so   magisk-module/prebuilt/arm64/
cp hal/build/armeabi-v7a/audio.primary.vmicproxy.so magisk-module/prebuilt/arm/
cp hal/build/x86_64/audio.primary.vmicproxy.so      magisk-module/prebuilt/x64/
# 32 位 arm 设备可另建 armeabi-v7a 产物放 arm/;x86 同理放 x86/
```

**3) 打包 zip（`update-binary` 需保留可执行位）**

```bash
cd magisk-module
chmod +x META-INF/com/google/android/update-binary
zip -r9 ../virtual-mic-hal-magisk.zip . -x '*.gitkeep' -x '.git*'
```

**4) 刷入与验证**

- Magisk App → 模块 → 从存储安装 → 选 `virtual-mic-hal-magisk.zip` → **重启**。
- 安装时 `customize.sh` 自动：探测 SoC（从 `/vendor/lib*/hw/audio.primary.*.so` 文件名提取）
  → 备份原厂为 `.orig.so` → 放入代理（重装/升级会识别 marker，绝不把代理误备份为原厂）。
- 重启后查日志：`adb shell su -c 'cat /data/local/tmp/virtual_mic_hal.log'`
  （应看到 `proxy active` 与 `orig backup`；目标 App 首次采集后 `service.sh` 记录 UDS 监听）。
- 运行推流端（`VmicSender` / `vmic_sender_demo`）连 `@virtual_mic_socket` 注入音频。

> 覆盖由 Magisk magic-mount 完成（模块 `system/vendor/...`），无需手动挂载；
> 运行时代理 `dlopen` 同目录 `.orig.so` 转发非 read 调用。SELinux 放行见 `sepolicy.rule`。

## 自测（host 侧，已通过）

| 组件 | 覆盖 | 结果 |
|------|------|------|
| `AudioRingBuffer` | 环绕 / 丢最旧保留最新 / SPSC 并发 4MB 字节序一致 | ✅ |
| `SocketServer` | 抽象 UDS bind/accept/recv→ring、断连检测、stop/join | ✅ |
| `Resampler` | 直通恒等(rel-err 0) / 48k→16k 频率+幅度保持 / 反相下混归零 / 6 种位深转换精确 / 欠载部分产出+续读守恒 | ✅ |
| `ClockPacer` | 快读锁 1x / 无漂移 / 停读重置防突发 / 换采样率 | ✅ |
| `VmicSender` ↔ `SocketServer` | 正常流字节精确·零丢弃 / 背压丢最旧(丢弃量精确=推入−容量) / 空闲期断线检测 / 二次自动重连数据恢复 | ✅ |
| Native `.so` | g++ 编译链接通过，`HMI` 符号导出 | ✅ |
| Java 客户端 | javac 17 `-Xlint:all` 零告警编译通过(Android 桩) | ✅ |
| Magisk 脚本 | `sh -n` 语法通过 / `install_for_dir` 首装+重装逻辑(SoC 提取、备份不误伤代理) / zip 打包结构与可执行位 | ✅ |

> HAL 结构体劫持(就地补丁函数指针)、SELinux 注入生效、进程内运行需上真机 + Magisk 实测。
